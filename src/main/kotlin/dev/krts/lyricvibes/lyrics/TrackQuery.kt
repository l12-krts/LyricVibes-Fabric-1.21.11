package dev.krts.lyricvibes.lyrics

import dev.krts.lyricvibes.media.NowPlaying
import dev.krts.lyricvibes.media.PlayerKind

data class TrackQuery(val title: String, val artist: String, val album: String, val durationSec: Int?, val loose: Boolean) {
    val bareTitle get() = title.replace(FEAT, "").replace(Regex("\\s+"), " ").trim()

    companion object {
        private val NOISE = Regex(
            """\s*[(\[](?:[^)\]]*?(?:official|video|audio|lyric|lyrics|visuali[sz]er|hd|4k|hq|m/?v|explicit|clean|live session|color coded)[^)\]]*)[)\]]""",
            RegexOption.IGNORE_CASE,
        )
        private val REMASTER = Regex("""\s+-\s+(?:\d{4}\s+)?remaster(?:ed)?(?:\s+\d{4})?.*$""", RegexOption.IGNORE_CASE)
        private val FEAT = Regex("""\s*[(\[]?\s*(?:feat\.?|ft\.?|featuring)\s+[^)\]]*[)\]]?""", RegexOption.IGNORE_CASE)
        private val CHANNEL = Regex("""\s*(?:-\s*Topic|VEVO|Official)$""", RegexOption.IGNORE_CASE)

        fun of(np: NowPlaying): TrackQuery {
            var title = NOISE.replace(np.title, "").trim()
            var artist = CHANNEL.replace(np.artist, "").trim()
            val browser = np.kind == PlayerKind.BROWSER
            if (" - " in title && (browser || artist.isBlank())) {
                val left = title.substringBefore(" - ").trim()
                val right = title.substringAfter(" - ").trim()
                if (left.isNotEmpty() && right.isNotEmpty()) {
                    artist = left
                    title = right
                }
            }
            title = REMASTER.replace(title, "").trim().trim('"', '\'', '“', '”')
            val dur = (np.durationMs / 1000).toInt().takeIf { it in 20..1800 && !browser }
            return TrackQuery(title, artist, np.album, dur, browser)
        }
    }
}
