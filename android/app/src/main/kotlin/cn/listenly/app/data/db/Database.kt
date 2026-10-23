package cn.listenly.app.data.db

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

/** A stored, already-validated paper. [json] is the full [cn.listenly.app.model.Paper]. */
@Entity(tableName = "papers")
data class PaperEntity(
    @PrimaryKey val id: String,
    val title: String,
    @ColumnInfo(name = "collection_id") val collectionId: String,
    @ColumnInfo(name = "collection_title") val collectionTitle: String,
    val year: Int?,
    val json: String,
    /** `demo`, `import` or `legacy`. */
    val origin: String,
    /** Folder under files/imports holding this paper's files, if any. */
    @ColumnInfo(name = "import_id") val importId: String?,
    @ColumnInfo(name = "imported_at") val importedAt: Long,
)

/**
 * Every piece of study data that may sync, in the exact shape of docs/v3/sync-protocol.md:
 * answers, positions, bookmarks, completions, per-device activity days and synced settings.
 */
@Entity(
    tableName = "records",
    primaryKeys = ["collection", "key"],
    indices = [Index(value = ["dirty"])],
)
data class RecordEntity(
    val collection: String,
    val key: String,
    /** JSON object text, or null for a tombstone / null value. */
    val value: String?,
    val deleted: Boolean,
    val clock: Long,
    val device: String,
    /** True until the server has acknowledged this exact (clock, device). */
    val dirty: Boolean,
)

@Dao
interface PaperDao {
    @Query("SELECT * FROM papers ORDER BY collection_id, year DESC, imported_at")
    fun observeAll(): Flow<List<PaperEntity>>

    @Query("SELECT * FROM papers ORDER BY collection_id, year DESC, imported_at")
    suspend fun all(): List<PaperEntity>

    @Query("SELECT * FROM papers WHERE id = :id")
    suspend fun byId(id: String): PaperEntity?

    @Upsert
    suspend fun upsertAll(papers: List<PaperEntity>)

    @Query("DELETE FROM papers WHERE id = :id")
    suspend fun delete(id: String)

    @Query("SELECT COUNT(*) FROM papers WHERE import_id = :importId")
    suspend fun countForImport(importId: String): Int
}

@Dao
interface RecordDao {
    @Query("SELECT * FROM records WHERE collection = :collection AND key = :key")
    suspend fun get(collection: String, key: String): RecordEntity?

    @Query("SELECT * FROM records WHERE collection = :collection AND deleted = 0")
    fun observeCollection(collection: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE collection = :collection AND deleted = 0")
    suspend fun collection(collection: String): List<RecordEntity>

    @Query("SELECT * FROM records WHERE collection = :collection AND key = :key AND deleted = 0")
    fun observe(collection: String, key: String): Flow<RecordEntity?>

    @Query("SELECT * FROM records WHERE collection = :collection AND key LIKE :prefix || '%' ESCAPE '\\' AND deleted = 0")
    fun observePrefix(collection: String, prefix: String): Flow<List<RecordEntity>>

    @Query("SELECT * FROM records WHERE dirty = 1 AND collection IN (:collections) ORDER BY clock LIMIT :limit")
    suspend fun dirty(collections: List<String>, limit: Int): List<RecordEntity>

    @Query("SELECT MAX(clock) FROM records")
    suspend fun maxClock(): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(record: RecordEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun putAll(records: List<RecordEntity>)

    /** Clears [dirty] only if the row still holds the exact version that was pushed. */
    @Query("UPDATE records SET dirty = 0 WHERE collection = :collection AND key = :key AND clock = :clock")
    suspend fun markClean(collection: String, key: String, clock: Long)

    @Query("UPDATE records SET dirty = 0")
    suspend fun markAllClean()

    @Query("UPDATE records SET dirty = 1, device = :device")
    suspend fun markAllDirty(device: String)

    /** Applies a remote record only when it is strictly newer by (clock, device). */
    @Transaction
    suspend fun applyRemote(remote: RecordEntity): Boolean {
        val local = get(remote.collection, remote.key)
        if (local != null && !isNewer(remote.clock, remote.device, local.clock, local.device)) return false
        put(remote.copy(dirty = false))
        return true
    }
}

fun isNewer(clock: Long, device: String, otherClock: Long, otherDevice: String): Boolean =
    clock > otherClock || (clock == otherClock && device > otherDevice)

@Database(entities = [PaperEntity::class, RecordEntity::class], version = 1, exportSchema = true)
abstract class ListenlyDatabase : RoomDatabase() {
    abstract fun papers(): PaperDao
    abstract fun records(): RecordDao
}
