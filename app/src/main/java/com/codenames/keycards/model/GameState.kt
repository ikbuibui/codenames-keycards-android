package com.codenames.keycards.model

const val DEFAULT_TURN_DURATION_SECONDS = 5 * 60

/** A null duration represents the explicit no-timer mode. */
data class TurnTimer(val durationSeconds: Int? = null) {
  val hasTimer: Boolean get() = durationSeconds != null
}

/** Everything needed to resume the setup or an in-progress game. */
data class GameState(
  val settings: KeycardSettings = KeycardSettings(),
  val keycard: List<Int> = generateKeycard(normalized(settings)),
  val timer: TurnTimer = TurnTimer(),
  val gameMode: Boolean = false,
  val activeTeam: Int = 1,
  val remainingSeconds: Int? = null,
  val isPaused: Boolean = true,
) {
  val isRunning: Boolean get() = gameMode && !isPaused
}

/** The persistence representation written by the private preferences store. */
data class GameStateSnapshot(
  val teamCount: Int,
  val boardRows: Int,
  val boardColumns: Int,
  val linkBoardDimensions: Boolean = false,
  val tilesPerTeam: Int,
  val turnOrder: List<Int>,
  val firstTeamBonus: Boolean,
  val keycard: List<Int>,
  val timerDurationSeconds: Int?,
  val gameMode: Boolean,
  val activeTeam: Int,
  val remainingSeconds: Int?,
  val isPaused: Boolean,
)

fun GameState.toSnapshot(): GameStateSnapshot =
  GameStateSnapshot(
    teamCount = settings.teamCount,
    boardRows = settings.boardRows,
    boardColumns = settings.boardColumns,
    linkBoardDimensions = settings.linkBoardDimensions,
    tilesPerTeam = settings.tilesPerTeam,
    turnOrder = settings.turnOrder,
    firstTeamBonus = settings.firstTeamBonus,
    keycard = keycard,
    timerDurationSeconds = timer.durationSeconds,
    gameMode = gameMode,
    activeTeam = activeTeam,
    remainingSeconds = remainingSeconds,
    isPaused = isPaused,
  )

fun GameStateSnapshot.toGameState(): GameState =
  normalizedGameState(
    GameState(
      settings = KeycardSettings(
        teamCount = teamCount,
        boardRows = boardRows,
        boardColumns = boardColumns,
        linkBoardDimensions = linkBoardDimensions,
        tilesPerTeam = tilesPerTeam,
        turnOrder = turnOrder,
        firstTeamBonus = firstTeamBonus,
      ),
      keycard = keycard,
      timer = TurnTimer(timerDurationSeconds),
      gameMode = gameMode,
      activeTeam = activeTeam,
      remainingSeconds = remainingSeconds,
      isPaused = isPaused,
    ),
  )

/** Repairs corrupt or old saved values while retaining as much game state as possible. */
fun normalizedGameState(state: GameState): GameState {
  val settings = normalized(state.settings)
  val keycard = state.keycard.takeIf { isValidKeycard(it, settings) } ?: generateKeycard(settings)
  val duration = state.timer.durationSeconds?.takeIf { it > 0 }
  val timer = TurnTimer(duration)
  val activeTeam = state.activeTeam.takeIf { it in settings.turnOrder } ?: settings.turnOrder.first()
  val remainingSeconds = duration?.let { configuredDuration ->
    (state.remainingSeconds ?: configuredDuration).coerceIn(0, configuredDuration)
  }

  return state.copy(
    settings = settings,
    keycard = keycard,
    timer = timer,
    activeTeam = activeTeam,
    remainingSeconds = remainingSeconds,
    isPaused = if (state.gameMode) state.isPaused else true,
  )
}

/** Restores a saved state without charging time while the app was away. */
fun restoreAfterRestart(state: GameState): GameState {
  val normalized = normalizedGameState(state)
  return if (normalized.gameMode) normalized.copy(isPaused = true) else normalized
}

/** Begins a new game from the first team in the configured order. */
fun startGame(state: GameState): GameState {
  val normalized = normalizedGameState(state)
  return normalized.copy(
    gameMode = true,
    activeTeam = normalized.settings.turnOrder.first(),
    remainingSeconds = normalized.timer.durationSeconds,
    isPaused = false,
  )
}

fun pauseGame(state: GameState): GameState =
  normalizedGameState(state).copy(isPaused = true)

fun resumeGame(state: GameState): GameState =
  normalizedGameState(state).copy(isPaused = false)

fun exitToSetup(state: GameState): GameState =
  normalizedGameState(state).copy(gameMode = false, isPaused = true)

/** Advances through the configured order and resets a finite turn timer. */
fun advanceTurn(state: GameState): GameState {
  val normalized = normalizedGameState(state)
  val order = normalized.settings.turnOrder
  val currentIndex = order.indexOf(normalized.activeTeam)
  val nextTeam = order[(currentIndex + 1).floorMod(order.size)]
  return normalized.copy(
    activeTeam = nextTeam,
    remainingSeconds = normalized.timer.durationSeconds,
  )
}

/** Decrements a finite timer without allowing it to advance automatically at zero. */
fun tickTimer(state: GameState): GameState {
  val normalized = normalizedGameState(state)
  val remaining = normalized.remainingSeconds ?: return normalized
  if (!normalized.isRunning || remaining == 0) return normalized
  return normalized.copy(remainingSeconds = remaining - 1)
}

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
