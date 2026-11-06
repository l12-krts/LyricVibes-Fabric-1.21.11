package dev.krts.lyricvibes.lyrics

import com.google.gson.Gson
import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.config.LvConfig
import dev.krts.lyricvibes.config.SpotifyMode
import dev.krts.lyricvibes.media.MediaBridge
import dev.krts.lyricvibes.media.NowPlaying
import dev.krts.lyricvibes.spotify.SpotifyLink
import dev.krts.lyricvibes.spotify.SpotifyLyrics
import java.nio.file.Path
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
    @Volatile private var current: RawLyrics? = null

    private val providers: List<LyricsProvider> = listOf(LrcLibProvider)
    private val pool = Executors.newSingleThreadExecutor { Thread(it, "LyricVibes-Lyrics").apply { isDaemon = true } }
    private val gson = Gson()
    private val cacheDir = LvConfig.dir.resolve("cache").resolve("lyrics")
    private const val NEGATIVE_TTL = 24L * 3600_000L

    private class Cached(val synced: String?, val plain: String?, val source: String?, val instrumental: Boolean, val at: Long) {
        val found get() = synced != null || plain != null || instrumental
        fun raw() = RawLyrics(synced, plain, source ?: "cache", instrumental)
    }

    fun onTrack(np: NowPlaying?) {
        val key = np?.key
        if (key == trackKey) return
        trackKey = key
        lyrics = null
        current = null
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
        runCatching {
            Files.deleteIfExists(cacheFile(np.key))
            Files.deleteIfExists(spotifyFile(np.key))
        }
        SpotifyLink.forget(np.key)
        onTrack(np)
    }

    fun reload() {
        val np = MediaBridge.nowPlaying ?: return
        trackKey = null
        SpotifyLink.forget(np.key)
        onTrack(np)
    }

    fun onSpotify(np: NowPlaying, raw: RawLyrics) {
        writeCache(spotifyFile(np.key), raw)
        pool.execute {
            if (trackKey != np.key || current?.source == SpotifyLyrics.SOURCE) return@execute
            if (pick(current, raw) === raw) apply(raw, np)
        }
    }

    // nothing Spotify could improve
    fun settled(key: String) = trackKey == key && status == LyricsStatus.SYNCED &&
        (lyrics?.source == SpotifyLyrics.SOURCE || LyricVibes.config.spotifyLyrics != SpotifyMode.PREFER)

    private fun cacheFile(key: String) = cacheDir.resolve(MediaBridge.sha1(key) + ".json")

    private fun spotifyFile(key: String) = cacheDir.resolve(MediaBridge.sha1(key) + ".spotify.json")

    private fun resolve(np: NowPlaying, key: String) {
        val early = spotifyCached(key)
        val lrc = if (LyricVibes.config.spotifyLyrics == SpotifyMode.PREFER && early?.synced != null) null else lrclib(np, key)
        if (trackKey != key) return
        apply(pick(lrc, early ?: spotifyCached(key)), np)
    }

    private fun lrclib(np: NowPlaying, key: String): RawLyrics? {
        val cached = readCache(cacheFile(key))
        return when {
            cached != null && cached.found -> cached.raw()
            cached != null && System.currentTimeMillis() - cached.at < NEGATIVE_TTL -> null
            else -> fetch(np, key)
        }
    }

    private fun spotifyCached(key: String) =
        if (LyricVibes.config.spotifyLyrics == SpotifyMode.OFF) null else readCache(spotifyFile(key))?.takeIf { it.found }?.raw()

    private fun rank(r: RawLyrics?) = when {
        r == null -> 0
        r.synced != null -> 3
        r.instrumental -> 2
        r.plain != null -> 1
        else -> 0
    }

    private fun pick(base: RawLyrics?, spotify: RawLyrics?): RawLyrics? {
        val mode = LyricVibes.config.spotifyLyrics
        if (spotify == null || mode == SpotifyMode.OFF) return base
        val a = rank(base)
        val b = rank(spotify)
        return if (b > a || (b == a && mode == SpotifyMode.PREFER)) spotify else base
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
        writeCache(cacheFile(key), result)
        return result
    }

    private fun apply(raw: RawLyrics?, np: NowPlaying) {
        current = raw
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
        LyricVibes.LOGGER.info("Lyrics for '{}': {} via {} ({} lines)", np.title, status, raw?.source ?: "none", lyrics?.lines?.size ?: 0)
    }

    private fun waitForDuration(np: NowPlaying): Long {
        if (np.durationMs > 0) return np.durationMs
        repeat(10) {
            TimeUnit.MILLISECONDS.sleep(200)
            MediaBridge.nowPlaying?.takeIf { it.key == np.key && it.durationMs > 0 }?.let { return it.durationMs }
        }
        return 0
    }

    private fun readCache(f: Path): Cached? = runCatching {
        if (!Files.exists(f)) null else Files.newBufferedReader(f).use { gson.fromJson(it, Cached::class.java) }
    }.getOrNull()

    private fun writeCache(f: Path, raw: RawLyrics?) {
        runCatching {
            Files.createDirectories(cacheDir)
            val c = Cached(raw?.synced, raw?.plain, raw?.source, raw?.instrumental == true, System.currentTimeMillis())
            Files.newBufferedWriter(f).use { gson.toJson(c, it) }
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
