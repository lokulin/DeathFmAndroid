package com.terraeclectic.deathfm.queueplayed

import com.terraeclectic.deathfm.playback.Station
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.jsoup.Jsoup

/** One fetch's worth of both lists - always fetched/returned together since they come from the same endpoint. */
data class QueuePlayedSnapshot(
    val queue: List<QueueEntry>,
    val played: List<QueueEntry>,
)

/**
 * Fetches the player page's own "get_db_info" endpoint - the same one that
 * backs its Queue/Played tabs - and parses the HTML fragments it returns
 * into [QueueEntry] lists.
 *
 * Deliberately NOT polled continuously like [com.terraeclectic.deathfm.nowplaying.NowPlayingRepository]:
 * this is supplementary, browse-on-demand information, not something that
 * needs to be kept warm in the background while nobody's looking at it -
 * callers fetch it only while the Queue/Played screen is actually open.
 */
class QueuePlayedRepository(
    private val station: Station,
    private val httpClient: OkHttpClient = OkHttpClient(),
) {
    /** @param asin the currently playing track's Amazon ASIN (from [com.terraeclectic.deathfm.nowplaying.NowPlayingMetadata.asin]) - this endpoint is keyed by "what's playing right now." */
    suspend fun fetch(asin: String): QueuePlayedSnapshot = withContext(Dispatchers.IO) {
        val url = "https://death.fm/player.php?ajax_action=get_db_info&station=${station.id}&asin=$asin"
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return@withContext QueuePlayedSnapshot(emptyList(), emptyList())
            val body = response.body?.string() ?: return@withContext QueuePlayedSnapshot(emptyList(), emptyList())
            val obj = JSONObject(body)
            QueuePlayedSnapshot(
                queue = parseRows(obj.optString("queue_html")),
                played = parseRows(obj.optString("played_html")),
            )
        }
    }

    // queue_html/played_html are a bare sequence of <tr> elements (no
    // enclosing <table>/<tbody>) - wrapping them before parsing avoids
    // relying on Jsoup's implicit table-repair behavior for stray <tr>s,
    // which isn't guaranteed to produce the same DOM shape.
    //
    // Row shape (both lists):
    //   <tr>
    //     <td>&#8595; 1</td>                          <- rank (arrow is just up/down styling, not data)
    //     <td><img src='/images/cover/040/....jpg'></td>
    //     <td><strong>Artist</strong><br><span>Album or track title</span></td>
    //   </tr>
    private fun parseRows(html: String): List<QueueEntry> {
        if (html.isBlank()) return emptyList()
        val doc = Jsoup.parse("<table><tbody>$html</tbody></table>")
        return doc.select("tr").mapNotNull { row ->
            val cells = row.select("td")
            if (cells.size < 3) return@mapNotNull null
            val rank = cells[0].text().filter { it.isDigit() }.toIntOrNull() ?: return@mapNotNull null
            val thumbSrc = cells[1].select("img").attr("src").ifBlank { null }
            val thumbnailUrl = thumbSrc?.let { if (it.startsWith("http")) it else "https://death.fm$it" }
            val artist = cells[2].select("strong").text()
            val albumOrTrack = cells[2].select("span").text()
            QueueEntry(rank, thumbnailUrl, artist, albumOrTrack)
        }
    }
}
