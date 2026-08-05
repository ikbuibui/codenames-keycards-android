package com.codenames.keycards.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeycardGeneratorTest {
  @Test
  fun generatedCard_hasTheExpectedRoleCounts() {
    val card = generateKeycard(KeycardSettings(seed = 42L))

    assertEquals(25, card.size)
    assertEquals(9, card.count { it == 1 }) // Eight red tiles plus the first-team bonus.
    assertEquals(8, card.count { it == 2 })
    assertEquals(1, card.count { it == TileRole.ASSASSIN })
    assertEquals(7, card.count { it == TileRole.BYSTANDER })
  }

  @Test
  fun disabledFirstTeamBonus_givesNoTeamAnExtraTile() {
    val card = generateKeycard(KeycardSettings(firstTeamBonus = false, seed = 42L))

    assertEquals(8, card.count { it == 1 })
    assertEquals(8, card.count { it == 2 })
    assertEquals(8, card.count { it == TileRole.BYSTANDER })
  }

  @Test
  fun seed_recreatesTheSameCard() {
    val settings = KeycardSettings(teamCount = 4, boardSize = 7, tilesPerTeam = 9, turnOrder = listOf(3, 1, 4, 2), seed = 123_456L)

    assertEquals(generateKeycard(settings), generateKeycard(settings))
    assertNotEquals(generateKeycard(settings), generateKeycard(settings.copy(seed = 123_457L)))
  }

  @Test
  fun uniquePicker_neverRepeatsAnIndex() {
    val picker = UniqueRandomIndexes(0, 24, 99L)
    val values = List(25) { picker.next() }

    assertEquals(25, values.toSet().size)
  }

  @Test
  fun normalize_reducesTilesWhenTheBoardCannotFitThem() {
    val result = normalized(KeycardSettings(teamCount = 4, boardSize = 5, tilesPerTeam = 8, firstTeamBonus = true))

    assertEquals(5, result.tilesPerTeam)
    assertEquals(22, requiredTiles(result.teamCount, result.tilesPerTeam, result.firstTeamBonus))
  }

  @Test
  fun normalize_removesUnavailableTeamsAndCompletesTurnOrder() {
    val result = normalized(KeycardSettings(teamCount = 3, turnOrder = listOf(4, 2, 2)))

    assertEquals(listOf(2, 1, 3), result.turnOrder)
  }
}
