package dev.krts.lyricvibes.lyrics

import com.google.gson.JsonArray
import com.google.gson.JsonElement
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.krts.lyricvibes.LyricVibes
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import kotlin.math.abs

data class RawLyrics(val synced: String?, val plain: String?, val source: String, val instrumental: Boolean = false)

interface LyricsProvider {
    val id: String
    fun fetch(q: TrackQuery): RawLyrics?
}

object LrcLibProvider : LyricsProvider {
    override val id = "LRCLIB"
    private const val BASE = "https://lrclib.net/api"
    private val http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(6)).followRedirects(HttpClient.Redirect.NORMAL).build()

    override fun fetch(q: TrackQuery): RawLyrics? {
        if (q.title.isBlank()) return null
        if (q.artist.isNotBlank()) {
            val params = buildMap {
                put("track_name", q.title)
                put("artist_name", q.artist)
                if (q.album.isNotBlank()) put("album_name", q.album)
                q.durationSec?.let { put("duration", it.toString()) }
            }
            get("/get", params)?.asJsonObjectOrNull()?.let { toRaw(it) }?.let { if (it.synced != null || it.instrumental) return it }
        }
        val searches = listOfNotNull(
            if (q.artist.isNotBlank()) mapOf("track_name" to q.bareTitle, "artist_name" to q.artist) else null,
            mapOf("q" to "${q.artist} ${q.bareTitle}".trim()),
            if (q.loose) mapOf("q" to q.bareTitle) else null,
        )
        var plainFallback: RawLyrics? = null
        for (params in searches) {
            val arr = get("/search", params) as? JsonArray ?: continue
            val best = arr.mapNotNull { it.asJsonObjectOrNull() }
                .map { it to score(it, q) }
                .filter { it.second > 0 }
                .maxByOrNull { it.second }?.first ?: continue
            val raw = toRaw(best)
            if (raw.synced != null) return raw
            if (plainFallback == null && raw.plain != null) plainFallback = raw
        }
        return plainFallback
    }

    private fun score(o: JsonObject, q: TrackQuery): Int {
        var s = 1
        val synced = o.get("syncedLyrics")?.takeUnless { it.isNullish() }?.asString
        if (usableSynced(synced)) s += 50 else if (!usablePlain(o.get("plainLyrics")?.takeUnless { it.isNullish() }?.asString)) return 0
        val name = o.get("trackName")?.asString?.lowercase() ?: ""
        val artist = o.get("artistName")?.asString?.lowercase() ?: ""
        if (name == q.bareTitle.lowercase()) s += 30 else if (q.bareTitle.lowercase() in name) s += 10
        if (q.artist.isNotBlank() && (q.artist.lowercase() in artist || artist in q.artist.lowercase())) s += 25
        q.durationSec?.let { d ->
            val diff = abs((o.get("duration")?.asDouble ?: 0.0) - d)
            s += if (diff <= 3) 20 else if (diff > 20) -40 else 0
        }
        return s
    }

    private val STAMP = Regex("""^\s*\[\d{1,3}:\d{1,2}""", RegexOption.MULTILINE)

    private fun usableSynced(s: String?) = s != null && STAMP.findAll(s).count() >= 4
    private fun usablePlain(s: String?) = s != null && s.lineSequence().count { it.isNotBlank() } >= 3

    private fun toRaw(o: JsonObject) = RawLyrics(
        synced = o.get("syncedLyrics")?.takeUnless { it.isNullish() }?.asString?.takeIf(::usableSynced),
        plain = o.get("plainLyrics")?.takeUnless { it.isNullish() }?.asString?.takeIf(::usablePlain),
        source = id,
        instrumental = o.get("instrumental")?.takeUnless { it.isNullish() }?.asBoolean == true,
    )

    private fun get(path: String, params: Map<String, String>): JsonElement? {
        val qs = params.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, Charsets.UTF_8)}" }
        val req = HttpRequest.newBuilder(URI.create("$BASE$path?$qs"))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "LyricVibes/${LyricVibes.VERSION} (https://github.com/L12-MC/LyricVibes)")
            .GET().build()
        return runCatching {
            val res = http.send(req, HttpResponse.BodyHandlers.ofString())
            if (res.statusCode() != 200) null else JsonParser.parseString(res.body())
        }.onFailure { LyricVibes.LOGGER.debug("LRCLIB request failed", it) }.getOrNull()
    }

    private fun JsonElement.asJsonObjectOrNull() = if (isJsonObject) asJsonObject else null
    private fun JsonElement?.isNullish() = this == null || isJsonNull
}
