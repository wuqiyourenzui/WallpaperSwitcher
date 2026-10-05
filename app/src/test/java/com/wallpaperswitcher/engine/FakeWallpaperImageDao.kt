package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.GroupMediaCount
import com.wallpaperswitcher.data.MediaWeight
import com.wallpaperswitcher.data.ScannedFolderPath
import com.wallpaperswitcher.data.WallpaperImage
import com.wallpaperswitcher.data.WallpaperImageDao
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf

/**
 * In-memory [WallpaperImageDao] for unit tests.
 *
 * It reproduces exactly the slot filtering of the real queries - enabled
 * groups, the group's 应用位置 (HOME / LOCK / BOTH) and "the lock screen only
 * takes still images" - so the picking logic can be tested without a Room
 * database.
 *
 * @param groupTargets groupId -> stored target value ("HOME" | "LOCK" | "BOTH");
 *   a group that is missing from the map behaves like "BOTH".
 */
class FakeWallpaperImageDao(
    private val images: MutableList<WallpaperImage> = mutableListOf(),
    private val groupTargets: Map<Long, String> = emptyMap(),
    private val disabledGroups: Set<Long> = emptySet()
) : WallpaperImageDao {

    /**
     * How many times [getEnabledIds] was served - the SHUFFLE deck's id-list
     * cache (see MediaPick) is asserted through this counter.
     */
    var enabledIdsQueries: Int = 0
        private set

    /**
     * How many times [weightsForSlot] was served - the picker's id+weight list
     * cache (see MediaPick) is asserted through this counter.
     */
    var weightQueries: Int = 0
        private set

    /** Mirror of the DAO's slot filter, in the same ascending-id order. */
    private fun forSlot(slot: String): List<WallpaperImage> = images
        .asSequence()
        .filter { it.groupId !in disabledGroups }
        .filter { image ->
            val target = groupTargets[image.groupId]
            target == null || target == "BOTH" || target == slot
        }
        .filter { slot != WallpaperTarget.SLOT_LOCK || it.mediaType == MediaTypes.IMAGE }
        .sortedBy { it.id }
        .toList()

    override suspend fun getImageCountByGroup(groupId: Long): Int =
        images.count { it.groupId == groupId }

    override suspend fun getImageIdsByGroup(groupId: Long): List<Long> =
        images.filter { it.groupId == groupId }.map { it.id }

    override suspend fun getUrisByGroup(groupId: Long): List<String> =
        images.filter { it.groupId == groupId }.map { it.uri }

    override suspend fun getUrisLike(pattern: String): List<String> {
        val prefix = pattern.removeSuffix("%")
        return images.map { it.uri }.filter { it.startsWith(prefix) }
    }

    override suspend fun getUrisByIds(ids: List<Long>): List<String> =
        images.filter { it.id in ids }.map { it.uri }

    override suspend fun getIdsByFolder(folderPath: String): List<Long> =
        images.filter { it.folderPath == folderPath }.map { it.id }

    override suspend fun getScannedFolderPaths(): List<ScannedFolderPath> =
        images.filter { it.isFromFolder && it.folderPath.isNotEmpty() }
            .map { ScannedFolderPath(it.groupId, it.folderPath) }
            .distinct()

    override suspend fun getImagesByGroupSync(groupId: Long): List<WallpaperImage> =
        images.filter { it.groupId == groupId }

    /** Mirrors the paginated query of the group grid (see engine.MediaWindow). */
    override suspend fun getImagesByGroupPage(
        groupId: Long,
        limit: Int,
        offset: Int,
    ): List<WallpaperImage> = images
        .filter { it.groupId == groupId }
        .sortedWith(compareByDescending<WallpaperImage> { it.addedAt }.thenByDescending { it.id })
        .drop(offset.coerceAtLeast(0))
        .take(limit.coerceAtLeast(0))

    override suspend fun getAllImagesSync(): List<WallpaperImage> = images.toList()

    override suspend fun getImageById(id: Long): WallpaperImage? =
        images.firstOrNull { it.id == id }

    override suspend fun setFavorite(id: Long, favorite: Boolean) {
        val index = images.indexOfFirst { it.id == id }
        if (index >= 0) images[index] = images[index].copy(isFavorite = favorite)
    }

    /**
     * 按 uri 批量改收藏（真实 DAO 的 `WHERE uri = :uri`）：同一个文件在多个分组里
     * 各有一行是预期情况，界面按 uri 判断星标，所以这里所有同 uri 的行一起改。
     */
    override suspend fun setFavoriteByUri(uri: String, favorite: Boolean) {
        for (i in images.indices) {
            if (images[i].uri == uri) images[i] = images[i].copy(isFavorite = favorite)
        }
    }

    override suspend fun weightsForSlot(slot: String, favoriteWeight: Int): List<MediaWeight> =
        forSlot(slot).map {
            MediaWeight(it.id, if (it.isFavorite) favoriteWeight else 1)
        }.also { weightQueries++ }

    override suspend fun getFirstFromEnabledGroups(slot: String): WallpaperImage? =
        forSlot(slot).firstOrNull()


    override suspend fun getSequentialImageFromEnabledGroupsAfter(
        slot: String,
        lastId: Long
    ): WallpaperImage? = forSlot(slot).firstOrNull { it.id > lastId }

    override suspend fun insert(image: WallpaperImage): Long {
        images.add(image)
        return image.id
    }

    override suspend fun insertAll(images: List<WallpaperImage>) {
        this.images.addAll(images)
    }

    override suspend fun delete(image: WallpaperImage) {
        images.removeAll { it.id == image.id }
    }

    override suspend fun updateMediaType(id: Long, mediaType: String) {
        val index = images.indexOfFirst { it.id == id }
        if (index >= 0) images[index] = images[index].copy(mediaType = mediaType)
    }

    override suspend fun updateMediaMeta(
        uri: String,
        width: Int,
        height: Int,
        rotationDegrees: Int
    ) {
        for (index in images.indices) {
            if (images[index].uri == uri) {
                images[index] = images[index].copy(
                    width = width,
                    height = height,
                    rotationDegrees = rotationDegrees
                )
            }
        }
    }

    override suspend fun deleteByIds(ids: List<Long>) {
        images.removeAll { it.id in ids }
    }

    override suspend fun getRandomImageFromEnabledGroups(slot: String): WallpaperImage? =
        forSlot(slot).randomOrNull()

    override suspend fun getRandomImageFromEnabledGroupsExcluding(
        slot: String,
        excludeId: Long
    ): WallpaperImage? = forSlot(slot).filter { it.id != excludeId }.randomOrNull()

    override suspend fun getEnabledIds(slot: String): List<Long> {
        enabledIdsQueries++
        return forSlot(slot).map { it.id }
    }

    override suspend fun getRandomImageFromEnabledGroupsExcludingAt(
        slot: String,
        excludeId: Long,
        offset: Int
    ): WallpaperImage? = forSlot(slot).filter { it.id != excludeId }.getOrNull(offset)

    override suspend fun countByEnabledGroups(slot: String): Int = forSlot(slot).size

    override fun getMediaCounts(): Flow<List<GroupMediaCount>> = flowOf(
        images.groupBy { it.groupId }.map { (groupId, media) ->
            GroupMediaCount(groupId, media.size)
        }
    )

    /** Rows that need READ_MEDIA_* (the home screen's permission hint). */
    override fun getMediaStoreRowCount(): Flow<Int> =
        flowOf(images.count { it.uri.startsWith("content://media/") })

    /**
     * 收藏聚合（跨分组，按 uri 去重）。fake 里就按同样的语义实时算：
     * 收藏页渲染靠它，`setFavorite` 改完后再 collect 就能看到新值。
     */
    override fun observeFavorites(): Flow<List<WallpaperImage>> = flowOf(
        images.filter { it.isFavorite }
            .distinctBy { it.uri }
            .sortedWith(compareByDescending<WallpaperImage> { it.addedAt }.thenByDescending { it.id })
    )
}
