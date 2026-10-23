package cn.listenly.app.sync

import cn.listenly.app.data.AppPreferences
import cn.listenly.app.data.Hlc
import cn.listenly.app.data.db.RecordDao
import kotlinx.coroutines.CoroutineScope

/** Standard flavor: real sync over HTTPS. */
object SyncFactory {
    suspend fun create(records: RecordDao, prefs: AppPreferences, hlc: Hlc, scope: CoroutineScope, deviceName: String): SyncEngine =
        DefaultSyncEngine(HttpSyncTransport(), records, prefs, hlc, scope, deviceName).also { it.restore() }
}
