package com.codenames.keycards.model

import java.security.SecureRandom

const val MIN_TEAM_COUNT = 2
const val MAX_TEAM_COUNT = 4
const val MIN_BOARD_DIMENSION = 2
const val MAX_BOARD_DIMENSION = 10

/** The configuration of a keycard. The generated card itself is stored in [GameState]. */
data class KeycardSettings(
  val teamCount: Int = 2,
  val boardRows: Int = 5,
  val boardColumns: Int = 5,
  val linkBoardDimensions: Boolean = false,
  val tilesPerTeam: Int = 8,
  val turnOrder: List<Int> = listOf(1, 2),
  val firstTeamBonus: Boolean = true,
  val assassinCount: Int = 1,
) {
  val tileCount: Int get() = boardRows * boardColumns
}

/**
 * Generates a keycard uniformly from all cards that have the configured role counts.
 *
 * Fisher-Yates is uniform when each bounded random draw is uniform. [SecureRandom]
 * provides unbiased bounded draws, and shuffling duplicate roles gives every distinct
 * keycard the same number of underlying permutations.
 */
fun generateKeycard(settings: KeycardSettings): List<Int> =
  generateKeycard(settings, secureRandom::nextInt)

internal fun generateKeycard(settings: KeycardSettings, nextInt: (Int) -> Int): List<Int> {
  validate(settings)

  val card = buildList(settings.tileCount) {
    repeat(settings.teamCount) { team ->
      repeat(settings.tilesPerTeam) { add(team + 1) }
    }
    if (settings.firstTeamBonus) add(settings.turnOrder.first())
    repeat(settings.assassinCount) { add(TileRole.ASSASSIN) }
    while (size < settings.tileCount) add(TileRole.BYSTANDER)
  }.toMutableList()

  for (last in card.lastIndex downTo 1) {
    val selected = nextInt(last + 1)
    require(selected in 0..last) { "Random index $selected is outside 0..$last" }
    val value = card[last]
    card[last] = card[selected]
    card[selected] = value
  }
  return card
}

/** Integer values used by a generated board. Team values start at one. */
object TileRole {
  const val ASSASSIN = -1
  const val BYSTANDER = 0
}

fun requiredTiles(teamCount: Int, tilesPerTeam: Int, firstTeamBonus: Boolean, assassinCount: Int = 1): Int =
  teamCount * tilesPerTeam + assassinCount + if (firstTeamBonus) 1 else 0

fun maximumTilesPerTeam(
  boardRows: Int,
  boardColumns: Int,
  teamCount: Int,
  firstTeamBonus: Boolean,
  assassinCount: Int = 1,
): Int =
  ((boardRows * boardColumns - fixedTileCount(firstTeamBonus, assassinCount)) / teamCount)
    .coerceAtLeast(1)

fun maximumTeamCount(
  boardRows: Int,
  boardColumns: Int,
  tilesPerTeam: Int,
  firstTeamBonus: Boolean,
  assassinCount: Int = 1,
): Int =
  ((boardRows * boardColumns - fixedTileCount(firstTeamBonus, assassinCount)) / tilesPerTeam)
    .coerceIn(MIN_TEAM_COUNT, MAX_TEAM_COUNT)

fun maximumAssassinCount(
  boardRows: Int,
  boardColumns: Int,
  teamCount: Int,
  tilesPerTeam: Int,
  firstTeamBonus: Boolean,
): Int =
  (boardRows * boardColumns - teamCount * tilesPerTeam - (if (firstTeamBonus) 1 else 0))
    .coerceAtLeast(0)

/** Makes changed or persisted settings valid while retaining as much as possible. */
fun normalized(settings: KeycardSettings): KeycardSettings {
  val requestedRows = settings.boardRows.coerceIn(MIN_BOARD_DIMENSION, MAX_BOARD_DIMENSION)
  val requestedColumns = settings.boardColumns.coerceIn(MIN_BOARD_DIMENSION, MAX_BOARD_DIMENSION)
  // Prefer the larger side when linking a rectangular board so enabling the option never
  // removes capacity from an otherwise valid setup.
  val squareDimension = maxOf(requestedRows, requestedColumns)
  val boardRows = if (settings.linkBoardDimensions) squareDimension else requestedRows
  val boardColumns = if (settings.linkBoardDimensions) squareDimension else requestedColumns
  val fixedForAssassinsAndBonus = settings.assassinCount.coerceAtLeast(0) + if (settings.firstTeamBonus) 1 else 0
  val availableTeamTiles = boardRows * boardColumns - fixedForAssassinsAndBonus
  // A 2x2 grid always has room for the minimum two teams with one tile each.
  val teamCount =
    settings.teamCount
      .coerceIn(MIN_TEAM_COUNT, MAX_TEAM_COUNT)
      .coerceAtMost(availableTeamTiles.coerceAtLeast(0))
  val maxTiles = ((boardRows * boardColumns - fixedForAssassinsAndBonus) / teamCount).coerceAtLeast(1)
  val turnOrder = normalizeTurnOrder(settings.turnOrder, teamCount)
  val maxAssassins = maximumAssassinCount(boardRows, boardColumns, teamCount, settings.tilesPerTeam.coerceIn(1, maxTiles), settings.firstTeamBonus)

  return settings.copy(
    teamCount = teamCount,
    boardRows = boardRows,
    boardColumns = boardColumns,
    tilesPerTeam = settings.tilesPerTeam.coerceIn(1, maxTiles),
    turnOrder = turnOrder,
    assassinCount = settings.assassinCount.coerceIn(0, maxAssassins),
  )
}

/** Keeps valid configured teams in their existing order and appends any new teams. */
fun normalizeTurnOrder(turnOrder: List<Int>, teamCount: Int): List<Int> {
  val validTeams = 1..teamCount
  return buildList {
    turnOrder.forEach { team ->
      if (team in validTeams && team !in this) add(team)
    }
    validTeams.forEach { team -> if (team !in this) add(team) }
  }
}

/** Whether [card] is a complete, valid card for [settings]. */
fun isValidKeycard(card: List<Int>, settings: KeycardSettings): Boolean {
  if (runCatching { validate(settings) }.isFailure) return false
  if (card.size != settings.tileCount) return false

  val expectedTeamCounts = IntArray(settings.teamCount + 1) { settings.tilesPerTeam }
  if (settings.firstTeamBonus) expectedTeamCounts[settings.turnOrder.first()]++
  val actualTeamCounts = IntArray(settings.teamCount + 1)
  var assassins = 0
  var bystanders = 0

  card.forEach { role ->
    when {
      role == TileRole.ASSASSIN -> assassins++
      role == TileRole.BYSTANDER -> bystanders++
      role in 1..settings.teamCount -> actualTeamCounts[role]++
      else -> return false
    }
  }

  return assassins == settings.assassinCount &&
    bystanders == settings.tileCount - requiredTiles(settings.teamCount, settings.tilesPerTeam, settings.firstTeamBonus, settings.assassinCount) &&
    (1..settings.teamCount).all { actualTeamCounts[it] == expectedTeamCounts[it] }
}

private fun validate(settings: KeycardSettings) {
  require(settings.teamCount in MIN_TEAM_COUNT..MAX_TEAM_COUNT)
  require(settings.boardRows in MIN_BOARD_DIMENSION..MAX_BOARD_DIMENSION)
  require(settings.boardColumns in MIN_BOARD_DIMENSION..MAX_BOARD_DIMENSION)
  require(settings.tilesPerTeam >= 1)
  require(settings.turnOrder.sorted() == (1..settings.teamCount).toList())
  require(settings.assassinCount >= 0)
  require(requiredTiles(settings.teamCount, settings.tilesPerTeam, settings.firstTeamBonus, settings.assassinCount) <= settings.tileCount)
}

private fun fixedTileCount(firstTeamBonus: Boolean, assassinCount: Int): Int = assassinCount + if (firstTeamBonus) 1 else 0

private val secureRandom by lazy(::SecureRandom)
