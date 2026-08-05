package com.codenames.keycards.ui

import android.app.Activity
import android.view.ContextThemeWrapper
import android.view.WindowManager
import android.widget.NumberPicker
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.codenames.keycards.data.GameStateStore
import com.codenames.keycards.model.DEFAULT_TURN_DURATION_SECONDS
import com.codenames.keycards.model.GameState
import com.codenames.keycards.model.KeycardSettings
import com.codenames.keycards.model.MAX_BOARD_SIZE
import com.codenames.keycards.model.MIN_BOARD_SIZE
import com.codenames.keycards.model.TurnTimer
import com.codenames.keycards.model.TileRole
import com.codenames.keycards.model.advanceTurn
import com.codenames.keycards.model.exitToSetup
import com.codenames.keycards.model.generateKeycard
import com.codenames.keycards.model.maximumTeamCount
import com.codenames.keycards.model.maximumTilesPerTeam
import com.codenames.keycards.model.minimumBoardSize
import com.codenames.keycards.model.normalized
import com.codenames.keycards.model.normalizedGameState
import com.codenames.keycards.model.pauseGame
import com.codenames.keycards.model.resumeGame
import com.codenames.keycards.model.startGame
import com.codenames.keycards.model.tickTimer
import com.codenames.keycards.theme.CodenamesKeycardsTheme
import kotlinx.coroutines.delay
import java.util.Locale

private val Red = Color(0xFFB33038)
private val Blue = Color(0xFF288DC9)
private val Green = Color(0xFF2A9034)
private val Orange = Color(0xFFDA781F)
private val Bystander = Color(0xFFD7C69F)
private val Assassin = Color(0xFF404040)
private val WoodLight = Color(0xFFC8AB99)
private val WoodDark = Color(0xFFA88573)
private const val MAX_TURN_DURATION_MINUTES = 59

private data class TeamOption(val number: Int, val name: String, val color: Color, val symbol: String)

private val teams =
  listOf(
    TeamOption(1, "Red", Red, "◆"),
    TeamOption(2, "Blue", Blue, "●"),
    TeamOption(3, "Green", Green, "■"),
    TeamOption(4, "Orange", Orange, "▲"),
  )

@Composable
fun KeycardApp() {
  val appContext = LocalContext.current.applicationContext
  val gameStateStore = remember(appContext) { GameStateStore(appContext) }
  var gameState by remember { mutableStateOf(gameStateStore.load()) }

  fun updateState(change: (GameState) -> GameState) {
    gameState = normalizedGameState(change(gameState))
    gameStateStore.save(gameState)
  }

  fun updateSettings(change: (KeycardSettings) -> KeycardSettings) {
    updateState { state -> state.copy(settings = normalized(change(state.settings))) }
  }

  KeepScreenOn(keepScreenOn = gameState.isRunning)
  PauseWhenActivityStops(
    gameState = gameState,
    onPause = { updateState(::pauseGame) },
  )

  LaunchedEffect(gameState.isRunning, gameState.timer.durationSeconds, gameState.remainingSeconds) {
    if (gameState.isRunning && gameState.timer.hasTimer && gameState.remainingSeconds!! > 0) {
      delay(1_000)
      updateState(::tickTimer)
    }
  }

  if (gameState.gameMode) {
    GameScreen(
      gameState = gameState,
      onAdvanceTurn = { updateState(::advanceTurn) },
      onPause = { updateState(::pauseGame) },
      onResume = { updateState(::resumeGame) },
      onExit = { updateState(::exitToSetup) },
    )
  } else {
    SetupScreen(
      gameState = gameState,
      onGenerate = {
        updateSettings { settings ->
          settings.copy(seed = maxOf(System.currentTimeMillis(), settings.seed + 1))
        }
      },
      onStartGame = { updateState(::startGame) },
      onSettingsChanged = ::updateSettings,
      onTimerChanged = { timer -> updateState { state -> state.copy(timer = timer) } },
    )
  }
}

@Composable
private fun PauseWhenActivityStops(gameState: GameState, onPause: () -> Unit) {
  val lifecycleOwner = LocalLifecycleOwner.current
  val latestIsRunning by rememberUpdatedState(gameState.isRunning)
  val latestOnPause by rememberUpdatedState(onPause)
  DisposableEffect(lifecycleOwner) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_STOP && latestIsRunning) latestOnPause()
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
  }
}

@Composable
private fun KeepScreenOn(keepScreenOn: Boolean) {
  val activity = LocalContext.current as? Activity
  DisposableEffect(activity, keepScreenOn) {
    if (keepScreenOn) {
      activity?.window?.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    } else {
      activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    onDispose { activity?.window?.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON) }
  }
}

@Composable
private fun SetupScreen(
  gameState: GameState,
  onGenerate: () -> Unit,
  onStartGame: () -> Unit,
  onSettingsChanged: ((KeycardSettings) -> KeycardSettings) -> Unit,
  onTimerChanged: (TurnTimer) -> Unit,
) {
  val settings = gameState.settings
  val board = remember(settings) { generateKeycard(settings) }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Column(
      modifier =
        Modifier
          .fillMaxWidth()
          .widthIn(max = 720.dp)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Header()
      KeycardBoard(board = board, settings = settings)
      BoardActions(onGenerate = onGenerate, onStartGame = onStartGame)
      SettingsPanel(
        settings = settings,
        timer = gameState.timer,
        onSettingsChanged = onSettingsChanged,
        onTimerChanged = onTimerChanged,
      )
      Text(
        text = "Saved only on this device. This app has no network permission, web view, or remote service.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      )
    }
  }
}

@Composable
private fun Header() {
  Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
    Text(text = "CODENAMES", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
    Text(
      text = "Keycard generator · offline",
      style = MaterialTheme.typography.titleSmall,
      color = MaterialTheme.colorScheme.primary,
      fontWeight = FontWeight.SemiBold,
    )
  }
}

@Composable
private fun KeycardBoard(board: List<Int>, settings: KeycardSettings) {
  val firstTeam = settings.turnOrder.firstOrNull()
  val borderColor = firstTeam?.let(::teamFor)?.color ?: WoodLight

  BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val boardWidth = minOf(maxWidth - 8.dp, 560.dp)
    val symbolSize = (boardWidth.value / settings.boardSize * 0.45f).coerceIn(10f, 30f).sp

    Box(
      modifier =
        Modifier
          .width(boardWidth)
          .aspectRatio(1f)
          .clip(RoundedCornerShape(30.dp))
          .background(borderColor)
          .padding(if (firstTeam == null) 0.dp else 7.dp),
    ) {
      Box(
        modifier =
          Modifier
            .fillMaxSize()
            .clip(RoundedCornerShape(24.dp))
            .background(WoodLight)
            .padding(9.dp)
            .clip(RoundedCornerShape(17.dp))
            .background(WoodDark)
            .padding(10.dp)
            .clip(RoundedCornerShape(10.dp))
            .background(Color.Black)
            .padding(5.dp),
      ) {
        Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
          repeat(settings.boardSize) { row ->
            Row(
              modifier = Modifier.fillMaxWidth().weight(1f),
              horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
              repeat(settings.boardSize) { column ->
                val role = board[row * settings.boardSize + column]
                KeycardTile(role = role, symbolSize = symbolSize, modifier = Modifier.weight(1f).fillMaxHeight())
              }
            }
          }
        }
      }
    }
  }
}

@Composable
private fun KeycardTile(role: Int, symbolSize: androidx.compose.ui.unit.TextUnit, modifier: Modifier = Modifier) {
  val symbol =
    when (role) {
      TileRole.ASSASSIN -> "×"
      TileRole.BYSTANDER -> ""
      else -> teamFor(role).symbol
    }
  Box(
    contentAlignment = Alignment.Center,
    modifier = modifier.clip(RoundedCornerShape(4.dp)).background(tileColor(role)),
  ) {
    if (symbol.isNotEmpty()) {
      Text(
        text = symbol,
        color = Color.White.copy(alpha = 0.52f),
        fontSize = symbolSize,
        fontWeight = FontWeight.Bold,
        lineHeight = symbolSize,
      )
    }
  }
}

@Composable
private fun BoardActions(onGenerate: () -> Unit, onStartGame: () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    Button(onClick = onStartGame, modifier = Modifier.fillMaxWidth()) {
      Text("Start game")
    }
    OutlinedButton(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
      Text("Generate new board")
    }
  }
}

@Composable
private fun SettingsPanel(
  settings: KeycardSettings,
  timer: TurnTimer,
  onSettingsChanged: ((KeycardSettings) -> KeycardSettings) -> Unit,
  onTimerChanged: (TurnTimer) -> Unit,
) {
  val minBoard = minimumBoardSize(settings.teamCount, settings.tilesPerTeam, settings.firstTeamBonus)
  val maxTiles = maximumTilesPerTeam(settings.boardSize, settings.teamCount, settings.firstTeamBonus)
  val maxTeams = maximumTeamCount(settings.boardSize, settings.tilesPerTeam, settings.firstTeamBonus)

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
          "Your setup and board are saved automatically.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      SettingStepper(
        title = "Board size",
        value = settings.boardSize,
        decreaseEnabled = settings.boardSize > minBoard,
        increaseEnabled = settings.boardSize < MAX_BOARD_SIZE,
        onDecrease = { onSettingsChanged { it.copy(boardSize = it.boardSize - 1) } },
        onIncrease = { onSettingsChanged { it.copy(boardSize = it.boardSize + 1) } },
      )
      SettingStepper(
        title = "Tiles per team",
        value = settings.tilesPerTeam,
        decreaseEnabled = settings.tilesPerTeam > 1,
        increaseEnabled = settings.tilesPerTeam < maxTiles,
        onDecrease = { onSettingsChanged { it.copy(tilesPerTeam = it.tilesPerTeam - 1) } },
        onIncrease = { onSettingsChanged { it.copy(tilesPerTeam = it.tilesPerTeam + 1) } },
      )
      SettingStepper(
        title = "Number of teams",
        value = settings.teamCount,
        decreaseEnabled = settings.teamCount > 2,
        increaseEnabled = settings.teamCount < maxTeams,
        onDecrease = { onSettingsChanged { it.copy(teamCount = it.teamCount - 1) } },
        onIncrease = { onSettingsChanged { it.copy(teamCount = it.teamCount + 1) } },
      )
      TurnOrderEditor(
        turnOrder = settings.turnOrder,
        onMove = { from, to ->
          onSettingsChanged { current -> current.copy(turnOrder = moveTeam(current.turnOrder, from, to)) }
        },
      )
      FirstTeamBonusSetting(
        enabled = settings.firstTeamBonus,
        firstTeam = teamFor(settings.turnOrder.first()),
        onEnabledChanged = { enabled -> onSettingsChanged { it.copy(firstTeamBonus = enabled) } },
      )
      TimerSetting(timer = timer, onTimerChanged = onTimerChanged)
      Text(
        text = "Board sizes run from $MIN_BOARD_SIZE×$MIN_BOARD_SIZE to $MAX_BOARD_SIZE×$MAX_BOARD_SIZE. A setting is unavailable when the board cannot fit every team tile, the assassin, and the optional extra tile.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SettingStepper(
  title: String,
  value: Int,
  decreaseEnabled: Boolean,
  increaseEnabled: Boolean,
  onDecrease: () -> Unit,
  onIncrease: () -> Unit,
) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Text(title, style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
    Row(
      verticalAlignment = Alignment.CenterVertically,
      modifier = Modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
    ) {
      TextButton(onClick = onDecrease, enabled = decreaseEnabled, modifier = Modifier.size(42.dp)) { Text("−", fontSize = 22.sp) }
      Text(value.toString(), fontWeight = FontWeight.Bold, textAlign = TextAlign.Center, modifier = Modifier.width(28.dp))
      TextButton(onClick = onIncrease, enabled = increaseEnabled, modifier = Modifier.size(42.dp)) { Text("+", fontSize = 20.sp) }
    }
  }
}

@Composable
private fun TurnOrderEditor(turnOrder: List<Int>, onMove: (from: Int, to: Int) -> Unit) {
  var draggingTeam by remember { mutableStateOf<Int?>(null) }
  var dragOffset by remember { mutableFloatStateOf(0f) }
  val moveThreshold = with(LocalDensity.current) { 36.dp.toPx() }
  val latestTurnOrder by rememberUpdatedState(turnOrder)
  val latestOnMove by rememberUpdatedState(onMove)

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Turn order", style = MaterialTheme.typography.titleSmall)
    Text(
      "Long-press and drag teams. The first team begins the game.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      turnOrder.forEachIndexed { index, teamNumber ->
        key(teamNumber) {
          val team = teamFor(teamNumber)
          val isDragging = draggingTeam == team.number
          Surface(
          color = if (index == 0) team.color.copy(alpha = 0.18f) else MaterialTheme.colorScheme.surfaceVariant,
          shape = RoundedCornerShape(12.dp),
          modifier =
            Modifier
              .fillMaxWidth()
              .graphicsLayer { translationY = if (isDragging) dragOffset else 0f }
              .border(if (index == 0) 1.dp else 0.dp, if (index == 0) team.color else Color.Transparent, RoundedCornerShape(12.dp))
              .pointerInput(team.number) {
                detectDragGesturesAfterLongPress(
                  onDragStart = {
                    draggingTeam = team.number
                    dragOffset = 0f
                  },
                  onDragCancel = {
                    draggingTeam = null
                    dragOffset = 0f
                  },
                  onDragEnd = {
                    draggingTeam = null
                    dragOffset = 0f
                  },
                  onDrag = { change, dragAmount ->
                    change.consume()
                    dragOffset += dragAmount.y
                    val currentIndex = latestTurnOrder.indexOf(team.number)
                    when {
                      dragOffset > moveThreshold && currentIndex < latestTurnOrder.lastIndex -> {
                        latestOnMove(currentIndex, currentIndex + 1)
                        dragOffset -= moveThreshold
                      }
                      dragOffset < -moveThreshold && currentIndex > 0 -> {
                        latestOnMove(currentIndex, currentIndex - 1)
                        dragOffset += moveThreshold
                      }
                    }
                  },
                )
              }
              .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☰", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 18.sp)
            Spacer(Modifier.width(10.dp))
            Box(modifier = Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(team.color))
            Spacer(Modifier.width(8.dp))
            Text("${team.name} team", fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            if (index == 0) {
              Text("First", style = MaterialTheme.typography.labelMedium, color = team.color, fontWeight = FontWeight.Bold)
            }
          }
        }
      }
    }
  }
}
}

@Composable
private fun FirstTeamBonusSetting(enabled: Boolean, firstTeam: TeamOption, onEnabledChanged: (Boolean) -> Unit) {
  Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
    Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("First team +1 tile", style = MaterialTheme.typography.titleSmall)
      Text(
        if (enabled) "${firstTeam.name} gets one extra board tile." else "No team gets an extra board tile.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    Switch(checked = enabled, onCheckedChange = onEnabledChanged)
  }
}

@Composable
private fun TimerSetting(timer: TurnTimer, onTimerChanged: (TurnTimer) -> Unit) {
  var showDurationPicker by remember { mutableStateOf(false) }

  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Turn timer", style = MaterialTheme.typography.titleSmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      FilterChip(
        selected = !timer.hasTimer,
        onClick = { onTimerChanged(TurnTimer()) },
        label = { Text("No timer  ∞") },
      )
      FilterChip(
        selected = timer.hasTimer,
        onClick = { showDurationPicker = true },
        label = { Text(timer.durationSeconds?.let(::formatDuration) ?: "Set duration") },
      )
    }
    Text(
      if (timer.hasTimer) "Tap the duration to change it." else "The timer control still advances turns.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }

  if (showDurationPicker) {
    DurationPickerDialog(
      initialDurationSeconds = timer.durationSeconds ?: DEFAULT_TURN_DURATION_SECONDS,
      onDismiss = { showDurationPicker = false },
      onDurationSelected = { seconds ->
        onTimerChanged(TurnTimer(seconds))
        showDurationPicker = false
      },
    )
  }
}

@Composable
private fun DurationPickerDialog(
  initialDurationSeconds: Int,
  onDismiss: () -> Unit,
  onDurationSelected: (Int) -> Unit,
) {
  var minutes by remember(initialDurationSeconds) {
    mutableStateOf((initialDurationSeconds / 60).coerceIn(0, MAX_TURN_DURATION_MINUTES))
  }
  var seconds by remember(initialDurationSeconds) { mutableStateOf(initialDurationSeconds % 60) }
  val selectedDurationSeconds = minutes * 60 + seconds

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Turn duration") },
    text = {
      Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
      ) {
        RollingNumberPicker(
          label = "Minutes",
          value = minutes,
          range = 0..MAX_TURN_DURATION_MINUTES,
          onValueChanged = { minutes = it },
        )
        RollingNumberPicker(
          label = "Seconds",
          value = seconds,
          range = 0..59,
          formatter = { String.format(Locale.getDefault(), "%02d", it) },
          onValueChanged = { seconds = it },
        )
      }
    },
    confirmButton = {
      TextButton(
        onClick = { onDurationSelected(selectedDurationSeconds) },
        enabled = selectedDurationSeconds > 0,
      ) {
        Text("Set timer")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) { Text("Cancel") }
    },
  )
}

@Composable
private fun RollingNumberPicker(
  label: String,
  value: Int,
  range: IntRange,
  formatter: (Int) -> String = Int::toString,
  onValueChanged: (Int) -> Unit,
) {
  Column(horizontalAlignment = Alignment.CenterHorizontally) {
    AndroidView(
      modifier = Modifier.width(100.dp).height(160.dp).semantics { contentDescription = "$label picker" },
      factory = { context ->
        NumberPicker(ContextThemeWrapper(context, android.R.style.Theme_Material_NoActionBar)).apply {
          minValue = range.first
          maxValue = range.last
          wrapSelectorWheel = true
          setFormatter(NumberPicker.Formatter { number -> formatter(number) })
        }
      },
      update = { picker ->
        if (picker.value != value) picker.value = value
        picker.setOnValueChangedListener { _, _, newValue -> onValueChanged(newValue) }
      },
    )
    Text(label, style = MaterialTheme.typography.labelLarge)
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun GameScreen(
  gameState: GameState,
  onAdvanceTurn: () -> Unit,
  onPause: () -> Unit,
  onResume: () -> Unit,
  onExit: () -> Unit,
) {
  val settings = gameState.settings
  val board = remember(settings) { generateKeycard(settings) }
  val activeTeam = teamFor(gameState.activeTeam)
  var showPauseSheet by remember { mutableStateOf(false) }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Column(modifier = Modifier.fillMaxSize()) {
      Spacer(modifier = Modifier.fillMaxWidth().height(5.dp).background(activeTeam.color))
      Column(
        modifier =
          Modifier
            .fillMaxWidth()
            .widthIn(max = 720.dp)
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 16.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
      ) {
        GameTimerControl(
          team = activeTeam,
          timer = gameState.timer,
          remainingSeconds = gameState.remainingSeconds,
          isPaused = gameState.isPaused,
          onAdvanceTurn = onAdvanceTurn,
        )
        KeycardBoard(board = board, settings = settings)
        OutlinedButton(
          onClick = {
            if (!gameState.isPaused) onPause()
            showPauseSheet = true
          },
          modifier = Modifier.align(Alignment.CenterHorizontally).semantics { contentDescription = "Open pause menu" },
        ) {
          Text("Ⅱ", fontSize = 18.sp)
        }
      }
    }
  }

  if (showPauseSheet) {
    ModalBottomSheet(onDismissRequest = { showPauseSheet = false }) {
      Column(
        modifier = Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, bottom = 32.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
      ) {
        Text("Game paused", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Button(
          onClick = {
            onResume()
            showPauseSheet = false
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Resume game")
        }
        OutlinedButton(
          onClick = {
            onExit()
            showPauseSheet = false
          },
          modifier = Modifier.fillMaxWidth(),
        ) {
          Text("Exit to setup")
        }
      }
    }
  }
}

@Composable
private fun GameTimerControl(
  team: TeamOption,
  timer: TurnTimer,
  remainingSeconds: Int?,
  isPaused: Boolean,
  onAdvanceTurn: () -> Unit,
) {
  Surface(
    color = MaterialTheme.colorScheme.surface,
    shape = RoundedCornerShape(20.dp),
    modifier =
      Modifier
        .fillMaxWidth()
        .border(2.dp, team.color, RoundedCornerShape(20.dp))
        .clip(RoundedCornerShape(20.dp))
        .clickable(enabled = !isPaused, onClick = onAdvanceTurn)
        .padding(vertical = 14.dp, horizontal = 20.dp),
  ) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
      Text("${team.name} team", color = team.color, fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
      Text(
        text = if (timer.hasTimer) formatDuration(remainingSeconds ?: timer.durationSeconds!!) else "∞",
        fontSize = 48.sp,
        lineHeight = 52.sp,
        fontWeight = FontWeight.Black,
      )
      Text(
        if (isPaused) "Paused" else "Tap to advance turn",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

private fun moveTeam(order: List<Int>, from: Int, to: Int): List<Int> {
  if (from !in order.indices || to !in order.indices || from == to) return order
  return order.toMutableList().apply { add(to, removeAt(from)) }
}

private fun formatDuration(totalSeconds: Int): String {
  val hours = totalSeconds / 3_600
  val minutes = totalSeconds % 3_600 / 60
  val seconds = totalSeconds % 60
  return if (hours > 0) {
    String.format(Locale.getDefault(), "%d:%02d:%02d", hours, minutes, seconds)
  } else {
    String.format(Locale.getDefault(), "%02d:%02d", minutes, seconds)
  }
}

private fun teamFor(role: Int): TeamOption = teams.first { it.number == role }

private fun tileColor(role: Int): Color =
  when (role) {
    TileRole.ASSASSIN -> Assassin
    TileRole.BYSTANDER -> Bystander
    else -> teamFor(role).color
  }

@Preview(showBackground = true, backgroundColor = 0xFF181818)
@Composable
private fun SetupPreview() {
  CodenamesKeycardsTheme {
    SetupScreen(
      gameState = GameState(settings = KeycardSettings(seed = 42L)),
      onGenerate = {},
      onStartGame = {},
      onSettingsChanged = {},
      onTimerChanged = {},
    )
  }
}

@Preview(showBackground = true, backgroundColor = 0xFF181818)
@Composable
private fun GamePreview() {
  CodenamesKeycardsTheme {
    GameScreen(
      gameState = startGame(GameState(settings = KeycardSettings(seed = 42L))),
      onAdvanceTurn = {},
      onPause = {},
      onResume = {},
      onExit = {},
    )
  }
}
