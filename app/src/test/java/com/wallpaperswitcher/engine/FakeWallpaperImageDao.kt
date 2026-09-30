package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.GroupMediaCount
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

    override suspend fun getScannedFolderPaths(): List<ScannedFolderPath> =
        images.filter { it.isFromFolder && it.folderPath.isNotEmpty() }
            .map { ScannedFolderPath(it.groupId, it.folderPath) }
            .distinct()

    override suspend fun getImagesByGroupSync(groupId: Long): List<WallpaperImage> =
        images.filter { it.groupId == groupId }

    override suspend fun getImageById(id: Long): WallpaperImage? =
        images.firstOrNull { it.id == id }

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

    override suspend fun getEnabledIds(slot: String): List<Long> = forSlot(slot).map { it.id }

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
}
