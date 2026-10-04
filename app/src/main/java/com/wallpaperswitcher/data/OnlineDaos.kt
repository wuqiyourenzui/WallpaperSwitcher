package com.wallpaperswitcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/** 在线壁纸源 CRUD (see [OnlineSource]). */
@Dao
interface OnlineSourceDao {

    @Query("SELECT * FROM online_sources ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<OnlineSource>>

    @Query("SELECT * FROM online_sources ORDER BY createdAt DESC")
    suspend fun getAll(): List<OnlineSource>

    @Query("SELECT * FROM online_sources WHERE id = :id")
    suspend fun getById(id: Long): OnlineSource?

    @Query("SELECT * FROM online_sources WHERE enabled = 1")
    suspend fun getEnabled(): List<OnlineSource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: OnlineSource): Long

    @Update
    suspend fun update(source: OnlineSource)

    @Query("DELETE FROM online_sources WHERE id = :id")
    suspend fun delete(id: Long)

    /**
     * One sync result. [errorAt] is non-zero only for failures, so the UI can
     * show "上次失败" without parsing [result].
     */
    @Query(
        "UPDATE online_sources SET lastFetchAt = :at, lastResult = :result, " +
            "lastErrorAt = :errorAt WHERE id = :id"
    )
    suspend fun recordResult(id: Long, at: Long, result: String, errorAt: Long)

    /** TYPE_URL validators: skip the download when the server answers 304. */
    @Query("UPDATE online_sources SET etag = :etag, lastModified = :lastModified WHERE id = :id")
    suspend fun recordHttpValidators(id: Long, etag: String, lastModified: String)
}

/** Downloaded items of a source (see [OnlineItem]). */
@Dao
interface OnlineItemDao {

    @Query("SELECT * FROM online_items WHERE sourceId = :sourceId ORDER BY fetchedAt DESC")
    suspend fun getBySource(sourceId: Long): List<OnlineItem>

    @Query("SELECT remoteKey FROM online_items WHERE sourceId = :sourceId")
    suspend fun remoteKeys(sourceId: Long): List<String>

    @Query(
        "SELECT contentHash FROM online_items " +
            "WHERE sourceId = :sourceId AND contentHash != ''"
    )
    suspend fun contentHashes(sourceId: Long): List<String>

    /** The item that already holds [hash] (used for content de-duplication). */
    @Query(
        "SELECT * FROM online_items WHERE sourceId = :sourceId AND contentHash = :hash LIMIT 1"
    )
    suspend fun itemByHash(sourceId: Long, hash: String): OnlineItem?

    /**
     * The rows beyond the newest [keep] ones (oldest first), i.e. the retention
     * deletions. Kept as one indexed query instead of loading every row into
     * Kotlin: a long-running Bing source reaches thousands of rows.
     *
     * `album:%` rows are 美人图 progress markers, not downloaded items - they
     * must never be evicted by the retention window, otherwise the source would
     * re-scrape albums that were already consumed.
     */
    @Query(
        "SELECT * FROM online_items WHERE sourceId = :sourceId AND remoteKey NOT LIKE 'album:%' " +
            "ORDER BY fetchedAt DESC LIMIT -1 OFFSET :keep"
    )
    suspend fun itemsBeyond(sourceId: Long, keep: Int): List<OnlineItem>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(item: OnlineItem)

    @Query("DELETE FROM online_items WHERE sourceId = :sourceId AND remoteKey = :remoteKey")
    suspend fun delete(sourceId: Long, remoteKey: String)

    /** Clear the media id of an item whose media row is gone (tombstone kept). */
    @Query(
        "UPDATE online_items SET imageId = 0, filePath = '' " +
            "WHERE sourceId = :sourceId AND remoteKey = :remoteKey"
    )
    suspend fun clearMedia(sourceId: Long, remoteKey: String)
}
