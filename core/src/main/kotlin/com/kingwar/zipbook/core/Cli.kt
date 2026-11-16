package com.kingwar.zipbook.core

import java.io.File

/**
 * Desktop entry point for inspecting an archive without a phone.
 *
 * Usage: `inspect <archive.zip> [more.zip ...]`
 *
 * Prints the grouping exactly as the app would compute it, which makes a
 * misgrouped archive diagnosable from a terminal instead of by guesswork on a device.
 */
object Cli {

    @JvmStatic
    fun main(args: Array<String>) {
        if (args.isEmpty()) {
            println("usage: inspect <archive.zip> [more.zip ...]")
            println("       inspect --extract <outDir> <archive.zip>")
            return
        }
        if (args[0] == "--extract") {
            require(args.size >= 3) { "usage: inspect --extract <outDir> <archive.zip>" }
            extract(File(args[1]), File(args[2]))
            return
        }
        var failures = 0
        for (path in args) {
            val file = File(path)
            println("=".repeat(72))
            println("ARCHIVE: ${file.name}  (${humanBytes(file.length())})")
            if (!file.isFile) {
                println("  !! not a file")
                failures++
                continue
            }
            try {
                describe(ZipReader.readLibrary(file))
            } catch (e: Exception) {
                println("  !! ${e::class.simpleName}: ${e.message}")
                failures++
            }
        }
        println("=".repeat(72))
        if (failures > 0) {
            println("FAILURES: $failures")
            throw IllegalStateException("$failures archive(s) could not be read")
        }
        println("OK")
    }

    /**
     * Extracts every chapter in playback order, using the same index-prefixed naming the
     * Android importer uses, so the on-disk result can be checked with ffprobe.
     */
    private fun extract(outDir: File, archive: File) {
        val lib = ZipReader.readLibrary(archive)
        val byPath = ZipReader.listEntries(archive).associateBy { it.name.trimStart('/') }
        outDir.mkdirs()
        var written = 0
        ZipSource.of(archive).use { source ->
            lib.books.forEachIndexed { bookIndex, book ->
                val bookDir = File(outDir, "%02d_%s".format(bookIndex, book.title.replace('/', '_')))
                bookDir.mkdirs()
                book.chapters.forEach { chapter ->
                    val raw = byPath.getValue(chapter.entry.path)
                    val out = File(bookDir, "%05d_%s".format(chapter.index, chapter.entry.fileName))
                    ZipReader.openEntry(source, raw).use { input ->
                        out.outputStream().use { input.copyTo(it, 128 * 1024) }
                    }
                    written++
                    println("${out.path}  ${humanBytes(out.length())}")
                }
            }
        }
        println("EXTRACTED $written file(s) to ${outDir.path}")
    }

    private fun describe(lib: ArchiveLibrary) {
        println("  layout:   ${lib.layout}")
        println("  books:    ${lib.books.size}")
        println("  chapters: ${lib.chapterCount}")
        if (lib.skipped.isNotEmpty()) {
            println("  skipped:  ${lib.skipped.size}")
            lib.skipped.take(8).forEach { println("    - ${it.path}  [${it.reason}]") }
        }
        lib.books.forEach { book ->
            val seriesPart = book.series?.let { s ->
                " | series='$s'" + (book.seriesIndex?.let { " #$it" } ?: "")
            }.orEmpty()
            println("  BOOK '${book.title}'$seriesPart  dir='${book.sourceDirectory}'  ${humanBytes(book.totalBytes)}")
            book.chapters.forEach { ch ->
                println("      ${"%3d".format(ch.number)}  ${ch.title.padEnd(28)}  ${ch.entry.path}")
            }
        }
    }

    private fun humanBytes(b: Long): String = when {
        b >= 1L shl 30 -> "%.2f GiB".format(b.toDouble() / (1L shl 30))
        b >= 1L shl 20 -> "%.1f MiB".format(b.toDouble() / (1L shl 20))
        b >= 1L shl 10 -> "%.0f KiB".format(b.toDouble() / (1L shl 10))
        else -> "$b B"
    }
}
