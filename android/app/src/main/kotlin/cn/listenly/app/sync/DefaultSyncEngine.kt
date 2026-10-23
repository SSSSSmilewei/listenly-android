package cn.listenly.app.sync

import cn.listenly.app.data.AppPreferences
import cn.listenly.app.data.Hlc
import cn.listenly.app.data.SyncToggles
import cn.listenly.app.data.db.RecordDao
import cn.listenly.app.data.db.RecordEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import java.time.Instant

/**
 * Offline-first sync over a [SyncTransport]: push dirty rows, pull everything after the cursor,
 * apply last-writer-wins by (clock, device). The local database stays the source of truth.
 */
class DefaultSyncEngine(
    private val transport: SyncTransport,
    private val records: RecordDao,
    private val prefs: AppPreferences,
    private val hlc: Hlc,
    private val scope: CoroutineScope,
    private val deviceName: String,
    private val now: () -> Instant = Instant::now,
) : SyncEngine {

    override val available = true

    private val _account = MutableStateFlow<SyncAccount?>(null)
    override val account: StateFlow<SyncAccount?> = _account.asStateFlow()

    private val _status = MutableStateFlow<SyncStatus>(SyncStatus.SignedOut)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val mutex = Mutex()
    private val refreshMutex = Mutex()
    private var pending: Job? = null

    suspend fun restore() {
        val saved = prefs.syncAccountJson.first()?.let { runCatching { json.decodeFromString(SyncAccount.serializer(), it) }.getOrNull() }
        _account.value = saved
        _status.value = if (saved == null) SyncStatus.SignedOut else SyncStatus.Idle(prefs.syncLastAt.first())
    }

    override suspend fun register(serverUrl: String, username: String, password: String) =
        signIn(serverUrl, username) { server, name -> transport.register(server, AuthRequest(name, password, deviceName)) }

    override suspend fun login(serverUrl: String, username: String, password: String) =
        signIn(serverUrl, username) { server, name -> transport.login(server, AuthRequest(name, password, deviceName)) }

    private suspend fun signIn(serverUrl: String, username: String, call: suspend (String, String) -> AuthResponse) {
        val server = normalizeServer(serverUrl)
        val name = username.trim().lowercase()
        val auth = call(server, name)
        val owner = prefs.syncOwner()
        val sameOwner = owner == null || owner == auth.userId
        mutex.withLock {
            save(auth.toAccount(server, name))
            prefs.setDeviceId(auth.deviceId)
            if (sameOwner) {
                // This device's records belong to this account: (re-)push everything we have.
                records.markAllDirty(auth.deviceId)
            } else {
                // Signing into a DIFFERENT account never uploads the previous account's records;
                // they stay on this device only. Edits made from now on do sync.
                records.markAllClean()
            }
            prefs.setSyncOwner(auth.userId)
            prefs.setSyncCursor(0)
        }
        _status.value = SyncStatus.Idle(null)
        syncNow()
        if (!sameOwner) throw SyncException("other_owner", syncErrorMessage("other_owner"))
    }

    override suspend fun logout() {
        if (_account.value == null) return
        runCatching { withFreshToken { a -> transport.logout(a.serverUrl, a.accessToken) } }
        clearSession()
    }

    override suspend fun deleteAccount(password: String) {
        if (_account.value == null) return
        withFreshToken { a -> transport.deleteMe(a.serverUrl, a.accessToken, password) }
        clearSession()
    }

    override suspend fun devices(): List<SyncDevice> {
        if (_account.value == null) return emptyList()
        return withFreshToken { a -> transport.me(a.serverUrl, a.accessToken) }
            .devices.map { SyncDevice(it.id, it.name, it.lastSeenAt, it.current) }
    }

    override fun requestSync() {
        if (_account.value == null) return
        // Throttle, don't debounce: frequent writes (position checkpoints every 5 s) must not keep
        // postponing sync, and a sync already running is never cancelled by a new write.
        if (pending?.isActive == true) return
        pending = scope.launch {
            delay(DEBOUNCE_MS)
            runCatching { syncNow() }
        }
    }

    override suspend fun resetCursor() {
        mutex.withLock { prefs.setSyncCursor(0) }
    }

    override suspend fun syncNow() {
        mutex.withLock {
            val start = _account.value ?: return
            val toggles = prefs.syncToggles.first()
            _status.value = SyncStatus.Syncing
            try {
                val rejected = runRounds(start, toggles)
                val at = now().toEpochMilli()
                prefs.setSyncLastAt(at)
                _status.value = if (rejected == 0) {
                    SyncStatus.Idle(at)
                } else {
                    SyncStatus.Failed("$rejected 条记录被服务器拒收（多为手机时间不准），已保留在本机待重试", at)
                }
            } catch (error: SyncException) {
                if (error.code == "invalid_refresh_token" || error.code == "unauthorized") {
                    clearSession(SyncStatus.Failed(syncErrorMessage(error.code), null))
                } else {
                    _status.value = SyncStatus.Failed(error.message ?: syncErrorMessage(error.code), prefs.syncLastAt.first())
                }
            } catch (error: CancellationException) {
                if (_status.value == SyncStatus.Syncing) _status.value = SyncStatus.Idle(prefs.syncLastAt.first())
                throw error
            } catch (error: Exception) {
                _status.value = SyncStatus.Failed(syncErrorMessage("bad_response"), prefs.syncLastAt.first())
            }
        }
    }

    /** Returns how many local rows the server rejected; those stay dirty for a later retry. */
    private suspend fun runRounds(account: SyncAccount, toggles: SyncToggles): Int {
        val collections = toggles.collections
        val rejectedRows = mutableSetOf<Triple<String, String, Long>>()
        repeat(MAX_ROUNDS) {
            val dirty = if (collections.isEmpty()) {
                emptyList()
            } else {
                records.dirty(collections, PUSH_LIMIT).filter { Triple(it.collection, it.key, it.clock) !in rejectedRows }
            }
            val cursor = prefs.syncCursor()
            val request = SyncRequest(cursor, dirty.map { it.toWire() })
            val response = withFreshToken { current -> transport.sync(current.serverUrl, current.accessToken, request) }
            val rejectedIndexes = response.rejected.map { it.index }.toSet()
            dirty.forEachIndexed { index, row ->
                if (index in rejectedIndexes) {
                    rejectedRows += Triple(row.collection, row.key, row.clock)
                } else {
                    // Accepted or ignored (server already newer): this version is settled.
                    records.markClean(row.collection, row.key, row.clock)
                }
            }
            for (remote in response.changes) {
                if (remote.collection !in collections) continue
                hlc.observe(remote.clock)
                records.applyRemote(remote.toEntity())
            }
            prefs.setSyncCursor(response.cursor)
            val morePush = dirty.size - rejectedIndexes.size > 0 && dirty.size == PUSH_LIMIT
            if (!response.hasMore && !morePush) return rejectedRows.size
        }
        return rejectedRows.size
    }

    /** Runs [block] with a valid session, refreshing once on expiry or a 401. */
    private suspend fun <T> withFreshToken(block: suspend (SyncAccount) -> T): T {
        var current = _account.value ?: throw SyncException("unauthorized", syncErrorMessage("unauthorized"))
        if (expiresSoon(current.accessExpiresAt)) current = refresh(current)
        return try {
            block(current)
        } catch (error: SyncException) {
            if (error.code != "unauthorized") throw error
            block(refresh(current))
        }
    }

    /**
     * Serialized: concurrent refreshes with one refresh token would trip the server's rotation
     * (only one successor survives). A caller holding a stale account gets the already-rotated one.
     */
    private suspend fun refresh(stale: SyncAccount): SyncAccount = refreshMutex.withLock {
        val latest = _account.value ?: throw SyncException("unauthorized", syncErrorMessage("unauthorized"))
        if (latest.accessToken != stale.accessToken && !expiresSoon(latest.accessExpiresAt)) return latest
        val auth = transport.refresh(latest.serverUrl, latest.refreshToken)
        val updated = auth.toAccount(latest.serverUrl, latest.username)
        save(updated)
        updated
    }

    private fun expiresSoon(iso: String): Boolean =
        runCatching { Instant.parse(iso).isBefore(now().plusSeconds(60)) }.getOrDefault(true)

    private suspend fun save(account: SyncAccount) {
        prefs.setSyncAccountJson(json.encodeToString(SyncAccount.serializer(), account))
        _account.value = account
    }

    private suspend fun clearSession(status: SyncStatus = SyncStatus.SignedOut) {
        pending?.cancel()
        prefs.setSyncAccountJson(null)
        prefs.setSyncCursor(0)
        _account.value = null
        _status.value = status
    }

    private fun AuthResponse.toAccount(server: String, username: String) = SyncAccount(
        serverUrl = server,
        username = username,
        userId = userId,
        deviceId = deviceId,
        accessToken = accessToken,
        accessExpiresAt = accessExpiresAt,
        refreshToken = refreshToken,
        refreshExpiresAt = refreshExpiresAt,
    )

    companion object {
        const val PUSH_LIMIT = 500
        const val MAX_ROUNDS = 40
        const val DEBOUNCE_MS = 5_000L
        val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

        fun normalizeServer(input: String): String {
            val trimmed = input.trim().trimEnd('/')
            if (!trimmed.startsWith("https://") || trimmed.length <= "https://".length) {
                throw SyncException("bad_server", syncErrorMessage("bad_server"))
            }
            return trimmed
        }

        fun RecordEntity.toWire() = WireRecord(
            collection = collection,
            key = key,
            value = if (deleted || value == null) JsonNull else json.parseToJsonElement(value),
            deleted = deleted,
            clock = clock,
            device = device,
        )

        fun WireRecord.toEntity() = RecordEntity(
            collection = collection,
            key = key,
            value = value?.takeUnless { it is JsonNull || deleted }?.let { json.encodeToString(JsonElement.serializer(), it) },
            deleted = deleted,
            clock = clock,
            device = device,
            dirty = false,
        )
    }
}
