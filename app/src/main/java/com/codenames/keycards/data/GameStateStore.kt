package com.codenames.keycards.data

import android.content.Context
import com.codenames.keycards.model.GameState
import com.codenames.keycards.model.GameStateSnapshot
import com.codenames.keycards.model.normalizedGameState
import com.codenames.keycards.model.restoreAfterRestart
import com.codenames.keycards.model.toGameState
import com.codenames.keycards.model.toSnapshot

/** Always persists the current setup and game in private SharedPreferences. */
class GameStateStore(context: Context) {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun load(): GameState {
    val state =
      if (preferences.contains(KEY_SCHEMA_VERSION)) readSnapshot().toGameState() else GameState()

    // Time must never elapse while the activity/process was absent.
    return restoreAfterRestart(state).also(::save)
  }

  fun save(state: GameState) {
    val snapshot = normalizedGameState(state).toSnapshot()
    preferences.edit()
      .putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
      .putInt(KEY_TEAM_COUNT, snapshot.teamCount)
      .putInt(KEY_BOARD_SIZE, snapshot.boardSize)
      .putInt(KEY_TILES_PER_TEAM, snapshot.tilesPerTeam)
      .putString(KEY_TURN_ORDER, snapshot.turnOrder.joinToString(separator = ","))
      .putBoolean(KEY_FIRST_TEAM_BONUS, snapshot.firstTeamBonus)
      .putLong(KEY_SEED, snapshot.seed)
      .putBoolean(KEY_HAS_TIMER, snapshot.timerDurationSeconds != null)
      .putInt(KEY_TIMER_DURATION_SECONDS, snapshot.timerDurationSeconds ?: 0)
      .putBoolean(KEY_GAME_MODE, snapshot.gameMode)
      .putInt(KEY_ACTIVE_TEAM, snapshot.activeTeam)
      .putBoolean(KEY_HAS_REMAINING_TIME, snapshot.remainingSeconds != null)
      .putInt(KEY_REMAINING_SECONDS, snapshot.remainingSeconds ?: 0)
      .putBoolean(KEY_IS_PAUSED, snapshot.isPaused)
      .commit()
  }

  private fun readSnapshot(): GameStateSnapshot =
    GameStateSnapshot(
      teamCount = preferences.getInt(KEY_TEAM_COUNT, DEFAULT_TEAM_COUNT),
      boardSize = preferences.getInt(KEY_BOARD_SIZE, DEFAULT_BOARD_SIZE),
      tilesPerTeam = preferences.getInt(KEY_TILES_PER_TEAM, DEFAULT_TILES_PER_TEAM),
      turnOrder = preferences.getString(KEY_TURN_ORDER, null).toTurnOrder(),
      firstTeamBonus = preferences.getBoolean(KEY_FIRST_TEAM_BONUS, true),
      seed = preferences.getLong(KEY_SEED, System.currentTimeMillis()),
      timerDurationSeconds = preferences.getInt(KEY_TIMER_DURATION_SECONDS, 0).takeIf {
        preferences.getBoolean(KEY_HAS_TIMER, false) && it > 0
      },
      gameMode = preferences.getBoolean(KEY_GAME_MODE, false),
      activeTeam = preferences.getInt(KEY_ACTIVE_TEAM, 1),
      remainingSeconds = preferences.getInt(KEY_REMAINING_SECONDS, 0).takeIf {
        preferences.getBoolean(KEY_HAS_REMAINING_TIME, false)
      },
      isPaused = preferences.getBoolean(KEY_IS_PAUSED, true),
    )

  private fun String?.toTurnOrder(): List<Int> =
    this
      ?.split(',')
      ?.mapNotNull { it.trim().toIntOrNull() }
      .orEmpty()

  private companion object {
    const val PREFERENCES_NAME = "game_state"
    const val SCHEMA_VERSION = 1
    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_TEAM_COUNT = "team_count"
    const val KEY_BOARD_SIZE = "board_size"
    const val KEY_TILES_PER_TEAM = "tiles_per_team"
    const val KEY_TURN_ORDER = "turn_order"
    const val KEY_FIRST_TEAM_BONUS = "first_team_bonus"
    const val KEY_SEED = "seed"
    const val KEY_HAS_TIMER = "has_timer"
    const val KEY_TIMER_DURATION_SECONDS = "timer_duration_seconds"
    const val KEY_GAME_MODE = "game_mode"
    const val KEY_ACTIVE_TEAM = "active_team"
    const val KEY_HAS_REMAINING_TIME = "has_remaining_time"
    const val KEY_REMAINING_SECONDS = "remaining_seconds"
    const val KEY_IS_PAUSED = "is_paused"

    const val DEFAULT_TEAM_COUNT = 2
    const val DEFAULT_BOARD_SIZE = 5
    const val DEFAULT_TILES_PER_TEAM = 8
  }
}
