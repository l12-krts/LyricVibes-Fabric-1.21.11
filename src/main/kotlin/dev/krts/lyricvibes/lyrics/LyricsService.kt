package dev.krts.lyricvibes.lyrics

import com.google.gson.Gson
import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.config.LvConfig
import dev.krts.lyricvibes.media.MediaBridge
import dev.krts.lyricvibes.media.NowPlaying
import java.nio.file.Files
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

enum class LyricsStatus(val label: String) {
    IDLE("Nothing playing"),
    LOADING("Searching lyrics"),
    SYNCED("Synced lyrics"),
    UNSYNCED("Unsynced lyrics"),
    INSTRUMENTAL("Instrumental"),
    NONE("No lyrics found"),
}

object LyricsService {
    @Volatile var lyrics: Lyrics? = null
        private set
    @Volatile var status = LyricsStatus.IDLE
        private set
    @Volatile var trackKey: String? = null
        private set

    private val providers: List<LyricsProvider> = listOf(LrcLibProvider)
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "LyricVibes-Lyrics").apply { isDaemon = true } }
    private val gson = Gson()
    private val cacheDir = LvConfig.dir.resolve("cache").resolve("lyrics")
    private const val NEGATIVE_TTL = 24L * 3600_000L

    private class Cached(val synced: String?, val plain: String?, val source: String?, val instrumental: Boolean, val at: Long)

    fun onTrack(np: NowPlaying?) {
        val key = np?.key
        if (key == trackKey) return
        trackKey = key
        lyrics = null
        if (np == null) {
            status = LyricsStatus.IDLE
            return
        }
        status = LyricsStatus.LOADING
        pool.execute { resolve(np, key!!) }
    }

    fun retry() {
        val np = MediaBridge.nowPlaying ?: return
        trackKey = null
        runCatching { Files.deleteIfExists(cacheFile(np.key)) }
        onTrack(np)
    }

    private fun cacheFile(key: String) = cacheDir.resolve(MediaBridge.sha1(key) + ".json")

    private fun resolve(np: NowPlaying, key: String) {
        val cached = readCache(key)
        val raw = when {
            cached != null && (cached.synced != null || cached.plain != null || cached.instrumental) ->
                RawLyrics(cached.synced, cached.plain, cached.source ?: "cache", cached.instrumental)
            cached != null && System.currentTimeMillis() - cached.at < NEGATIVE_TTL -> null
            else -> fetch(np, key)
        }
        if (trackKey != key) return
        apply(raw, np)
    }

    private fun fetch(np: NowPlaying, key: String): RawLyrics? {
        val query = TrackQuery.of(np)
        var result: RawLyrics? = null
        for (p in providers) {
            val r = runCatching { p.fetch(query) }.onFailure { LyricVibes.LOGGER.debug("Provider {} failed", p.id, it) }.getOrNull() ?: continue
            if (r.synced != null || r.instrumental) {
                result = r
                break
            }
            if (result == null) result = r
        }
        writeCache(key, result)
        return result
    }

    private fun apply(raw: RawLyrics?, np: NowPlaying) {
        val parsed = raw?.synced?.let { LrcParser.parse(it, raw.source) }
        when {
            parsed != null -> {
                lyrics = parsed
                status = LyricsStatus.SYNCED
            }
            raw?.instrumental == true -> status = LyricsStatus.INSTRUMENTAL
            raw?.plain != null -> {
                lyrics = LrcParser.fromPlain(raw.plain, waitForDuration(np), raw.source)
                status = if (lyrics != null) LyricsStatus.UNSYNCED else LyricsStatus.NONE
            }
            else -> status = LyricsStatus.NONE
        }
        LyricVibes.LOGGER.info("Lyrics for '{}': {} ({} lines)", np.title, status, lyrics?.lines?.size ?: 0)
    }

    private fun waitForDuration(np: NowPlaying): Long {
        if (np.durationMs > 0) return np.durationMs
        repeat(10) {
            TimeUnit.MILLISECONDS.sleep(200)
            MediaBridge.nowPlaying?.takeIf { it.key == np.key && it.durationMs > 0 }?.let { return it.durationMs }
        }
        return 0
    }

    private fun readCache(key: String): Cached? = runCatching {
        val f = cacheFile(key)
        if (!Files.exists(f)) null else Files.newBufferedReader(f).use { gson.fromJson(it, Cached::class.java) }
    }.getOrNull()

    private fun writeCache(key: String, raw: RawLyrics?) {
        runCatching {
            Files.createDirectories(cacheDir)
            val c = Cached(raw?.synced, raw?.plain, raw?.source, raw?.instrumental == true, System.currentTimeMillis())
            Files.newBufferedWriter(cacheFile(key)).use { gson.toJson(c, it) }
        }
    }
}

object PlaybackClock {
    var positionMs = 0L
        private set
    private var smooth = 0.0
    private var lastNanos = 0L
    private var lastKey: String? = null

    fun update(np: NowPlaying?, offsetMs: Int) {
        val now = System.nanoTime()
        val dt = if (lastNanos == 0L) 0.0 else (now - lastNanos) / 1_000_000.0
        lastNanos = now
        if (np == null) {
            positionMs = 0
            lastKey = null
            return
        }
        val reported = np.positionAt(System.currentTimeMillis()).toDouble()
        if (np.key != lastKey || !np.playing || abs(reported - smooth) > 350.0) {
            smooth = reported
            lastKey = np.key
        } else {
            smooth += dt.coerceAtMost(100.0) * np.rate
            smooth += (reported - smooth) * 0.08
        }
        positionMs = smooth.toLong() + offsetMs
    }
}
