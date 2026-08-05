package com.codenames.keycards.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class KeycardGeneratorTest {
  @Test
  fun generatedCard_hasTheExpectedRoleCounts() {
    val card = generateKeycard(KeycardSettings(seed = 42L))

    assertEquals(25, card.size)
    assertEquals(9, card.count { it == 1 }) // Eight red tiles plus the starting tile.
    assertEquals(8, card.count { it == 2 })
    assertEquals(1, card.count { it == TileRole.ASSASSIN })
    assertEquals(7, card.count { it == TileRole.BYSTANDER })
  }

  @Test
  fun seed_recreatesTheSameCard() {
    val settings = KeycardSettings(teamCount = 4, boardSize = 7, tilesPerTeam = 9, startingTeam = 3, seed = 123_456L)

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
    val result = normalized(KeycardSettings(teamCount = 4, boardSize = 5, tilesPerTeam = 8, startingTeam = 4))

    assertEquals(5, result.tilesPerTeam)
    assertEquals(22, requiredTiles(result.teamCount, result.tilesPerTeam, result.startingTeam))
  }

  @Test
  fun normalize_clearsAStartingTeamThatWasRemoved() {
    val result = normalized(KeycardSettings(teamCount = 3, startingTeam = 4))

    assertEquals(TileRole.BYSTANDER, result.startingTeam)
  }
}
