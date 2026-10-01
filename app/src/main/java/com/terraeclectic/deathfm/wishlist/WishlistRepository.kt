package com.terraeclectic.deathfm.wishlist

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer

/** A radio track the user liked - identified by text, since a live stream has no catalog ids. */
data class WishlistEntry(
    val artist: String,
    val title: String,
    val album: String = "",
    val coverUrl: String? = null,
    val source: String = "deathfm",
) {
    /**
     * Local identity for "is this already liked": the same folding the server's
     * `normalizeWishlistKey` does (case, accents, punctuation, "&" = "and"), so
     * a station that spells a title two ways doesn't get two hearts.
     */
    val key: String get() = "${fold(artist)}|${fold(title)}"

    /** False for the "Death.FM / Death.FM" placeholder shown before the first real track, and for blanks. */
    val isRealTrack: Boolean get() = artist.isNotBlank() && title.isNotBlank() && key != PLACEHOLDER_KEY

    private companion object {
        const val PLACEHOLDER_KEY = "death fm|death fm"
        private val MARKS = Regex("\\p{M}+")
        private val NON_ALNUM = Regex("[^\\p{L}\\p{N}]+")

        fun fold(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD)
                .replace(MARKS, "")
                .lowercase()
                .replace("&", " and ")
                .replace(NON_ALNUM, " ")
                .trim()
    }
}

/** Something still to tell the server: add ([liked] = true) or remove the entry. */
data class PendingOp(val entry: WishlistEntry, val liked: Boolean, val at: Long)

/** Where the liked set and the not-yet-delivered ops survive restarts. */
interface WishlistStorage {
    fun read(): String?
    fun write(text: String)
}

/** Delivers one op. Returns true if it's done with (delivered, or permanently rejected); false to keep it and retry later. */
interface WishlistSender {
    suspend fun send(op: PendingOp): Boolean
}

/**
 * The liked radio tracks, on this device, plus the queue of changes still to
 * send to the wishlist server. A like takes effect immediately (the heart fills, on
 * the phone and in the car) and is delivered whenever the network allows: a
 * failed send just leaves it queued for the next [toggle] or [flush] - nothing
 * is lost in a tunnel. Only the latest intent per track is kept, so
 * like-then-unlike while offline sends a single "remove", not two calls.
 */
class WishlistRepository(
    private val storage: WishlistStorage,
    private val sender: WishlistSender,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lock = Mutex()
    private val _liked = MutableStateFlow<Set<String>>(emptySet())
    private val pending = LinkedHashMap<String, PendingOp>()
    // The liked tracks themselves (oldest first), for the Liked list - _liked is only their folded keys.
    private val entries = LinkedHashMap<String, WishlistEntry>()

    /** Keys ([WishlistEntry.key]) of the liked tracks. */
    val liked: StateFlow<Set<String>> = _liked.asStateFlow()

    val pendingCount: Int get() = pending.size

    init {
        load()
    }

    fun isLiked(entry: WishlistEntry): Boolean = entry.key in _liked.value

    /** The liked tracks, most recently liked first. */
    fun likedEntries(): List<WishlistEntry> = entries.values.toList().asReversed()

    /** Flips [entry]'s liked state, persists it, tries to deliver, and returns the new state. */
    suspend fun toggle(entry: WishlistEntry): Boolean = lock.withLock {
        val nowLiked = entry.key !in _liked.value
        _liked.value = if (nowLiked) _liked.value + entry.key else _liked.value - entry.key
        entries.remove(entry.key)
        if (nowLiked) entries[entry.key] = entry
        pending.remove(entry.key) // the latest intent wins, and goes to the back of the queue
        pending[entry.key] = PendingOp(entry, nowLiked, clock())
        save()
        flushLocked()
        nowLiked
    }

    /** Retries everything still queued (app start, connectivity back). */
    suspend fun flush() = lock.withLock { flushLocked() }

    // Oldest first, stopping at the first failure: if the network is down there is no point trying the rest.
    private suspend fun flushLocked() {
        for (op in pending.values.toList()) {
            if (!sender.send(op)) return
            pending.remove(op.entry.key)
            save()
        }
    }

    private fun save() {
        val json = JSONObject()
            .put("liked", JSONArray(_liked.value.toList()))
            .put("entries", JSONArray(entries.values.map { entryJson(it) }))
            .put(
                "pending",
                JSONArray(
                    pending.values.map { op ->
                        JSONObject()
                            .put("artist", op.entry.artist)
                            .put("title", op.entry.title)
                            .put("album", op.entry.album)
                            .put("coverUrl", op.entry.coverUrl ?: JSONObject.NULL)
                            .put("source", op.entry.source)
                            .put("liked", op.liked)
                            .put("at", op.at)
                    },
                ),
            )
        storage.write(json.toString())
    }

    private fun entryJson(entry: WishlistEntry) = JSONObject()
        .put("artist", entry.artist)
        .put("title", entry.title)
        .put("album", entry.album)
        .put("coverUrl", entry.coverUrl ?: JSONObject.NULL)
        .put("source", entry.source)

    private fun entryFrom(o: JSONObject) = WishlistEntry(
        artist = o.getString("artist"),
        title = o.getString("title"),
        album = o.optString("album", ""),
        coverUrl = if (o.isNull("coverUrl")) null else o.getString("coverUrl"),
        source = o.optString("source", "deathfm"),
    )

    private fun load() {
        val text = storage.read() ?: return
        try {
            val json = JSONObject(text)
            val likedKeys = json.optJSONArray("liked")
            _liked.value = (0 until (likedKeys?.length() ?: 0)).map { likedKeys!!.getString(it) }.toSet()
            val saved = json.optJSONArray("entries")
            for (i in 0 until (saved?.length() ?: 0)) {
                val entry = entryFrom(saved!!.getJSONObject(i))
                if (entry.key in _liked.value) entries[entry.key] = entry
            }
            // Likes from before entries were stored: all that survives is the folded key ("artist|title").
            for (key in _liked.value - entries.keys) {
                val (artist, title) = key.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
                entries[key] = WishlistEntry(artist, title)
            }
            val ops = json.optJSONArray("pending")
            for (i in 0 until (ops?.length() ?: 0)) {
                val o = ops!!.getJSONObject(i)
                val entry = WishlistEntry(
                    artist = o.getString("artist"),
                    title = o.getString("title"),
                    album = o.optString("album", ""),
                    coverUrl = if (o.isNull("coverUrl")) null else o.getString("coverUrl"),
                    source = o.optString("source", "deathfm"),
                )
                pending[entry.key] = PendingOp(entry, o.getBoolean("liked"), o.getLong("at"))
            }
        } catch (e: Exception) {
            // A corrupt file shouldn't take the player down - start empty.
            _liked.value = emptySet()
            entries.clear()
            pending.clear()
        }
    }
}
