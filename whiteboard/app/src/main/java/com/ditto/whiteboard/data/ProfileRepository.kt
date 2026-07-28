package com.ditto.whiteboard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn

private val Context.profileDataStore by preferencesDataStore(name = "whiteboard_profile")

data class ProfileSettings(val displayName: String, val colorArgb: Int)

data class ProfileLoadState(val loaded: Boolean = false, val profile: ProfileSettings? = null)

class ProfileRepository(context: Context, scope: CoroutineScope) {
  private val dataStore = context.profileDataStore

  val state: StateFlow<ProfileLoadState> = dataStore.data
    .catch { error ->
      if (error is IOException) emit(androidx.datastore.preferences.core.emptyPreferences()) else throw error
    }
    .map { preferences ->
      val name = preferences[DISPLAY_NAME]
      val color = preferences[DEFAULT_COLOR]
      ProfileLoadState(
        loaded = true,
        profile = if (name != null && color != null) ProfileSettings(name, color) else null,
      )
    }
    .stateIn(scope, SharingStarted.Eagerly, ProfileLoadState())

  suspend fun save(displayName: String, colorArgb: Int) {
    val trimmed = displayName.trim()
    require(trimmed.length in 1..24) { "Display name must be 1–24 characters" }
    dataStore.edit { preferences ->
      preferences[DISPLAY_NAME] = trimmed
      preferences[DEFAULT_COLOR] = colorArgb
    }
  }

  private companion object {
    val DISPLAY_NAME = stringPreferencesKey("display_name")
    val DEFAULT_COLOR = intPreferencesKey("default_color")
  }
}
