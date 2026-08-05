package com.codenames.keycards.data

import android.content.Context
import com.codenames.keycards.model.KeycardSettings
import com.codenames.keycards.model.normalized

/** Stores only a frozen card's settings on the device, so it survives app restarts. */
class FrozenKeycardStore(context: Context) {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun load(): KeycardSettings? {
    if (!preferences.getBoolean(KEY_FROZEN, false)) return null

    return normalized(
      KeycardSettings(
        teamCount = preferences.getInt(KEY_TEAM_COUNT, 2),
        boardSize = preferences.getInt(KEY_BOARD_SIZE, 5),
        tilesPerTeam = preferences.getInt(KEY_TILES_PER_TEAM, 8),
        startingTeam = preferences.getInt(KEY_STARTING_TEAM, 1),
        seed = preferences.getLong(KEY_SEED, System.currentTimeMillis()),
        frozen = true,
      )
    )
  }

  fun save(settings: KeycardSettings) {
    preferences.edit()
      .putBoolean(KEY_FROZEN, true)
      .putInt(KEY_TEAM_COUNT, settings.teamCount)
      .putInt(KEY_BOARD_SIZE, settings.boardSize)
      .putInt(KEY_TILES_PER_TEAM, settings.tilesPerTeam)
      .putInt(KEY_STARTING_TEAM, settings.startingTeam)
      .putLong(KEY_SEED, settings.seed)
      .apply()
  }

  fun clear() {
    preferences.edit().clear().apply()
  }

  private companion object {
    const val PREFERENCES_NAME = "frozen_keycard"
    const val KEY_FROZEN = "frozen"
    const val KEY_TEAM_COUNT = "team_count"
    const val KEY_BOARD_SIZE = "board_size"
    const val KEY_TILES_PER_TEAM = "tiles_per_team"
    const val KEY_STARTING_TEAM = "starting_team"
    const val KEY_SEED = "seed"
  }
}
