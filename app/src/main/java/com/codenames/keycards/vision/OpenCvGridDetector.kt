package com.codenames.keycards.vision

import android.graphics.Bitmap
import android.graphics.PointF
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.imgproc.Imgproc
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/** OpenCV contour extraction backed by a geometry-only, unit-tested lattice fitter. */
internal object OpenCvGridDetection {
  private const val MAX_DETECTION_DIMENSION = 1400

  fun detect(bitmap: Bitmap): GridDetection {
    if (!runCatching { OpenCVLoader.initLocal() }.getOrDefault(false)) {
      return GridDetection.Uncertain(GridDiagnostics())
    }
    val scale = (MAX_DETECTION_DIMENSION.toDouble() / max(bitmap.width, bitmap.height)).coerceAtMost(1.0)
    val detectionBitmap =
      if (scale < 1.0) {
        Bitmap.createScaledBitmap(
          bitmap,
          (bitmap.width * scale).toInt().coerceAtLeast(1),
          (bitmap.height * scale).toInt().coerceAtLeast(1),
          true,
        )
      } else {
        bitmap
      }
    return try {
      detectDownsampled(detectionBitmap, inverseScale = 1.0 / scale)
    } finally {
      if (detectionBitmap !== bitmap) detectionBitmap.recycle()
    }
  }

  private fun detectDownsampled(bitmap: Bitmap, inverseScale: Double): GridDetection {
    val source = Mat()
    val grayscale = Mat()
    val normalized = Mat()
    val blurred = Mat()
    val thresholdVariants = mutableListOf<Mat>()
    val hierarchy = Mat()
    try {
      Utils.bitmapToMat(bitmap, source)
      Imgproc.cvtColor(source, grayscale, Imgproc.COLOR_RGBA2GRAY)
      val exposure = Core.mean(grayscale).`val`[0]
      val clahe = Imgproc.createCLAHE(2.2, org.opencv.core.Size(8.0, 8.0))
      try {
        clahe.apply(grayscale, normalized)
      } finally {
        clahe.collectGarbage()
      }
      Imgproc.GaussianBlur(normalized, blurred, org.opencv.core.Size(3.0, 3.0), 0.0)
      val difference = Mat()
      val blurScore =
        try {
          Core.absdiff(normalized, blurred, difference)
          Core.mean(difference).`val`[0]
        } finally {
          difference.release()
        }
      listOf(21 to 5.0, 31 to 7.0, 51 to 5.0).forEach { (blockSize, constant) ->
        thresholdVariants += Mat().also { threshold ->
          Imgproc.adaptiveThreshold(
            blurred,
            threshold,
            255.0,
            Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
            Imgproc.THRESH_BINARY_INV,
            blockSize,
            constant,
          )
        }
      }
      val canny = Mat()
      val edgeKernel = Imgproc.getStructuringElement(Imgproc.MORPH_RECT, org.opencv.core.Size(3.0, 3.0))
      try {
        Imgproc.Canny(blurred, canny, 35.0, 105.0)
        thresholdVariants += Mat().also { dilated -> Imgproc.dilate(canny, dilated, edgeKernel) }
      } finally {
        edgeKernel.release()
        canny.release()
      }
      val contours = mutableListOf<MatOfPoint>()
      thresholdVariants.forEach { threshold ->
        val variantContours = mutableListOf<MatOfPoint>()
        Imgproc.findContours(threshold, variantContours, hierarchy, Imgproc.RETR_LIST, Imgproc.CHAIN_APPROX_SIMPLE)
        contours += variantContours
      }
      try {
        val rawCandidates = contours.mapNotNull { it.toCandidate(source.width(), source.height()) }
        val imageArea = source.width().toDouble() * source.height()
        val withoutBoardOutline = rawCandidates.filter { it.area < imageArea * .22 }
        val unnested = suppressNestedAndDuplicateCandidates(withoutBoardOutline)
        val cards = selectDominantCardScale(unnested)
        val initialDiagnostics =
          GridDiagnostics(
            cardCount = cards.size,
            blurScore = blurScore,
            exposure = exposure,
            perspectiveSkew = median(cards.map { quadrilateralSkew(it.corners) }),
          )
        if (cards.size < 4) return GridDetection.Uncertain(initialDiagnostics)

        val fit =
          LatticeFitter().fit(
            cards.mapIndexed { index, card ->
              CardObservation(
                id = index,
                centerX = card.centerX,
                centerY = card.centerY,
                longSide = card.longSide,
                shortSide = card.shortSide,
                longAxisRadians = card.longAxisRadians,
                area = card.area,
              )
            },
          )
        if (fit !is LatticeFit.Verified) {
          val uncertain = fit as LatticeFit.Uncertain
          return GridDetection.Uncertain(
            initialDiagnostics.copy(
              detectedRows = uncertain.candidateRows,
              detectedColumns = uncertain.candidateColumns,
              confidence = uncertain.confidence,
            ),
          )
        }

        val assignments = fit.assignments.associateBy(LatticeAssignment::observationId)
        val detectedCards =
          cards.mapIndexedNotNull { index, card ->
            val assignment = assignments[index] ?: return@mapIndexedNotNull null
            DetectedCard(
              row = assignment.row,
              column = assignment.column,
              corners = card.corners.map { point -> PointF((point.x * inverseScale).toFloat(), (point.y * inverseScale).toFloat()) },
            )
          }
        if (detectedCards.size != fit.rows * fit.columns) {
          return GridDetection.Uncertain(initialDiagnostics.copy(confidence = fit.confidence))
        }
        return GridDetection.Verified(
          rows = fit.rows,
          columns = fit.columns,
          cards = detectedCards,
          diagnostics =
            initialDiagnostics.copy(
              detectedRows = fit.rows,
              detectedColumns = fit.columns,
              cardCount = detectedCards.size,
              confidence = fit.confidence,
            ),
        )
      } finally {
        contours.forEach(Mat::release)
      }
    } catch (_: RuntimeException) {
      return GridDetection.Uncertain(GridDiagnostics())
    } finally {
      hierarchy.release()
      thresholdVariants.forEach(Mat::release)
      blurred.release()
      normalized.release()
      grayscale.release()
      source.release()
    }
  }

  private data class Candidate(
    val corners: List<PointF>,
    val area: Double,
    val longSide: Double,
    val shortSide: Double,
    val centerX: Double,
    val centerY: Double,
    val longAxisRadians: Double,
  )

  private fun MatOfPoint.toCandidate(imageWidth: Int, imageHeight: Int): Candidate? {
    val contour2f = MatOfPoint2f(*toArray())
    val approximation = MatOfPoint2f()
    try {
      Imgproc.approxPolyDP(contour2f, approximation, Imgproc.arcLength(contour2f, true) * .025, true)
      if (approximation.total() != 4L) return null
      val polygon = MatOfPoint(*approximation.toArray())
      val area =
        try {
          if (!Imgproc.isContourConvex(polygon)) return null
          abs(Imgproc.contourArea(polygon))
        } finally {
          polygon.release()
        }
      val imageArea = imageWidth.toDouble() * imageHeight
      if (area < imageArea * .00025) return null

      val corners = approximation.toArray().toList().orderedCorners()
      val firstPair = (corners[0].distanceTo(corners[1]) + corners[2].distanceTo(corners[3])) / 2.0
      val secondPair = (corners[1].distanceTo(corners[2]) + corners[3].distanceTo(corners[0])) / 2.0
      val longSide = max(firstPair, secondPair)
      val shortSide = min(firstPair, secondPair)
      val aspect = longSide / shortSide
      if (aspect !in 1.12..3.2) return null
      val longVector =
        if (firstPair >= secondPair) {
          (corners[1].x - corners[0].x).toDouble() to (corners[1].y - corners[0].y).toDouble()
        } else {
          (corners[2].x - corners[1].x).toDouble() to (corners[2].y - corners[1].y).toDouble()
        }
      return Candidate(
        corners = corners,
        area = area,
        longSide = longSide,
        shortSide = shortSide,
        centerX = corners.map { it.x.toDouble() }.average(),
        centerY = corners.map { it.y.toDouble() }.average(),
        longAxisRadians = normalizeUndirectedAngle(atan2(longVector.second, longVector.first)),
      )
    } finally {
      approximation.release()
      contour2f.release()
    }
  }

  private fun suppressNestedAndDuplicateCandidates(candidates: List<Candidate>): List<Candidate> =
    candidates.sortedByDescending(Candidate::area).fold(mutableListOf()) { kept, candidate ->
      val samePhysicalCenter =
        kept.any { outer ->
          val distance = hypot(candidate.centerX - outer.centerX, candidate.centerY - outer.centerY)
          distance < min(candidate.shortSide, outer.shortSide) * .38
        }
      if (!samePhysicalCenter) kept += candidate
      kept
    }

  /** Selects the most populous card-size band without assuming all contours are cards. */
  private fun selectDominantCardScale(candidates: List<Candidate>): List<Candidate> {
    if (candidates.size < 4) return candidates
    return candidates
      .map { anchor -> candidates.filter { it.area / anchor.area in .48..2.05 } }
      .maxWithOrNull(compareBy<List<Candidate>> { it.size }.thenBy { median(it.map(Candidate::area)) ?: 0.0 })
      .orEmpty()
  }

  private fun List<Point>.orderedCorners(): List<PointF> {
    val points = map { PointF(it.x.toFloat(), it.y.toFloat()) }
    val topLeft = points.minBy { it.x + it.y }
    val bottomRight = points.maxBy { it.x + it.y }
    val topRight = points.maxBy { it.x - it.y }
    val bottomLeft = points.minBy { it.x - it.y }
    if (setOf(topLeft, topRight, bottomRight, bottomLeft).size != 4) {
      val centerX = points.map(PointF::x).average()
      val centerY = points.map(PointF::y).average()
      val circular = points.sortedBy { atan2(it.y - centerY, it.x - centerX) }
      val start = circular.indices.minBy { circular[it].x + circular[it].y }
      return List(4) { circular[(start + it) % 4] }
    }
    return listOf(topLeft, topRight, bottomRight, bottomLeft)
  }

  private fun PointF.distanceTo(other: PointF): Double = hypot((x - other.x).toDouble(), (y - other.y).toDouble())

  private fun normalizeUndirectedAngle(value: Double): Double {
    var angle = value % PI
    if (angle < -PI / 2.0) angle += PI
    if (angle >= PI / 2.0) angle -= PI
    return angle
  }
}
