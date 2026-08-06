package com.codenames.keycards.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.PointF
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.codenames.keycards.model.BoardOrientation
import com.codenames.keycards.model.RecognizedCell
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.opencv.android.OpenCVLoader
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class BoardScannerParallelTest {
  @Test
  fun outOfOrderWorkers_preserveSourceIndicesAndBoundConcurrency() = runBlocking {
    assertTrue(OpenCVLoader.initLocal())
    val bitmap = Bitmap.createBitmap(420, 420, Bitmap.Config.ARGB_8888)
    val cards =
      listOf(
        card(0, 0, 10f, 10f, Color.rgb(40, 0, 0)),
        card(0, 1, 220f, 10f, Color.rgb(80, 0, 0)),
        card(1, 0, 10f, 220f, Color.rgb(120, 0, 0)),
        card(1, 1, 220f, 220f, Color.rgb(160, 0, 0)),
      )
    cards.forEach { colored ->
      for (x in colored.card.corners.minOf { it.x.toInt() }..colored.card.corners.maxOf { it.x.toInt() }) {
        for (y in colored.card.corners.minOf { it.y.toInt() }..colored.card.corners.maxOf { it.y.toInt() }) {
          bitmap.setPixel(x.coerceIn(0, bitmap.width - 1), y.coerceIn(0, bitmap.height - 1), colored.color)
        }
      }
    }

    val active = AtomicInteger()
    val maximumActive = AtomicInteger()
    val closed = AtomicInteger()
    val detector =
      object : GridDetector {
        override fun detect(bitmap: Bitmap): GridDetection =
          GridDetection.Verified(
            rows = 2,
            columns = 2,
            cards = cards.map(ColoredCard::card),
            diagnostics = GridDiagnostics(detectedRows = 2, detectedColumns = 2, cardCount = 4, confidence = 1.0),
          )
      }
    val scanner =
      BoardScanner(
        gridDetector = detector,
        ocrEngineFactory =
          OcrEngineFactory {
            object : OcrEngine {
              override fun recognize(card: Bitmap): RecognizedCell {
                val running = active.incrementAndGet()
                maximumActive.accumulateAndGet(running, ::maxOf)
                return try {
                  val value = Color.red(card.getPixel(card.width / 2, card.height / 2)) / 40
                  Thread.sleep((5 - value) * 25L)
                  RecognizedCell(value.toString(), confidence = 99)
                } finally {
                  active.decrementAndGet()
                }
              }

              override fun close() {
                closed.incrementAndGet()
              }
            }
          },
        workerCount = 3,
      )
    val progress = mutableListOf<Int>()
    val result =
      try {
        scanner.scan(bitmap, 2, 2) { completed, _ -> progress += completed }
      } finally {
        scanner.close()
        bitmap.recycle()
      }

    assertTrue(result is ScanResult.ReadyForReview)
    result as ScanResult.ReadyForReview
    assertEquals(listOf("1", "2", "3", "4"), result.board.cells.map { it.text })
    assertEquals(BoardOrientation.entries, result.compatibleOrientations)
    assertEquals((0..4).toList(), progress)
    assertEquals(3, maximumActive.get())
    assertEquals(3, closed.get())
  }

  private fun card(row: Int, column: Int, left: Float, top: Float, color: Int): ColoredCard =
    ColoredCard(
      DetectedCard(
        row,
        column,
        listOf(
          PointF(left, top),
          PointF(left + 180f, top),
          PointF(left + 180f, top + 180f),
          PointF(left, top + 180f),
        ),
      ),
      color,
    )

  private data class ColoredCard(val card: DetectedCard, val color: Int)
}
