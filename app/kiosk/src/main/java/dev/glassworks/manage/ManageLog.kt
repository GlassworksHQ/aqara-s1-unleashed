/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at https://mozilla.org/MPL/2.0/. */

package dev.glassworks.manage

import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader

/** One line of the panel's log. [source] is what it is about, in words ("Voice", "Radio"). */
data class LogEntry(val timeMs: Long, val level: Level, val source: String, val text: String) {
    enum class Level { INFO, WARNING, ERROR }
}

/**
 * The log the Manage page shows: kept in the app's files, so it survives an app restart and a reboot,
 * for [keepMs] (7 days). Two files of at most [fileBytes] each: when the current one is full it becomes
 * the old one, and the old one goes. Text is redacted before it is written. Thread-safe.
 */
class ManageLog(
    private val dir: File,
    private val clock: () -> Long = System::currentTimeMillis,
    private val keepMs: Long = 7L * 24 * 3600 * 1000,
    private val fileBytes: Long = 256L * 1024,
) {
    private val current get() = File(dir, "log.0")
    private val old get() = File(dir, "log.1")

    @Synchronized
    fun append(e: LogEntry) {
        dir.mkdirs()
        prune()
        if (current.length() >= fileBytes) {
            old.delete()
            current.renameTo(old)
        }
        val line = "${e.timeMs}\t${e.level.name[0]}\t${clean(e.source)}\t${clean(Redact.text(e.text))}\n"
        current.appendText(line, Charsets.UTF_8)
    }

    /** Newest first, at most [limit]. [level]: only that level and worse. [search]: case-insensitive, in source or text. */
    @Synchronized
    fun read(level: LogEntry.Level = LogEntry.Level.INFO, search: String = "", limit: Int = 500): List<LogEntry> {
        prune()
        val since = clock() - keepMs
        val all = (readFile(old) + readFile(current)).filter { it.timeMs >= since && it.level >= level }
        val q = search.trim().lowercase()
        return all.asReversed().asSequence()
            .filter { q.isEmpty() || q in it.text.lowercase() || q in it.source.lowercase() }
            .take(limit).toList()
    }

    /** Takes lines older than [keepMs] off the disk, also when nothing new is written. Cheap when there are none. */
    private fun prune() {
        val since = clock() - keepMs
        for (f in listOf(old, current)) {
            if (!f.exists()) continue
            val first = f.bufferedReader(Charsets.UTF_8).use { it.readLine() }?.let(::parse) ?: continue
            if (first.timeMs >= since) continue
            val keep = f.readLines(Charsets.UTF_8).filter { (parse(it)?.timeMs ?: 0) >= since }
            if (keep.isEmpty()) f.delete() else f.writeText(keep.joinToString("\n", postfix = "\n"), Charsets.UTF_8)
        }
    }

    private fun readFile(f: File): List<LogEntry> = if (!f.exists()) emptyList() else f.readLines(Charsets.UTF_8).mapNotNull(::parse)

    private fun clean(s: String) = s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ')

    companion object {
        fun parse(line: String): LogEntry? {
            val f = line.split('\t', limit = 4)
            if (f.size < 4) return null
            val t = f[0].toLongOrNull() ?: return null
            val level = when (f[1]) { "W" -> LogEntry.Level.WARNING; "E" -> LogEntry.Level.ERROR; "I" -> LogEntry.Level.INFO; else -> return null }
            return LogEntry(t, level, f[2], f[3])
        }

        private val LOGCAT = Regex("^\\s*(\\d+)\\.(\\d{3})\\s+\\d+\\s+\\d+\\s+([VDIWEF])\\s+([^:]+?)\\s*:\\s?(.*)$")

        /**
         * One `logcat -v epoch` line (`1731456000.123  1234  1250 I S1Kiosk.Voice: text`) as an entry. [source]
         * names the tag in words, or returns null for a tag the log leaves out. Verbose and debug lines are
         * left out.
         */
        fun fromLogcat(line: String, source: (String) -> String?): LogEntry? {
            val m = LOGCAT.matchEntire(line) ?: return null
            val (sec, ms, prio, tag, text) = m.destructured
            val level = when (prio) { "I" -> LogEntry.Level.INFO; "W" -> LogEntry.Level.WARNING; "E", "F" -> LogEntry.Level.ERROR; else -> return null }
            val src = source(tag.trim()) ?: return null
            return LogEntry(sec.toLong() * 1000 + ms.toLong(), level, src, text)
        }
    }
}

/**
 * Copies the app's own log lines into [log] while the app runs: `logcat -v epoch -T 1`, which on
 * Android shows an app only its own lines. [source] picks the tags and names them.
 */
class LogcatTail(private val log: ManageLog, private val source: (String) -> String?, private val onError: (String) -> Unit = {}) {
    private var running = false
    private var process: Process? = null

    @Synchronized
    fun start() {
        if (running) return
        running = true
        Thread({
            try {
                // -T 1: from now on; earlier lines were copied by the previous run, or were before the app started.
                val p = ProcessBuilder("logcat", "-v", "epoch", "-T", "1").redirectErrorStream(true).start()
                // stop() may have run while logcat started: then this process is not wanted.
                val keep = synchronized(this) { if (running) { process = p; true } else false }
                if (!keep) { p.destroy(); return@Thread }
                BufferedReader(InputStreamReader(p.inputStream)).useLines { lines ->
                    for (l in lines) ManageLog.fromLogcat(l, source)?.let { runCatching { log.append(it) } }
                }
            } catch (e: Exception) {
                onError("logcat: ${e.message}")
            }
        }, "glassworks-log").apply { isDaemon = true; start() }
    }

    @Synchronized
    fun stop() {
        running = false
        process?.destroy()
        process = null
    }
}
