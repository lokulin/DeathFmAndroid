package com.terraeclectic.deathfm.wishlist

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WishlistRepositoryTest {
    private class MemoryStorage(var text: String? = null) : WishlistStorage {
        override fun read() = text
        override fun write(text: String) {
            this.text = text
        }
    }

    /** Records what was sent; [online] = false makes every send fail (and be kept for retry). */
    private class FakeSender(var online: Boolean = true) : WishlistSender {
        val sent = mutableListOf<PendingOp>()
        override suspend fun send(op: PendingOp): Boolean {
            if (!online) return false
            sent += op
            return true
        }
    }

    private val abnormality = WishlistEntry("Abnormality", "Monarch Alpha", "Sociopathic Constructs")
    private val pantera = WishlistEntry("Pantera", "Walk")

    private fun repo(storage: WishlistStorage = MemoryStorage(), sender: WishlistSender = FakeSender(), clock: () -> Long = { 1000L }) =
        WishlistRepository(storage, sender, clock)

    @Test fun likingSendsAnAddAndFillsTheHeart() = runBlocking {
        val sender = FakeSender()
        val r = repo(sender = sender)
        assertTrue(r.toggle(abnormality))
        assertTrue(r.isLiked(abnormality))
        assertEquals(listOf(true), sender.sent.map { it.liked })
        assertEquals(0, r.pendingCount)
    }

    @Test fun togglingAgainUnlikesAndSendsARemove() = runBlocking {
        val sender = FakeSender()
        val r = repo(sender = sender)
        r.toggle(abnormality)
        assertFalse(r.toggle(abnormality))
        assertFalse(r.isLiked(abnormality))
        assertEquals(listOf(true, false), sender.sent.map { it.liked })
    }

    @Test fun offlineLikesStayLikedAndQueued() = runBlocking {
        val sender = FakeSender(online = false)
        val r = repo(sender = sender)
        r.toggle(abnormality)
        r.toggle(pantera)
        assertTrue(r.isLiked(abnormality) && r.isLiked(pantera))
        assertEquals(2, r.pendingCount)
        assertTrue(sender.sent.isEmpty())
    }

    @Test fun flushDeliversTheQueueOldestFirstOnceBackOnline() = runBlocking {
        val sender = FakeSender(online = false)
        val r = repo(sender = sender)
        r.toggle(abnormality)
        r.toggle(pantera)
        sender.online = true
        r.flush()
        assertEquals(listOf("Abnormality", "Pantera"), sender.sent.map { it.entry.artist })
        assertEquals(0, r.pendingCount)
    }

    @Test fun likeThenUnlikeWhileOfflineSendsOnlyTheRemove() = runBlocking {
        val sender = FakeSender(online = false)
        val r = repo(sender = sender)
        r.toggle(abnormality)
        r.toggle(abnormality)
        assertEquals(1, r.pendingCount)
        sender.online = true
        r.flush()
        assertEquals(listOf(false), sender.sent.map { it.liked })
    }

    @Test fun aFailureStopsTheFlushSoNothingIsSentOutOfOrder() = runBlocking {
        val sender = FakeSender(online = false)
        val r = repo(sender = sender)
        r.toggle(abnormality)
        r.toggle(pantera)
        r.flush() // still offline
        assertEquals(2, r.pendingCount)
        assertTrue(sender.sent.isEmpty())
    }

    @Test fun stateSurvivesARestart() = runBlocking {
        val storage = MemoryStorage()
        val offline = FakeSender(online = false)
        val first = repo(storage = storage, sender = offline, clock = { 42L })
        first.toggle(abnormality)

        val online = FakeSender()
        val second = repo(storage = storage, sender = online)
        assertTrue(second.isLiked(abnormality))
        assertEquals(1, second.pendingCount)
        second.flush()
        assertEquals("Monarch Alpha", online.sent.single().entry.title)
        assertEquals("Sociopathic Constructs", online.sent.single().entry.album)
        assertEquals(42L, online.sent.single().at) // the time it was liked, not the time it was delivered
    }

    @Test fun aCorruptFileStartsEmptyInsteadOfCrashing() {
        val r = repo(storage = MemoryStorage("{ not json"))
        assertEquals(0, r.pendingCount)
        assertTrue(r.liked.value.isEmpty())
    }

    @Test fun spellingVariantsAreTheSameTrack() {
        assertEquals(WishlistEntry("Simon & Garfunkel", "Cecilia").key, WishlistEntry("simon AND garfunkel", "Cecilia!").key)
        assertEquals(WishlistEntry("Motörhead", "Ace of Spades").key, WishlistEntry("Motorhead", "ace of spades").key)
    }

    @Test fun differentTracksAreDifferent() {
        assertFalse(WishlistEntry("Pantera", "Walk").key == WishlistEntry("Pantera", "Domination").key)
    }

    @Test fun thePlaceholderAndBlanksAreNotRealTracks() {
        assertFalse(WishlistEntry("Death.FM", "Death.FM").isRealTrack)
        assertFalse(WishlistEntry("", "Walk").isRealTrack)
        assertFalse(WishlistEntry("Pantera", "  ").isRealTrack)
        assertTrue(pantera.isRealTrack)
    }

    @Test fun likedFlowReflectsToggles() = runBlocking {
        val r = repo()
        r.toggle(pantera)
        assertEquals(setOf(pantera.key), r.liked.value)
        r.toggle(pantera)
        assertTrue(r.liked.value.isEmpty())
    }

    @Test fun likedEntriesAreNewestFirstSurviveRestartAndDropOnUnlike() = runBlocking {
        val storage = MemoryStorage()
        val r = repo(storage = storage)
        r.toggle(abnormality)
        r.toggle(pantera)
        assertEquals(listOf(pantera, abnormality), r.likedEntries())
        assertEquals(listOf(pantera, abnormality), repo(storage = storage).likedEntries())
        r.toggle(pantera)
        assertEquals(listOf(abnormality), r.likedEntries())
    }
}
