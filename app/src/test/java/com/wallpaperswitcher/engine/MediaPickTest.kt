package com.wallpaperswitcher.engine

import com.wallpaperswitcher.data.WallpaperImage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Media selection shared by the live engine and the static applier: both paths
 * must pick from exactly the same set (enabled groups, per-screen 应用位置, and
 * "no motion media on the lock screen").
 */
class MediaPickTest {

    private val home = WallpaperTarget.SLOT_HOME
    private val lock = WallpaperTarget.SLOT_LOCK

    private fun image(
        id: Long,
        type: String = MediaTypes.IMAGE,
        group: Long = 1L
    ) = WallpaperImage(
        id = id,
        groupId = group,
        uri = "content://media/$id",
        mediaType = type
    )

    @Test
    fun randomReturnsNullWhenNothingTargetsTheSlot() = runBlocking {
        val dao = FakeWallpaperImageDao(
            mutableListOf(image(1L, group = 7L)),
            groupTargets = mapOf(7L to "LOCK")
        )
        assertNull(MediaPick.random(dao, home, 0L))
        // ...while the lock slot does see it.
        assertEquals(1L, MediaPick.random(dao, lock, 0L)?.id ?: -1L)
    }

    @Test
    fun randomWithASingleItemReturnsThatItem() = runBlocking {
        val dao = FakeWallpaperImageDao(mutableListOf(image(5L)))
        // The slot's only media is returned even when it is the one on screen:
        // there is nothing else to show.
        assertEquals(5L, MediaPick.random(dao, home, lastId = 5L)?.id ?: -1L)
    }

    @Test
    fun randomNeverReturnsTheCurrentlyShownMedia() = runBlocking {
        val dao = FakeWallpaperImageDao((1L..6L).map { image(it) }.toMutableList())
        repeat(200) {
            val picked = MediaPick.random(dao, home, lastId = 3L)
            assertNotNull(picked)
            assertNotEquals(3L, picked?.id)
        }
    }

    @Test
    fun randomOffsetLandsOnTheOnlyRemainingCandidate() = runBlocking {
        // count = 2, one of them excluded: the offset must stay inside the
        // filtered set (the old range could ask for row 2 of a 1-row set, which
        // fell back to the slower ORDER BY RANDOM query).
        val dao = FakeWallpaperImageDao(mutableListOf(image(1L), image(2L)))
        repeat(50) {
            assertEquals(1L, MediaPick.random(dao, home, lastId = 2L)?.id ?: -1L)
        }
    }

    @Test
    fun lockSlotNeverOffersMotionMedia() = runBlocking {
        val dao = FakeWallpaperImageDao(
            mutableListOf(
                image(1L, MediaTypes.VIDEO),
                image(2L, MediaTypes.GIF),
                image(3L, MediaTypes.IMAGE)
            )
        )
        repeat(20) {
            assertEquals(3L, MediaPick.random(dao, lock, 0L)?.id ?: -1L)
        }
    }

    @Test
    fun randomPickIsReproducibleSoThePreviewMatchesTheSwitch() = runBlocking {
        // 下一张预览 and the switch that follows it ask the same question with
        // the same cursor; with a true RNG they got different answers (the
        // preview disagreed with the switch AND changed on every click).
        val dao = FakeWallpaperImageDao((1L..40L).map { image(it) }.toMutableList())
        repeat(20) { cursorStep ->
            val cursor = cursorStep.toLong() * 3L
            val preview = MediaPick.random(dao, home, lastId = cursor)
            val actual = MediaPick.random(dao, home, lastId = cursor)
            assertEquals(preview?.id, actual?.id)
        }
    }

    @Test
    fun randomPickMovesOnWhenTheCursorMoves() = runBlocking {
        // Two different cursors must not always resolve to the same media, or
        // every switch would show the same picture.
        val dao = FakeWallpaperImageDao((1L..60L).map { image(it) }.toMutableList())
        val picks = (0L until 60L).map { cursor -> MediaPick.random(dao, home, lastId = cursor)?.id }
        assertTrue("expected a varied sequence, got $picks", picks.toSet().size > 10)
    }

    @Test
    fun shufflePreviewRepeatsUntilTheDeckActuallyMoves() = runBlocking {
        // The preview must not consume a card: two previews in a row are the
        // same, and only after the switch records the id does the pick change.
        val dao = FakeWallpaperImageDao((1L..8L).map { image(it) }.toMutableList())
        val deck = mutableSetOf<Long>()
        val first = MediaPick.shuffleUnseen(dao, home, deck, excludeId = 0L)?.id
        val second = MediaPick.shuffleUnseen(dao, home, deck, excludeId = 0L)?.id
        assertEquals(first, second)
        assertNotNull(first)
        deck.add(first!!)
        val third = MediaPick.shuffleUnseen(dao, home, deck, excludeId = first)?.id
        assertNotNull(third)
        assertNotEquals(first, third)
    }

    @Test
    fun shuffleUnseenSkipsShownAndCurrentMedia() = runBlocking {
        val dao = FakeWallpaperImageDao((1L..5L).map { image(it) }.toMutableList())
        val picked = MediaPick.shuffleUnseen(
            imageDao = dao,
            slot = home,
            shownIds = mutableSetOf(1L, 2L, 3L),
            excludeId = 4L
        )
        assertEquals(5L, picked?.id ?: -1L)
    }

    @Test
    fun shuffleUnseenReturnsNullWhenThePassIsOver() = runBlocking {
        val dao = FakeWallpaperImageDao((1L..3L).map { image(it) }.toMutableList())
        assertNull(
            MediaPick.shuffleUnseen(dao, home, mutableSetOf(1L, 2L, 3L), excludeId = 0L)
        )
    }

    @Test
    fun shuffleUnseenHandlesPassesBeyondTheSqlVariableLimit() = runBlocking {
        // 1500 media with 1400 already shown: the `id NOT IN (:shownIds)` variant
        // threw once the list passed SQLite's bound-variable limit (999 on older
        // builds) and the whole switch failed instead of showing a wallpaper.
        val dao = FakeWallpaperImageDao((1L..1500L).map { image(it) }.toMutableList())
        val shown = (1L..1400L).toMutableSet()
        repeat(50) {
            val picked = MediaPick.shuffleUnseen(dao, home, shown, excludeId = 0L)
            assertNotNull(picked)
            assertTrue(picked!!.id in 1401L..1500L)
        }
    }

    @Test
    fun shuffleUnseenPlaysAPassThroughWithoutRepeats() = runBlocking {
        val dao = FakeWallpaperImageDao((1L..4L).map { image(it) }.toMutableList())
        val shown = mutableSetOf<Long>()
        var current = 0L
        repeat(4) {
            val picked = MediaPick.shuffleUnseen(dao, home, shown, excludeId = current)
            assertNotNull(picked)
            assertFalse(picked!!.id in shown)
            shown.add(picked.id)
            current = picked.id
        }
        assertEquals(setOf(1L, 2L, 3L, 4L), shown)
    }

    @Test
    fun shuffleNeverReturnsTheMediaOnScreenWhileAnotherIsUnseen() = runBlocking {
        // The pre-review pick filters the excluded id out of the candidates, so a
        // pass can never hand the media that is on screen straight back.
        val dao = FakeWallpaperImageDao((1L..3L).map { image(it) }.toMutableList())
        repeat(50) {
            val picked = MediaPick.shuffleUnseen(dao, home, mutableSetOf(), excludeId = 2L)
            assertNotNull(picked)
            assertNotEquals(2L, picked!!.id)
        }
    }

    @Test
    fun passContinuesWithoutRepeatsWhenMediaAreAddedMidPass() = runBlocking {
        // The user imports a folder (or enables a group) in the middle of a pass.
        // Media that were already shown must NOT come back just because the enabled
        // set grew: the next pick filters the enabled ids by the shown ones.
        val dao = FakeWallpaperImageDao((1L..4L).map { image(it) }.toMutableList())
        val shown = mutableSetOf<Long>()
        var current = 0L
        repeat(2) {
            val picked = MediaPick.shuffleUnseen(dao, home, shown, excludeId = current)!!
            shown.add(picked.id)
            current = picked.id
        }
        assertEquals(2, shown.size)
        dao.insert(image(5L))
        dao.insert(image(6L))
        val seenThisPass = shown.toMutableSet()
        repeat(4) {
            val picked = MediaPick.shuffleUnseen(dao, home, shown, excludeId = current)
                ?: return@repeat
            assertFalse(
                "media shown before the set changed must not repeat: ${picked.id}",
                picked.id in seenThisPass
            )
            seenThisPass.add(picked.id)
            shown.add(picked.id)
            current = picked.id
        }
        assertEquals("the whole pass must be played", 6, seenThisPass.size)
    }

    @Test
    fun shownIdsOfMediaThatIsNoLongerEnabledArePruned() = runBlocking {
        // A pass ran over 1..4; then media 2..4 disappear and 5 appears, so only 1
        // and 5 are enabled. The stale shown ids used to count towards "already
        // shown", which made the pass look finished early (shown 4 >= total 2) and
        // repeated media 1 right away.
        val dao = FakeWallpaperImageDao((1L..4L).map { image(it) }.toMutableList())
        val shown = mutableSetOf<Long>()
        var current = 0L
        repeat(3) {
            val picked = MediaPick.shuffleUnseen(dao, home, shown, excludeId = current)!!
            shown.add(picked.id)
            current = picked.id
        }
        dao.deleteByIds(listOf(2L, 3L, 4L))
        dao.insert(image(5L))
        val picked = MediaPick.shuffleUnseen(dao, home, shown, excludeId = current)
        assertNotNull(picked)
        assertTrue("only enabled media may be dealt", picked!!.id == 1L || picked.id == 5L)
        assertTrue(
            "shown ids of deleted/disabled media must be pruned, was $shown",
            shown.none { it in listOf(2L, 3L, 4L) }
        )
    }

    /**
     * The SHUFFLE deck filters the slot's whole id list in memory, so the list is
     * cached (see MediaPick.enabledIdsFor): on an 18k-media library re-reading it
     * on every switch is a full id scan plus an 18k allocation for nothing.
     */
    @Test
    fun shuffleReusesTheIdListUntilSomethingInvalidatesIt() = runBlocking {
        MediaPick.invalidateEnabledIds()
        val dao = FakeWallpaperImageDao((1L..5L).map { image(it) }.toMutableList())
        val shown = mutableSetOf<Long>()

        // First pick reads the list, the second one is served from the cache.
        MediaPick.shuffleUnseen(dao, home, shown, 0L, generation = 7L, knownCount = 5)
        val afterFirst = dao.weightQueries
        MediaPick.shuffleUnseen(dao, home, shown, 0L, generation = 7L, knownCount = 5)
        assertEquals("an unchanged set must not re-read the id list", afterFirst, dao.weightQueries)

        // A new media-store generation invalidates it (a scan import).
        MediaPick.shuffleUnseen(dao, home, shown, 0L, generation = 8L, knownCount = 5)
        assertEquals(afterFirst + 1, dao.weightQueries)

        // So does an explicit invalidation (group toggled, media deleted).
        MediaPick.shuffleUnseen(dao, home, shown, 0L, generation = 8L, knownCount = 5)
        MediaPick.invalidateEnabledIds()
        MediaPick.shuffleUnseen(dao, home, shown, 0L, generation = 8L, knownCount = 5)
        assertEquals(afterFirst + 2, dao.weightQueries)

        // ...and so does the count changing without any of the above: that is the
        // auto-scan worker inserting media, which never pokes the service.
        dao.insert(image(6L))
        MediaPick.shuffleUnseen(dao, home, shown, 0L, generation = 8L, knownCount = 6)
        assertEquals(afterFirst + 3, dao.weightQueries)
    }
}
