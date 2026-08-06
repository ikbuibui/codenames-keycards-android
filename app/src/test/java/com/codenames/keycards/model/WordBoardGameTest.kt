package com.codenames.keycards.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WordBoardGameTest {
  @Test
  fun targetOrders_shuffleStableIndices_notWords_andUndoKeepsRelativeOrder() {
    val state = wordBoardState()
    val started = startGame(state, BoundedRandom { 0 })

    // Both red cells deliberately have the same displayed word but remain distinct positions.
    assertEquals(listOf(2, 0), targetDisplayOrder(started, 1))
    assertEquals(listOf(2, 0), remainingTargetCellIndices(started))

    val guessed = markActiveTargetGuessed(started, 2)
    assertEquals(listOf(0), remainingTargetCellIndices(guessed))
    val restored = undoTargetGuessed(guessed, 2)
    assertEquals(listOf(2, 0), remainingTargetCellIndices(restored))

    val shuffled = shuffleActiveTargetOrder(restored, BoundedRandom { 0 })
    assertEquals(listOf(0, 2), targetDisplayOrder(shuffled, 1))
    assertEquals(state.keycard, shuffled.keycard)
    assertEquals(state.recognizedBoard, shuffled.recognizedBoard)
    assertTrue(shuffled.guessedCellIndices.isEmpty())

    // Another team's stored order is not altered by an active-team shuffle.
    val afterTurn = advanceTurn(shuffled)
    assertEquals(targetDisplayOrder(shuffled, 2), targetDisplayOrder(afterTurn, 2))
  }

  @Test
  fun startGame_initializesAllTeamPermutations_andScanFreeGamesHaveNone() {
    val started = startGame(wordBoardState(), BoundedRandom { 0 })
    assertEquals(setOf(1, 2), started.targetDisplayOrders.keys)
    assertEquals(setOf(0, 2), started.targetDisplayOrders.getValue(1).toSet())
    assertEquals(setOf(1, 3), started.targetDisplayOrders.getValue(2).toSet())

    val scanFree = startGame(GameState(settings = wordBoardState().settings), BoundedRandom { 0 })
    assertTrue(scanFree.targetDisplayOrders.isEmpty())
    assertFalse(canShuffleTargets(scanFree))
  }

  @Test
  fun changedKeycardOrDimensions_clearPositionalWordData() {
    val state = wordBoardState()
    val regenerated = generateNewKeycard(state)
    assertEquals(null, regenerated.recognizedBoard)
    assertTrue(regenerated.guessedCellIndices.isEmpty())
    assertTrue(regenerated.targetDisplayOrders.isEmpty())

    val resized = normalizedGameState(state.copy(settings = state.settings.copy(boardRows = 3)))
    assertEquals(null, resized.recognizedBoard)
    assertTrue(resized.guessedCellIndices.isEmpty())
    assertTrue(resized.targetDisplayOrders.isEmpty())
  }

  @Test
  fun descendingFisherYates_exhaustivelyProducesEveryPermutationForEightIndices() {
    val original = (0 until 8).toList()
    val permutations = mutableSetOf<List<Int>>()
    val draws = mutableListOf<Int>()

    fun enumerate(nextBound: Int) {
      if (nextBound == 1) {
        val queue = ArrayDeque(draws)
        permutations += fisherYates(original, BoundedRandom { queue.removeFirst() })
        return
      }
      for (draw in 0 until nextBound) {
        draws += draw
        enumerate(nextBound - 1)
        draws.removeAt(draws.lastIndex)
      }
    }

    enumerate(original.size)

    assertEquals(40_320, permutations.size)
    assertEquals((0 until 8).toList(), original) // The input was never mutated.
    assertTrue(permutations.all { it.size == original.size && it.toSet() == original.toSet() })
  }

  @Test
  fun rotatingRectangularBoard_mapsEveryCellIntoTheExpectedPosition() {
    val board =
      RecognizedBoard(
        2,
        3,
        listOf("0", "1", "2", "3", "4", "5").map(::RecognizedCell),
      )

    assertEquals(listOf("3", "0", "4", "1", "5", "2"), board.rotate(BoardRotation.CLOCKWISE).cells.map(RecognizedCell::text))
    assertEquals(listOf("2", "5", "1", "4", "0", "3"), board.rotate(BoardRotation.COUNTERCLOCKWISE).cells.map(RecognizedCell::text))
    assertEquals(listOf("5", "4", "3", "2", "1", "0"), board.rotate(BoardRotation.HALF_TURN).cells.map(RecognizedCell::text))
    assertEquals(5, transformedCellIndex(1, 2, 2, 3, null))
    assertEquals(1, transformedCellIndex(0, 0, 2, 3, BoardRotation.CLOCKWISE))
    assertEquals(4, transformedCellIndex(1, 2, 2, 3, BoardRotation.CLOCKWISE))
    assertEquals(1, transformedCellIndex(1, 2, 2, 3, BoardRotation.COUNTERCLOCKWISE))
    assertEquals(0, transformedCellIndex(1, 2, 2, 3, BoardRotation.HALF_TURN))
    assertEquals(3, transformedCellIndex(0, 1, 2, 2, BoardRotation.CLOCKWISE))
    assertEquals(0, transformedCellIndex(0, 1, 2, 2, BoardRotation.COUNTERCLOCKWISE))
    assertEquals(2, transformedCellIndex(0, 1, 2, 2, BoardRotation.HALF_TURN))
  }

  @Test
  fun absoluteOrientations_onlyExposeTransformsCompatibleWithSettings() {
    assertEquals(
      listOf(BoardOrientation.ORIGINAL, BoardOrientation.HALF_TURN),
      compatibleBoardOrientations(4, 6, 4, 6),
    )
    assertEquals(
      listOf(BoardOrientation.CLOCKWISE, BoardOrientation.COUNTERCLOCKWISE),
      compatibleBoardOrientations(6, 4, 4, 6),
    )
    assertEquals(BoardOrientation.entries, compatibleBoardOrientations(5, 5, 5, 5))
    assertTrue(compatibleBoardOrientations(3, 7, 4, 6).isEmpty())
  }

  @Test
  fun sourceIndex_isTheInverseOfEveryAbsoluteOrientation() {
    val board = RecognizedBoard(2, 3, List(6) { RecognizedCell(it.toString()) })
    BoardOrientation.entries.forEach { orientation ->
      val oriented = board.orient(orientation)
      oriented.cells.indices.forEach { destinationIndex ->
        val sourceIndex = sourceCellIndex(destinationIndex, board.rows, board.columns, orientation)
        assertEquals(board.cells[sourceIndex], oriented.cells[destinationIndex])
      }
    }
  }

  private fun wordBoardState(): GameState {
    val settings = KeycardSettings(boardRows = 2, boardColumns = 3, tilesPerTeam = 2, firstTeamBonus = false)
    return GameState(
      settings = settings,
      keycard = listOf(1, 2, 1, 2, TileRole.ASSASSIN, TileRole.BYSTANDER),
      recognizedBoard =
        RecognizedBoard(
          2,
          3,
          listOf("SAME", "BLUE", "SAME", "OCEAN", "DANGER", "CIVILIAN").map(::RecognizedCell),
        ),
    )
  }
}
