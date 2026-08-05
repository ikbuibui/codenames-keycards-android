package com.codenames.keycards.model

import kotlin.math.ceil
import kotlin.math.sqrt

const val MIN_TEAM_COUNT = 2
const val MAX_TEAM_COUNT = 4
const val MIN_BOARD_SIZE = 2
const val MAX_BOARD_SIZE = 10

/** All information needed to recreate one keycard without any network access. */
data class KeycardSettings(
  val teamCount: Int = 2,
  val boardSize: Int = 5,
  val tilesPerTeam: Int = 8,
  val startingTeam: Int = 1,
  val seed: Long = System.currentTimeMillis(),
  val frozen: Boolean = false,
)

/** Returns a deterministic keycard using the same roles as the web generator. */
fun generateKeycard(settings: KeycardSettings): List<Int> {
  require(settings.teamCount in MIN_TEAM_COUNT..MAX_TEAM_COUNT)
  require(settings.boardSize in MIN_BOARD_SIZE..MAX_BOARD_SIZE)
  require(settings.tilesPerTeam >= 1)
  require(settings.startingTeam in 0..settings.teamCount)
  require(requiredTiles(settings.teamCount, settings.tilesPerTeam, settings.startingTeam) <= settings.boardSize * settings.boardSize)

  val card = MutableList(settings.boardSize * settings.boardSize) { TileRole.BYSTANDER }
  val indexes = UniqueRandomIndexes(0, card.lastIndex, settings.seed)

  repeat(settings.teamCount) { team ->
    repeat(settings.tilesPerTeam) { card[indexes.next()] = team + 1 }
  }
  if (settings.startingTeam != TileRole.BYSTANDER) {
    card[indexes.next()] = settings.startingTeam
  }
  card[indexes.next()] = TileRole.ASSASSIN
  return card
}

/** Integer values used by the generated board. Team values start at one. */
object TileRole {
  const val ASSASSIN = -1
  const val BYSTANDER = 0
}

fun requiredTiles(teamCount: Int, tilesPerTeam: Int, startingTeam: Int): Int =
  teamCount * tilesPerTeam + 1 + if (startingTeam == TileRole.BYSTANDER) 0 else 1

fun minimumBoardSize(teamCount: Int, tilesPerTeam: Int, startingTeam: Int): Int =
  ceil(sqrt(requiredTiles(teamCount, tilesPerTeam, startingTeam).toDouble())).toInt()

fun maximumTilesPerTeam(boardSize: Int, teamCount: Int, startingTeam: Int): Int =
  ((boardSize * boardSize - 1 - if (startingTeam == TileRole.BYSTANDER) 0 else 1) / teamCount)
    .coerceAtLeast(1)

fun maximumTeamCount(boardSize: Int, tilesPerTeam: Int, startingTeam: Int): Int =
  ((boardSize * boardSize - 1 - if (startingTeam == TileRole.BYSTANDER) 0 else 1) / tilesPerTeam)
    .coerceIn(MIN_TEAM_COUNT, MAX_TEAM_COUNT)

/** Makes a changed setting safe for the currently selected board. */
fun normalized(settings: KeycardSettings): KeycardSettings {
  val teamCount = settings.teamCount.coerceIn(MIN_TEAM_COUNT, MAX_TEAM_COUNT)
  val startingTeam = settings.startingTeam.takeIf { it in TileRole.BYSTANDER..teamCount } ?: TileRole.BYSTANDER
  val boardSize = settings.boardSize.coerceIn(MIN_BOARD_SIZE, MAX_BOARD_SIZE)
  val maxTiles = maximumTilesPerTeam(boardSize, teamCount, startingTeam)
  return settings.copy(teamCount = teamCount, boardSize = boardSize, tilesPerTeam = settings.tilesPerTeam.coerceIn(1, maxTiles), startingTeam = startingTeam)
}

/**
 * A seeded pseudo-random picker that returns every index in its range at most once.
 *
 * It uses Park-Miller/Lehmer random numbers so a frozen seed always yields the same
 * card. No platform random source or online service is involved.
 */
class UniqueRandomIndexes(min: Int, max: Int, seed: Long) {
  private val options = (min..max).toMutableList()
  private val random = ParkMillerRandom(seed)

  fun next(): Int {
    check(options.isNotEmpty()) { "No more unique indexes" }
    return options.removeAt(random.nextInt(options.size))
  }
}

private class ParkMillerRandom(seed: Long) {
  private var state = seed % MODULUS

  init {
    if (state <= 0) state += MODULUS - 1
  }

  fun nextInt(until: Int): Int {
    require(until > 0)
    state = (state * MULTIPLIER) % MODULUS
    return (state % until).toInt()
  }

  private companion object {
    const val MODULUS = 2_147_483_647L
    const val MULTIPLIER = 16_807L
  }
}
