package dev.krts.lyricvibes.media

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.krts.lyricvibes.LyricVibes
import dev.krts.lyricvibes.config.LvConfig
import java.io.BufferedWriter
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Base64

object MediaBridge {
    @Volatile var nowPlaying: NowPlaying? = null
        private set
    @Volatile var art: Art? = null
        private set
    @Volatile var bands = FloatArray(16)
        private set
    @Volatile var bandsStampMs = 0L
        private set
    @Volatile var status = "Starting"
        private set

    private var process: Process? = null
    private var stdin: BufferedWriter? = null
    @Volatile private var running = false
    private val artDir: Path = LvConfig.dir.resolve("cache").resolve("art")

    val supported get() = System.getProperty("os.name").startsWith("Windows")

    fun start() {
        System.getProperty("lyricvibes.demo")?.let {
            startDemo(it)
            return
        }
        if (!supported) {
            status = "Windows only"
            return
        }
        running = true
        Thread(::supervise, "LyricVibes-Bridge").apply { isDaemon = true }.start()
    }

    fun stop() {
        running = false
        runCatching { send("quit") }
        process?.destroy()
    }

    fun send(cmd: String) {
        val w = stdin ?: return
        synchronized(w) {
            runCatching { w.write(cmd); w.newLine(); w.flush() }
        }
    }

    fun togglePlay() {
        send("toggle")
        nowPlaying?.let { np ->
            val now = System.currentTimeMillis()
            val flipped = if (np.playing) NowPlaying.STATUS_PAUSED else NowPlaying.STATUS_PLAYING
            nowPlaying = np.copy(status = flipped, positionMs = np.positionAt(now), stampMs = now)
        }
    }

    private fun startDemo(spec: String) {
        val parts = spec.split('|')
        val title = parts.getOrNull(1) ?: "Never Gonna Give You Up"
        val artist = parts.getOrNull(0)?.takeIf { it != "true" } ?: "Rick Astley"
        val dur = (parts.getOrNull(2)?.toLongOrNull() ?: 213L) * 1000
        val start = System.currentTimeMillis()
        status = "Demo"
        Thread({
            while (true) {
                val now = System.currentTimeMillis()
                val t = (now - start) % dur
                nowPlaying = NowPlaying(title, artist, "", "Spotify.exe", NowPlaying.STATUS_PLAYING, t, dur, 1.0, now)
                bands = FloatArray(16) { i -> (0.35f + 0.3f * kotlin.math.sin(now / 180.0 + i).toFloat()).coerceIn(0f, 1f) }
                bandsStampMs = now
                Thread.sleep(200)
            }
        }, "LyricVibes-Demo").apply { isDaemon = true }.start()
    }

    private fun supervise() {
        var backoff = 1000L
        while (running) {
            try {
                val exe = extract()
                val p = ProcessBuilder(exe.toString())
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start()
                process = p
                stdin = p.outputStream.bufferedWriter()
                status = "Connected"
                backoff = 1000L
                p.inputStream.bufferedReader().useLines { lines -> lines.forEach(::handle) }
                p.waitFor()
            } catch (t: Throwable) {
                LyricVibes.LOGGER.warn("Media bridge failed", t)
                status = "Bridge error"
            }
            stdin = null
            nowPlaying = null
            if (!running) break
            status = "Reconnecting"
            Thread.sleep(backoff)
            backoff = (backoff * 2).coerceAtMost(15_000L)
        }
    }

    private fun extract(): Path {
        val bin = LvConfig.dir.resolve("bin")
        Files.createDirectories(bin)
        val target = bin.resolve("lyricvibes-bridge-${LyricVibes.VERSION}.exe")
        val res = MediaBridge::class.java.getResourceAsStream("/natives/lyricvibes-bridge.exe")
            ?: error("Bridge binary missing from jar")
        res.use { input ->
            val bytes = input.readAllBytes()
            if (!Files.exists(target) || Files.size(target) != bytes.size.toLong()) {
                val tmp = Files.createTempFile(bin, "bridge", ".tmp")
                Files.write(tmp, bytes)
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
            }
        }
        return target
    }

    private fun handle(line: String) {
        val obj = runCatching { JsonParser.parseString(line).asJsonObject }.getOrNull() ?: return
        when (obj.str("t")) {
            "state" -> nowPlaying = NowPlaying(
                title = obj.str("title"),
                artist = obj.str("artist"),
                album = obj.str("album"),
                app = obj.str("app"),
                status = obj.get("status")?.asInt ?: 0,
                positionMs = obj.get("pos")?.asLong ?: 0L,
                durationMs = obj.get("dur")?.asLong ?: 0L,
                rate = obj.get("rate")?.asDouble ?: 1.0,
                stampMs = obj.get("ts")?.asLong ?: System.currentTimeMillis(),
            ).takeIf { it.title.isNotBlank() }
            "none" -> nowPlaying = null
            "bands" -> {
                val arr = obj.getAsJsonArray("v") ?: return
                bands = FloatArray(arr.size()) { arr[it].asFloat }
                bandsStampMs = System.currentTimeMillis()
            }
            "art" -> saveArt(obj)
            "error" -> LyricVibes.LOGGER.debug("Bridge: {}", obj.str("msg"))
        }
    }

    private fun saveArt(obj: JsonObject) {
        runCatching {
            val key = obj.str("key")
            val bytes = Base64.getDecoder().decode(obj.str("data"))
            val ext = if ("png" in obj.str("mime")) "png" else "jpg"
            Files.createDirectories(artDir)
            val file = artDir.resolve("${sha1(key).take(16)}.$ext")
            Files.write(file, bytes)
            art = Art(key, file, ArtColor.accentOf(bytes))
        }.onFailure { LyricVibes.LOGGER.debug("Art save failed", it) }
    }

    private fun JsonObject.str(k: String) = get(k)?.takeIf { !it.isJsonNull }?.asString ?: ""

    fun sha1(s: String): String =
        MessageDigest.getInstance("SHA-1").digest(s.toByteArray()).joinToString("") { "%02x".format(it) }
}
