package com.codenames.keycards.vision

import android.graphics.Bitmap
import android.graphics.PointF
import android.os.Trace
import com.codenames.keycards.model.BoardOrientation
import com.codenames.keycards.model.RecognizedBoard
import com.codenames.keycards.model.RecognizedCell
import com.codenames.keycards.model.compatibleBoardOrientations
import com.codenames.keycards.model.orient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExecutorCoroutineDispatcher
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.opencv.android.Utils
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import java.io.Closeable
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.hypot

sealed interface ScanResult {
  data class ReadyForReview(
    /** Cells in the geometry detector's stable source coordinates. */
    val sourceBoard: RecognizedBoard,
    val diagnostics: GridDiagnostics,
    val compatibleOrientations: List<BoardOrientation>,
    val initialOrientation: BoardOrientation,
  ) : ScanResult {
    /** Compatibility helper for callers that only need the stable initial presentation. */
    val board: RecognizedBoard get() = sourceBoard.orient(initialOrientation)
  }

  data class GridMismatch(
    val expectedRows: Int,
    val expectedColumns: Int,
    val foundRows: Int,
    val foundColumns: Int,
    val diagnostics: GridDiagnostics,
  ) : ScanResult

  data class GridUncertain(val diagnostics: GridDiagnostics) : ScanResult
}

/** Coordinates independent grid verification, bounded card rectification, and local OCR. */
class BoardScanner(
  private val gridDetector: GridDetector = OpenCvGridDetector(),
  private val ocrEngineFactory: OcrEngineFactory,
  private val workerCount: Int = 1,
) : Closeable {
  init {
    require(workerCount > 0)
  }

  /** Keeps tests and custom callers that supply one engine on the supported serial path. */
  constructor(
    gridDetector: GridDetector = OpenCvGridDetector(),
    ocrEngine: OcrEngine,
  ) : this(gridDetector, OcrEngineFactory { ocrEngine }, 1) {
    workerEngines = listOf(ocrEngine)
  }

  private val lifecycleLock = Any()
  private val scanMutex = Mutex()
  private var workerEngines: List<OcrEngine>? = null
  private var workerDispatcher: ExecutorCoroutineDispatcher? = null
  private var closed = false

  suspend fun scan(
    capturedImage: Bitmap,
    expectedRows: Int,
    expectedColumns: Int,
    onProgress: suspend (completed: Int, total: Int) -> Unit = { _, _ -> },
  ): ScanResult =
    scanMutex.withLock {
      scanOnce(capturedImage, expectedRows, expectedColumns, onProgress)
    }

  private suspend fun scanOnce(
    capturedImage: Bitmap,
    expectedRows: Int,
    expectedColumns: Int,
    onProgress: suspend (completed: Int, total: Int) -> Unit,
  ): ScanResult {
    currentCoroutineContext().ensureActive()
    val detection = traced("Grid detection") { gridDetector.detect(capturedImage) }
    if (detection !is GridDetection.Verified) return ScanResult.GridUncertain(detection.diagnostics)

    val orientations =
      compatibleBoardOrientations(
        sourceRows = detection.rows,
        sourceColumns = detection.columns,
        expectedRows = expectedRows,
        expectedColumns = expectedColumns,
      )
    if (orientations.isEmpty()) {
      return ScanResult.GridMismatch(
        expectedRows,
        expectedColumns,
        detection.rows,
        detection.columns,
        detection.diagnostics,
      )
    }

    val cards = detection.cards.sortedWith(compareBy<DetectedCard> { it.row }.thenBy { it.column })
    val cells = arrayOfNulls<RecognizedCell>(cards.size)
    val source = Mat()
    try {
      traced("Source bitmap conversion") { Utils.bitmapToMat(capturedImage, source) }
      val engines = engines().take(cards.size.coerceAtLeast(1))
      val dispatcher = checkNotNull(workerDispatcher)
      val jobs = Channel<IndexedValue<DetectedCard>>(capacity = engines.size)
      val progressMutex = Mutex()
      var completed = 0
      onProgress(0, cards.size)

      coroutineScope {
        val producer =
          launch {
            try {
              cards.forEachIndexed { index, card -> jobs.send(IndexedValue(index, card)) }
            } finally {
              jobs.close()
            }
          }
        val consumers =
          engines.map { engine ->
            launch(dispatcher) {
              for ((index, card) in jobs) {
                currentCoroutineContext().ensureActive()
                val cell =
                  try {
                    val rectified = traced("Card rectification") { rectify(source, card.corners) }
                    try {
                      traced("Card OCR") { engine.recognize(rectified) }
                    } finally {
                      rectified.recycle()
                    }
                  } catch (cancelled: CancellationException) {
                    throw cancelled
                  } catch (_: RuntimeException) {
                    // One native failure must not shift or discard the remaining physical cells.
                    RecognizedCell("")
                  }
                cells[index] = cell
                progressMutex.withLock {
                  completed += 1
                  onProgress(completed, cards.size)
                }
              }
            }
          }
        (consumers + producer).joinAll()
      }
    } finally {
      source.release()
    }

    val sourceBoard =
      RecognizedBoard(
        detection.rows,
        detection.columns,
        cells.map { it ?: RecognizedCell("") },
      )
    val initialOrientation =
      when {
        BoardOrientation.ORIGINAL in orientations -> BoardOrientation.ORIGINAL
        BoardOrientation.CLOCKWISE in orientations -> BoardOrientation.CLOCKWISE
        else -> orientations.first()
      }
    return ScanResult.ReadyForReview(sourceBoard, detection.diagnostics, orientations, initialOrientation)
  }

  private fun engines(): List<OcrEngine> =
    synchronized(lifecycleLock) {
      check(!closed) { "Scanner is closed" }
      workerEngines?.let { existing ->
        if (workerDispatcher == null) workerDispatcher = newWorkerDispatcher(existing.size)
        return@synchronized existing
      }

      val created = mutableListOf<OcrEngine>()
      try {
        repeat(workerCount) { created += ocrEngineFactory.create() }
      } catch (failure: RuntimeException) {
        created.forEach { runCatching { it.close() } }
        throw failure
      }
      created.toList().also {
        workerEngines = it
        workerDispatcher = newWorkerDispatcher(it.size)
      }
    }

  private fun newWorkerDispatcher(count: Int): ExecutorCoroutineDispatcher =
    Executors.newFixedThreadPool(count) { task ->
      Thread(task, "word-board-ocr-${workerThreadNumber.incrementAndGet()}").apply { isDaemon = true }
    }.asCoroutineDispatcher()

  override fun close() {
    val (engines, dispatcher) =
      synchronized(lifecycleLock) {
        if (closed) return
        closed = true
        val ownedEngines = workerEngines.orEmpty()
        val ownedDispatcher = workerDispatcher
        workerEngines = null
        workerDispatcher = null
        ownedEngines to ownedDispatcher
      }
    engines.forEach { runCatching { it.close() } }
    dispatcher?.close()
  }

  private fun rectify(source: Mat, corners: List<PointF>): Bitmap {
    require(corners.size == 4)
    val topLength = corners[0].distanceTo(corners[1])
    val leftLength = corners[0].distanceTo(corners[3])
    val portrait = leftLength > topLength
    val width = if (portrait) RECTIFIED_SHORT_EDGE else RECTIFIED_LONG_EDGE
    val height = if (portrait) RECTIFIED_LONG_EDGE else RECTIFIED_SHORT_EDGE
    val output = Mat()
    val sourcePoints = MatOfPoint2f(*corners.map { Point(it.x.toDouble(), it.y.toDouble()) }.toTypedArray())
    val destinationPoints =
      MatOfPoint2f(
        Point(0.0, 0.0),
        Point((width - 1).toDouble(), 0.0),
        Point((width - 1).toDouble(), (height - 1).toDouble()),
        Point(0.0, (height - 1).toDouble()),
      )
    val transform = Imgproc.getPerspectiveTransform(sourcePoints, destinationPoints)
    try {
      Imgproc.warpPerspective(source, output, transform, Size(width.toDouble(), height.toDouble()))
      val rectified = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
      Utils.matToBitmap(output, rectified)
      return if (portrait) {
        val landscape =
          Bitmap.createBitmap(
            rectified,
            0,
            0,
            rectified.width,
            rectified.height,
            android.graphics.Matrix().apply { postRotate(90f) },
            true,
          )
        rectified.recycle()
        landscape
      } else {
        rectified
      }
    } finally {
      transform.release()
      destinationPoints.release()
      sourcePoints.release()
      output.release()
    }
  }

  private fun PointF.distanceTo(other: PointF): Double =
    hypot((x - other.x).toDouble(), (y - other.y).toDouble())

  private inline fun <T> traced(name: String, operation: () -> T): T {
    Trace.beginSection(name)
    return try {
      operation()
    } finally {
      Trace.endSection()
    }
  }

  private companion object {
    const val RECTIFIED_LONG_EDGE = 900
    const val RECTIFIED_SHORT_EDGE = 563
    val workerThreadNumber = AtomicInteger()
  }
}
