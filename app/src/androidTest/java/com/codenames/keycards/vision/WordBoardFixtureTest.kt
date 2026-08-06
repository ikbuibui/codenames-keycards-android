package com.codenames.keycards.vision

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Typeface
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import com.codenames.keycards.model.CandidateAgreement
import com.codenames.keycards.model.needsOcrAttention
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/** Device-side release gate for native OpenCV/Tesseract behavior on the supplied photographs. */
@RunWith(AndroidJUnit4::class)
class WordBoardFixtureTest {
  private val instrumentation = InstrumentationRegistry.getInstrumentation()
  private val fixtureContext = instrumentation.context
  private val appContext = instrumentation.targetContext

  @Test
  fun suppliedPhotosHaveIndependentlyDetectedDimensions() {
    fixtures().forEach { fixture ->
      val bitmap = loadBitmap(fixture.file)
      val detection = try {
        OpenCvGridDetector().detect(bitmap)
      } finally {
        bitmap.recycle()
      }

      assertTrue("${fixture.file}: $detection", detection is GridDetection.Verified)
      detection as GridDetection.Verified
      assertEquals(fixture.rows, detection.rows)
      assertEquals(fixture.columns, detection.columns)
      assertEquals(fixture.rows * fixture.columns, detection.cards.size)
    }
  }

  @Test
  fun generatedCustomCardNeedsOnlyOneLabelInEitherDirection() {
    listOf(0f, 180f).forEach { rotation ->
      val card = Bitmap.createBitmap(1200, 750, Bitmap.Config.ARGB_8888)
      val canvas = Canvas(card)
      canvas.drawColor(Color.WHITE)
      canvas.rotate(rotation, card.width / 2f, card.height / 2f)
      val paint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
          color = Color.BLACK
          textSize = 112f
          typeface = Typeface.DEFAULT_BOLD
          textAlign = Paint.Align.CENTER
        }
      canvas.drawText("CUSTOM LABEL", card.width / 2f, card.height / 2f - (paint.ascent() + paint.descent()) / 2f, paint)

      val engine = TesseractOcrEngine(appContext)
      val cell = try {
        engine.recognize(card)
      } finally {
        engine.close()
        card.recycle()
      }
      assertEquals("CUSTOM LABEL", cell.text.uppercase())
      assertEquals(CandidateAgreement.SINGLE_READ, cell.candidateAgreement)
    }
  }

  @Test
  fun suppliedPhotosRecognizeMoreThanNinetyPercentAndFlagEveryMiss() = runBlocking {
    val scanner = BoardScanner(ocrEngine = TesseractOcrEngine(appContext))
    try {
      fixtures().forEach { fixture ->
        val bitmap = loadBitmap(fixture.file)
        val result = try {
          scanner.scan(bitmap, fixture.rows, fixture.columns)
        } finally {
          bitmap.recycle()
        }

        assertTrue("${fixture.file}: $result", result is ScanResult.ReadyForReview)
        assertAcceptableRecognition(fixture, (result as ScanResult.ReadyForReview).board.cells)
      }
    } finally {
      scanner.close()
    }
  }

  @Test
  fun boundedParallelScannerPreservesResultsAndProgress() = runBlocking {
    val fixture = fixtures().first { it.rows == 4 && it.columns == 6 }
    val scanner =
      BoardScanner(
        ocrEngineFactory = OcrEngineFactory { TesseractOcrEngine(appContext) },
        workerCount = 3,
      )
    val progress = mutableListOf<Int>()
    val bitmap = loadBitmap(fixture.file)
    val started = SystemClock.elapsedRealtime()
    val result =
      try {
        scanner.scan(bitmap, fixture.rows, fixture.columns) { completed, _ -> progress += completed }
      } finally {
        scanner.close()
        bitmap.recycle()
      }
    Log.i("WordBoardFixtureTest", "Parallel 4x6 scan took ${SystemClock.elapsedRealtime() - started} ms")

    assertTrue(result is ScanResult.ReadyForReview)
    assertAcceptableRecognition(fixture, (result as ScanResult.ReadyForReview).board.cells)
    assertEquals((0..fixture.cells.size).toList(), progress)
  }

  private fun assertAcceptableRecognition(fixture: Fixture, cells: List<com.codenames.keycards.model.RecognizedCell>) {
    val actual = cells.map { it.text.trim().uppercase() }
    val incorrectIndices = fixture.cells.indices.filter { actual[it] != fixture.cells[it] }
    val accuracy = (fixture.cells.size - incorrectIndices.size).toDouble() / fixture.cells.size
    val details = incorrectIndices.joinToString { index ->
      "$index expected=${fixture.cells[index]} actual=${actual[index]} confidence=${cells[index].confidence} agreement=${cells[index].candidateAgreement}"
    }
    val attentionCount = cells.count { it.needsOcrAttention() }
    Log.i("WordBoardFixtureTest", "${fixture.file}: accuracy=$accuracy attention=$attentionCount; $details")
    assertTrue("${fixture.file}: accuracy=$accuracy; $details", accuracy > .90)
    assertTrue(
      "${fixture.file}: incorrect reads must be presented for review; $details",
      incorrectIndices.all { index -> cells[index].needsOcrAttention() },
    )
  }

  private fun fixtures(): List<Fixture> {
    val manifest = fixtureContext.assets.open("word-boards/ground-truth.json").bufferedReader().use { JSONObject(it.readText()) }
    val fixtures = manifest.getJSONArray("fixtures")
    return List(fixtures.length()) { index ->
      val fixture = fixtures.getJSONObject(index)
      val words = fixture.getJSONArray("cells")
      Fixture(
        file = fixture.getString("file"),
        rows = fixture.getInt("rows"),
        columns = fixture.getInt("columns"),
        cells = List(words.length()) { words.getString(it) },
      )
    }
  }

  private fun loadBitmap(file: String) =
    fixtureContext.assets.open("word-boards/$file").use { input ->
      requireNotNull(BitmapFactory.decodeStream(input)) { "Could not decode $file" }
    }

  private data class Fixture(val file: String, val rows: Int, val columns: Int, val cells: List<String>)
}
