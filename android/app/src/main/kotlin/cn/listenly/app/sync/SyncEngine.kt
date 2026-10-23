package cn.listenly.app.sync

import kotlinx.coroutines.flow.StateFlow
import kotlinx.serialization.Serializable

/** A signed-in sync session. Stored in app-private DataStore; never logged. */
@Serializable
data class SyncAccount(
    val serverUrl: String,
    val username: String,
    val userId: String,
    val deviceId: String,
    val accessToken: String,
    val accessExpiresAt: String,
    val refreshToken: String,
    val refreshExpiresAt: String,
)

sealed interface SyncStatus {
    /** This build has no network access (离线版). */
    data object Unavailable : SyncStatus
    data object SignedOut : SyncStatus
    data class Idle(val lastSyncAt: Long?) : SyncStatus
    data object Syncing : SyncStatus
    data class Failed(val message: String, val lastSyncAt: Long?) : SyncStatus
}

data class SyncDevice(val id: String, val name: String, val lastSeenAt: String?, val current: Boolean)

class SyncException(val code: String, message: String) : Exception(message)

/**
 * Optional sync. The standard flavor talks to a server speaking docs/v3/sync-protocol.md;
 * the offline flavor provides a no-op that never touches the network.
 */
interface SyncEngine {
    val available: Boolean
    val status: StateFlow<SyncStatus>
    val account: StateFlow<SyncAccount?>

    suspend fun register(serverUrl: String, username: String, password: String)
    suspend fun login(serverUrl: String, username: String, password: String)
    suspend fun logout()
    suspend fun deleteAccount(password: String)
    suspend fun devices(): List<SyncDevice>

    /** Push local changes and pull remote ones now. Safe to call repeatedly. */
    suspend fun syncNow()

    /** Debounced background sync after local writes. */
    fun requestSync()

    /** After a sync toggle is switched on, pull everything again so skipped records arrive. */
    suspend fun resetCursor()
}
