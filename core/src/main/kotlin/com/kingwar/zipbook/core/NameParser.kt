package com.kingwar.zipbook.core

/** A single audio entry inside an archive, after name recovery. */
data class AudioEntry(
    /** Full path inside the archive, '/' separated, e.g. "Tom 01/0001.mp3". */
    val path: String,
    val sizeBytes: Long,
    val compressedSizeBytes: Long = sizeBytes,
    /** True when the entry is stored uncompressed, which allows seekable playback. */
    val stored: Boolean = false,
) {
    val fileName: String get() = path.substringAfterLast('/')
    val baseName: String get() = fileName.substringBeforeLast('.', fileName)
    val extension: String get() = fileName.substringAfterLast('.', "").lowercase()
    val directory: String get() = path.substringBeforeLast('/', "")
    val pathSegments: List<String> get() = path.split('/').dropLast(1)
}

/** Numbers recovered from one name (file or directory segment). */
data class NumberHints(
    val volume: Int? = null,
    val chapter: Int? = null,
    val part: Int? = null,
    /** First bare number found, used as sequence when no labelled number exists. */
    val bare: Int? = null,
    /** Name with the recognised numbering tokens stripped, trimmed of separators. */
    val cleanTitle: String = "",
)

/**
 * Extracts volume / chapter / part numbers from Russian and English naming schemes.
 *
 * Handles the forms that actually show up in audiobook archives:
 *   "Том 3", "том_03", "tom3", "Книга 2", "Часть 4", "Vol. 2", "Volume 02",
 *   "Book 1", "Part 3", "Диск 2", "CD2", "Disc 03",
 *   "Глава 12", "глава-012", "гл 7", "gl12", "Chapter 5", "Ch. 05", "Track 7",
 *   "001", "0001", "00", "01 - Prologue", "12. Название"
 *
 * Deliberately conservative: a number only becomes a volume or chapter when a keyword
 * supports it. Unlabelled numbers are reported as [NumberHints.bare] so the grouping
 * stage decides what they mean from context (directory depth, how many files share
 * the directory), instead of guessing per file.
 */
object NameParser {

    private const val NUM = """(\d{1,4})"""
    // Keyword, optional dot, optional separators, then the number.
    private fun kw(vararg words: String) =
        Regex("""(?<![\p{L}\d])(?:${words.joinToString("|")})\.?\s*[-_. №#]*$NUM(?![\d])""", RegexOption.IGNORE_CASE)

    private val volumeRe = kw("том", "тома", "tom", "книга", "книги", "kniga", "vol", "volume", "book", "bk")
    private val partRe = kw("часть", "части", "chast", "part", "pt", "диск", "disk", "disc", "cd")
    private val chapterRe = kw("глава", "главы", "главa", "glava", "гл", "gl", "chapter", "chap", "ch", "track", "trk", "tr")

    /** Trailing or leading bare number: "0001", "01 - Intro", "Intro 05". */
    private val leadingBareRe = Regex("""^[\s_\-.\[(]*$NUM(?![\d])""")
    private val anyBareRe = Regex("""(?<![\p{L}\d])$NUM(?![\d])""")

    private val stripTokens = Regex(
        """(?<![\p{L}\d])(?:том|tom|книга|kniga|часть|chast|part|pt|диск|disk|disc|cd|глава|glava|гл|gl|chapter|chap|ch|track|trk|tr|vol|volume|book|bk)\.?\s*[-_. №#]*\d{1,4}(?![\d])""",
        RegexOption.IGNORE_CASE,
    )

    /**
     * Removes a trailing file extension only when the suffix actually looks like one:
     * 1-5 characters, letters and digits only.
     *
     * A naive `substringBeforeLast('.')` corrupts real names — "Vol. 2" would lose
     * the number and "Ch. 05" would become "Ch", because the dot there is punctuation,
     * not an extension separator.
     */
    private fun stripExtension(name: String): String {
        val dot = name.lastIndexOf('.')
        if (dot <= 0 || dot == name.length - 1) return name
        val suffix = name.substring(dot + 1)
        val looksLikeExtension = suffix.length <= 5 && suffix.all { it.isLetterOrDigit() }
        return if (looksLikeExtension) name.substring(0, dot) else name
    }

    fun parse(name: String): NumberHints {
        val normalized = name.replace('\\', '/').substringAfterLast('/')
        val withoutExt = stripExtension(normalized)

        val volume = volumeRe.find(withoutExt)?.groupValues?.get(1)?.toIntOrNull()
        val part = partRe.find(withoutExt)?.groupValues?.get(1)?.toIntOrNull()
        val chapter = chapterRe.find(withoutExt)?.groupValues?.get(1)?.toIntOrNull()

        // Bare number: prefer a leading one, else the first unlabelled number that is
        // not already consumed by a keyword match.
        val consumed = stripTokens.findAll(withoutExt).map { it.range }.toList()
        val bare = leadingBareRe.find(withoutExt)?.let { m ->
            if (consumed.any { m.range.first in it }) null else m.groupValues[1].toIntOrNull()
        } ?: anyBareRe.findAll(withoutExt)
            .firstOrNull { m -> consumed.none { m.range.first in it } }
            ?.groupValues?.get(1)?.toIntOrNull()

        val clean = withoutExt
            .let { stripTokens.replace(it, " ") }
            .let { if (bare != null) leadingBareRe.replace(it, " ") else it }
            .replace(Regex("""[\s_\-.]+"""), " ")
            .trim(' ', '-', '_', '.', '[', ']', '(', ')')

        return NumberHints(
            volume = volume,
            chapter = chapter,
            part = part,
            bare = bare,
            cleanTitle = clean,
        )
    }

    /**
     * Human label for a chapter, used when the UI has nothing better to show.
     * Falls back to the cleaned filename, then to the raw filename.
     */
    fun chapterLabel(entry: AudioEntry, index: Int): String {
        val hints = parse(entry.fileName)
        val number = hints.chapter ?: hints.bare ?: (index + 1)
        val title = hints.cleanTitle.takeIf { it.isNotBlank() && !it.all { c -> c.isDigit() } }
        return if (title != null) "$number. $title" else "Глава $number"
    }
}
