package dev.krts.lyricvibes.media

enum class PlayerKind(val label: String) { SPOTIFY("Spotify"), BROWSER("Browser"), PLAYER("Media player") }

data class NowPlaying(
    val title: String,
    val artist: String,
    val album: String,
    val app: String,
    val status: Int,
    val positionMs: Long,
    val durationMs: Long,
    val rate: Double,
    val stampMs: Long,
) {
    val playing get() = status == STATUS_PLAYING
    val key get() = "$title\u001f$artist\u001f$album"

    val kind: PlayerKind
        get() {
            val a = app.lowercase()
            return when {
                "spotify" in a -> PlayerKind.SPOTIFY
                BROWSERS.any { it in a } -> PlayerKind.BROWSER
                else -> PlayerKind.PLAYER
            }
        }

    fun positionAt(nowMs: Long): Long {
        if (!playing) return positionMs
        val p = positionMs + ((nowMs - stampMs).coerceAtLeast(0) * rate).toLong()
        return if (durationMs > 0) p.coerceAtMost(durationMs) else p
    }

    companion object {
        const val STATUS_PLAYING = 4
        const val STATUS_PAUSED = 5
        private val BROWSERS = listOf("chrome", "msedge", "firefox", "arc", "opera", "brave", "vivaldi", "zen", "browser")
    }
}

data class Art(val key: String, val file: java.nio.file.Path, val accent: Int?)
