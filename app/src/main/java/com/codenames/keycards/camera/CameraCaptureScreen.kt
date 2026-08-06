package com.codenames.keycards.camera

import android.graphics.Bitmap
import android.graphics.Matrix
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.codenames.keycards.vision.GridDetection
import com.codenames.keycards.vision.OpenCvGridDetector
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong

/** Lifecycle-bound CameraX preview and in-memory high-quality still capture. */
@Composable
fun CameraCaptureScreen(
  expectedRows: Int,
  expectedColumns: Int,
  captureInProgress: Boolean,
  onCapture: (Bitmap) -> Unit,
  onPickImage: () -> Unit,
  onError: (String) -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val lifecycleOwner = LocalLifecycleOwner.current
  val previewView = remember { PreviewView(context) }
  val mainExecutor = remember(context) { ContextCompat.getMainExecutor(context) }
  var imageCapture by remember { mutableStateOf<ImageCapture?>(null) }
  var qualityHint by remember { mutableStateOf("Keep every card and its outer edges visible.") }
  val analysisExecutor = remember(lifecycleOwner) { Executors.newSingleThreadExecutor() }
  val liveGridDetector = remember { OpenCvGridDetector() }
  val lastAnalysisNanos = remember { AtomicLong(0L) }

  DisposableEffect(lifecycleOwner) {
    val providerFuture = ProcessCameraProvider.getInstance(context)
    var disposed = false
    var targetRotation = previewView.display?.rotation ?: Surface.ROTATION_0
    var boundCapture: ImageCapture? = null
    var boundAnalysis: ImageAnalysis? = null
    val orientationListener =
      object : OrientationEventListener(context) {
        override fun onOrientationChanged(orientation: Int) {
          if (orientation == ORIENTATION_UNKNOWN) return
          targetRotation =
            when (orientation) {
              in 45 until 135 -> Surface.ROTATION_270
              in 135 until 225 -> Surface.ROTATION_180
              in 225 until 315 -> Surface.ROTATION_90
              else -> Surface.ROTATION_0
            }
          boundCapture?.targetRotation = targetRotation
          boundAnalysis?.targetRotation = targetRotation
        }
      }.also { it.enable() }
    val bindCamera = Runnable {
      if (disposed) return@Runnable
      runCatching {
        val provider = providerFuture.get()
        val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
        val capture =
          ImageCapture.Builder()
            .setTargetRotation(targetRotation)
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
        val analysis =
          ImageAnalysis.Builder()
            .setTargetRotation(targetRotation)
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
            .also { useCase ->
              useCase.setAnalyzer(analysisExecutor) { image ->
                try {
                  val now = System.nanoTime()
                  val previousAnalysis = lastAnalysisNanos.get()
                  if (now - previousAnalysis < 650_000_000L || !lastAnalysisNanos.compareAndSet(previousAnalysis, now)) return@setAnalyzer
                  val previewBitmap = image.toUprightBitmap()
                  val detection =
                    try {
                      liveGridDetector.detect(previewBitmap)
                    } finally {
                      previewBitmap.recycle()
                    }
                  val diagnostics = detection.diagnostics
                  val expectedCount = expectedRows * expectedColumns
                  val hint =
                    when {
                      (diagnostics.exposure ?: 128.0) < 52.0 -> "More light"
                      (diagnostics.exposure ?: 128.0) > 232.0 -> "Reduce glare"
                      (diagnostics.blurScore ?: 10.0) < 1.4 -> "Too blurry — hold still"
                      detection is GridDetection.Verified &&
                        ((detection.rows == expectedRows && detection.columns == expectedColumns) ||
                          (detection.rows == expectedColumns && detection.columns == expectedRows)) ->
                        "All $expectedCount cards found"
                      detection is GridDetection.Verified -> "Found ${detection.rows} × ${detection.columns}; expected $expectedRows × $expectedColumns"
                      diagnostics.cardCount in 1 until expectedCount -> "Move closer and keep every card visible"
                      (diagnostics.perspectiveSkew ?: 0.0) > .32 -> "Hold the phone more level"
                      else -> "Keep every card visible; you can still capture when the grid is uncertain"
                    }
                  mainExecutor.execute { qualityHint = hint }
                } catch (_: RuntimeException) {
                  mainExecutor.execute { qualityHint = "Keep every card visible; you can still capture" }
                } finally {
                  image.close()
                }
              }
            }
        if (disposed) return@runCatching
        provider.unbindAll()
        provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, capture, analysis)
        boundCapture = capture
        boundAnalysis = analysis
        imageCapture = capture
      }.onFailure { onError("Could not open the camera.") }
    }
    providerFuture.addListener(bindCamera, mainExecutor)
    onDispose {
      disposed = true
      orientationListener.disable()
      imageCapture = null
      boundCapture = null
      boundAnalysis = null
      analysisExecutor.shutdownNow()
      runCatching { providerFuture.get().unbindAll() }
    }
  }

  fun takePicture() {
    imageCapture?.takePicture(
      mainExecutor,
      object : ImageCapture.OnImageCapturedCallback() {
        override fun onCaptureSuccess(image: ImageProxy) {
          try {
            onCapture(image.toUprightBitmap())
          } catch (_: RuntimeException) {
            onError("Could not read the captured image. Please retake it.")
          } finally {
            image.close()
          }
        }

        override fun onError(exception: ImageCaptureException) {
          onError("Could not capture a photo. Please try again.")
        }
      },
    )
  }

  BoxWithConstraints(modifier = Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
    val landscape = maxWidth > maxHeight
    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
    CaptureGuide(modifier = Modifier.fillMaxSize())

    if (landscape) {
      Row(
        modifier = Modifier.fillMaxWidth().align(Alignment.TopCenter).padding(horizontal = 140.dp, vertical = 14.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CameraHint("Portrait or landscape · looking for $expectedRows × $expectedColumns cards", title = true)
        CameraHint(qualityHint)
      }
      Row(
        modifier = Modifier.align(Alignment.BottomCenter).padding(18.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        CaptureButton(imageCapture != null && !captureInProgress, captureInProgress, ::takePicture)
        PickImageButton(!captureInProgress, onPickImage)
      }
    } else {
      Column(
        modifier = Modifier.fillMaxWidth().align(Alignment.BottomCenter).padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(10.dp),
      ) {
        CameraHint("Portrait or landscape · looking for $expectedRows × $expectedColumns cards", title = true)
        CameraHint(qualityHint)
        CaptureButton(imageCapture != null && !captureInProgress, captureInProgress, ::takePicture)
        PickImageButton(!captureInProgress, onPickImage)
      }
    }
  }
}

@Composable
private fun CameraHint(text: String, title: Boolean = false) {
  Text(
    text,
    color = MaterialTheme.colorScheme.onPrimary,
    style = if (title) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
    modifier = Modifier.background(MaterialTheme.colorScheme.primary.copy(alpha = .88f)).padding(horizontal = 14.dp, vertical = 8.dp),
  )
}

@Composable
private fun CaptureButton(enabled: Boolean, captureInProgress: Boolean, onClick: () -> Unit) {
  Button(enabled = enabled, onClick = onClick) {
    if (captureInProgress) CircularProgressIndicator(modifier = Modifier.size(20.dp)) else Text("Capture word board")
  }
}

@Composable
private fun PickImageButton(enabled: Boolean, onClick: () -> Unit) {
  OutlinedButton(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.background(MaterialTheme.colorScheme.surface.copy(alpha = .90f), MaterialTheme.shapes.extraLarge),
  ) {
    Text("Choose existing photo")
  }
}

/** Loose framing only; card aspect ratio and grid geometry come from the detector. */
@Composable
private fun CaptureGuide(modifier: Modifier = Modifier) {
  androidx.compose.foundation.Canvas(modifier) {
    val left = size.width * .07f
    val right = size.width * .93f
    val top = size.height * .13f
    val bottom = size.height * .68f
    val corner = minOf(size.width, size.height) * .075f
    val stroke = 3.dp.toPx()
    val color = androidx.compose.ui.graphics.Color.White.copy(alpha = .88f)

    drawLine(color, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Offset(left + corner, top), stroke)
    drawLine(color, androidx.compose.ui.geometry.Offset(left, top), androidx.compose.ui.geometry.Offset(left, top + corner), stroke)
    drawLine(color, androidx.compose.ui.geometry.Offset(right, top), androidx.compose.ui.geometry.Offset(right - corner, top), stroke)
    drawLine(color, androidx.compose.ui.geometry.Offset(right, top), androidx.compose.ui.geometry.Offset(right, top + corner), stroke)
    drawLine(color, androidx.compose.ui.geometry.Offset(left, bottom), androidx.compose.ui.geometry.Offset(left + corner, bottom), stroke)
    drawLine(color, androidx.compose.ui.geometry.Offset(left, bottom), androidx.compose.ui.geometry.Offset(left, bottom - corner), stroke)
    drawLine(color, androidx.compose.ui.geometry.Offset(right, bottom), androidx.compose.ui.geometry.Offset(right - corner, bottom), stroke)
    drawLine(color, androidx.compose.ui.geometry.Offset(right, bottom), androidx.compose.ui.geometry.Offset(right, bottom - corner), stroke)
  }
}

private fun ImageProxy.toUprightBitmap(): Bitmap {
  val bitmap = toBitmap()
  val rotation = imageInfo.rotationDegrees
  return if (rotation == 0) {
    bitmap
  } else {
    Bitmap.createBitmap(bitmap, 0, 0, bitmap.width, bitmap.height, Matrix().apply { postRotate(rotation.toFloat()) }, true)
      .also { bitmap.recycle() }
  }
}
