package com.example.smbsync

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.util.UUID

/**
 * Runs the real SMB client and the real sync engine against a live SMB server.
 *
 * Skipped unless SMB_TEST_HOST is set, e.g.
 *   SMB_TEST_HOST=127.0.0.1:4450  SMB_TEST_SHARE=test  [SMB_TEST_USER=..  SMB_TEST_PASS=..]
 */
class SmbIntegrationTest {
    private val host = System.getenv("SMB_TEST_HOST") ?: ""
    private val share = System.getenv("SMB_TEST_SHARE") ?: "test"
    private val user = System.getenv("SMB_TEST_USER") ?: ""
    private val pass = System.getenv("SMB_TEST_PASS") ?: ""

    private lateinit var tmp: File
    private lateinit var phone: File
    private lateinit var state: File
    private val base = "it-" + UUID.randomUUID().toString().take(8)
    private var clock = 1_700_000_000_000L
    private val progress = ArrayList<Progress>()

    private fun pair(mode: SyncMode = SyncMode.TWO_WAY, deletions: Boolean = false) = SyncPair(
        id = "it", host = host, share = share, username = user, password = pass, remotePath = base,
        localPath = phone.path, mode = mode, syncDeletions = deletions,
    )

    private fun <T> pc(block: (Smb) -> T): T = Smb(pair()).use { it.connect(); block(it) }

    private fun sync(mode: SyncMode = SyncMode.TWO_WAY, deletions: Boolean = false): SyncResult {
        val p = pair(mode, deletions)
        return SyncEngine(p, Smb(p), state, { _, _, _ -> }, { progress += it }, { false }).run()
    }

    private fun writePhone(rel: String, bytes: ByteArray) {
        val f = File(phone, rel)
        f.parentFile!!.mkdirs()
        f.writeBytes(bytes)
        clock += 10_000
        f.setLastModified(clock)
    }

    private fun writePc(rel: String, bytes: ByteArray) {
        clock += 10_000
        pc { smb ->
            smb.mkdirs(rel.substringBeforeLast('/', ""))
            smb.upload(rel, ByteArrayInputStream(bytes), clock, {}, { false })
        }
    }

    private fun readPc(rel: String): ByteArray = pc { smb ->
        ByteArrayOutputStream().also { smb.download(rel, it, {}, { false }) }.toByteArray()
    }

    private fun pcFiles(): List<String> = pc { smb ->
        val out = ArrayList<String>()
        fun walk(d: String) {
            for (m in smb.list(d)) if (m.dir) walk(m.path) else out += m.path
        }
        walk("")
        out.sorted()
    }

    private fun phoneFiles() =
        phone.walkTopDown().filter { it.isFile }.map { it.relativeTo(phone).path.replace('\\', '/') }.sorted().toList()

    @Before
    fun setUp() {
        assumeTrue("SMB_TEST_HOST not set", host.isNotEmpty())
        tmp = Files.createTempDirectory("smbsync-it").toFile()
        phone = File(tmp, "phone").apply { mkdirs() }
        state = File(tmp, "state.tsv")
    }

    @After
    fun tearDown() {
        if (host.isEmpty()) return
        try {
            pc { smb ->
                fun wipe(d: String) {
                    for (m in smb.list(d)) if (m.dir) { wipe(m.path); smb.deleteEmptyDir(m.path) } else smb.deleteFile(m.path)
                }
                wipe("")
            }
        } catch (_: Exception) {
        }
        tmp.deleteRecursively()
    }

    @Test
    fun clientBasics_uploadStatListDownloadRenameDelete() {
        pc { smb ->
            assertFalse("base folder should not exist yet", smb.baseExists())
            smb.mkdirs("a/b/c")
            assertTrue(smb.baseExists())

            val data = ByteArray(300_000) { (it % 251).toByte() }
            val mtime = 1_650_000_000_000L
            smb.upload("a/b/c/file.bin", ByteArrayInputStream(data), mtime, {}, { false })

            val st = smb.stat("a/b/c/file.bin")
            assertEquals(300_000L, st.size)
            assertEquals("modified time must be carried to the PC", mtime, st.mtime)

            val listing = smb.list("a/b/c")
            assertEquals(listOf("a/b/c/file.bin"), listing.map { it.path })
            assertEquals("no temp file may be left behind", 1, listing.size)
            assertEquals(mtime, listing[0].mtime)
            assertEquals(300_000L, listing[0].size)
            assertTrue(smb.list("a").single().dir)

            val out = ByteArrayOutputStream()
            smb.download("a/b/c/file.bin", out, {}, { false })
            assertTrue(data.contentEquals(out.toByteArray()))

            // Overwriting an existing file goes through temp-file + rename-over.
            smb.upload("a/b/c/file.bin", ByteArrayInputStream("short".toByteArray()), mtime + 5000, {}, { false })
            assertEquals(5L, smb.stat("a/b/c/file.bin").size)
            assertEquals(mtime + 5000, smb.stat("a/b/c/file.bin").mtime)
            assertEquals(1, smb.list("a/b/c").size)

            smb.deleteFile("a/b/c/file.bin")
            assertTrue(smb.list("a/b/c").isEmpty())
            smb.deleteEmptyDir("a/b/c")
            assertTrue(smb.list("a/b").isEmpty())
        }
    }

    @Test
    fun fullSyncCycle_overRealSmb() {
        val big = ByteArray(12 * 1024 * 1024) { (it * 31 % 256).toByte() }
        writePhone("photo one.jpg", "phone photo".toByteArray())
        writePhone("Camera/clip.mp4", big)
        writePhone("Ünïcödé – ファイル.txt", "unicode".toByteArray())
        writePc("docs/report.pdf", "pc report".toByteArray())
        writePc("docs/deep/er/notes.txt", "pc notes".toByteArray())

        // 1. first sync merges both sides
        var r = sync()
        assertTrue("errors: ${r.errors}", r.errors.isEmpty())
        assertEquals(3, r.up)
        assertEquals(2, r.down)
        val all = listOf("Camera/clip.mp4", "docs/deep/er/notes.txt", "docs/report.pdf", "photo one.jpg", "Ünïcödé – ファイル.txt").sorted()
        assertEquals(all, phoneFiles())
        assertEquals(all, pcFiles())
        assertTrue("large file must arrive intact", big.contentEquals(readPc("Camera/clip.mp4")))
        assertEquals("pc notes", File(phone, "docs/deep/er/notes.txt").readText())
        assertEquals(File(phone, "photo one.jpg").lastModified(), pc { it.stat("photo one.jpg").mtime })
        assertTrue("per-file progress was reported for the large file", progress.any { it.fileSize == big.size.toLong() && it.fileDone in 1 until it.fileSize })

        // 2. nothing changed -> nothing to do (timestamps are stable over SMB)
        r = sync()
        assertEquals("Already in sync", r.summary())

        // 3. edits travel both ways
        writePhone("photo one.jpg", "phone photo, edited".toByteArray())
        writePc("docs/report.pdf", "pc report v2, longer".toByteArray())
        r = sync()
        assertTrue("errors: ${r.errors}", r.errors.isEmpty())
        assertEquals(1, r.up)
        assertEquals(1, r.down)
        assertEquals("phone photo, edited", String(readPc("photo one.jpg")))
        assertEquals("pc report v2, longer", File(phone, "docs/report.pdf").readText())
        assertEquals("Already in sync", sync().summary())

        // 4. deletions propagate both ways when enabled
        File(phone, "Camera/clip.mp4").delete()
        pc { it.deleteFile("docs/deep/er/notes.txt") }
        r = sync(deletions = true)
        assertTrue("errors: ${r.errors}", r.errors.isEmpty())
        assertEquals(1, r.delRemote)
        assertEquals(1, r.delLocal)
        assertEquals(phoneFiles(), pcFiles())
        assertFalse(pcFiles().contains("Camera/clip.mp4"))
        assertEquals("Already in sync", sync(deletions = true).summary())
    }

    /** Throughput check; only runs when SMB_TEST_PERF_MB is set (size in MB). */
    @Test
    fun throughput() {
        val mb = (System.getenv("SMB_TEST_PERF_MB") ?: "").toIntOrNull()
        assumeTrue("SMB_TEST_PERF_MB not set", mb != null)
        val size = mb!! * 1024L * 1024L
        val src = File(tmp, "perf.bin")
        src.outputStream().use { o -> val b = ByteArray(1 shl 20) { (it * 7).toByte() }; repeat(mb) { o.write(b) } }
        pc { smb ->
            smb.mkdirs("")
            var t = System.nanoTime()
            src.inputStream().use { smb.upload("perf.bin", it, 1_700_000_000_000L, {}, { false }) }
            val up = size / 1048576.0 / ((System.nanoTime() - t) / 1e9)
            t = System.nanoTime()
            File(tmp, "back.bin").outputStream().use { smb.download("perf.bin", it, {}, { false }) }
            val down = size / 1048576.0 / ((System.nanoTime() - t) / 1e9)
            File(tmp, "perf-result.txt").writeText("")
            File(System.getProperty("java.io.tmpdir"), "smbsync-perf.txt").writeText("upload %.1f MB/s, download %.1f MB/s".format(up, down))
            assertEquals(size, File(tmp, "back.bin").length())
        }
    }

    @Test
    fun oneWayToPc_overRealSmb() {
        writePhone("a.txt", "from phone".toByteArray())
        writePc("pc-only.txt", "stays on pc".toByteArray())
        val r = sync(SyncMode.TO_PC)
        assertTrue("errors: ${r.errors}", r.errors.isEmpty())
        assertEquals(listOf("a.txt"), phoneFiles())
        assertEquals(listOf("a.txt", "pc-only.txt"), pcFiles())
    }
}
