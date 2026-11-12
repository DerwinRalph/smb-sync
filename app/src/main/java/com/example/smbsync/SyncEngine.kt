package com.example.smbsync

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TreeSet

/** What both sides looked like for one path right after it was last synced. */
class Entry(val dir: Boolean, val lSize: Long, val lTime: Long, val rSize: Long, val rTime: Long) {
    companion object {
        fun dir() = Entry(true, 0, 0, 0, 0)
    }
}

/** Remembered snapshot of the last sync, keyed by lower-cased relative path. */
class SyncState(private val file: File) {
    val map = HashMap<String, Entry>()

    init {
        if (file.exists()) {
            try {
                file.forEachLine { line ->
                    val p = line.split('\t', limit = 6)
                    if (p.size == 6) map[p[5]] = Entry(p[0] == "D", p[1].toLong(), p[2].toLong(), p[3].toLong(), p[4].toLong())
                }
            } catch (_: Exception) {
                map.clear()
            }
        }
    }

    fun save() {
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w ->
            for ((k, e) in map) {
                w.write(if (e.dir) "D" else "F")
                w.write("\t${e.lSize}\t${e.lTime}\t${e.rSize}\t${e.rTime}\t$k\n")
            }
        }
        Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

class SyncAbort(msg: String) : Exception(msg)

enum class LogType { START, END, FAILED, UP, DOWN, DEL_PHONE, DEL_PC, FOLDER, CONFLICT, ERROR, INFO }

/** Live state of a running sync. Byte counts are 0 until the list of changes is known. */
data class Progress(
    val phase: String,
    val index: Int = 0,
    val count: Int = 0,
    val fileDone: Long = 0,
    val fileSize: Long = 0,
    val totalDone: Long = 0,
    val totalBytes: Long = 0,
    val speed: Long = 0,
) {
    /** Overall completion 0..1, by bytes when there is data to move, otherwise by item count. */
    val fraction: Float
        get() = when {
            totalBytes > 0 -> (totalDone.toDouble() / totalBytes).toFloat().coerceIn(0f, 1f)
            count > 0 -> ((index - 1).toFloat() / count).coerceIn(0f, 1f)
            else -> 0f
        }
    val fileFraction: Float get() = if (fileSize > 0) (fileDone.toDouble() / fileSize).toFloat().coerceIn(0f, 1f) else 0f
}

class SyncResult {
    var up = 0
    var down = 0
    var delLocal = 0
    var delRemote = 0
    var conflicts = 0
    var bytes = 0L
    var cancelled = false
    val errors = ArrayList<String>()

    fun summary(): String {
        val parts = ArrayList<String>()
        if (up > 0) parts += "$up sent to PC"
        if (down > 0) parts += "$down received"
        if (delLocal + delRemote > 0) parts += "${delLocal + delRemote} deleted"
        if (conflicts > 0) parts += if (conflicts == 1) "1 conflict" else "$conflicts conflicts"
        if (errors.isNotEmpty()) parts += if (errors.size == 1) "1 error" else "${errors.size} errors"
        var s = if (parts.isEmpty()) "Already in sync" else parts.joinToString(", ")
        if (bytes > 0) s += " (${fmtBytes(bytes)})"
        return if (cancelled) "Stopped – $s" else s
    }
}

fun fmtBytes(n: Long): String = when {
    n >= 1L shl 30 -> String.format(Locale.US, "%.2f GB", n / 1073741824.0)
    n >= 1L shl 20 -> String.format(Locale.US, "%.1f MB", n / 1048576.0)
    n >= 1L shl 10 -> String.format(Locale.US, "%.0f KB", n / 1024.0)
    else -> "$n B"
}

/**
 * Compares the phone folder with the PC folder and applies the differences.
 * Has no Android dependencies so it can be exercised by plain unit tests.
 */
class SyncEngine(
    private val pair: SyncPair,
    private val smb: Remote,
    stateFile: File,
    private val log: (LogType, String, Long) -> Unit,
    private val progress: (Progress) -> Unit,
    private val stopped: () -> Boolean,
    /** Called for every phone file that was added, replaced, renamed or deleted. */
    private val onLocalChange: (File) -> Unit = {},
) {
    private class Action(val type: LogType, val path: String, val bytes: Long, val run: () -> Unit)

    private val root = File(pair.localPath)
    private val state = SyncState(stateFile)
    private val res = SyncResult()
    private val canUp = pair.mode != SyncMode.TO_PHONE
    private val canDown = pair.mode != SyncMode.TO_PC
    private val del = pair.syncDeletions
    private val actions = ArrayList<Action>()
    private val dirDeletes = ArrayList<Triple<String, Boolean, String>>() // key, isLocal, path
    private val remoteDirs = HashSet<String>()

    // progress bookkeeping
    private var phase = ""
    private var index = 0
    private var totalBytes = 0L
    private var doneBytes = 0L      // bytes of finished actions
    private var innerBase = 0L      // finished part of the current multi-step action
    private var fileDone = 0L
    private var fileSize = 0L
    private var lastDone = 0L
    private var moved = 0L          // bytes actually transferred, for the speed figure
    private var sampleTime = 0L
    private var sampleMoved = 0L
    private var speed = 0L
    private var lastEmit = 0L

    fun run(): SyncResult {
        if (pair.localPath.isBlank()) throw SyncAbort("Pair is not fully set up")
        if (!root.isDirectory) {
            if (state.map.isEmpty() && root.mkdirs()) log(LogType.INFO, "Created phone folder", 0) else
                throw SyncAbort("Phone folder not found: ${pair.localPath}")
        }
        try {
            progress(Progress("Connecting…"))
            smb.connect()
            if (!smb.baseExists()) {
                if (state.map.isEmpty() && canUp) smb.mkdirs("") else
                    throw SyncAbort("Folder not found on the PC")
            }

            progress(Progress("Scanning phone…"))
            val unknown = ArrayList<String>()
            val local = HashMap<String, Meta>()
            scanLocal(root, "", local, unknown)
            progress(Progress("Scanning PC…"))
            val remote = HashMap<String, Meta>()
            scanRemote("", remote, unknown)
            remote.values.filter { it.dir }.forEach { remoteDirs += key(it.path) }

            // Never let a side that suddenly looks empty wipe the other one.
            val known = state.map.values.count { !it.dir }
            if (del && known >= 5) {
                if (local.values.none { !it.dir }) throw SyncAbort(
                    "Safety stop: phone folder looks empty but $known files were synced before. Nothing was deleted."
                )
                if (remote.values.none { !it.dir }) throw SyncAbort(
                    "Safety stop: PC folder looks empty but $known files were synced before. Nothing was deleted."
                )
            }

            plan(local, remote, unknown.map { key(it) })
            execute()
            return res
        } finally {
            try {
                state.save()
            } catch (_: Exception) {
            }
            smb.close()
        }
    }

    private fun key(p: String) = p.lowercase(Locale.ROOT)

    private val excludedPaths = pair.excludes.filter { it.startsWith("/") }
        .map { key(cleanPath(it)) }.filter { it.isNotEmpty() }
    private val excludedNames = pair.excludes.filter { !it.startsWith("/") && it.isNotBlank() }.map { pattern ->
        Regex(pattern.trim().split('*').joinToString(".*") { part -> part.split('?').joinToString(".") { Regex.escape(it) } }, RegexOption.IGNORE_CASE)
    }

    /** True for anything the user chose to leave out of this pair. */
    private fun excluded(rel: String, name: String): Boolean {
        val k = key(rel)
        if (excludedPaths.any { k == it || k.startsWith("$it/") }) return true
        return excludedNames.any { it.matches(name) }
    }

    private fun ignored(name: String, hidden: Boolean): Boolean {
        if (name.endsWith(TMP_SUFFIX)) return true
        if (name.startsWith("~$")) return true // Office lock files, only exist while a document is open
        when (name.lowercase(Locale.ROOT)) {
            "desktop.ini", "thumbs.db", "\$recycle.bin", "system volume information", ".ds_store" -> return true
        }
        return pair.skipHidden && (hidden || name.startsWith("."))
    }

    private fun scanLocal(dir: File, rel: String, out: HashMap<String, Meta>, unknown: MutableList<String>) {
        if (stopped()) throw SyncAbort("Stopped")
        val kids = dir.listFiles()
        if (kids == null) {
            if (rel.isEmpty()) throw SyncAbort("Can't read the phone folder")
            unknown += rel
            return
        }
        for (f in kids) {
            if (ignored(f.name, false)) continue
            val r = if (rel.isEmpty()) f.name else "$rel/${f.name}"
            if (excluded(r, f.name)) continue
            if (f.isDirectory) {
                if (!pair.subfolders) continue
                out[key(r)] = Meta(r, true, 0, 0)
                scanLocal(f, r, out, unknown)
            } else if (f.isFile) {
                out[key(r)] = Meta(r, false, f.length(), f.lastModified())
            }
        }
    }

    private fun scanRemote(rel: String, out: HashMap<String, Meta>, unknown: MutableList<String>) {
        if (stopped()) throw SyncAbort("Stopped")
        val kids = try {
            smb.list(rel)
        } catch (e: Exception) {
            if (rel.isEmpty() || !smb.isConnected) throw e
            log(LogType.ERROR, "$rel — can't read this PC folder, skipped (${friendlyError(e)})", 0)
            unknown += rel
            return
        }
        for (m in kids) {
            if (ignored(m.name, m.hidden) || excluded(m.path, m.name)) continue
            if (m.dir) {
                if (!pair.subfolders) continue
                out[key(m.path)] = m
                scanRemote(m.path, out, unknown)
            } else {
                out[key(m.path)] = m
            }
        }
    }

    private fun plan(local: Map<String, Meta>, remote: Map<String, Meta>, unknown: List<String>) {
        val keys = TreeSet<String>().apply { addAll(local.keys); addAll(remote.keys); addAll(state.map.keys) }
        for (k in keys) {
            if (unknown.any { k == it || k.startsWith("$it/") }) continue
            val l = local[k]
            val r = remote[k]
            if (l == null && r == null) {
                state.map.remove(k)
                continue
            }
            if (l != null && r != null && l.dir != r.dir) {
                res.errors += "${l.path}: is a folder on one side and a file on the other"
                log(LogType.ERROR, "${l.path} — is a folder on one side and a file on the other", 0)
                continue
            }
            val isDir = l?.dir ?: r!!.dir
            val s = state.map[k]?.takeIf { it.dir == isDir }
            if (isDir) planDir(k, l, r, s) else planFile(k, l, r, s)
        }
    }

    private fun planDir(k: String, l: Meta?, r: Meta?, s: Entry?) {
        when {
            l != null && r != null -> state.map[k] = Entry.dir()
            l != null ->
                if (s != null && canDown && del) dirDeletes += Triple(k, true, l.path)
                else if (canUp) actions += Action(LogType.FOLDER, l.path, 0) {
                    ensureRemoteDir(l.path)
                    state.map[k] = Entry.dir()
                }
            r != null ->
                if (s != null && canUp && del) dirDeletes += Triple(k, false, r.path)
                else if (canDown) actions += Action(LogType.FOLDER, r.path, 0) {
                    File(root, r.path).mkdirs()
                    state.map[k] = Entry.dir()
                }
        }
    }

    private fun planFile(k: String, l: Meta?, r: Meta?, s: Entry?) {
        val lChanged = l != null && (s == null || s.lSize != l.size || s.lTime != l.mtime)
        val rChanged = r != null && (s == null || s.rSize != r.size || s.rTime != r.mtime)
        when {
            l != null && r != null -> when {
                !lChanged && !rChanged -> {}
                // First time we see this file on both sides, or both changed to the same thing.
                (s == null || (lChanged && rChanged)) && l.size == r.size &&
                    (s == null || Math.abs(l.mtime - r.mtime) <= 2000) ->
                    state.map[k] = Entry(false, l.size, l.mtime, r.size, r.mtime)
                lChanged && rChanged -> planConflict(k, l, r)
                lChanged -> if (canUp) actions += upload(k, l)
                else -> if (canDown) actions += download(k, r)
            }
            l != null ->
                if (s != null && !lChanged) {
                    // Unchanged on the phone, gone from the PC: it was deleted on the PC.
                    if (canDown && del) actions += Action(LogType.DEL_PHONE, l.path, 0) {
                        val f = File(root, l.path)
                        if (f.exists() && !f.delete()) throw IllegalStateException("could not delete")
                        onLocalChange(f)
                        state.map.remove(k)
                        res.delLocal++
                    } else if (canUp) actions += upload(k, l)
                } else if (canUp) actions += upload(k, l)
            r != null ->
                if (s != null && !rChanged) {
                    if (canUp && del) actions += Action(LogType.DEL_PC, r.path, 0) {
                        smb.deleteFile(r.path)
                        state.map.remove(k)
                        res.delRemote++
                    } else if (canDown) actions += download(k, r)
                } else if (canDown) actions += download(k, r)
        }
    }

    private fun planConflict(k: String, l: Meta, r: Meta) {
        val choice = when (pair.mode) {
            SyncMode.TO_PC -> ConflictPolicy.PHONE_WINS
            SyncMode.TO_PHONE -> ConflictPolicy.PC_WINS
            SyncMode.TWO_WAY -> pair.conflict
        }
        when (choice) {
            ConflictPolicy.PHONE_WINS -> actions += upload(k, l)
            ConflictPolicy.PC_WINS -> actions += download(k, r)
            ConflictPolicy.NEWER_WINS -> actions += if (l.mtime >= r.mtime) upload(k, l) else download(k, r)
            ConflictPolicy.SKIP -> {
                res.conflicts++
                log(LogType.CONFLICT, "${l.path} — changed on both sides, skipped", 0)
            }
            ConflictPolicy.KEEP_BOTH -> actions += Action(LogType.CONFLICT, l.path, l.size + r.size) {
                val f = File(root, l.path)
                val stamp = SimpleDateFormat("yyyy-MM-dd HHmmss", Locale.US).format(Date())
                val base = f.name.substringBeforeLast('.', f.name)
                val ext = if (f.name.contains('.')) "." + f.name.substringAfterLast('.') else ""
                val copy = File(f.parentFile, "$base (phone copy $stamp)$ext")
                if (!f.renameTo(copy)) throw IllegalStateException("could not rename phone copy")
                onLocalChange(copy)
                doDownload(k, r)
                innerBase += r.size
                val parent = l.path.substringBeforeLast('/', "")
                val cRel = if (parent.isEmpty()) copy.name else "$parent/${copy.name}"
                doUpload(key(cRel), Meta(cRel, false, copy.length(), copy.lastModified()))
                res.conflicts++
            }
        }
    }

    private fun ensureRemoteDir(relDir: String) {
        if (relDir.isEmpty() || !remoteDirs.add(key(relDir))) return
        smb.mkdirs(relDir)
    }

    // ------------------------------------------------------------ progress

    private fun emit(force: Boolean) {
        val now = System.nanoTime() / 1_000_000
        if (now - sampleTime >= 1000) {
            val inst = (moved - sampleMoved) * 1000 / (now - sampleTime)
            speed = if (speed == 0L) inst else (speed + inst) / 2
            sampleTime = now
            sampleMoved = moved
        }
        if (!force && now - lastEmit < 200) return
        lastEmit = now
        progress(
            Progress(phase, index, actions.size, fileDone, fileSize, minOf(doneBytes + innerBase + fileDone, totalBytes), totalBytes, speed)
        )
    }

    private fun startFile(size: Long) {
        fileSize = size
        fileDone = 0
        lastDone = 0
        emit(true)
    }

    private fun onBytes(done: Long) {
        moved += done - lastDone
        lastDone = done
        fileDone = done
        emit(false)
    }

    // ------------------------------------------------------------ transfers

    private fun upload(k: String, l: Meta) = Action(LogType.UP, l.path, l.size) { doUpload(k, l) }
    private fun download(k: String, r: Meta) = Action(LogType.DOWN, r.path, r.size) { doDownload(k, r) }

    private fun doUpload(k: String, l: Meta) {
        startFile(l.size)
        ensureRemoteDir(l.path.substringBeforeLast('/', ""))
        FileInputStream(File(root, l.path)).use { input ->
            smb.upload(l.path, input, l.mtime, ::onBytes, stopped)
        }
        val rm = smb.stat(l.path)
        state.map[k] = Entry(false, l.size, l.mtime, rm.size, rm.mtime)
        res.up++
        res.bytes += l.size
    }

    private fun doDownload(k: String, r: Meta) {
        startFile(r.size)
        val target = File(root, r.path)
        target.parentFile?.mkdirs()
        val tmp = File(target.parentFile, target.name + TMP_SUFFIX)
        try {
            FileOutputStream(tmp).use { out ->
                smb.download(r.path, out, ::onBytes, stopped)
            }
            tmp.setLastModified(r.mtime)
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            if (tmp.exists()) tmp.delete()
        }
        onLocalChange(target)
        state.map[k] = Entry(false, target.length(), target.lastModified(), r.size, r.mtime)
        res.down++
        res.bytes += r.size
    }

    private fun verb(t: LogType) = when (t) {
        LogType.UP -> "Sending"
        LogType.DOWN -> "Receiving"
        LogType.DEL_PHONE -> "Deleting on phone"
        LogType.DEL_PC -> "Deleting on PC"
        LogType.FOLDER -> "Creating folder"
        LogType.CONFLICT -> "Resolving conflict"
        else -> ""
    }

    private fun execute() {
        val n = actions.size
        totalBytes = actions.sumOf { it.bytes }
        if (n > 0) log(LogType.INFO, "$n changes to apply, ${fmtBytes(totalBytes)} to transfer", 0)
        sampleTime = System.nanoTime() / 1_000_000
        for ((i, a) in actions.withIndex()) {
            if (stopped()) {
                res.cancelled = true
                return
            }
            index = i + 1
            phase = "${verb(a.type)}  ${a.path}"
            fileSize = 0
            fileDone = 0
            innerBase = 0
            emit(true)
            try {
                a.run()
                log(a.type, a.path, a.bytes)
            } catch (e: Exception) {
                if (stopped()) {
                    res.cancelled = true
                    return
                }
                val msg = friendlyError(e)
                res.errors += "${a.path}: $msg"
                log(LogType.ERROR, "${a.path} — ${verb(a.type).lowercase(Locale.ROOT)} failed: $msg", 0)
                if (!smb.isConnected) throw SyncAbort("Connection to the PC was lost (${res.summary()})")
            }
            doneBytes += a.bytes
            innerBase = 0
            fileDone = 0
            if (i % 20 == 19) try {
                state.save()
            } catch (_: Exception) {
            }
        }
        // Folder deletions last, deepest first, and only if the folder ended up empty.
        for ((k, isLocal, path) in dirDeletes.sortedByDescending { it.third.count { c -> c == '/' } }) {
            try {
                if (isLocal) {
                    val d = File(root, path)
                    if (d.list()?.isEmpty() == true && d.delete()) {
                        state.map.remove(k)
                        log(LogType.DEL_PHONE, "$path/", 0)
                    }
                } else {
                    smb.deleteEmptyDir(path)
                    state.map.remove(k)
                    log(LogType.DEL_PC, "$path/", 0)
                }
            } catch (_: Exception) {
            }
        }
    }
}
