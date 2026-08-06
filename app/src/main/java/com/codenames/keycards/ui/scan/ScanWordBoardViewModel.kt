package com.codenames.keycards.ui.scan

import android.app.ActivityManager
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.net.Uri
import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.codenames.keycards.model.BoardOrientation
import com.codenames.keycards.model.RecognizedBoard
import com.codenames.keycards.model.needsOcrAttention
import com.codenames.keycards.model.orient
import com.codenames.keycards.model.sourceCellIndex
import com.codenames.keycards.vision.BoardScanner
import com.codenames.keycards.vision.GridDiagnostics
import com.codenames.keycards.vision.OcrEngineFactory
import com.codenames.keycards.vision.ScanResult
import com.codenames.keycards.vision.TesseractOcrEngine
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.max

internal sealed interface ScanStage {
  data object PermissionExplanation : ScanStage
  data class PermissionDenied(val permanentlyDenied: Boolean) : ScanStage
  data object Camera : ScanStage
  data class Reading(val current: Int = 0, val total: Int = 0) : ScanStage
  data class Review(
    val sourceBoard: RecognizedBoard,
    val compatibleOrientations: List<BoardOrientation>,
    val orientation: BoardOrientation,
    val acknowledgedSourceIndices: Set<Int> = emptySet(),
  ) : ScanStage {
    val board: RecognizedBoard get() = sourceBoard.orient(orientation)

    val isReadyToUse: Boolean
      get() =
        sourceBoard.isComplete &&
          sourceBoard.cells.indices.none { sourceIndex ->
            sourceBoard.cells[sourceIndex].needsOcrAttention() && sourceIndex !in acknowledgedSourceIndices
          }
  }
  data class Mismatch(val result: ScanResult.GridMismatch) : ScanStage
  data class Uncertain(val result: ScanResult.GridUncertain) : ScanStage
}

/** Retains scan progress and reviewed words across activity recreation. A mismatched image stays only in memory until the next action. */
internal class ScanWordBoardViewModel(context: Context) : ViewModel() {
  private val applicationContext = context.applicationContext
  private var scanner: BoardScanner? = null
  private var recognitionJob: Job? = null
  private var recognitionGeneration = 0L
  private val mismatchImage = AtomicReference<Bitmap?>(null)

  var stage: ScanStage by mutableStateOf(ScanStage.PermissionExplanation)
    private set

  fun synchronizeCameraPermission(granted: Boolean) {
    if (granted && (stage is ScanStage.PermissionExplanation || stage is ScanStage.PermissionDenied)) {
      stage = ScanStage.Camera
    }
  }

  fun cameraPermissionDenied(permanentlyDenied: Boolean) {
    stage = ScanStage.PermissionDenied(permanentlyDenied)
  }

  fun showCamera() {
    recognitionGeneration += 1
    recognitionJob?.cancel()
    discardMismatchImage()
    stage = ScanStage.Camera
  }

  fun cameraError() {
    stage = ScanStage.Uncertain(ScanResult.GridUncertain(GridDiagnostics()))
  }

  fun selectReviewOrientation(orientation: BoardOrientation) {
    val review = stage as? ScanStage.Review ?: return
    if (orientation in review.compatibleOrientations) stage = review.copy(orientation = orientation)
  }

  fun updateReviewCell(displayedIndex: Int, text: String) {
    val review = stage as? ScanStage.Review ?: return
    val sourceIndex = sourceCellIndex(displayedIndex, review.sourceBoard.rows, review.sourceBoard.columns, review.orientation)
    val sourceCell = review.sourceBoard.cells[sourceIndex]
    val updatedBoard =
      review.sourceBoard.copy(
        cells = review.sourceBoard.cells.toMutableList().apply {
          this[sourceIndex] = sourceCell.copy(text = text, manuallyEdited = true)
        },
      )
    stage =
      review.copy(
        sourceBoard = updatedBoard,
        acknowledgedSourceIndices = review.acknowledgedSourceIndices - sourceIndex,
      )
  }

  fun acknowledgeReviewCell(displayedIndex: Int) {
    val review = stage as? ScanStage.Review ?: return
    val sourceIndex = sourceCellIndex(displayedIndex, review.sourceBoard.rows, review.sourceBoard.columns, review.orientation)
    if (review.sourceBoard.cells[sourceIndex].text.isNotBlank()) {
      stage = review.copy(acknowledgedSourceIndices = review.acknowledgedSourceIndices + sourceIndex)
    }
  }

  fun scanBitmap(bitmap: Bitmap, expectedRows: Int, expectedColumns: Int) {
    discardMismatchImage()
    startRecognition(
      expectedRows,
      expectedColumns,
      loadBitmap = { bitmap },
      releaseIfUnused = { if (!bitmap.isRecycled) bitmap.recycle() },
    )
  }

  fun scanUri(uri: Uri, expectedRows: Int, expectedColumns: Int) {
    discardMismatchImage()
    startRecognition(expectedRows, expectedColumns, loadBitmap = { decodeImportedBitmap(applicationContext, uri) })
  }

  /** Reuses the mismatch source after the setup has adopted its independently verified dimensions. */
  fun rescanMismatchImage(expectedRows: Int, expectedColumns: Int) {
    val image = mismatchImage.getAndSet(null) ?: return
    startRecognition(
      expectedRows,
      expectedColumns,
      loadBitmap = { image },
      releaseIfUnused = { if (!image.isRecycled) image.recycle() },
    )
  }

  fun cancelAndReset() {
    recognitionGeneration += 1
    recognitionJob?.cancel()
    recognitionJob = null
    discardMismatchImage()
    closeScannerInBackground()
    stage = ScanStage.PermissionExplanation
  }

  private fun startRecognition(
    expectedRows: Int,
    expectedColumns: Int,
    loadBitmap: suspend () -> Bitmap,
    releaseIfUnused: () -> Unit = {},
  ) {
    recognitionJob?.cancel()
    val generation = ++recognitionGeneration
    val activeScanner =
      scanner ?: BoardScanner(
        ocrEngineFactory = OcrEngineFactory { TesseractOcrEngine(applicationContext) },
        workerCount = recommendedOcrWorkerCount(applicationContext),
      ).also { scanner = it }
    stage = ScanStage.Reading()
    val bitmapClaimed = AtomicBoolean(false)
    recognitionJob =
      viewModelScope.launch {
        try {
          val result =
            withContext(Dispatchers.Default) {
              bitmapClaimed.set(true)
              val bitmap = loadBitmap()
              var retainedForMismatch = false
              try {
                activeScanner.scan(bitmap, expectedRows, expectedColumns) { current, total ->
                  withContext(Dispatchers.Main.immediate) {
                    if (generation == recognitionGeneration) stage = ScanStage.Reading(current, total)
                  }
                }.also { scanResult ->
                  if (scanResult is ScanResult.GridMismatch && generation == recognitionGeneration) {
                    replaceMismatchImage(bitmap)
                    retainedForMismatch = true
                  }
                }
              } finally {
                if (!retainedForMismatch && !bitmap.isRecycled) bitmap.recycle()
              }
            }
          if (generation == recognitionGeneration) stage = result.toStage()
        } catch (cancelled: CancellationException) {
          // An obsolete result can finish just as a newer scan starts; never alter newer state.
          if (generation == recognitionGeneration) discardMismatchImage()
          throw cancelled
        } catch (_: Exception) {
          if (generation == recognitionGeneration) {
            stage = ScanStage.Uncertain(ScanResult.GridUncertain(GridDiagnostics()))
          }
        } finally {
          if (generation == recognitionGeneration) recognitionJob = null
        }
      }.also { job ->
        job.invokeOnCompletion {
          if (bitmapClaimed.compareAndSet(false, true)) releaseIfUnused()
        }
      }
  }

  private fun replaceMismatchImage(image: Bitmap) {
    mismatchImage.getAndSet(image)?.let { previous -> if (!previous.isRecycled) previous.recycle() }
  }

  private fun discardMismatchImage() {
    mismatchImage.getAndSet(null)?.let { image -> if (!image.isRecycled) image.recycle() }
  }

  private fun closeScannerInBackground() {
    val scannerToClose = scanner ?: return
    scanner = null
    viewModelScope.launch(Dispatchers.Default) { scannerToClose.close() }
  }

  override fun onCleared() {
    recognitionJob?.cancel()
    discardMismatchImage()
    scanner?.close()
    scanner = null
  }

  private fun ScanResult.toStage(): ScanStage =
    when (this) {
      is ScanResult.ReadyForReview ->
        ScanStage.Review(sourceBoard, compatibleOrientations, initialOrientation)
      is ScanResult.GridMismatch -> ScanStage.Mismatch(this)
      is ScanResult.GridUncertain -> ScanStage.Uncertain(this)
    }
}

private fun recommendedOcrWorkerCount(context: Context): Int {
  val activityManager = context.getSystemService(ActivityManager::class.java)
  if (activityManager?.isLowRamDevice == true) return 1
  return (Runtime.getRuntime().availableProcessors() - 1).coerceIn(1, 3)
}

private const val MAX_IMPORTED_DIMENSION = 6000

/** Decodes a picker URI at a bounded size and honors EXIF orientation without storage permission. */
private fun decodeImportedBitmap(context: Context, uri: Uri): Bitmap {
  if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
    val source = ImageDecoder.createSource(context.contentResolver, uri)
    return ImageDecoder.decodeBitmap(source) { decoder, info, _ ->
      decoder.allocator = ImageDecoder.ALLOCATOR_SOFTWARE
      val largest = max(info.size.width, info.size.height)
      if (largest > MAX_IMPORTED_DIMENSION) {
        val scale = MAX_IMPORTED_DIMENSION.toDouble() / largest
        decoder.setTargetSize((info.size.width * scale).toInt(), (info.size.height * scale).toInt())
      }
    }
  }

  val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
  context.contentResolver.openInputStream(uri).use { input ->
    requireNotNull(input) { "Could not open selected image" }
    BitmapFactory.decodeStream(input, null, bounds)
  }
  var sampleSize = 1
  while (max(bounds.outWidth, bounds.outHeight) / sampleSize > MAX_IMPORTED_DIMENSION) sampleSize *= 2
  val options = BitmapFactory.Options().apply {
    inSampleSize = sampleSize
    inPreferredConfig = Bitmap.Config.ARGB_8888
  }
  val decoded =
    context.contentResolver.openInputStream(uri).use { input ->
      requireNotNull(input) { "Could not open selected image" }
      requireNotNull(BitmapFactory.decodeStream(input, null, options)) { "Could not decode selected image" }
    }
  val orientation =
    context.contentResolver.openInputStream(uri).use { input ->
      requireNotNull(input) { "Could not read selected image metadata" }
      ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
    }
  val transform = Matrix()
  when (orientation) {
    ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> transform.postScale(-1f, 1f)
    ExifInterface.ORIENTATION_ROTATE_180 -> transform.postRotate(180f)
    ExifInterface.ORIENTATION_FLIP_VERTICAL -> transform.postScale(1f, -1f)
    ExifInterface.ORIENTATION_TRANSPOSE -> {
      transform.postRotate(90f)
      transform.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_90 -> transform.postRotate(90f)
    ExifInterface.ORIENTATION_TRANSVERSE -> {
      transform.postRotate(270f)
      transform.postScale(-1f, 1f)
    }
    ExifInterface.ORIENTATION_ROTATE_270 -> transform.postRotate(270f)
    else -> return decoded
  }
  return Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true)
    .also { decoded.recycle() }
}
