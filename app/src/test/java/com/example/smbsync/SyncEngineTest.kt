package com.example.smbsync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.file.Files

/** Stands in for the PC: a plain folder on disk behind the same interface the SMB client implements. */
private class FakePc(private val dir: File) : Remote {
    override var isConnected = false
    var failList = false

    private fun f(rel: String) = if (rel.isEmpty()) dir else File(dir, rel)
    override fun connect() {
        isConnected = true
    }

    override fun baseExists() = dir.isDirectory
    override fun list(relDir: String): List<Meta> {
        if (failList) throw java.io.IOException("simulated failure")
        return f(relDir).listFiles()!!.map {
            val rel = if (relDir.isEmpty()) it.name else "$relDir/${it.name}"
            Meta(rel, it.isDirectory, if (it.isDirectory) 0 else it.length(), it.lastModified())
        }
    }

    override fun stat(rel: String) = Meta(rel, false, f(rel).length(), f(rel).lastModified())
    override fun mkdirs(relDir: String) {
        f(relDir).mkdirs()
    }

    override fun download(rel: String, out: OutputStream, onBytes: (Long) -> Unit, stopped: () -> Boolean) {
        val b = f(rel).readBytes()
        out.write(b)
        onBytes(b.size.toLong())
    }

    override fun upload(rel: String, input: InputStream, mtime: Long, onBytes: (Long) -> Unit, stopped: () -> Boolean) {
        val b = input.readBytes()
        f(rel).writeBytes(b)
        f(rel).setLastModified(mtime)
        onBytes(b.size.toLong())
    }

    override fun deleteFile(rel: String) {
        if (!f(rel).delete()) throw java.io.IOException("delete failed")
    }

    override fun deleteEmptyDir(rel: String) {
        if (!f(rel).delete()) throw java.io.IOException("not empty")
    }

    override fun close() {
        isConnected = false
    }
}

class SyncEngineTest {
    private lateinit var tmp: File
    private lateinit var phone: File
    private lateinit var pc: File
    private lateinit var state: File
    private var clock = 1_700_000_000_000L
    private val logs = ArrayList<String>()
    private val progress = ArrayList<Progress>()

    @Before
    fun setUp() {
        tmp = Files.createTempDirectory("smbsync-test").toFile()
        phone = File(tmp, "phone").apply { mkdirs() }
        pc = File(tmp, "pc").apply { mkdirs() }
        state = File(tmp, "state.tsv")
    }

    @After
    fun tearDown() {
        tmp.deleteRecursively()
    }

    /** Writes a file with a distinct, whole-second modified time so edits are always detectable. */
    private fun write(root: File, rel: String, text: String) {
        val f = File(root, rel)
        f.parentFile!!.mkdirs()
        f.writeText(text)
        clock += 10_000
        f.setLastModified(clock)
    }

    private fun read(root: File, rel: String) = File(root, rel).readText()
    private fun has(root: File, rel: String) = File(root, rel).exists()
    private fun files(root: File) =
        root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).path.replace('\\', '/') }.sorted().toList()

    private fun sync(
        mode: SyncMode = SyncMode.TWO_WAY, deletions: Boolean = false,
        conflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH, subfolders: Boolean = true, fake: FakePc = FakePc(pc),
        excludes: List<String> = emptyList(),
    ): SyncResult {
        val pair = SyncPair(
            id = "t", localPath = phone.path, mode = mode, syncDeletions = deletions,
            conflict = conflict, subfolders = subfolders, excludes = excludes,
        )
        return SyncEngine(pair, fake, state, { t, s, _ -> logs += "$t $s" }, { progress += it }, { false }).run()
    }

    // ---------------------------------------------------------------- first sync

    @Test
    fun firstSync_mergesBothSides_andDeletesNothing() {
        write(phone, "a.txt", "from phone")
        write(phone, "sub/b.txt", "phone sub")
        write(pc, "c.txt", "from pc")
        write(pc, "deep/er/d.txt", "pc deep")

        val r = sync(deletions = true)

        val all = listOf("a.txt", "c.txt", "deep/er/d.txt", "sub/b.txt")
        assertEquals(all, files(phone))
        assertEquals(all, files(pc))
        assertEquals("from phone", read(pc, "a.txt"))
        assertEquals("pc deep", read(phone, "deep/er/d.txt"))
        assertEquals(2, r.up)
        assertEquals(2, r.down)
        assertEquals(0, r.delLocal + r.delRemote)
        assertTrue(r.errors.isEmpty())
    }

    @Test
    fun firstSync_sameNameSameSize_isLeftAlone() {
        write(phone, "same.txt", "12345")
        write(pc, "same.txt", "abcde")
        val r = sync()
        assertEquals(0, r.up + r.down + r.conflicts)
        assertEquals("12345", read(phone, "same.txt"))
        assertEquals("abcde", read(pc, "same.txt"))
    }

    @Test
    fun firstSync_sameNameDifferentSize_keepsBothVersions() {
        write(phone, "photo.jpg", "phone version")
        write(pc, "photo.jpg", "pc")
        val r = sync()
        assertEquals(1, r.conflicts)
        assertEquals("pc", read(phone, "photo.jpg"))
        assertEquals("pc", read(pc, "photo.jpg"))
        val copy = files(phone).single { it.startsWith("photo (phone copy") }
        assertTrue(copy.endsWith(".jpg"))
        assertEquals("phone version", read(phone, copy))
        assertEquals("phone version", read(pc, copy))
    }

    @Test
    fun secondSync_withNoChanges_doesNothing() {
        write(phone, "a.txt", "one")
        write(pc, "b.txt", "two")
        sync()
        val r = sync(deletions = true)
        assertEquals("Already in sync", r.summary())
    }

    @Test
    fun mtimeIsCarriedAcross() {
        write(phone, "a.txt", "one")
        write(pc, "b.txt", "two")
        sync()
        assertEquals(File(phone, "a.txt").lastModified(), File(pc, "a.txt").lastModified())
        assertEquals(File(pc, "b.txt").lastModified(), File(phone, "b.txt").lastModified())
    }

    // ---------------------------------------------------------------- two-way

    @Test
    fun twoWay_newAndEditedFilesTravelBothDirections() {
        write(phone, "a.txt", "v1")
        write(pc, "b.txt", "v1")
        sync()

        write(phone, "a.txt", "v2 edited on phone")
        write(pc, "b.txt", "v2 edited on pc")
        write(phone, "new-phone.txt", "np")
        write(pc, "new-pc.txt", "npc")
        val r = sync()

        assertEquals("v2 edited on phone", read(pc, "a.txt"))
        assertEquals("v2 edited on pc", read(phone, "b.txt"))
        assertTrue(has(pc, "new-phone.txt"))
        assertTrue(has(phone, "new-pc.txt"))
        assertEquals(2, r.up)
        assertEquals(2, r.down)
        assertEquals(0, r.conflicts)
    }

    @Test
    fun twoWay_deletionsOff_deletedFileComesBack() {
        write(phone, "a.txt", "keep me")
        sync()
        File(phone, "a.txt").delete()
        val r = sync(deletions = false)
        assertEquals("keep me", read(phone, "a.txt"))
        assertTrue(has(pc, "a.txt"))
        assertEquals(1, r.down)
    }

    @Test
    fun twoWay_deletionsOn_deletePropagatesEachWay() {
        write(phone, "p.txt", "p")
        write(phone, "q.txt", "q")
        write(phone, "keep.txt", "k")
        sync()
        File(phone, "p.txt").delete()
        File(pc, "q.txt").delete()
        val r = sync(deletions = true)
        assertEquals(listOf("keep.txt"), files(phone))
        assertEquals(listOf("keep.txt"), files(pc))
        assertEquals(1, r.delLocal)
        assertEquals(1, r.delRemote)
    }

    @Test
    fun twoWay_editBeatsDelete() {
        write(phone, "a.txt", "v1")
        sync()
        File(pc, "a.txt").delete()
        write(phone, "a.txt", "v2 edited after pc deleted it")
        sync(deletions = true)
        assertEquals("v2 edited after pc deleted it", read(pc, "a.txt"))
        assertTrue(has(phone, "a.txt"))
    }

    @Test
    fun twoWay_emptyFolderDeletedWithItsFiles() {
        write(phone, "album/x.txt", "x")
        write(phone, "keep.txt", "k")
        sync()
        File(pc, "album").deleteRecursively()
        sync(deletions = true)
        assertFalse(has(phone, "album"))
        assertTrue(has(phone, "keep.txt"))
    }

    @Test
    fun twoWay_emptyFoldersAreCreated() {
        File(phone, "empty-on-phone").mkdirs()
        File(pc, "empty-on-pc/nested").mkdirs()
        sync()
        assertTrue(File(pc, "empty-on-phone").isDirectory)
        assertTrue(File(phone, "empty-on-pc/nested").isDirectory)
    }

    // ---------------------------------------------------------------- conflicts after a sync

    private fun editBoth() {
        write(phone, "doc.txt", "original")
        sync()
        write(pc, "doc.txt", "pc edit (older)")
        write(phone, "doc.txt", "phone edit, newer")
    }

    @Test
    fun conflict_newerWins() {
        editBoth()
        sync(conflict = ConflictPolicy.NEWER_WINS)
        assertEquals("phone edit, newer", read(pc, "doc.txt"))
        assertEquals("phone edit, newer", read(phone, "doc.txt"))
    }

    @Test
    fun conflict_pcWins() {
        editBoth()
        sync(conflict = ConflictPolicy.PC_WINS)
        assertEquals("pc edit (older)", read(phone, "doc.txt"))
    }

    @Test
    fun conflict_phoneWins() {
        editBoth()
        sync(conflict = ConflictPolicy.PHONE_WINS)
        assertEquals("phone edit, newer", read(pc, "doc.txt"))
    }

    @Test
    fun conflict_skip_touchesNeitherSide() {
        editBoth()
        val r = sync(conflict = ConflictPolicy.SKIP)
        assertEquals(1, r.conflicts)
        assertEquals("phone edit, newer", read(phone, "doc.txt"))
        assertEquals("pc edit (older)", read(pc, "doc.txt"))
    }

    @Test
    fun conflict_keepBoth_losesNothing() {
        editBoth()
        sync(conflict = ConflictPolicy.KEEP_BOTH)
        assertEquals("pc edit (older)", read(phone, "doc.txt"))
        val copy = files(pc).single { it.startsWith("doc (phone copy") }
        assertEquals("phone edit, newer", read(pc, copy))
        assertEquals(files(phone), files(pc))
        assertEquals("Already in sync", sync().summary())
    }

    // ---------------------------------------------------------------- phone -> PC only

    @Test
    fun toPc_onlyPhoneChangesTravel() {
        write(phone, "a.txt", "phone")
        write(pc, "pc-only.txt", "pc")
        val r = sync(SyncMode.TO_PC)
        assertTrue(has(pc, "a.txt"))
        assertFalse("PC file must not come to the phone", has(phone, "pc-only.txt"))
        assertTrue("PC file must not be deleted", has(pc, "pc-only.txt"))
        assertEquals(1, r.up)
        assertEquals(0, r.down)
    }

    @Test
    fun toPc_pcEditIsIgnored_phoneEditOverwrites() {
        write(phone, "a.txt", "v1")
        sync(SyncMode.TO_PC)
        write(pc, "a.txt", "edited on pc")
        sync(SyncMode.TO_PC)
        assertEquals("v1", read(phone, "a.txt"))
        assertEquals("edited on pc", read(pc, "a.txt"))
        write(phone, "a.txt", "v2 from phone")
        sync(SyncMode.TO_PC)
        assertEquals("v2 from phone", read(pc, "a.txt"))
    }

    @Test
    fun toPc_deletions_followThePhoneOnly() {
        write(phone, "a.txt", "a")
        write(phone, "b.txt", "b")
        sync(SyncMode.TO_PC, deletions = true)
        File(phone, "a.txt").delete()   // should be removed from the PC
        File(pc, "b.txt").delete()      // PC-side delete: phone copy must survive and be re-sent
        val r = sync(SyncMode.TO_PC, deletions = true)
        assertFalse(has(pc, "a.txt"))
        assertTrue(has(phone, "b.txt"))
        assertTrue(has(pc, "b.txt"))
        assertEquals(0, r.delLocal)
    }

    @Test
    fun toPc_deletionsOff_phoneDeleteLeavesPcCopy() {
        write(phone, "a.txt", "a")
        sync(SyncMode.TO_PC)
        File(phone, "a.txt").delete()
        sync(SyncMode.TO_PC, deletions = false)
        assertTrue(has(pc, "a.txt"))
        assertFalse(has(phone, "a.txt"))
    }

    // ---------------------------------------------------------------- PC -> phone only

    @Test
    fun toPhone_onlyPcChangesTravel() {
        write(phone, "phone-only.txt", "phone")
        write(pc, "a.txt", "pc")
        val r = sync(SyncMode.TO_PHONE)
        assertTrue(has(phone, "a.txt"))
        assertFalse(has(pc, "phone-only.txt"))
        assertTrue(has(phone, "phone-only.txt"))
        assertEquals(0, r.up)
        assertEquals(1, r.down)
    }

    @Test
    fun toPhone_deletions_followThePcOnly() {
        write(pc, "a.txt", "a")
        write(pc, "b.txt", "b")
        sync(SyncMode.TO_PHONE, deletions = true)
        File(pc, "a.txt").delete()
        File(phone, "b.txt").delete()
        val r = sync(SyncMode.TO_PHONE, deletions = true)
        assertFalse(has(phone, "a.txt"))
        assertTrue(has(pc, "b.txt"))
        assertTrue(has(phone, "b.txt"))
        assertEquals(0, r.delRemote)
    }

    // ---------------------------------------------------------------- safety

    @Test
    fun safetyStop_whenPhoneFolderSuddenlyEmpty() {
        for (i in 1..6) write(phone, "f$i.txt", "data $i")
        sync(deletions = true)
        phone.listFiles()!!.forEach { it.delete() }
        try {
            sync(deletions = true)
            fail("expected a safety stop")
        } catch (e: SyncAbort) {
            assertTrue(e.message!!.contains("Safety stop"))
        }
        assertEquals(6, files(pc).size)
    }

    @Test
    fun safetyStop_whenPcFolderSuddenlyEmpty() {
        for (i in 1..6) write(phone, "f$i.txt", "data $i")
        sync(deletions = true)
        pc.listFiles()!!.forEach { it.delete() }
        try {
            sync(deletions = true)
            fail("expected a safety stop")
        } catch (e: SyncAbort) {
            assertTrue(e.message!!.contains("Safety stop"))
        }
        assertEquals(6, files(phone).size)
    }

    @Test
    fun pcListingFailure_deletesNothing() {
        for (i in 1..6) write(phone, "f$i.txt", "data $i")
        sync(deletions = true)
        try {
            sync(deletions = true, fake = FakePc(pc).apply { failList = true })
            fail("expected the sync to fail")
        } catch (_: Exception) {
        }
        assertEquals(6, files(phone).size)
        assertEquals(6, files(pc).size)
    }

    // ---------------------------------------------------------------- filters

    @Test
    fun hiddenAndTempFilesAreSkipped() {
        write(phone, ".nomedia", "")
        write(phone, ".thumbnails/t.jpg", "thumb")
        write(phone, "partial.bin$TMP_SUFFIX", "half")
        write(phone, "real.txt", "real")
        write(pc, "desktop.ini", "ini")
        write(pc, "Thumbs.db", "db")
        sync()
        assertEquals(listOf("desktop.ini", "real.txt", "Thumbs.db").sorted(), files(pc).sorted())
        assertFalse(has(phone, "desktop.ini"))
    }

    @Test
    fun subfoldersOff_onlyTopLevelFilesSync() {
        write(phone, "top.txt", "t")
        write(phone, "sub/inner.txt", "i")
        sync(subfolders = false)
        assertEquals(listOf("top.txt"), files(pc))
    }

    @Test
    fun nameCaseDifference_isTreatedAsTheSameFile() {
        write(phone, "Photo.JPG", "12345")
        write(pc, "photo.jpg", "12345")
        val r = sync()
        assertEquals(0, r.up + r.down)
    }

    // ---------------------------------------------------------------- exclusions

    @Test
    fun excludedFolder_isIgnoredOnBothSides() {
        write(phone, "keep.txt", "k")
        write(phone, "Private/secret.txt", "s")
        write(phone, "Work/Private/deep.txt", "not excluded: different path")
        write(pc, "private/from-pc.txt", "p")
        write(pc, "other.txt", "o")
        sync(excludes = listOf("/Private"))
        assertEquals(listOf("Work/Private/deep.txt", "keep.txt", "other.txt").sorted(), files(pc).filter { !it.startsWith("private/") }.sorted())
        assertFalse(has(pc, "Private/secret.txt"))
        assertFalse(has(phone, "Private/from-pc.txt"))
        assertTrue(has(pc, "Work/Private/deep.txt"))
    }

    @Test
    fun excludedNamePattern_matchesAnywhere() {
        write(phone, "a/__Previews/x.bin", "x")
        write(phone, "a/doc.pdf", "d")
        write(phone, "b/cache.tmp", "c")
        write(phone, "b/photo.jpg", "p")
        write(phone, "~\$lock.docx", "office lock file, always skipped")
        sync(excludes = listOf("__previews", "*.TMP"))
        assertEquals(listOf("a/doc.pdf", "b/photo.jpg"), files(pc))
    }

    @Test
    fun excludingAfterSync_deletesNothing() {
        for (i in 1..6) write(phone, "Album/f$i.jpg", "data $i")
        write(phone, "keep.txt", "k")
        sync(deletions = true)
        // Folder is now excluded and then removed from the phone: the PC copy must survive.
        File(phone, "Album").deleteRecursively()
        val r = sync(deletions = true, excludes = listOf("/Album"))
        assertEquals(0, r.delLocal + r.delRemote)
        assertEquals(6, files(pc).count { it.startsWith("Album/") })
        // Un-excluding later brings it back instead of deleting it.
        sync(deletions = true)
        assertEquals(6, files(phone).count { it.startsWith("Album/") })
    }

    // ---------------------------------------------------------------- schedule times

    @Test
    fun nextDailyTime_picksNextEnabledDay() {
        val zone = java.time.ZoneId.of("UTC")
        fun at(s: String) = java.time.LocalDateTime.parse(s).atZone(zone).toInstant().toEpochMilli()
        // 2026-11-12 is a Thursday.
        assertEquals(at("2026-11-12T22:30"), nextDailyTime(at("2026-11-12T10:00"), 22 * 60 + 30, ALL_DAYS, zone))
        assertEquals(at("2026-11-13T22:30"), nextDailyTime(at("2026-11-12T22:30"), 22 * 60 + 30, ALL_DAYS, zone))
        val monOnly = 1
        assertEquals(at("2026-11-16T07:00"), nextDailyTime(at("2026-11-12T10:00"), 7 * 60, monOnly, zone))
        val thuOnly = 1 shl 3
        assertEquals(at("2026-11-19T07:00"), nextDailyTime(at("2026-11-12T10:00"), 7 * 60, thuOnly, zone))
        assertEquals(null, nextDailyTime(at("2026-11-12T10:00"), 7 * 60, 0, zone))
    }

    @Test
    fun scheduleLabels() {
        assertEquals("Every 15 min", SyncPair(intervalMin = 15).scheduleLabel)
        assertEquals("Every hour", SyncPair(intervalMin = 60).scheduleLabel)
        assertEquals("Every 3 hours", SyncPair(intervalMin = 180).scheduleLabel)
        assertEquals("Every 2 days", SyncPair(intervalMin = 2880).scheduleLabel)
        assertEquals("Daily at 22:30", SyncPair(schedType = SchedType.DAILY, dailyAt = 1350).scheduleLabel)
        assertEquals("Weekdays at 07:05", SyncPair(schedType = SchedType.DAILY, dailyAt = 425, days = 31).scheduleLabel)
        assertEquals("Mon Wed at 07:05", SyncPair(schedType = SchedType.DAILY, dailyAt = 425, days = 5).scheduleLabel)
    }

    // ---------------------------------------------------------------- scan details

    @Test
    fun scanReportsCountsAndLogsSummary() {
        write(phone, "a/b.txt", "12")
        write(phone, "c.txt", "3")
        write(pc, "d.txt", "4")
        sync()
        assertTrue(progress.any { it.phase.startsWith("Scanning phone") && it.phase.contains("2 files in 1 folder") })
        assertTrue(progress.any { it.phase.startsWith("Scanning PC") && it.phase.contains("1 file in 0 folders") })
        // Folder-by-folder updates are throttled to 4 a second, so a scan this quick only shows its start.
        assertTrue(progress.any { it.phase.startsWith("Scanning phone") && it.detail == "(top folder)" })
        assertTrue(progress.any { it.phase.startsWith("Comparing 2 phone files with 1 PC file") })
        assertTrue(logs.toString(), logs.any { it.startsWith("SCAN Checked 2 phone files and 1 PC file in") })
        // A run with nothing to do still logs what it checked.
        logs.clear()
        sync()
        assertEquals(1, logs.count { it.startsWith("SCAN Checked 3 phone files and 3 PC files") })
    }

    // ---------------------------------------------------------------- stats and log

    @Test
    fun progressReportsTotalsAndReachesTheEnd() {
        write(phone, "a.bin", "x".repeat(1000))
        write(phone, "b.bin", "y".repeat(3000))
        write(pc, "c.bin", "z".repeat(500))
        val r = sync()
        assertEquals(4500L, r.bytes)
        val withTotals = progress.filter { it.count > 0 }
        assertTrue(withTotals.isNotEmpty())
        assertTrue(withTotals.all { it.totalBytes == 4500L && it.count == 3 })
        assertTrue(withTotals.all { it.totalDone in 0..4500 && it.fraction in 0f..1f })
        assertEquals("total progress must never go backwards", withTotals.map { it.totalDone }.sorted(), withTotals.map { it.totalDone })
        assertTrue(withTotals.any { it.fileSize == 3000L })
        assertEquals(3, logs.count { it.startsWith("UP ") || it.startsWith("DOWN ") })
    }
}
