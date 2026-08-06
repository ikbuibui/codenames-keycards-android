package com.codenames.keycards.vision

import android.graphics.Bitmap
import android.graphics.PointF
import kotlin.math.abs
import kotlin.math.max

/** Diagnostic data is retained for actionable capture feedback; it is never an acceptance Boolean. */
data class GridDiagnostics(
  val detectedRows: Int? = null,
  val detectedColumns: Int? = null,
  val cardCount: Int = 0,
  val blurScore: Double? = null,
  val exposure: Double? = null,
  val perspectiveSkew: Double? = null,
  val confidence: Double = 0.0,
)

data class DetectedCard(
  val row: Int,
  val column: Int,
  /** Clockwise points beginning at the visual top-left corner. */
  val corners: List<PointF>,
)

sealed interface GridDetection {
  val diagnostics: GridDiagnostics

  data class Verified(
    val rows: Int,
    val columns: Int,
    val cards: List<DetectedCard>,
    override val diagnostics: GridDiagnostics,
  ) : GridDetection

  data class Uncertain(override val diagnostics: GridDiagnostics) : GridDetection
}

interface GridDetector {
  /** Finds card rectangles first; callers must not derive dimensions by dividing the image. */
  fun detect(bitmap: Bitmap): GridDetection
}

/**
 * OpenCV contour and lattice detector. It accepts only a one-card-per-lattice-cell result, so OCR
 * output can never make an unverified layout look valid.
 */
class OpenCvGridDetector : GridDetector {
  override fun detect(bitmap: Bitmap): GridDetection = OpenCvGridDetection.detect(bitmap)
}

internal fun median(values: List<Double>): Double? =
  values.sorted().let { sorted ->
    when {
      sorted.isEmpty() -> null
      sorted.size % 2 == 1 -> sorted[sorted.size / 2]
      else -> (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
  }

internal fun quadrilateralSkew(corners: List<PointF>): Double {
  if (corners.size != 4) return Double.POSITIVE_INFINITY
  fun distance(a: PointF, b: PointF): Double = kotlin.math.hypot((a.x - b.x).toDouble(), (a.y - b.y).toDouble())
  val a = distance(corners[0], corners[1])
  val b = distance(corners[1], corners[2])
  val c = distance(corners[2], corners[3])
  val d = distance(corners[3], corners[0])
  return max(abs(a - c) / max(a, c), abs(b - d) / max(b, d))
}
