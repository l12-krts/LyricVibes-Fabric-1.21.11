package dev.krts.lyricvibes.lyrics

data class Word(val startMs: Long, val text: String)

data class Line(val index: Int, val startMs: Long, val endMs: Long, val text: String, val words: List<Word>)

class Lyrics(val lines: List<Line>, val synced: Boolean, val source: String) {
    fun indexAt(ms: Long): Int {
        var lo = 0
        var hi = lines.size - 1
        var found = -1
        while (lo <= hi) {
            val mid = (lo + hi) ushr 1
            if (lines[mid].startMs <= ms) {
                found = mid
                lo = mid + 1
            } else hi = mid - 1
        }
        return found
    }

    fun activeAt(ms: Long): Line? = indexAt(ms).takeIf { it >= 0 }?.let(lines::get)?.takeIf { ms < it.endMs }

    fun nextAfter(ms: Long): Line? = lines.getOrNull(indexAt(ms) + 1)
}

object LrcParser {
    private val TIME = Regex("""\[(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?]""")
    private val WORD = Regex("""<(\d{1,3}):(\d{1,2})(?:[.:](\d{1,3}))?>""")
    private val OFFSET = Regex("""\[offset:\s*([+-]?\d+)]""", RegexOption.IGNORE_CASE)
    private const val MAX_LINE_MS = 7000L

    private fun ms(m: MatchResult): Long {
        val (min, sec, frac) = m.destructured
        val f = when (frac.length) {
            0 -> 0L
            1 -> frac.toLong() * 100
            2 -> frac.toLong() * 10
            else -> frac.take(3).toLong()
        }
        return min.toLong() * 60_000 + sec.toLong() * 1000 + f
    }

    fun parse(lrc: String, source: String): Lyrics? {
        val offset = OFFSET.find(lrc)?.groupValues?.get(1)?.toLongOrNull() ?: 0L
        val raw = ArrayList<Triple<Long, String, List<Word>?>>()
        for (rawLine in lrc.lineSequence()) {
            val stamps = TIME.findAll(rawLine).toList()
            if (stamps.isEmpty()) continue
            val body = rawLine.substring(stamps.last().range.last + 1)
            val words = parseWords(body, offset)
            val text = WORD.replace(body, "").replace(Regex("\\s+"), " ").trim()
            for (s in stamps) raw += Triple(ms(s) - offset, text, words)
        }
        if (raw.isEmpty()) return null
        raw.sortBy { it.first }
        val lines = ArrayList<Line>()
        for ((i, r) in raw.withIndex()) {
            if (r.second.isEmpty()) continue
            val next = raw.getOrNull(i + 1)?.first ?: (r.first + MAX_LINE_MS)
            val end = minOf(next, r.first + MAX_LINE_MS).coerceAtLeast(r.first + 400)
            val words = r.third ?: interpolate(r.second, r.first, end)
            lines += Line(lines.size, r.first, end, r.second, words)
        }
        return if (lines.isEmpty()) null else Lyrics(lines, true, source)
    }

    private fun parseWords(body: String, offset: Long): List<Word>? {
        val marks = WORD.findAll(body).toList()
        if (marks.isEmpty()) return null
        val out = ArrayList<Word>()
        for ((i, m) in marks.withIndex()) {
            val end = marks.getOrNull(i + 1)?.range?.first ?: body.length
            val text = body.substring(m.range.last + 1, end).trim()
            if (text.isNotEmpty()) text.split(' ').filter { it.isNotBlank() }.forEach { out += Word(ms(m) - offset, it) }
        }
        return out.ifEmpty { null }
    }

    fun interpolate(text: String, start: Long, end: Long): List<Word> {
        val parts = text.split(' ').filter { it.isNotBlank() }
        if (parts.isEmpty()) return emptyList()
        val total = parts.sumOf { it.length + 1 }.toFloat()
        val reveal = minOf((end - start) * 0.8f, parts.size * 380f)
        var acc = 0
        return parts.map { w ->
            val word = Word(start + (reveal * acc / total).toLong(), w)
            acc += w.length + 1
            word
        }
    }

    fun fromPlain(text: String, durationMs: Long, source: String): Lyrics? {
        val rows = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("[") }.toList()
        if (rows.isEmpty()) return null
        val dur = if (durationMs > 30_000) durationMs else rows.size * 4000L
        val start = (dur * 0.06).toLong()
        val span = (dur * 0.9 - start) / rows.size
        val lines = rows.mapIndexed { i, t ->
            val s = start + (span * i).toLong()
            val e = s + span.toLong()
            Line(i, s, e, t, interpolate(t, s, e))
        }
        return Lyrics(lines, false, source)
    }
}
