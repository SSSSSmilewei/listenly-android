package cn.listenly.app.sync

import cn.listenly.app.data.AppPreferences
import cn.listenly.app.data.Hlc
import cn.listenly.app.data.db.RecordDao
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/** Offline flavor: no INTERNET permission, no HTTP client, nothing ever leaves the device. */
object SyncFactory {
    @Suppress("UNUSED_PARAMETER")
    suspend fun create(records: RecordDao, prefs: AppPreferences, hlc: Hlc, scope: CoroutineScope, deviceName: String): SyncEngine =
        OfflineSyncEngine
}

private object OfflineSyncEngine : SyncEngine {
    private fun unavailable(): Nothing = throw SyncException("offline", "离线版不包含同步功能")

    override val available = false
    override val status: StateFlow<SyncStatus> = MutableStateFlow(SyncStatus.Unavailable)
    override val account: StateFlow<SyncAccount?> = MutableStateFlow(null)
    override suspend fun register(serverUrl: String, username: String, password: String) = unavailable()
    override suspend fun login(serverUrl: String, username: String, password: String) = unavailable()
    override suspend fun logout() = Unit
    override suspend fun deleteAccount(password: String) = unavailable()
    override suspend fun devices(): List<SyncDevice> = emptyList()
    override suspend fun syncNow() = Unit
    override fun requestSync() = Unit
    override suspend fun resetCursor() = Unit
}
