package com.codenames.keycards.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GameStateTest {
  @Test
  fun snapshotRoundTrip_preservesTheSavedBoardAndGameState() {
    val state =
      GameState(
        settings =
          KeycardSettings(
            teamCount = 3,
            boardRows = 4,
            boardColumns = 6,
            tilesPerTeam = 7,
            turnOrder = listOf(3, 1, 2),
            firstTeamBonus = false,
          ),
        timer = TurnTimer(90),
        gameMode = true,
        activeTeam = 1,
        remainingSeconds = 47,
        isPaused = true,
      )

    assertEquals(state, state.toSnapshot().toGameState())
  }

  @Test
  fun snapshotRoundTrip_preservesLinkedBoardDimensions() {
    val state =
      GameState(
        settings =
          KeycardSettings(
            boardRows = 6,
            boardColumns = 6,
            linkBoardDimensions = true,
          ),
      )

    assertEquals(state, state.toSnapshot().toGameState())
  }

  @Test
  fun restoringRunningGame_pausesWithoutChangingRemainingTime() {
    val runningGame =
      startGame(
        GameState(
          settings = KeycardSettings(turnOrder = listOf(2, 1)),
          timer = TurnTimer(60),
        ),
      ).copy(remainingSeconds = 23)

    val restored = restoreAfterRestart(runningGame.toSnapshot().toGameState())

    assertTrue(restored.gameMode)
    assertTrue(restored.isPaused)
    assertEquals(23, restored.remainingSeconds)
    assertEquals(2, restored.activeTeam)
  }

  @Test
  fun turnsFollowConfiguredOrderAndResetFiniteTimer() {
    val started =
      startGame(
        GameState(
          settings = KeycardSettings(teamCount = 3, turnOrder = listOf(3, 1, 2)),
          timer = TurnTimer(60),
        ),
      )

    assertEquals(3, started.activeTeam)
    assertEquals(60, started.remainingSeconds)
    assertFalse(started.isPaused)

    val secondTurn = advanceTurn(started.copy(remainingSeconds = 12))
    val thirdTurn = advanceTurn(secondTurn)
    val wrappedTurn = advanceTurn(thirdTurn)

    assertEquals(1, secondTurn.activeTeam)
    assertEquals(60, secondTurn.remainingSeconds)
    assertEquals(2, thirdTurn.activeTeam)
    assertEquals(3, wrappedTurn.activeTeam)
  }

  @Test
  fun timer_stopsAtZeroUntilTheTurnIsAdvanced() {
    val state = startGame(GameState(timer = TurnTimer(2)))

    val atOne = tickTimer(state)
    val atZero = tickTimer(atOne)
    val stillAtZero = tickTimer(atZero)
    val nextTurn = advanceTurn(stillAtZero)

    assertEquals(1, atOne.remainingSeconds)
    assertEquals(0, atZero.remainingSeconds)
    assertEquals(0, stillAtZero.remainingSeconds)
    assertEquals(2, nextTurn.remainingSeconds)
  }

  @Test
  fun noTimer_turnsAreStillAdvancedByTheControl() {
    val started = startGame(GameState(settings = KeycardSettings(turnOrder = listOf(2, 1))))
    val advanced = advanceTurn(started)

    assertNull(started.remainingSeconds)
    assertEquals(2, started.activeTeam)
    assertEquals(1, advanced.activeTeam)
    assertNull(advanced.remainingSeconds)
  }
}
