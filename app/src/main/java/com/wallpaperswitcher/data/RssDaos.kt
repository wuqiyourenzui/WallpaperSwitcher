package com.wallpaperswitcher.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * How many articles one source keeps, and how many the list can show. Paging
 * appends older pages, so this must be well above one page (otherwise the
 * freshly fetched rows would immediately be pruned again).
 */
const val LIST_LIMIT = 1000

/** 阅读订阅源 CRUD (see [RssSource]). */
@Dao
interface RssSourceDao {

    @Query("SELECT * FROM rss_sources ORDER BY createdAt DESC")
    fun observeAll(): Flow<List<RssSource>>

    @Query("SELECT * FROM rss_sources ORDER BY createdAt DESC")
    suspend fun getAll(): List<RssSource>

    @Query("SELECT * FROM rss_sources WHERE id = :id")
    suspend fun getById(id: Long): RssSource?

    @Query("SELECT * FROM rss_sources WHERE enabled = 1")
    suspend fun getEnabled(): List<RssSource>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(source: RssSource): Long

    @Update
    suspend fun update(source: RssSource)

    @Query("DELETE FROM rss_sources WHERE id = :id")
    suspend fun delete(id: Long)

    @Query(
        "UPDATE rss_sources SET lastFetchAt = :at, lastResult = :result, " +
            "lastErrorAt = :errorAt WHERE id = :id"
    )
    suspend fun recordResult(id: Long, at: Long, result: String, errorAt: Long)
}

/** Articles of a subscription (see [RssArticle]). */
@Dao
interface RssArticleDao {

    /**
     * Newest first, capped so a huge feed cannot blow up the list memory.
     *
     * The `content` column (a cached article body, often tens of KB) is left
     * out on purpose: the list only needs the metadata, and [RssSync.fetchContent]
     * reads the stored body from the database when an article is opened.
     */
    @Query(
        "SELECT sourceId, guid, title, link, description, '' AS content, " +
            "imageUrl, requestHeaders, sort, publishedAt, fetchedAt, isRead " +
            "FROM rss_articles WHERE sourceId = :sourceId " +
            "ORDER BY publishedAt DESC, fetchedAt DESC LIMIT :limit"
    )
    fun observeBySource(sourceId: Long, limit: Int = LIST_LIMIT): Flow<List<RssArticle>>

    /** Articles of one 阅读 category (`sort`), newest first. */
    @Query(
        "SELECT sourceId, guid, title, link, description, '' AS content, " +
            "imageUrl, requestHeaders, sort, publishedAt, fetchedAt, isRead " +
            "FROM rss_articles WHERE sourceId = :sourceId AND sort = :sort " +
            "ORDER BY publishedAt DESC, fetchedAt DESC LIMIT :limit"
    )
    fun observeBySourceSort(
        sourceId: Long,
        sort: String,
        limit: Int = LIST_LIMIT,
    ): Flow<List<RssArticle>>

    /** Oldest `fetchedAt` of a source: "load more" appends below this. */
    @Query("SELECT MIN(fetchedAt) FROM rss_articles WHERE sourceId = :sourceId")
    suspend fun minFetchedAt(sourceId: Long): Long?

    /** The cached body of one article (the list query does not carry it). */
    @Query("SELECT content FROM rss_articles WHERE sourceId = :sourceId AND guid = :guid")
    suspend fun getContent(sourceId: Long, guid: String): String?

    @Query("SELECT COUNT(*) FROM rss_articles WHERE sourceId = :sourceId")
    fun observeCount(sourceId: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM rss_articles WHERE sourceId = :sourceId")
    suspend fun count(sourceId: Long): Int

    @Query("SELECT COUNT(*) FROM rss_articles WHERE sourceId = :sourceId AND sort = :sort")
    suspend fun countOfSort(sourceId: Long, sort: String): Int

    @Query("SELECT * FROM rss_articles WHERE sourceId = :sourceId ORDER BY publishedAt DESC")
    suspend fun getBySource(sourceId: Long): List<RssArticle>

    /** Light-weight metadata queries: never pull the (large) `content` column. */
    @Query("SELECT guid FROM rss_articles WHERE sourceId = :sourceId")
    suspend fun getGuids(sourceId: Long): List<String>

    @Query("SELECT guid FROM rss_articles WHERE sourceId = :sourceId AND isRead = 1")
    suspend fun getReadGuids(sourceId: Long): List<String>

    /** Cached bodies for the guids being merged (keeps them off the bulk path). */
    @Query(
        "SELECT guid, content FROM rss_articles " +
            "WHERE sourceId = :sourceId AND guid IN (:guids)"
    )
    suspend fun getContents(sourceId: Long, guids: List<String>): List<GuidContent>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAll(articles: List<RssArticle>)

    /**
     * "Load more" must NOT touch rows the user already sees: a REPLACE would
     * re-stamp `fetchedAt` and move the article inside the list (that is what
     * made paging jump to the freshly fetched page).
     */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertNew(articles: List<RssArticle>)

    /** Keep the newest [keep] articles of a source, delete the rest. */
    @Query(
        "DELETE FROM rss_articles WHERE sourceId = :sourceId AND guid NOT IN (" +
            "SELECT guid FROM rss_articles WHERE sourceId = :sourceId " +
            "ORDER BY publishedAt DESC, fetchedAt DESC LIMIT :keep)"
    )
    suspend fun prune(sourceId: Long, keep: Int)

    @Query("UPDATE rss_articles SET isRead = 1 WHERE sourceId = :sourceId AND guid = :guid")
    suspend fun markRead(sourceId: Long, guid: String)

    /** Cache the article body fetched through the source's ruleContent. */
    @Query("UPDATE rss_articles SET content = :content WHERE sourceId = :sourceId AND guid = :guid")
    suspend fun updateContent(sourceId: Long, guid: String, content: String)

    @Query("UPDATE rss_articles SET isRead = 0 WHERE sourceId = :sourceId")
    suspend fun markAllUnread(sourceId: Long)

    /** Drop one source's cached list (the reader keeps no article cache). */
    @Query("DELETE FROM rss_articles WHERE sourceId = :sourceId")
    suspend fun deleteBySource(sourceId: Long)

    /** Drop every cached article (used once per app start). */
    @Query("DELETE FROM rss_articles")
    suspend fun deleteAll()
}
