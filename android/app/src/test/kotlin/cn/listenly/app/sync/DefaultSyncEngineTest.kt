package cn.listenly.app.sync

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.sqlite.driver.bundled.BundledSQLiteDriver
import androidx.test.core.app.ApplicationProvider
import cn.listenly.app.data.AppPreferences
import cn.listenly.app.data.Collections
import cn.listenly.app.data.Hlc
import cn.listenly.app.data.StudyRepository
import cn.listenly.app.data.db.ListenlyDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.Instant

/** Minimal in-memory server implementing docs/v3/sync-protocol.md semantics. */
private class FakeServer : SyncTransport {
    data class Row(val record: WireRecord, val seq: Long)

    val rows = mutableMapOf<Pair<String, String>, Row>()
    var seq = 0L
    var devices = 0
    var refreshCalls = 0
    var expireNextAccess = false
    var userId = "u_1"
    var rejectCollection: String? = null
    private val tokens = mutableMapOf<String, String>() // access token -> device

    private fun auth(): AuthResponse {
        val device = "d_${++devices}"
        val token = "access-$device-${System.nanoTime()}"
        tokens[token] = device
        return AuthResponse(userId, device, token, Instant.now().plusSeconds(900).toString(), "refresh-$device", Instant.now().plusSeconds(86400).toString())
    }

    override suspend fun register(server: String, body: AuthRequest) = auth()
    override suspend fun login(server: String, body: AuthRequest) = auth()
    override suspend fun refresh(server: String, refreshToken: String): AuthResponse {
        refreshCalls++
        val device = refreshToken.removePrefix("refresh-")
        val token = "access-$device-${System.nanoTime()}"
        tokens[token] = device
        return AuthResponse(userId, device, token, Instant.now().plusSeconds(900).toString(), refreshToken, Instant.now().plusSeconds(86400).toString())
    }
    override suspend fun logout(server: String, accessToken: String) = Unit
    override suspend fun me(server: String, accessToken: String) = MeResponse("u_1", "listener")
    override suspend fun deleteMe(server: String, accessToken: String, password: String) = Unit

    override suspend fun sync(server: String, accessToken: String, body: SyncRequest): SyncResponse {
        if (expireNextAccess) {
            expireNextAccess = false
            throw SyncException("unauthorized", "expired")
        }
        val device = tokens[accessToken] ?: throw SyncException("unauthorized", "bad token")
        var accepted = 0
        var ignored = 0
        val rejected = mutableListOf<Rejected>()
        for ((index, change) in body.changes.withIndex()) {
            if (change.collection == rejectCollection) {
                rejected += Rejected(index, "bad_clock")
                continue
            }
            val incoming = change.copy(device = device)
            val key = change.collection to change.key
            val existing = rows[key]?.record
            val newer = existing == null || incoming.clock > existing.clock || (incoming.clock == existing.clock && device > existing.device)
            if (newer) {
                rows[key] = Row(incoming, ++seq)
                accepted++
            } else {
                ignored++
            }
        }
        val out = rows.values.filter { it.seq > body.cursor }.sortedBy { it.seq }.map { it.record.copy(seq = it.seq) }
        return SyncResponse(accepted, ignored, rejected, out, out.lastOrNull()?.seq ?: maxOf(body.cursor, 0), false)
    }
}

private class Device(name: String, val server: FakeServer, scope: TestScope) {
    private val context: Context = ApplicationProvider.getApplicationContext()
    val db: ListenlyDatabase = Room.inMemoryDatabaseBuilder(context, ListenlyDatabase::class.java).setDriver(BundledSQLiteDriver()).build()
    val prefs = AppPreferences(PreferenceDataStoreFactory.create(scope = scope.backgroundScope) { File.createTempFile("prefs-$name", ".preferences_pb").also { it.delete() } })
    val hlc = Hlc()
    val study = StudyRepository(db, hlc, deviceId = { prefs.cachedDeviceIdOrNull() ?: "local" })
    val engine = DefaultSyncEngine(server, db.records(), prefs, hlc, scope.backgroundScope, "Test $name")
}

@RunWith(RobolectricTestRunner::class)
class DefaultSyncEngineTest {
    private val devices = mutableListOf<Device>()

    @After fun close() = devices.forEach { it.db.close() }

    @Test fun pushesLocalDataOnLoginAndPullsOnOtherDevice() = runTest {
        val server = FakeServer()
        val a = Device("a", server, this).also(devices::add)
        a.prefs.deviceId()
        a.study.choose("p", "art", "q1", "B")
        a.study.setBookmark("p", "art", true)

        a.engine.login("https://sync.example", "Listener", "password1")
        assertTrue(server.rows.containsKey(Collections.ANSWER to "p/art/q1"))
        assertEquals("d_1", server.rows.getValue(Collections.ANSWER to "p/art/q1").record.device)
        assertTrue(a.db.records().dirty(listOf(Collections.ANSWER, Collections.BOOKMARK), 10).isEmpty())
        assertEquals("listener", a.engine.account.value!!.username)

        val b = Device("b", server, this).also(devices::add)
        b.prefs.deviceId()
        b.engine.login("https://sync.example", "listener", "password1")
        val answer = b.db.records().get(Collections.ANSWER, "p/art/q1")!!
        assertTrue(answer.value!!.contains("\"B\""))
        assertEquals(setOf("p/art"), b.study.observeBookmarks().first())
    }

    @Test fun laterEditWinsAndTombstonesPropagate() = runTest {
        val server = FakeServer()
        val a = Device("a", server, this).also(devices::add)
        val b = Device("b", server, this).also(devices::add)
        a.prefs.deviceId(); b.prefs.deviceId()
        a.engine.login("https://s.example", "u", "password1")
        b.engine.login("https://s.example", "u", "password1")

        a.study.choose("p", "art", "q1", "A")
        a.engine.syncNow()
        b.engine.syncNow()
        b.study.choose("p", "art", "q1", "C") // later clock on B
        b.engine.syncNow()
        a.engine.syncNow()
        val onA = a.db.records().get(Collections.ANSWER, "p/art/q1")!!
        assertEquals(JsonPrimitive("C"), StudyRepository.json.parseToJsonElement(onA.value!!).jsonObject["choice"])

        b.study.setBookmark("p", "art", true); b.engine.syncNow(); a.engine.syncNow()
        b.study.setBookmark("p", "art", false); b.engine.syncNow(); a.engine.syncNow()
        assertTrue(a.study.observeBookmarks().first().isEmpty())
    }

    @Test fun disabledCollectionsAreNeitherPushedNorApplied() = runTest {
        val server = FakeServer()
        val a = Device("a", server, this).also(devices::add)
        a.prefs.deviceId()
        a.study.setSetting("accent", "amber") // settings sync is off by default
        a.engine.login("https://s.example", "u", "password1")
        assertNull(server.rows[Collections.SETTING to "accent"])
    }

    @Test fun refreshesOnceOnUnauthorizedAndRetries() = runTest {
        val server = FakeServer()
        val a = Device("a", server, this).also(devices::add)
        a.prefs.deviceId()
        a.engine.login("https://s.example", "u", "password1")
        server.expireNextAccess = true
        a.study.choose("p", "art", "q2", "D")
        a.engine.syncNow()
        assertEquals(1, server.refreshCalls)
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertTrue(server.rows.containsKey(Collections.ANSWER to "p/art/q2"))
    }

    @Test fun refusesPlainHttpServers() = runTest {
        val a = Device("a", FakeServer(), this).also(devices::add)
        val error = runCatching { a.engine.login("http://insecure.example", "u", "password1") }.exceptionOrNull()
        assertEquals("bad_server", (error as SyncException).code)
        assertNull(a.engine.account.value)
    }

    @Test fun rejectedRowsStayDirtyAndAreReported() = runTest {
        val server = FakeServer()
        val a = Device("a", server, this).also(devices::add)
        a.prefs.deviceId()
        a.engine.login("https://s.example", "u", "password1")
        server.rejectCollection = Collections.ANSWER
        a.study.choose("p", "art", "q1", "A")
        a.study.setBookmark("p", "art", true)
        a.engine.syncNow()
        assertTrue(a.engine.status.value is SyncStatus.Failed)
        assertEquals(1, a.db.records().dirty(listOf(Collections.ANSWER), 10).size)
        assertTrue(a.db.records().dirty(listOf(Collections.BOOKMARK), 10).isEmpty())
        server.rejectCollection = null
        a.engine.syncNow()
        assertTrue(a.engine.status.value is SyncStatus.Idle)
        assertTrue(server.rows.containsKey(Collections.ANSWER to "p/art/q1"))
    }

    @Test fun signingIntoAnotherAccountNeverUploadsPreviousData() = runTest {
        val server = FakeServer()
        val a = Device("a", server, this).also(devices::add)
        a.prefs.deviceId()
        a.study.choose("p", "art", "q1", "A")
        a.engine.login("https://s.example", "alice", "password1")
        a.engine.logout()
        a.study.choose("p", "art", "q2", "B") // made while signed out, still alice's device data
        server.rows.clear()
        server.userId = "u_2"
        val error = runCatching { a.engine.login("https://s.example", "bob", "password1") }.exceptionOrNull()
        assertEquals("other_owner", (error as SyncException).code)
        assertTrue(server.rows.isEmpty())
        a.study.choose("p", "art", "q3", "C") // new edits do sync to bob
        a.engine.syncNow()
        assertEquals(setOf(Collections.ANSWER to "p/art/q3"), server.rows.keys)
    }

    @Test fun concurrentCallersRefreshOnlyOnce() = runTest {
        val server = FakeServer()
        val a = Device("a", server, this).also(devices::add)
        a.prefs.deviceId()
        a.engine.login("https://s.example", "u", "password1")
        // Force an expired access token, then race sync with devices().
        val acct = a.engine.account.value!!
        a.prefs.setSyncAccountJson(DefaultSyncEngine.json.encodeToString(SyncAccount.serializer(), acct.copy(accessExpiresAt = "2000-01-01T00:00:00Z")))
        a.engine.restore()
        kotlinx.coroutines.coroutineScope {
            launch { a.engine.syncNow() }
            launch { a.engine.devices() }
        }
        assertEquals(1, server.refreshCalls)
    }
}
