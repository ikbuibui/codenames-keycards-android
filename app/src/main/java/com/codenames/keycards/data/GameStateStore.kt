package com.codenames.keycards.data

import android.content.Context
import androidx.core.content.edit
import com.codenames.keycards.model.CandidateAgreement
import com.codenames.keycards.model.GameState
import com.codenames.keycards.model.GameStateSnapshot
import com.codenames.keycards.model.RecognizedBoard
import com.codenames.keycards.model.RecognizedCell
import com.codenames.keycards.model.normalizedGameState
import com.codenames.keycards.model.restoreAfterRestart
import com.codenames.keycards.model.toGameState
import com.codenames.keycards.model.toSnapshot
import org.json.JSONArray
import org.json.JSONObject

/** Always persists the current setup and game in private SharedPreferences. */
class GameStateStore(context: Context) {
  private val preferences = context.getSharedPreferences(PREFERENCES_NAME, Context.MODE_PRIVATE)

  fun load(): GameState {
    // Schema two did not contain a word board. Keep its saved game and migrate on save.
    val schema = preferences.getInt(KEY_SCHEMA_VERSION, 0)
    val state = if (schema in LEGACY_SCHEMA_VERSION..SCHEMA_VERSION) readSnapshot().toGameState() else GameState()

    // Time must never elapse while the activity/process was absent.
    return restoreAfterRestart(state).also(::save)
  }

  fun save(state: GameState) {
    val snapshot = normalizedGameState(state).toSnapshot()
    preferences.edit {
      putInt(KEY_SCHEMA_VERSION, SCHEMA_VERSION)
      putInt(KEY_TEAM_COUNT, snapshot.teamCount)
      putInt(KEY_BOARD_ROWS, snapshot.boardRows)
      putInt(KEY_BOARD_COLUMNS, snapshot.boardColumns)
      putBoolean(KEY_LINK_BOARD_DIMENSIONS, snapshot.linkBoardDimensions)
      putInt(KEY_TILES_PER_TEAM, snapshot.tilesPerTeam)
      putString(KEY_TURN_ORDER, snapshot.turnOrder.joinToString(separator = ","))
      putBoolean(KEY_FIRST_TEAM_BONUS, snapshot.firstTeamBonus)
      putString(KEY_KEYCARD, snapshot.keycard.joinToString(separator = ","))
      putBoolean(KEY_HAS_TIMER, snapshot.timerDurationSeconds != null)
      putInt(KEY_TIMER_DURATION_SECONDS, snapshot.timerDurationSeconds ?: 0)
      putBoolean(KEY_GAME_MODE, snapshot.gameMode)
      putInt(KEY_ACTIVE_TEAM, snapshot.activeTeam)
      putBoolean(KEY_HAS_REMAINING_TIME, snapshot.remainingSeconds != null)
      putInt(KEY_REMAINING_SECONDS, snapshot.remainingSeconds ?: 0)
      putBoolean(KEY_IS_PAUSED, snapshot.isPaused)
      // Words can contain commas, line breaks, quotes, and Unicode: never hand-encode them.
      putString(KEY_RECOGNIZED_BOARD_JSON, snapshot.recognizedBoard?.toJson()?.toString())
      putString(KEY_GUESSED_CELL_INDICES_JSON, snapshot.guessedCellIndices.toJsonArray().toString())
      putString(KEY_TARGET_DISPLAY_ORDERS_JSON, snapshot.targetDisplayOrders.toJson().toString())
    }
  }

  private fun readSnapshot(): GameStateSnapshot =
    GameStateSnapshot(
      teamCount = preferences.getInt(KEY_TEAM_COUNT, DEFAULT_TEAM_COUNT),
      boardRows = preferences.getInt(KEY_BOARD_ROWS, DEFAULT_BOARD_DIMENSION),
      boardColumns = preferences.getInt(KEY_BOARD_COLUMNS, DEFAULT_BOARD_DIMENSION),
      linkBoardDimensions = preferences.getBoolean(KEY_LINK_BOARD_DIMENSIONS, false),
      tilesPerTeam = preferences.getInt(KEY_TILES_PER_TEAM, DEFAULT_TILES_PER_TEAM),
      turnOrder = preferences.getString(KEY_TURN_ORDER, null).toIntList(),
      firstTeamBonus = preferences.getBoolean(KEY_FIRST_TEAM_BONUS, true),
      keycard = preferences.getString(KEY_KEYCARD, null).toIntList(),
      timerDurationSeconds = preferences.getInt(KEY_TIMER_DURATION_SECONDS, 0).takeIf {
        preferences.getBoolean(KEY_HAS_TIMER, false) && it > 0
      },
      gameMode = preferences.getBoolean(KEY_GAME_MODE, false),
      activeTeam = preferences.getInt(KEY_ACTIVE_TEAM, 1),
      remainingSeconds = preferences.getInt(KEY_REMAINING_SECONDS, 0).takeIf {
        preferences.getBoolean(KEY_HAS_REMAINING_TIME, false)
      },
      isPaused = preferences.getBoolean(KEY_IS_PAUSED, true),
      recognizedBoard = preferences.getString(KEY_RECOGNIZED_BOARD_JSON, null).toRecognizedBoard(),
      guessedCellIndices = preferences.getString(KEY_GUESSED_CELL_INDICES_JSON, null).toIntSetJson(),
      targetDisplayOrders = preferences.getString(KEY_TARGET_DISPLAY_ORDERS_JSON, null).toTargetDisplayOrders(),
    )

  private fun String?.toIntList(): List<Int> =
    this?.split(',')?.mapNotNull { it.trim().toIntOrNull() }.orEmpty()

  private fun String?.toRecognizedBoard(): RecognizedBoard? {
    if (isNullOrBlank()) return null
    return runCatching {
      val json = JSONObject(this)
      require(json.getInt("version") == BOARD_SCHEMA_VERSION)
      val rows = json.getInt("rows")
      val columns = json.getInt("columns")
      val cells = json.getJSONArray("cells")
      val result = buildList(cells.length()) {
        for (index in 0 until cells.length()) {
          val cell = cells.getJSONObject(index)
          add(
            RecognizedCell(
              text = cell.getString("text"),
              confidence = if (cell.isNull("confidence")) null else cell.getInt("confidence"),
              candidateAgreement =
                runCatching { CandidateAgreement.valueOf(cell.getString("candidateAgreement")) }
                  .getOrDefault(CandidateAgreement.SINGLE_READ),
              manuallyEdited = cell.optBoolean("manuallyEdited", false),
            ),
          )
        }
      }
      RecognizedBoard(rows, columns, result)
    }.getOrNull()
  }

  private fun String?.toIntSetJson(): Set<Int> {
    if (isNullOrBlank()) return emptySet()
    return runCatching {
      val values = JSONArray(this)
      buildSet {
        for (index in 0 until values.length()) add(values.getInt(index))
      }
    }.getOrDefault(emptySet())
  }

  private fun String?.toTargetDisplayOrders(): Map<Int, List<Int>> {
    if (isNullOrBlank()) return emptyMap()
    return runCatching {
      val json = JSONObject(this)
      buildMap {
        val keys = json.keys()
        while (keys.hasNext()) {
          val team = keys.next().toIntOrNull() ?: continue
          val values = json.getJSONArray(team.toString())
          put(team, List(values.length()) { index -> values.getInt(index) })
        }
      }
    }.getOrDefault(emptyMap())
  }

  private companion object {
    const val PREFERENCES_NAME = "game_state"
    const val LEGACY_SCHEMA_VERSION = 2
    const val SCHEMA_VERSION = 3
    const val BOARD_SCHEMA_VERSION = 1
    const val KEY_SCHEMA_VERSION = "schema_version"
    const val KEY_TEAM_COUNT = "team_count"
    const val KEY_BOARD_ROWS = "board_rows"
    const val KEY_BOARD_COLUMNS = "board_columns"
    const val KEY_LINK_BOARD_DIMENSIONS = "link_board_dimensions"
    const val KEY_TILES_PER_TEAM = "tiles_per_team"
    const val KEY_TURN_ORDER = "turn_order"
    const val KEY_FIRST_TEAM_BONUS = "first_team_bonus"
    const val KEY_KEYCARD = "keycard"
    const val KEY_HAS_TIMER = "has_timer"
    const val KEY_TIMER_DURATION_SECONDS = "timer_duration_seconds"
    const val KEY_GAME_MODE = "game_mode"
    const val KEY_ACTIVE_TEAM = "active_team"
    const val KEY_HAS_REMAINING_TIME = "has_remaining_time"
    const val KEY_REMAINING_SECONDS = "remaining_seconds"
    const val KEY_IS_PAUSED = "is_paused"
    const val KEY_RECOGNIZED_BOARD_JSON = "recognized_board_json"
    const val KEY_GUESSED_CELL_INDICES_JSON = "guessed_cell_indices_json"
    const val KEY_TARGET_DISPLAY_ORDERS_JSON = "target_display_orders_json"

    const val DEFAULT_TEAM_COUNT = 2
    const val DEFAULT_BOARD_DIMENSION = 5
    const val DEFAULT_TILES_PER_TEAM = 8
  }
}

private fun RecognizedBoard.toJson(): JSONObject =
  JSONObject()
    .put("version", 1)
    .put("rows", rows)
    .put("columns", columns)
    .put(
      "cells",
      JSONArray().also { cellsJson ->
        cells.forEach { cell ->
          cellsJson.put(
            JSONObject()
              .put("text", cell.text)
              .put("confidence", cell.confidence)
              .put("candidateAgreement", cell.candidateAgreement.name)
              .put("manuallyEdited", cell.manuallyEdited),
          )
        }
      },
    )

private fun Set<Int>.toJsonArray(): JSONArray = JSONArray().also { json -> forEach(json::put) }

private fun Map<Int, List<Int>>.toJson(): JSONObject =
  JSONObject().also { json ->
    forEach { (team, indices) -> json.put(team.toString(), indices.toJsonArray()) }
  }

private fun List<Int>.toJsonArray(): JSONArray = JSONArray().also { json -> forEach(json::put) }
