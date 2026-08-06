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
  /** Present only after an explicitly reviewed, dimension-verified word scan. */
  val recognizedBoard: RecognizedBoard? = null,
  /** Stable keycard indices, rather than displayed strings. */
  val guessedCellIndices: Set<Int> = emptySet(),
  /** One complete target-index permutation for every team with words. */
  val targetDisplayOrders: Map<Int, List<Int>> = emptyMap(),
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
  val assassinCount: Int = 1,
  val keycard: List<Int>,
  val timerDurationSeconds: Int?,
  val gameMode: Boolean,
  val activeTeam: Int,
  val remainingSeconds: Int?,
  val isPaused: Boolean,
  val recognizedBoard: RecognizedBoard? = null,
  val guessedCellIndices: Set<Int> = emptySet(),
  val targetDisplayOrders: Map<Int, List<Int>> = emptyMap(),
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
    assassinCount = settings.assassinCount,
    keycard = keycard,
    timerDurationSeconds = timer.durationSeconds,
    gameMode = gameMode,
    activeTeam = activeTeam,
    remainingSeconds = remainingSeconds,
    isPaused = isPaused,
    recognizedBoard = recognizedBoard,
    guessedCellIndices = guessedCellIndices,
    targetDisplayOrders = targetDisplayOrders,
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
        assassinCount = assassinCount,
      ),
      keycard = keycard,
      timer = TurnTimer(timerDurationSeconds),
      gameMode = gameMode,
      activeTeam = activeTeam,
      remainingSeconds = remainingSeconds,
      isPaused = isPaused,
      recognizedBoard = recognizedBoard,
      guessedCellIndices = guessedCellIndices,
      targetDisplayOrders = targetDisplayOrders,
    ),
  )

/** Repairs corrupt or old saved values while retaining as much game state as possible. */
fun normalizedGameState(state: GameState): GameState {
  val settings = normalized(state.settings)
  val suppliedKeycardIsValid = isValidKeycard(state.keycard, settings)
  val keycard = state.keycard.takeIf { suppliedKeycardIsValid } ?: generateKeycard(settings)
  val duration = state.timer.durationSeconds?.takeIf { it > 0 }
  val timer = TurnTimer(duration)
  val activeTeam = state.activeTeam.takeIf { it in settings.turnOrder } ?: settings.turnOrder.first()
  val remainingSeconds = duration?.let { configuredDuration ->
    (state.remainingSeconds ?: configuredDuration).coerceIn(0, configuredDuration)
  }
  val recognizedBoard = state.recognizedBoard?.takeIf { it.isValidFor(settings) }
  val guessed =
    if (recognizedBoard == null) {
      emptySet()
    } else {
      state.guessedCellIndices.filterTo(linkedSetOf()) { index ->
        index in keycard.indices && keycard[index] in settings.turnOrder
      }
    }
  val displayOrders =
    if (recognizedBoard == null) {
      emptyMap()
    } else {
      normalizeTargetDisplayOrders(state.targetDisplayOrders, keycard, settings)
    }

  return state.copy(
    settings = settings,
    keycard = keycard,
    timer = timer,
    activeTeam = activeTeam,
    remainingSeconds = remainingSeconds,
    isPaused = if (state.gameMode) state.isPaused else true,
    recognizedBoard = recognizedBoard,
    guessedCellIndices = guessed,
    targetDisplayOrders = displayOrders,
  )
}

/** Restores a saved state without charging time while the app was away. */
fun restoreAfterRestart(state: GameState): GameState {
  val normalized = normalizedGameState(state)
  return if (normalized.gameMode) normalized.copy(isPaused = true) else normalized
}

/** Begins a new game and creates a stable, independently shuffled order for every team. */
fun startGame(state: GameState, random: BoundedRandom = DefaultBoundedRandom): GameState {
  val normalized = normalizedGameState(state)
  val displayOrders =
    if (normalized.recognizedBoard == null) {
      emptyMap()
    } else {
      normalized.settings.turnOrder.associateWith { team ->
        fisherYates(normalized.keycard.indices.filter { normalized.keycard[it] == team }, random)
      }
    }
  return normalized.copy(
    gameMode = true,
    activeTeam = normalized.settings.turnOrder.first(),
    remainingSeconds = normalized.timer.durationSeconds,
    isPaused = false,
    // A newly started game is a new set of guesses, even when its reviewed board is reused.
    guessedCellIndices = emptySet(),
    targetDisplayOrders = displayOrders,
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

/** Replaces words only after review; existing positional orders remain meaningful. */
fun attachRecognizedBoard(state: GameState, board: RecognizedBoard): GameState {
  val normalized = normalizedGameState(state)
  require(board.isValidFor(normalized.settings)) { "Word-board dimensions must match the keycard" }
  require(board.isComplete) { "Every reviewed word-board cell must be nonblank" }
  return normalizedGameState(normalized.copy(recognizedBoard = board))
}

/** Generates a replacement keycard and clears every position-dependent word-game value. */
fun generateNewKeycard(state: GameState): GameState {
  val normalized = normalizedGameState(state)
  return normalized.copy(
    keycard = generateKeycard(normalized.settings),
    recognizedBoard = null,
    guessedCellIndices = emptySet(),
    targetDisplayOrders = emptyMap(),
  )
}

/** Removes all data whose position-to-word interpretation is no longer valid. */
fun clearRecognizedBoard(state: GameState): GameState =
  normalizedGameState(state).copy(
    recognizedBoard = null,
    guessedCellIndices = emptySet(),
    targetDisplayOrders = emptyMap(),
  )

private fun RecognizedBoard.isValidFor(settings: KeycardSettings): Boolean =
  rows == settings.boardRows &&
    columns == settings.boardColumns &&
    cells.size == rows * columns &&
    cells.all { cell -> cell.confidence == null || cell.confidence in 0..100 }

private fun normalizeTargetDisplayOrders(
  orders: Map<Int, List<Int>>,
  keycard: List<Int>,
  settings: KeycardSettings,
): Map<Int, List<Int>> =
  settings.turnOrder.associateWith { team ->
    val targetIndices = keycard.indices.filter { keycard[it] == team }
    val saved = orders[team]
    if (saved != null && saved.size == targetIndices.size && saved.toSet() == targetIndices.toSet()) {
      saved.toList()
    } else {
      targetIndices
    }
  }

private fun Int.floorMod(modulus: Int): Int = ((this % modulus) + modulus) % modulus
