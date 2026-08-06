package com.codenames.keycards.model

import kotlin.random.Random

/** Injectable bounded source used by Fisher–Yates and exhaustive model tests. */
fun interface BoundedRandom {
  /** Returns a uniform integer in 0 until [bound]. */
  fun nextInt(bound: Int): Int
}

val DefaultBoundedRandom = BoundedRandom { bound -> Random.Default.nextInt(bound) }

/**
 * Returns a new uniformly shuffled permutation. The input is never modified.
 *
 * Descending Fisher–Yates has one legal draw sequence for each final permutation,
 * provided each [BoundedRandom.nextInt] result is uniform in its requested range.
 */
fun fisherYates(indices: List<Int>, random: BoundedRandom = DefaultBoundedRandom): List<Int> {
  val result = indices.toMutableList()
  for (index in result.lastIndex downTo 1) {
    val selected = random.nextInt(index + 1)
    require(selected in 0..index) { "Random index $selected is outside 0..$index" }
    val temporary = result[index]
    result[index] = result[selected]
    result[selected] = temporary
  }
  return result
}

/** All word-board cells belonging to [team], including cells already guessed. */
fun teamTargetCellIndices(state: GameState, team: Int): List<Int> =
  if (state.recognizedBoard == null || team !in state.settings.turnOrder) {
    emptyList()
  } else {
    state.keycard.indices.filter { state.keycard[it] == team }
  }

/** The persisted complete permutation for [team]; never identify targets by their text. */
fun targetDisplayOrder(state: GameState, team: Int): List<Int> {
  val targets = teamTargetCellIndices(state, team)
  val stored = state.targetDisplayOrders[team]
  return if (stored != null && stored.size == targets.size && stored.toSet() == targets.toSet()) stored else targets
}

/** The active display order after guessed cells are hidden. */
fun remainingTargetCellIndices(state: GameState, team: Int = state.activeTeam): List<Int> =
  targetDisplayOrder(state, team).filterNot(state.guessedCellIndices::contains)

fun canShuffleTargets(state: GameState, team: Int = state.activeTeam): Boolean =
  remainingTargetCellIndices(state, team).size >= 2

/** Shuffles only the active team's full cell-index permutation. */
fun shuffleActiveTargetOrder(
  state: GameState,
  random: BoundedRandom = DefaultBoundedRandom,
): GameState {
  val normalized = normalizedGameState(state)
  if (normalized.recognizedBoard == null || !canShuffleTargets(normalized)) return normalized
  val team = normalized.activeTeam
  return normalized.copy(
    targetDisplayOrders = normalized.targetDisplayOrders + (team to fisherYates(targetDisplayOrder(normalized, team), random)),
  )
}

/** Marks an active-team target guessed. Guesses are positional and survive duplicate text. */
fun markActiveTargetGuessed(state: GameState, cellIndex: Int): GameState {
  val normalized = normalizedGameState(state)
  if (cellIndex !in teamTargetCellIndices(normalized, normalized.activeTeam)) return normalized
  return normalized.copy(guessedCellIndices = normalized.guessedCellIndices + cellIndex)
}

/** Restores a target to its prior relative position because its full order was retained. */
fun undoTargetGuessed(state: GameState, cellIndex: Int): GameState {
  val normalized = normalizedGameState(state)
  return normalized.copy(guessedCellIndices = normalized.guessedCellIndices - cellIndex)
}
