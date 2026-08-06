package com.codenames.keycards.vision

import android.graphics.Bitmap
import android.graphics.Rect
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.max

/** A spatially distinct physical text-line candidate within one card. */
data class CardTextRegion(val id: String, val bounds: Rect, val score: Double)

/** Finds likely text lines throughout a card; official upper/lower bands are only ranking hints. */
object CardTextRegionDetector {
  fun detect(card: Bitmap, maximumRegions: Int = 4): List<CardTextRegion> {
    val source = Mat()
    val grayscale = Mat()
    val normalized = Mat()
    val threshold = Mat()
    val connected = Mat()
    val hierarchy = Mat()
    val kernel =
      Imgproc.getStructuringElement(
        Imgproc.MORPH_RECT,
        Size(max(9, card.width / 38).toDouble(), max(1, card.height / 220).toDouble()),
      )
    val contours = mutableListOf<MatOfPoint>()
    try {
      Utils.bitmapToMat(card, source)
      Imgproc.cvtColor(source, grayscale, Imgproc.COLOR_RGBA2GRAY)
      val clahe = Imgproc.createCLAHE(2.4, Size(8.0, 8.0))
      try {
        clahe.apply(grayscale, normalized)
      } finally {
        clahe.collectGarbage()
      }
      Imgproc.adaptiveThreshold(
        normalized,
        threshold,
        255.0,
        Imgproc.ADAPTIVE_THRESH_GAUSSIAN_C,
        Imgproc.THRESH_BINARY_INV,
        31,
        9.0,
      )
      Imgproc.morphologyEx(threshold, connected, Imgproc.MORPH_CLOSE, kernel)
      Imgproc.findContours(connected, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)
      val cardArea = card.width.toDouble() * card.height
      val candidates =
        contours.mapNotNull { contour ->
          val rectangle = Imgproc.boundingRect(contour)
          val widthFraction = rectangle.width.toDouble() / card.width
          val heightFraction = rectangle.height.toDouble() / card.height
          val aspect = rectangle.width.toDouble() / rectangle.height.coerceAtLeast(1)
          val areaFraction = rectangle.area() / cardArea
          if (widthFraction !in .07..0.96 || heightFraction !in .025..0.30 || aspect < 1.25 || areaFraction < .003) {
            return@mapNotNull null
          }
          val horizontalPadding = max(5, (rectangle.width * .08).toInt())
          val verticalPadding = max(4, (rectangle.height * .45).toInt())
          val bounds =
            Rect(
              (rectangle.x - horizontalPadding).coerceAtLeast(0),
              (rectangle.y - verticalPadding).coerceAtLeast(0),
              (rectangle.x + rectangle.width + horizontalPadding).coerceAtMost(card.width),
              (rectangle.y + rectangle.height + verticalPadding).coerceAtMost(card.height),
            )
          val centerY = (bounds.top + bounds.bottom) / 2.0 / card.height
          val officialBandBonus = if (centerY < .43 || centerY > .57) .12 else 0.0
          CardTextRegion(
            id = "line-${bounds.left}-${bounds.top}-${bounds.right}-${bounds.bottom}",
            bounds = bounds,
            score = widthFraction + officialBandBonus - heightFraction * .15,
          )
        }
      val ranked =
        candidates
          .sortedByDescending(CardTextRegion::score)
          .fold(mutableListOf<CardTextRegion>()) { kept, candidate ->
            if (kept.none { overlapRatio(it.bounds, candidate.bounds) > .62 }) kept += candidate
            kept
          }
      return ranked
        .take(maximumRegions)
        .sortedBy { it.bounds.centerY() }
    } catch (_: RuntimeException) {
      return emptyList()
    } finally {
      contours.forEach(Mat::release)
      kernel.release()
      hierarchy.release()
      connected.release()
      threshold.release()
      normalized.release()
      grayscale.release()
      source.release()
    }
  }

  private fun overlapRatio(first: Rect, second: Rect): Double {
    val left = maxOf(first.left, second.left)
    val top = maxOf(first.top, second.top)
    val right = minOf(first.right, second.right)
    val bottom = minOf(first.bottom, second.bottom)
    if (right <= left || bottom <= top) return 0.0
    val intersection = (right - left).toDouble() * (bottom - top)
    return intersection / minOf(first.width() * first.height(), second.width() * second.height()).coerceAtLeast(1)
  }
}
