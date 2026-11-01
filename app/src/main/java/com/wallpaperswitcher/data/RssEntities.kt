package com.wallpaperswitcher.data

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * 阅读订阅源: an RSS / Atom feed, primarily imported from a Legado (阅读)
 * subscription-source JSON so an existing Legado setup can be reused as-is.
 *
 * [rawJson] keeps the original Legado object: the extra rule fields
 * (`ruleArticles`, `ruleImage`, `header`, …) are not interpreted yet, but the
 * source stays a faithful copy that can be re-exported or upgraded later.
 */
@Entity(tableName = "rss_sources")
data class RssSource(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String = "",
    /** The feed URL (`sourceUrl` in Legado). */
    val url: String = "",
    /** Legado `type`: 0 = RSS, 1 = Atom, 2 = JSON; anything else is rule-based. */
    val type: Int = 0,
    /**
     * 阅读 `articleStyle`: 0 = 默认列表、1 = 列表、2 = 两列网格、3 = 瀑布流、
     * 4 = 三列网格。源怎么排版，列表就怎么排（见 SubscriptionScreen）。
     */
    val articleStyle: Int = 0,
    val enabled: Boolean = true,
    /** The original Legado JSON object (empty for a manually added feed). */
    val rawJson: String = "",
    val lastFetchAt: Long = 0L,
    /** Language-neutral result code (see OnlineSourceRules.ResultInfo). */
    val lastResult: String = "",
    val lastErrorAt: Long = 0L,
    val createdAt: Long = System.currentTimeMillis(),
)

/**
 * One feed item. `(sourceId, guid)` is the identity: a re-fetch updates the
 * existing row instead of duplicating it. [isRead] survives a refresh.
 */
@Entity(
    tableName = "rss_articles",
    primaryKeys = ["sourceId", "guid"],
    foreignKeys = [ForeignKey(
        entity = RssSource::class,
        parentColumns = ["id"],
        childColumns = ["sourceId"],
        onDelete = ForeignKey.CASCADE
    )],
    indices = [Index("sourceId")]
)
data class RssArticle(
    val sourceId: Long,
    val guid: String,
    val title: String = "",
    val link: String = "",
    val description: String = "",
    /** Full article body when the feed provides one (may contain HTML). */
    val content: String = "",
    /** First usable image of the item, for the list thumbnail / 设为壁纸. */
    val imageUrl: String = "",
    /**
     * 阅读 `URL,{…}` 请求选项里的请求头（JSON 对象，空串 = 没有）。
     * 文章页在 WebView 里打开、封面用 Coil 抓取时按它补齐 Referer 之类。
     */
    val requestHeaders: String = "",
    /** 阅读 category (`sortUrl` entry) this article was fetched from. */
    val sort: String = "",
    val publishedAt: Long = 0L,
    val fetchedAt: Long = System.currentTimeMillis(),
    val isRead: Boolean = false,
)

/** Projection: a cached article body (see RssArticleDao.getContents). */
data class GuidContent(val guid: String = "", val content: String = "")

