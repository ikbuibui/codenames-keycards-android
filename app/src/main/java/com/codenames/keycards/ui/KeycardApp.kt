package com.codenames.keycards.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Switch
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codenames.keycards.data.FrozenKeycardStore
import com.codenames.keycards.model.KeycardSettings
import com.codenames.keycards.model.MAX_BOARD_SIZE
import com.codenames.keycards.model.MIN_BOARD_SIZE
import com.codenames.keycards.model.TileRole
import com.codenames.keycards.model.generateKeycard
import com.codenames.keycards.model.maximumTeamCount
import com.codenames.keycards.model.maximumTilesPerTeam
import com.codenames.keycards.model.minimumBoardSize
import com.codenames.keycards.model.normalized
import com.codenames.keycards.theme.CodenamesKeycardsTheme
import androidx.compose.ui.platform.LocalContext

private val Red = Color(0xFFB33038)
private val Blue = Color(0xFF288DC9)
private val Green = Color(0xFF2A9034)
private val Orange = Color(0xFFDA781F)
private val Bystander = Color(0xFFD7C69F)
private val Assassin = Color(0xFF404040)
private val WoodLight = Color(0xFFC8AB99)
private val WoodDark = Color(0xFFA88573)

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
  val frozenStore = remember(appContext) { FrozenKeycardStore(appContext) }
  var settings by remember { mutableStateOf(frozenStore.load() ?: KeycardSettings()) }

  fun updateSettings(change: (KeycardSettings) -> KeycardSettings) {
    if (!settings.frozen) {
      val nextSeed = maxOf(System.currentTimeMillis(), settings.seed + 1)
      settings = normalized(change(settings)).copy(seed = nextSeed)
    }
  }

  fun setFrozen(frozen: Boolean) {
    settings = settings.copy(frozen = frozen)
    if (frozen) frozenStore.save(settings) else frozenStore.clear()
  }

  KeycardScreen(
    settings = settings,
    onGenerate = { updateSettings { it } },
    onSettingsChanged = ::updateSettings,
    onFrozenChanged = ::setFrozen,
  )
}

@Composable
private fun KeycardScreen(
  settings: KeycardSettings,
  onGenerate: () -> Unit,
  onSettingsChanged: ((KeycardSettings) -> KeycardSettings) -> Unit,
  onFrozenChanged: (Boolean) -> Unit,
) {
  val board = remember(settings.teamCount, settings.boardSize, settings.tilesPerTeam, settings.startingTeam, settings.seed) {
    generateKeycard(settings)
  }

  Surface(
    modifier = Modifier.fillMaxSize(),
    color = MaterialTheme.colorScheme.background,
    contentColor = MaterialTheme.colorScheme.onBackground,
  ) {
    Box(modifier = Modifier.fillMaxSize()) {
      Column(
      modifier =
        Modifier
          .align(Alignment.TopCenter)
          .fillMaxWidth()
          .widthIn(max = 720.dp)
          .windowInsetsPadding(WindowInsets.safeDrawing)
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 20.dp),
      verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Header()
      KeycardBoard(board = board, settings = settings)
      BoardActions(frozen = settings.frozen, onGenerate = onGenerate, onFrozenChanged = onFrozenChanged)
      SettingsPanel(settings = settings, onSettingsChanged = onSettingsChanged)
      Text(
        text = "Generated entirely on this device. This app has no network permission, web view, or remote service.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
      )
      }
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
  val startingColor = if (settings.startingTeam == TileRole.BYSTANDER) WoodLight else teamFor(settings.startingTeam).color

  BoxWithConstraints(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
    val boardWidth = minOf(maxWidth - 8.dp, 560.dp)
    val symbolSize = (boardWidth.value / settings.boardSize * 0.45f).coerceIn(10f, 30f).sp

    Box(
      modifier =
        Modifier
          .width(boardWidth)
          .aspectRatio(1f)
          .clip(RoundedCornerShape(30.dp))
          .background(startingColor)
          .padding(if (settings.startingTeam == TileRole.BYSTANDER) 0.dp else 7.dp),
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
private fun BoardActions(frozen: Boolean, onGenerate: () -> Unit, onFrozenChanged: (Boolean) -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
    if (frozen) {
      OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Text(
          text = "This keycard is frozen and will be restored when the app is reopened. Unfreeze it to change settings or generate another card.",
          modifier = Modifier.padding(14.dp),
          style = MaterialTheme.typography.bodyMedium,
        )
      }
    } else {
      Button(onClick = onGenerate, modifier = Modifier.fillMaxWidth()) {
        Text("Generate new board")
      }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
      Column(modifier = Modifier.weight(1f)) {
        Text("Freeze board", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
        Text(
          "Keep this exact keycard after closing the app",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      Switch(checked = frozen, onCheckedChange = onFrozenChanged)
    }
  }
}

@Composable
private fun SettingsPanel(
  settings: KeycardSettings,
  onSettingsChanged: ((KeycardSettings) -> KeycardSettings) -> Unit,
) {
  val enabled = !settings.frozen
  val minBoard = minimumBoardSize(settings.teamCount, settings.tilesPerTeam, settings.startingTeam)
  val maxTiles = maximumTilesPerTeam(settings.boardSize, settings.teamCount, settings.startingTeam)
  val maxTeams = maximumTeamCount(settings.boardSize, settings.tilesPerTeam, settings.startingTeam)

  Card(
    modifier = Modifier.fillMaxWidth(),
    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
  ) {
    Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
      Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text("Settings", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        Text(
          if (enabled) "Changing a setting creates a new keycard." else "Unfreeze the board to change settings.",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      SettingStepper(
        title = "Board size",
        value = settings.boardSize,
        decreaseEnabled = enabled && settings.boardSize > minBoard,
        increaseEnabled = enabled && settings.boardSize < MAX_BOARD_SIZE,
        onDecrease = { onSettingsChanged { it.copy(boardSize = it.boardSize - 1) } },
        onIncrease = { onSettingsChanged { it.copy(boardSize = it.boardSize + 1) } },
      )
      SettingStepper(
        title = "Tiles per team",
        value = settings.tilesPerTeam,
        decreaseEnabled = enabled && settings.tilesPerTeam > 1,
        increaseEnabled = enabled && settings.tilesPerTeam < maxTiles,
        onDecrease = { onSettingsChanged { it.copy(tilesPerTeam = it.tilesPerTeam - 1) } },
        onIncrease = { onSettingsChanged { it.copy(tilesPerTeam = it.tilesPerTeam + 1) } },
      )
      SettingStepper(
        title = "Number of teams",
        value = settings.teamCount,
        decreaseEnabled = enabled && settings.teamCount > 2,
        increaseEnabled = enabled && settings.teamCount < maxTeams,
        onDecrease = { onSettingsChanged { it.copy(teamCount = it.teamCount - 1) } },
        onIncrease = { onSettingsChanged { it.copy(teamCount = it.teamCount + 1) } },
      )
      StartingTeamPicker(
        settings = settings,
        enabled = enabled,
        onStartingTeamChanged = { team -> onSettingsChanged { it.copy(startingTeam = team) } },
      )
      Text(
        text = "Board sizes run from $MIN_BOARD_SIZE×$MIN_BOARD_SIZE to $MAX_BOARD_SIZE×$MAX_BOARD_SIZE. A setting is unavailable when the board cannot fit all team tiles, the assassin, and the extra starting tile.",
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
private fun StartingTeamPicker(
  settings: KeycardSettings,
  enabled: Boolean,
  onStartingTeamChanged: (Int) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text("Starting team", style = MaterialTheme.typography.titleSmall)
    Text(
      "The starting team gets one extra tile to guess.",
      style = MaterialTheme.typography.bodySmall,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
      FilterChip(
        selected = settings.startingTeam == TileRole.BYSTANDER,
        onClick = { onStartingTeamChanged(TileRole.BYSTANDER) },
        enabled = enabled,
        label = { Text("No starting team") },
      )
      teams.take(settings.teamCount).forEach { team ->
        FilterChip(
          selected = settings.startingTeam == team.number,
          onClick = { onStartingTeamChanged(team.number) },
          enabled = enabled,
          label = { Text("${team.name} team") },
          leadingIcon = {
            Box(
              modifier = Modifier.size(14.dp).clip(RoundedCornerShape(50)).background(team.color),
            )
          },
        )
      }
    }
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
private fun KeycardPreview() {
  CodenamesKeycardsTheme {
    KeycardScreen(
      settings = KeycardSettings(seed = 42L),
      onGenerate = {},
      onSettingsChanged = {},
      onFrozenChanged = {},
    )
  }
}
