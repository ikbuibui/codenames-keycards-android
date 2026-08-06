package com.codenames.keycards.ui.scan

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.LocalActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.viewmodel.compose.viewModel
import com.codenames.keycards.camera.CameraCaptureScreen
import com.codenames.keycards.model.BoardOrientation
import com.codenames.keycards.model.CandidateAgreement
import com.codenames.keycards.model.KeycardSettings
import com.codenames.keycards.model.OcrAlternative
import com.codenames.keycards.model.RecognizedBoard
import com.codenames.keycards.model.RecognizedCell
import com.codenames.keycards.model.needsOcrAttention
import com.codenames.keycards.model.sourceCellIndex
import com.codenames.keycards.vision.ScanResult

/** Optional, on-device scan flow. The only durable output is the reviewed word board. */
@Composable
fun ScanWordBoardFlow(
  settings: KeycardSettings,
  onUseWordBoard: (RecognizedBoard) -> Unit,
  onUpdateBoardSize: (rows: Int, columns: Int) -> Unit,
  onDismiss: () -> Unit,
) {
  val context = androidx.compose.ui.platform.LocalContext.current
  val activity = LocalActivity.current
  val factory = remember(context.applicationContext) {
    object : ViewModelProvider.Factory {
      override fun <T : ViewModel> create(modelClass: Class<T>): T {
        @Suppress("UNCHECKED_CAST")
        return ScanWordBoardViewModel(context.applicationContext) as T
      }
    }
  }
  val scanViewModel: ScanWordBoardViewModel = viewModel(factory = factory)
  val cameraGranted = ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
  LaunchedEffect(cameraGranted) { scanViewModel.synchronizeCameraPermission(cameraGranted) }
  LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
    scanViewModel.synchronizeCameraPermission(
      ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED,
    )
  }
  val permissionLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
      if (granted) {
        scanViewModel.showCamera()
      } else {
        scanViewModel.cameraPermissionDenied(
          permanentlyDenied = activity != null && !ActivityCompat.shouldShowRequestPermissionRationale(activity, Manifest.permission.CAMERA),
        )
      }
    }

  val photoLauncher =
    rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
      if (uri != null) scanViewModel.scanUri(uri, settings.boardRows, settings.boardColumns)
    }

  fun requestCamera() {
    if (ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
      scanViewModel.showCamera()
    } else {
      permissionLauncher.launch(Manifest.permission.CAMERA)
    }
  }

  fun pickPhoto() {
    photoLauncher.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))
  }

  fun dismiss() {
    scanViewModel.cancelAndReset()
    onDismiss()
  }

  when (val current = scanViewModel.stage) {
    ScanStage.PermissionExplanation ->
      ScanInfoScreen(
        title = "Scan word cards",
        body = "Use the camera or choose an existing photo. Grid verification and OCR stay on this device; the image stays only in memory while scanning or resolving a grid mismatch.",
        primaryLabel = "Allow camera",
        onPrimary = ::requestCamera,
        onSecondary = ::dismiss,
        tertiaryLabel = "Choose existing photo",
        onTertiary = ::pickPhoto,
      )

    is ScanStage.PermissionDenied ->
      ScanInfoScreen(
        title = "Camera permission needed",
        body = "Camera access is used only while you scan. You can also choose an existing photo without granting camera access.",
        primaryLabel = if (current.permanentlyDenied) "Open settings" else "Try again",
        onPrimary = {
          if (current.permanentlyDenied) {
            context.startActivity(
              Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.fromParts("package", context.packageName, null)
              },
            )
          } else {
            requestCamera()
          }
        },
        onSecondary = ::dismiss,
        tertiaryLabel = "Choose existing photo",
        onTertiary = ::pickPhoto,
      )

    ScanStage.Camera ->
      Box(Modifier.fillMaxSize()) {
        CameraCaptureScreen(
          expectedRows = settings.boardRows,
          expectedColumns = settings.boardColumns,
          captureInProgress = false,
          onCapture = { bitmap -> scanViewModel.scanBitmap(bitmap, settings.boardRows, settings.boardColumns) },
          onPickImage = ::pickPhoto,
          onError = { scanViewModel.cameraError() },
        )
        TextButton(
          onClick = ::dismiss,
          modifier = Modifier.align(Alignment.TopStart).padding(12.dp).background(MaterialTheme.colorScheme.surface.copy(alpha = .9f), RoundedCornerShape(20.dp)),
        ) { Text("Cancel") }
      }

    is ScanStage.Reading ->
      ScanInfoScreen(
        title = "Reading word cards",
        body = if (current.total > 0) "${current.current} of ${current.total} cards read" else "Verifying the card grid…",
        primaryLabel = null,
        onPrimary = {},
        onSecondary = ::dismiss,
        busy = true,
      )

    is ScanStage.Review ->
      WordBoardReviewScreen(
        review = current,
        expectedRows = settings.boardRows,
        expectedColumns = settings.boardColumns,
        onOrientationChanged = scanViewModel::selectReviewOrientation,
        onCellChanged = scanViewModel::updateReviewCell,
        onCellAcknowledged = scanViewModel::acknowledgeReviewCell,
        onUse = { board ->
          scanViewModel.cancelAndReset()
          onUseWordBoard(board)
        },
        onRetake = scanViewModel::showCamera,
      )

    is ScanStage.Mismatch ->
      GridMismatchScreen(
        result = current.result,
        onRetake = scanViewModel::showCamera,
        onUpdateBoardSize = {
          onUpdateBoardSize(current.result.foundRows, current.result.foundColumns)
          scanViewModel.rescanMismatchImage(current.result.foundRows, current.result.foundColumns)
        },
        onBackToSettings = ::dismiss,
      )

    is ScanStage.Uncertain ->
      ScanInfoScreen(
        title = "Could not verify the card grid",
        body = "The app could not independently assign one card to every grid position. Move closer, reduce glare, or choose another photo. Uneven spacing is allowed, but ambiguous or missing cards are not accepted.",
        primaryLabel = "Retake image",
        onPrimary = scanViewModel::showCamera,
        onSecondary = ::dismiss,
        tertiaryLabel = "Choose another photo",
        onTertiary = ::pickPhoto,
      )
  }
}

@Composable
private fun ScanInfoScreen(
  title: String,
  body: String,
  primaryLabel: String?,
  onPrimary: () -> Unit,
  onSecondary: () -> Unit,
  secondaryLabel: String = "Cancel",
  tertiaryLabel: String? = null,
  onTertiary: () -> Unit = {},
  busy: Boolean = false,
) {
  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(
      modifier =
        Modifier
          .fillMaxSize()
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .padding(24.dp),
      contentAlignment = Alignment.Center,
    ) {
      Card(
        modifier = Modifier.widthIn(max = 600.dp).fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
      ) {
        Column(
          modifier = Modifier.fillMaxWidth().padding(24.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
          verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
          Text(
            title,
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
          )
          Text(
            body,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
          )
          if (busy) CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
          if (primaryLabel != null) Button(onClick = onPrimary, modifier = Modifier.fillMaxWidth()) { Text(primaryLabel) }
          if (tertiaryLabel != null) OutlinedButton(onClick = onTertiary, modifier = Modifier.fillMaxWidth()) { Text(tertiaryLabel) }
          TextButton(onClick = onSecondary, modifier = Modifier.fillMaxWidth()) { Text(secondaryLabel) }
        }
      }
    }
  }
}

@Composable
private fun GridMismatchScreen(
  result: ScanResult.GridMismatch,
  onRetake: () -> Unit,
  onUpdateBoardSize: () -> Unit,
  onBackToSettings: () -> Unit,
) =
  ScanInfoScreen(
    title = "Card grid does not match",
    body = "Expected ${result.expectedRows} × ${result.expectedColumns} · Found ${result.foundRows} × ${result.foundColumns}. Updating the board size creates a matching keycard and rereads this same image.",
    primaryLabel = "Retake image",
    onPrimary = onRetake,
    onSecondary = onBackToSettings,
    secondaryLabel = "Back to settings",
    tertiaryLabel = "Update board size",
    onTertiary = onUpdateBoardSize,
  )

@Composable
private fun WordBoardReviewScreen(
  review: ScanStage.Review,
  expectedRows: Int,
  expectedColumns: Int,
  onOrientationChanged: (BoardOrientation) -> Unit,
  onCellChanged: (displayedIndex: Int, text: String) -> Unit,
  onCellAcknowledged: (displayedIndex: Int) -> Unit,
  onUse: (RecognizedBoard) -> Unit,
  onRetake: () -> Unit,
) {
  val board = review.board
  var editingIndex by rememberSaveable(review.orientation) { mutableIntStateOf(-1) }
  var showAllWords by rememberSaveable { mutableStateOf(false) }
  val unresolvedIndices =
    board.cells.indices.filter { displayedIndex ->
      val sourceIndex = sourceCellIndex(displayedIndex, review.sourceBoard.rows, review.sourceBoard.columns, review.orientation)
      review.sourceBoard.cells[sourceIndex].needsOcrAttention() && sourceIndex !in review.acknowledgedSourceIndices
    }
  val unresolvedSet = unresolvedIndices.toSet()
  val duplicateValues = board.cells.groupingBy { it.text.trim().lowercase() }.eachCount().filter { it.key.isNotBlank() && it.value > 1 }
  val dimensionsStillMatch = board.rows == expectedRows && board.columns == expectedColumns

  Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
    Box(Modifier.fillMaxSize()) {
      LazyColumn(
        modifier =
          Modifier
            .align(Alignment.TopCenter)
            .widthIn(max = 720.dp)
            .fillMaxWidth()
            .fillMaxHeight()
            .windowInsetsPadding(WindowInsets.safeDrawing),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        item {
          Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("Review word board", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text(
              "Spymaster view — these words reveal the keycard.",
              color = MaterialTheme.colorScheme.error,
            )
            Text(
              when {
                unresolvedIndices.isEmpty() -> "No words need attention. Confirm the board orientation and continue."
                unresolvedIndices.size == 1 -> "1 word needs attention. Confident reads are hidden from the checklist."
                else -> "${unresolvedIndices.size} words need attention. Confident reads are hidden from the checklist."
              },
              color = if (unresolvedIndices.isEmpty()) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.tertiary,
            )
          }
        }

        item {
          CompactWordBoard(
            board = board,
            unresolvedIndices = unresolvedSet,
            onCellClick = { editingIndex = it },
          )
        }

        item {
          OrientationChoices(
            choices = review.compatibleOrientations,
            selected = review.orientation,
            onSelected = onOrientationChanged,
          )
        }

        if (duplicateValues.isNotEmpty()) {
          item {
            Text(
              "Duplicate words are allowed. ${duplicateValues.size} duplicate value(s) appear on this board.",
              color = MaterialTheme.colorScheme.tertiary,
            )
          }
        }

        items(
          items = unresolvedIndices,
          key = { displayedIndex ->
            val sourceIndex = sourceCellIndex(displayedIndex, review.sourceBoard.rows, review.sourceBoard.columns, review.orientation)
            "attention-$sourceIndex"
          },
        ) { displayedIndex ->
          ReviewWordRow(
            board = board,
            displayedIndex = displayedIndex,
            requiresAction = true,
            onEdit = { editingIndex = displayedIndex },
            onKeep = { onCellAcknowledged(displayedIndex) },
          )
        }

        item {
          TextButton(onClick = { showAllWords = !showAllWords }, modifier = Modifier.fillMaxWidth()) {
            Text(if (showAllWords) "Hide confident words" else "Review all ${board.cells.size} recognized words")
          }
        }

        if (showAllWords) {
          items(
            items = board.cells.indices.toList(),
            key = { displayedIndex ->
              val sourceIndex = sourceCellIndex(displayedIndex, review.sourceBoard.rows, review.sourceBoard.columns, review.orientation)
              "all-$sourceIndex"
            },
          ) { displayedIndex ->
            ReviewWordRow(
              board = board,
              displayedIndex = displayedIndex,
              requiresAction = false,
              onEdit = { editingIndex = displayedIndex },
              onKeep = {},
            )
          }
        }

        if (!board.isComplete) {
          item { Text("Enter a word for every missing card before continuing.", color = MaterialTheme.colorScheme.tertiary) }
        }
        if (unresolvedIndices.any { board.cells[it].text.isNotBlank() }) {
          item { Text("Edit each uncertain word or choose Keep this word.", color = MaterialTheme.colorScheme.tertiary) }
        }
        if (!dimensionsStillMatch) {
          item { Text("Choose an orientation that matches the configured board dimensions.", color = MaterialTheme.colorScheme.tertiary) }
        }

        item {
          Button(
            onClick = { onUse(board) },
            enabled = review.isReadyToUse && dimensionsStillMatch,
            modifier = Modifier.fillMaxWidth(),
          ) {
            Text("Use word board")
          }
        }
        item { OutlinedButton(onClick = onRetake, modifier = Modifier.fillMaxWidth()) { Text("Retake image") } }
      }
    }
  }

  if (editingIndex >= 0 && editingIndex < board.cells.size) {
    val cell = board.cells[editingIndex]
    WordEditorDialog(
      initialText = cell.text,
      alternatives = cell.alternatives,
      onDismiss = { editingIndex = -1 },
      onSave = { text ->
        onCellChanged(editingIndex, text)
        editingIndex = -1
      },
    )
  }
}

@Composable
private fun CompactWordBoard(
  board: RecognizedBoard,
  unresolvedIndices: Set<Int>,
  onCellClick: (Int) -> Unit,
) {
  BoxWithConstraints(Modifier.fillMaxWidth()) {
    val spacing = 2.dp
    val cellWidth = (maxWidth - spacing * (board.columns - 1)) / board.columns
    val cellHeight = minOf(42.dp, cellWidth * .62f, 220.dp / board.rows)
    val showText = cellWidth >= 38.dp && cellHeight >= 20.dp
    Column(verticalArrangement = Arrangement.spacedBy(spacing)) {
      repeat(board.rows) { row ->
        Row(Modifier.fillMaxWidth().height(cellHeight), horizontalArrangement = Arrangement.spacedBy(spacing)) {
          repeat(board.columns) { column ->
            val index = row * board.columns + column
            val cell = board.cells[index]
            val description =
              "Row ${row + 1}, column ${column + 1}, ${cell.text.ifBlank { "missing word" }}, ${if (index in unresolvedIndices) "needs attention" else "ready"}"
            Card(
              modifier =
                Modifier
                  .weight(1f)
                  .fillMaxHeight()
                  .semantics { contentDescription = description }
                  .clickable { onCellClick(index) },
              colors = CardDefaults.cardColors(containerColor = compactCellTint(cell, index in unresolvedIndices)),
            ) {
              Box(Modifier.fillMaxSize().padding(horizontal = 2.dp), contentAlignment = Alignment.Center) {
                Text(
                  text = if (showText) cell.text.ifBlank { "!" } else if (index in unresolvedIndices) "!" else "",
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  fontSize = if (cellWidth >= 70.dp) 10.sp else 8.sp,
                  fontWeight = FontWeight.Bold,
                  textAlign = TextAlign.Center,
                )
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun OrientationChoices(
  choices: List<BoardOrientation>,
  selected: BoardOrientation,
  onSelected: (BoardOrientation) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text("Board orientation", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
    Text("Choose the view whose top-left word matches the physical board.", style = MaterialTheme.typography.bodySmall)
    choices.chunked(2).forEach { rowChoices ->
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        rowChoices.forEach { orientation ->
          if (orientation == selected) {
            Button(onClick = { onSelected(orientation) }, modifier = Modifier.weight(1f)) {
              Text(orientation.displayName())
            }
          } else {
            OutlinedButton(onClick = { onSelected(orientation) }, modifier = Modifier.weight(1f)) {
              Text(orientation.displayName())
            }
          }
        }
        if (rowChoices.size == 1) Spacer(Modifier.weight(1f))
      }
    }
  }
}

@Composable
private fun ReviewWordRow(
  board: RecognizedBoard,
  displayedIndex: Int,
  requiresAction: Boolean,
  onEdit: () -> Unit,
  onKeep: () -> Unit,
) {
  val row = displayedIndex / board.columns
  val column = displayedIndex % board.columns
  val cell = board.cells[displayedIndex]
  Card(
    colors = CardDefaults.cardColors(containerColor = if (requiresAction) Color(0xFF755000) else MaterialTheme.colorScheme.surfaceVariant),
  ) {
    Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
      Text("Row ${row + 1}, column ${column + 1}", style = MaterialTheme.typography.labelLarge)
      Text(cell.text.ifBlank { "Missing word" }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
      Text(cell.reviewStatus(), style = MaterialTheme.typography.bodySmall)
      Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedButton(onClick = onEdit, modifier = Modifier.weight(1f)) { Text("Edit") }
        if (requiresAction && cell.text.isNotBlank()) {
          TextButton(onClick = onKeep, modifier = Modifier.weight(1f)) { Text("Keep this word") }
        }
      }
    }
  }
}

@Composable
private fun WordEditorDialog(
  initialText: String,
  alternatives: List<OcrAlternative>,
  onDismiss: () -> Unit,
  onSave: (String) -> Unit,
) {
  var text by rememberSaveable(initialText) { mutableStateOf(initialText) }
  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Fix word") },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(
          value = text,
          onValueChange = { text = it },
          singleLine = true,
          label = { Text("Word or short phrase") },
        )
        if (alternatives.isNotEmpty()) {
          Text("Other OCR reads", style = MaterialTheme.typography.labelLarge)
          alternatives.forEach { alternative ->
            OutlinedButton(
              onClick = { text = alternative.text },
              modifier = Modifier.fillMaxWidth(),
            ) {
              Text(
                alternative.confidence?.let { "${alternative.text} · $it%" } ?: alternative.text,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
              )
            }
          }
        }
      }
    },
    confirmButton = { TextButton(onClick = { onSave(text.trim().replace(Regex("\\s+"), " ")) }) { Text("Save") } },
    dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
  )
}

private fun BoardOrientation.displayName(): String =
  when (this) {
    BoardOrientation.ORIGINAL -> "0°"
    BoardOrientation.CLOCKWISE -> "90° right"
    BoardOrientation.HALF_TURN -> "180°"
    BoardOrientation.COUNTERCLOCKWISE -> "90° left"
  }

private fun RecognizedCell.reviewStatus(): String =
  when {
    text.isBlank() -> "Missing — edit"
    manuallyEdited -> "Edited manually"
    text.count(Char::isLetterOrDigit) <= 1 -> confidence?.let { "Suspiciously short read · $it%" } ?: "Suspiciously short read"
    candidateAgreement == CandidateAgreement.CONFLICT -> confidence?.let { "Conflicting reads · $it%" } ?: "Conflicting reads"
    (confidence ?: 100) < 55 -> "Low confidence · ${confidence ?: 0}%"
    candidateAgreement == CandidateAgreement.AGREED -> confidence?.let { "Matched · $it%" } ?: "Matched"
    else -> confidence?.let { "One read · $it%" } ?: "One read"
  }

@Composable
private fun compactCellTint(cell: RecognizedCell, unresolved: Boolean): Color =
  when {
    cell.text.isBlank() -> MaterialTheme.colorScheme.errorContainer
    unresolved -> Color(0xFF755000)
    else -> MaterialTheme.colorScheme.surfaceVariant
  }
