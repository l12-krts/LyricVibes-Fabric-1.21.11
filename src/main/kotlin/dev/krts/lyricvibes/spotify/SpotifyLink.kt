package dev.krts.lyricvibes.spotify

import com.google.gson.JsonElement
import com.google.gson.JsonObject
import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.config.SpotifyMode
import dev.krts.lyricvibes.lyrics.LyricsService
import dev.krts.lyricvibes.lyrics.LyricsStatus
import dev.krts.lyricvibes.media.MediaBridge
import dev.krts.lyricvibes.media.NowPlaying
import dev.krts.lyricvibes.media.PlayerKind
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

enum class LinkState(val label: String) {
    OFF("Off"),
    CLOSED("Spotify isn't running"),
    UNLINKED("Not linked yet, needs one Spotify restart"),
    WAITING("Waiting for Spotify to load"),
    FOREIGN("Debug port is used by another app"),
    RESTARTING("Restarting Spotify…"),
    MISSING("Couldn't find Spotify.exe"),
    REFUSED("Spotify didn't open the debug port"),
    LINKED("Linked, reading lyrics Spotify loads"),
}

object SpotifyLink {
    @Volatile var state = LinkState.OFF
        private set
    val linked get() = state == LinkState.LINKED
    val button get() = when (state) {
        LinkState.LINKED -> "Restart Spotify"
        LinkState.RESTARTING -> "Restarting…"
        else -> "Link Spotify"
    }

    private val EVENTS = setOf("Network.responseReceived", "Network.loadingFinished", "Network.loadingFailed", "Target.attachedToTarget")
    private val LYRICS_URL = Regex("""/color-lyrics/v2/track/([A-Za-z0-9]{22})""")
    private val NORM = Regex("""[^\p{L}\p{N}]+""")

    // now playing bar
    private const val PROBE = """(() => {
  const w = document.querySelector('[data-testid="now-playing-widget"]');
  if (!w) return null;
  const t = w.querySelector('[data-testid="context-item-info-title"]');
  let id = null;
  for (const a of w.querySelectorAll('a[href]')) {
    let h = a.getAttribute('href');
    try { h = decodeURIComponent(h); } catch (e) {}
    const m = h.match(/track[:\/]([A-Za-z0-9]{22})/);
    if (m) { id = m[1]; break; }
  }
  return [id, t ? (t.textContent.trim() || null) : null];
})()"""

    private class Grab(val parsed: SpotifyLyrics.Parsed, val at: Long) {
        var owner: String? = null
    }

    private class Probe(val id: String?, val title: String?)

    @Volatile private var cdp: Cdp? = null
    @Volatile private var running = false
    @Volatile private var restarting = false
    @Volatile private var delivered: String? = null
    private val pending = ConcurrentHashMap<String, String>()
    private val grabs = object : LinkedHashMap<String, Grab>() {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Grab>?) = size > 24
    }
    private var watchKey: String? = null
    private var watchSince = 0L
    private var warned = false

    fun start() {
        if (running) return
        running = true
        Thread(::loop, "LyricVibes-Spotify").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        drop()
    }

    fun forget(key: String) {
        if (delivered == key) delivered = null
    }

    fun hint(np: NowPlaying): String? {
        if (LyricVibes.config.spotifyLyrics == SpotifyMode.OFF || np.kind != PlayerKind.SPOTIFY) return null
        val s = LyricsService.status
        if (s != LyricsStatus.NONE && s != LyricsStatus.UNSYNCED || LyricsService.lyrics?.source == SpotifyLyrics.SOURCE) return null
        return if (linked) "open lyrics in Spotify" else "link Spotify in the Lyrics tab"
    }

    private fun loop() {
        while (running) {
            runCatching { step() }.onFailure { LyricVibes.LOGGER.debug("Spotify link step failed", it) }
            Thread.sleep(if (cdp != null) 1000L else 3000L)
        }
    }

    private fun step() {
        if (LyricVibes.config.spotifyLyrics == SpotifyMode.OFF) {
            drop()
            if (!restarting) state = LinkState.OFF
            return
        }
        val c = cdp?.takeIf { it.open } ?: connect() ?: return
        reconcile(c)
    }

    private fun drop() {
        cdp?.close()
        cdp = null
        pending.clear()
    }

    private fun connect(): Cdp? {
        if (cdp != null) {
            drop()
            LyricVibes.LOGGER.info("Spotify link lost")
        }
        val port = LyricVibes.config.spotifyPort
        val targets = Cdp.targets(port)
        val url = targets?.mapNotNull { it.obj() }
            ?.firstOrNull { it.str("type") == "page" && "xpui" in it.str("url") }
            ?.str("webSocketDebuggerUrl")
        if (url.isNullOrEmpty()) {
            if (!restarting && state != LinkState.MISSING && state != LinkState.REFUSED) state = when {
                targets == null -> if (spotifyRunning()) LinkState.UNLINKED else LinkState.CLOSED
                spotifyRunning() -> LinkState.WAITING
                else -> LinkState.FOREIGN
            }
            return null
        }
        val c = Cdp.connect(url, EVENTS, ::onEvent)
        try {
            c.call("Network.enable", json("maxTotalBufferSize" to 8_000_000, "maxResourceBufferSize" to 2_000_000)).get(5, TimeUnit.SECONDS)
        } catch (t: Throwable) {
            c.close()
            throw t
        }
        // worker requests too
        c.call("Target.setAutoAttach", json("autoAttach" to true, "waitForDebuggerOnStart" to false, "flatten" to true))
        cdp = c
        state = LinkState.LINKED
        LyricVibes.LOGGER.info("Linked to Spotify on port {}", port)
        return c
    }

    private fun onEvent(c: Cdp, method: String, params: JsonObject, session: String?) {
        val req = "${session.orEmpty()}|${params.str("requestId")}"
        when (method) {
            "Network.responseReceived" -> {
                val res = params.get("response")?.obj() ?: return
                val track = LYRICS_URL.find(res.str("url"))?.groupValues?.get(1) ?: return
                if (res.str("status").toDoubleOrNull()?.toInt() == 200) pending[req] = track
            }
            "Network.loadingFinished" -> {
                val track = pending.remove(req) ?: return
                c.call("Network.getResponseBody", json("requestId" to params.str("requestId")), session)
                    .thenAccept { body(track, it) }
            }
            "Network.loadingFailed" -> pending.remove(req)
            "Target.attachedToTarget" -> c.call("Network.enable", session = params.str("sessionId"))
        }
    }

    private fun body(track: String, res: JsonObject) {
        val raw = res.str("body")
        val text = if (res.get("base64Encoded")?.asBoolean == true) String(Base64.getDecoder().decode(raw), Charsets.UTF_8) else raw
        val parsed = SpotifyLyrics.parse(text)
        if (parsed == null) {
            if (!warned) LyricVibes.LOGGER.warn("Spotify lyrics response not understood ({} chars)", text.length)
            warned = true
            return
        }
        synchronized(grabs) { grabs[track] = Grab(parsed, System.currentTimeMillis()) }
        LyricVibes.LOGGER.info("Grabbed Spotify lyrics for track {}", track)
    }

    private fun reconcile(c: Cdp) {
        val np = MediaBridge.nowPlaying?.takeIf { it.kind == PlayerKind.SPOTIFY } ?: return
        if (np.key != watchKey) {
            watchKey = np.key
            watchSince = System.currentTimeMillis()
        }
        if (np.key == delivered || LyricsService.settled(np.key)) return
        val p = probe(c)
        if (p.title != null && !same(p.title, np.title)) return
        val grab = synchronized(grabs) {
            if (p.id != null) grabs[p.id]
            else grabs.values.filter { it.owner == null && it.at >= watchSince - 1500 }.maxByOrNull { it.at }
        } ?: return
        if (np.durationMs > 0 && grab.parsed.lastMs > np.durationMs + 10_000) return
        grab.owner = np.key
        delivered = np.key
        LyricsService.onSpotify(np, grab.parsed.raw)
    }

    private fun probe(c: Cdp): Probe = runCatching {
        val r = c.call("Runtime.evaluate", json("expression" to PROBE, "returnByValue" to true)).get(3, TimeUnit.SECONDS)
        val v = r.get("result")?.obj()?.get("value")?.takeIf { it.isJsonArray }?.asJsonArray
        if (v == null || v.size() < 2) Probe(null, null) else Probe(v[0].strOrNull(), v[1].strOrNull())
    }.getOrElse { Probe(null, null) }

    private fun same(a: String, b: String): Boolean {
        val x = NORM.replace(a.lowercase(), "")
        val y = NORM.replace(b.lowercase(), "")
        return x == y || (x.isNotEmpty() && y.isNotEmpty() && (x.startsWith(y) || y.startsWith(x)))
    }

    fun relaunch() {
        if (restarting || !MediaBridge.supported) return
        restarting = true
        state = LinkState.RESTARTING
        Thread({
            runCatching { restart() }.onFailure { LyricVibes.LOGGER.warn("Spotify restart failed", it) }
            restarting = false
            if (state == LinkState.RESTARTING) state = LinkState.UNLINKED
        }, "LyricVibes-SpotifyRestart").apply { isDaemon = true }.start()
    }

    private fun restart() {
        val np = MediaBridge.nowPlaying
        val resume = np != null && np.kind == PlayerKind.SPOTIFY && np.playing
        val exe = findExe() ?: run {
            state = LinkState.MISSING
            return
        }
        drop()
        if (processes().isNotEmpty()) {
            ProcessBuilder("taskkill", "/F", "/T", "/IM", "Spotify.exe")
                .redirectErrorStream(true)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .start().waitFor(10, TimeUnit.SECONDS)
            waitUntil(10_000) { processes().isEmpty() }
            Thread.sleep(600)
        }
        LyricVibes.LOGGER.info("Starting Spotify with debug port {}", LyricVibes.config.spotifyPort)
        ProcessBuilder(exe.toString(), "--remote-debugging-port=${LyricVibes.config.spotifyPort}")
            .redirectOutput(ProcessBuilder.Redirect.DISCARD)
            .redirectError(ProcessBuilder.Redirect.DISCARD)
            .start().outputStream.close()
        if (!waitUntil(45_000) { linked }) {
            state = if (processes().isNotEmpty()) LinkState.REFUSED else LinkState.CLOSED
            return
        }
        // resume playback
        if (resume && waitUntil(20_000) { MediaBridge.nowPlaying?.kind == PlayerKind.SPOTIFY }) {
            Thread.sleep(800)
            if (MediaBridge.nowPlaying?.let { it.kind == PlayerKind.SPOTIFY && !it.playing } == true) MediaBridge.send("play")
        }
    }

    private fun findExe(): Path? {
        val alias = System.getenv("LOCALAPPDATA")?.let { Path.of(it, "Microsoft", "WindowsApps", "Spotify.exe") }
            ?.takeIf { Files.exists(it, LinkOption.NOFOLLOW_LINKS) }
        val live = processes().firstNotNullOfOrNull { it.info().command().orElse(null) }?.let { Path.of(it) }
        val classic = System.getenv("APPDATA")?.let { Path.of(it, "Spotify", "Spotify.exe") }?.takeIf { Files.exists(it) }
        return when {
            live != null && "\\WindowsApps\\" !in live.toString() -> live
            live != null -> alias
            else -> classic ?: alias
        }
    }

    private fun processes(): List<ProcessHandle> = ProcessHandle.allProcesses().filter { p ->
        p.info().command().map { it.substringAfterLast('\\').equals("Spotify.exe", ignoreCase = true) }.orElse(false)
    }.toList()

    private fun spotifyRunning() = MediaBridge.nowPlaying?.kind == PlayerKind.SPOTIFY || processes().isNotEmpty()

    private fun waitUntil(ms: Long, cond: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + ms
        while (System.currentTimeMillis() < end) {
            if (cond()) return true
            Thread.sleep(250)
        }
        return cond()
    }

    private fun json(vararg kv: Pair<String, Any>) = JsonObject().apply {
        for ((k, v) in kv) when (v) {
            is Boolean -> addProperty(k, v)
            is Number -> addProperty(k, v)
            else -> addProperty(k, v.toString())
        }
    }

    private fun JsonElement.obj() = takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonElement.strOrNull() = takeIf { it.isJsonPrimitive }?.asString
    private fun JsonObject.str(k: String) = get(k)?.strOrNull() ?: ""
}
