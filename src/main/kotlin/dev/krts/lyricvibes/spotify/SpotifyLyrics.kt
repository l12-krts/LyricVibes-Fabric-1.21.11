package dev.krts.lyricvibes.spotify

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import dev.krts.lyricvibes.lyrics.RawLyrics
import java.util.Locale

object SpotifyLyrics {
    const val SOURCE = "Spotify"
    private const val BREAK = "♪"

    class Parsed(val raw: RawLyrics, val lastMs: Long)

    private class Row(val start: Long, val end: Long, val text: String)

    fun parse(body: String): Parsed? {
        val root = runCatching { JsonParser.parseString(body) }.getOrNull()?.takeIf { it.isJsonObject }?.asJsonObject ?: return null
        val ly = root.obj("lyrics") ?: return null
        val rows = ly.get("lines")?.takeIf { it.isJsonArray }?.asJsonArray
            ?.mapNotNull { it.takeIf { e -> e.isJsonObject }?.asJsonObject }
            ?.map { Row(it.ms("startTimeMs"), it.ms("endTimeMs"), it.str("words").trim().takeUnless { w -> w == BREAK } ?: "") }
            ?.sortedBy { it.start }
            ?: return null
        if (rows.none { it.text.isNotEmpty() }) return null
        val lastMs = rows.last().start
        val synced = ly.str("syncType") != "UNSYNCED" && rows.any { it.start > 0 }
        if (!synced) {
            val plain = rows.filter { it.text.isNotEmpty() }.joinToString("\n") { it.text }
            return Parsed(RawLyrics(null, plain, SOURCE), lastMs)
        }
        val lrc = buildString {
            for ((i, r) in rows.withIndex()) {
                append(stamp(r.start)).append(r.text).append('\n')
                val next = rows.getOrNull(i + 1)?.start ?: Long.MAX_VALUE
                // explicit line end
                if (r.text.isNotEmpty() && r.end > r.start && next - r.end > 300) append(stamp(r.end)).append('\n')
            }
        }
        return Parsed(RawLyrics(lrc, null, SOURCE), lastMs)
    }

    private fun stamp(ms: Long) = String.format(Locale.ROOT, "[%02d:%02d.%02d]", ms / 60_000, ms / 1000 % 60, ms % 1000 / 10)

    private fun JsonObject.obj(k: String) = get(k)?.takeIf { it.isJsonObject }?.asJsonObject
    private fun JsonObject.str(k: String) = get(k)?.takeIf { it.isJsonPrimitive }?.asString ?: ""
    private fun JsonObject.ms(k: String) = str(k).toLongOrNull()?.coerceAtLeast(0) ?: 0L
}
