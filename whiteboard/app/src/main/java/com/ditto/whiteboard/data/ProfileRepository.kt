package com.ditto.whiteboard.data

import android.content.Context
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.ditto.whiteboard.protocol.MAX_PROTOCOL_COUNTER
import com.ditto.whiteboard.protocol.MAX_SESSION_LAMPORT
import com.ditto.whiteboard.protocol.MAX_SNAPSHOT_OPERATIONS
import com.ditto.whiteboard.domain.isApprovedWhiteboardColor
import com.ditto.whiteboard.domain.normalizeWhiteboardColor
import kotlin.math.max
import kotlin.coroutines.cancellation.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

private val Context.profileDataStore by preferencesDataStore(name = "whiteboard_profile")

data class ProfileSettings(val displayName: String, val colorArgb: Int)

internal fun loadedProfileSettings(displayName: String?, colorArgb: Int?): ProfileSettings? =
  if (displayName != null && colorArgb != null) {
    ProfileSettings(displayName, normalizeWhiteboardColor(colorArgb))
  } else {
    null
  }

enum class ProfileLoadFailure { StorageUnavailable }

data class ProfileLoadState(
  val loaded: Boolean = false,
  val profile: ProfileSettings? = null,
  val failure: ProfileLoadFailure? = null,
)

internal fun Flow<ProfileLoadState>.recoverProfileLoadFailures(): Flow<ProfileLoadState> =
  catch { error ->
    if (error is CancellationException) throw error
    if (error !is Exception) throw error
    emit(ProfileLoadState(loaded = true, failure = ProfileLoadFailure.StorageUnavailable))
  }

data class OperationClockReservation(
  val firstSenderSequence: Long,
  val initialLamport: Long,
  val lamportCeiling: Long,
)

class ProfileRepository(context: Context, private val scope: CoroutineScope) {
  private val dataStore = context.profileDataStore
  private val mutableState = MutableStateFlow(ProfileLoadState())
  val state: StateFlow<ProfileLoadState> = mutableState.asStateFlow()
  private var loadJob: Job? = null

  init {
    retryLoad()
  }

  fun retryLoad() {
    loadJob?.cancel()
    mutableState.value = ProfileLoadState()
    loadJob = scope.launch {
      dataStore.data.profileLoadStates().collect { mutableState.value = it }
    }
  }

  internal fun Flow<Preferences>.profileLoadStates(): Flow<ProfileLoadState> =
    map { preferences ->
      val name = preferences[DISPLAY_NAME]
      val color = preferences[DEFAULT_COLOR]
      ProfileLoadState(
        loaded = true,
        profile = loadedProfileSettings(name, color),
      )
    }
      .recoverProfileLoadFailures()

  suspend fun save(displayName: String, colorArgb: Int) {
    val trimmed = displayName.trim()
    require(trimmed.length in 1..24) { "Display name must be 1–24 characters" }
    require(trimmed.none(Char::isISOControl)) { "Display name cannot contain control characters" }
    require(isApprovedWhiteboardColor(colorArgb)) { "Drawing color must be in the approved palette" }
    dataStore.edit { preferences ->
      preferences[DISPLAY_NAME] = trimmed
      preferences[DEFAULT_COLOR] = colorArgb
    }
  }

  /**
   * Atomically reserves a durable sequence range for one process lifetime. Ditto peer keys persist
   * across process restarts, so restarting senderSequence at one would collide with this peer's
   * immutable operations when an established peer sends its snapshot back.
   */
  suspend fun reserveOperationClock(): OperationClockReservation {
    var firstSequence = 0L
    var initialLamport = 0L
    dataStore.edit { preferences ->
      // Sequence blocks prevent this persistent peer key from reusing operation ids after restart.
      // Lamport blocks are independent: remote Lamport values are not bounded by this peer's local
      // sequence and must be durably raised when observed.
      firstSequence = preferences[NEXT_OPERATION_SEQUENCE] ?: OPERATION_SEQUENCE_BLOCK_SIZE
      require(firstSequence <= MAX_PROTOCOL_COUNTER - OPERATION_SEQUENCE_BLOCK_SIZE) {
        "Operation sequence space is exhausted"
      }
      preferences[NEXT_OPERATION_SEQUENCE] = firstSequence + OPERATION_SEQUENCE_BLOCK_SIZE
      // Seed logical time from wall time as a hybrid logical clock. A peer that legitimately drew
      // offline after an older board Clear therefore sorts after that history even when discovery
      // takes longer than the initial readiness window.
      initialLamport = max(preferences[NEXT_LAMPORT] ?: 0L, System.currentTimeMillis())
      require(initialLamport <= MAX_SESSION_LAMPORT) {
        "Lamport clock space is exhausted"
      }
      val ceiling = (initialLamport + LAMPORT_BLOCK_SIZE)
        .coerceAtMost(MAX_SESSION_LAMPORT)
      preferences[NEXT_LAMPORT] = ceiling
    }
    return OperationClockReservation(
      firstSenderSequence = firstSequence,
      initialLamport = initialLamport,
      lamportCeiling = (initialLamport + LAMPORT_BLOCK_SIZE)
        .coerceAtMost(MAX_SESSION_LAMPORT),
    )
  }

  /**
   * Durably reserves a fresh Lamport block beyond [observedLamport]. The write completes before the
   * inbound operation is applied, so a process crash cannot make the next incarnation publish below
   * history it had already accepted.
   */
  suspend fun reserveLamportAfter(observedLamport: Long): Long {
    var ceiling = 0L
    dataStore.edit { preferences ->
      val alreadyReserved = preferences[NEXT_LAMPORT] ?: 0L
      if (observedLamport < alreadyReserved) {
        ceiling = alreadyReserved
      } else {
        require(observedLamport <= MAX_SESSION_LAMPORT) {
          "Lamport clock space is exhausted"
        }
        ceiling = (observedLamport + LAMPORT_BLOCK_SIZE)
          .coerceAtMost(MAX_SESSION_LAMPORT)
        preferences[NEXT_LAMPORT] = ceiling
      }
    }
    return ceiling
  }

  private companion object {
    const val OPERATION_SEQUENCE_BLOCK_SIZE = 1_000_000L
    const val LAMPORT_BLOCK_SIZE = MAX_SNAPSHOT_OPERATIONS.toLong() + 1L
    val DISPLAY_NAME = stringPreferencesKey("display_name")
    val DEFAULT_COLOR = intPreferencesKey("default_color")
    val NEXT_OPERATION_SEQUENCE = longPreferencesKey("next_operation_sequence")
    val NEXT_LAMPORT = longPreferencesKey("next_lamport")
  }
}
