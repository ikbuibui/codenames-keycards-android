package com.codenames.keycards.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeycardGeneratorTest {
  @Test
  fun generatedCard_hasTheExpectedRoleCounts() {
    val card = generateKeycard(KeycardSettings())

    assertEquals(25, card.size)
    assertEquals(9, card.count { it == 1 }) // Eight red tiles plus the first-team bonus.
    assertEquals(8, card.count { it == 2 })
    assertEquals(1, card.count { it == TileRole.ASSASSIN })
    assertEquals(7, card.count { it == TileRole.BYSTANDER })
  }

  @Test
  fun rectangularCard_hasTheExpectedDimensionsAndCounts() {
    val settings =
      KeycardSettings(
        teamCount = 3,
        boardRows = 4,
        boardColumns = 6,
        tilesPerTeam = 5,
        turnOrder = listOf(2, 3, 1),
      )
    val card = generateKeycard(settings)

    assertEquals(24, card.size)
    assertEquals(5, card.count { it == 1 })
    assertEquals(6, card.count { it == 2 })
    assertEquals(5, card.count { it == 3 })
    assertEquals(1, card.count { it == TileRole.ASSASSIN })
    assertEquals(7, card.count { it == TileRole.BYSTANDER })
    assertTrue(isValidKeycard(card, settings))
  }

  @Test
  fun fisherYates_mapsUniformDrawsUniformlyOntoDistinctKeycards() {
    val settings =
      KeycardSettings(
        boardRows = 2,
        boardColumns = 2,
        tilesPerTeam = 1,
      )
    val frequencies = mutableMapOf<List<Int>, Int>()

    // A four-item Fisher-Yates shuffle makes independent choices from 4, 3 and 2
    // positions. Enumerate all 24 equally likely choice sequences.
    for (first in 0 until 4) {
      for (second in 0 until 3) {
        for (third in 0 until 2) {
          val choices = ArrayDeque(listOf(first, second, third))
          val card = generateKeycard(settings) { choices.removeFirst() }
          frequencies[card] = frequencies.getOrDefault(card, 0) + 1
        }
      }
    }

    // Team 1 occurs twice, so each of the 12 distinct cards has two labeled
    // permutations and therefore exactly the same probability.
    assertEquals(12, frequencies.size)
    assertTrue(frequencies.values.all { it == 2 })
  }

  @Test
  fun disabledFirstTeamBonus_givesNoTeamAnExtraTile() {
    val card = generateKeycard(KeycardSettings(firstTeamBonus = false))

    assertEquals(8, card.count { it == 1 })
    assertEquals(8, card.count { it == 2 })
    assertEquals(8, card.count { it == TileRole.BYSTANDER })
  }

  @Test
  fun normalize_reducesTilesWhenTheGridCannotFitThem() {
    val result =
      normalized(
        KeycardSettings(
          teamCount = 4,
          boardRows = 4,
          boardColumns = 6,
          tilesPerTeam = 8,
          firstTeamBonus = true,
        ),
      )

    assertEquals(5, result.tilesPerTeam)
    assertEquals(22, requiredTiles(result.teamCount, result.tilesPerTeam, result.firstTeamBonus))
  }

  @Test
  fun normalize_repairsAnImpossibleSmallGrid() {
    val result =
      normalized(
        KeycardSettings(
          teamCount = 4,
          boardRows = 2,
          boardColumns = 2,
          tilesPerTeam = 8,
          turnOrder = listOf(4, 3, 2, 1),
        ),
      )

    assertEquals(2, result.teamCount)
    assertEquals(1, result.tilesPerTeam)
    assertEquals(listOf(2, 1), result.turnOrder)
    assertTrue(isValidKeycard(generateKeycard(result), result))
  }

  @Test
  fun normalize_linkedDimensions_turnsARectangleIntoTheLargerSquare() {
    val result =
      normalized(
        KeycardSettings(
          boardRows = 4,
          boardColumns = 6,
          linkBoardDimensions = true,
        ),
      )

    assertEquals(6, result.boardRows)
    assertEquals(6, result.boardColumns)
    assertTrue(result.linkBoardDimensions)
  }

  @Test
  fun normalize_removesUnavailableTeamsAndCompletesTurnOrder() {
    val result = normalized(KeycardSettings(teamCount = 3, turnOrder = listOf(4, 2, 2)))

    assertEquals(listOf(2, 1, 3), result.turnOrder)
  }

  @Test
  fun keycardValidation_rejectsWrongCountsAndUnknownRoles() {
    val settings = KeycardSettings()
    val card = generateKeycard(settings)

    assertFalse(isValidKeycard(card.dropLast(1), settings))
    assertFalse(isValidKeycard(card.toMutableList().apply { this[0] = 99 }, settings))
  }
}
