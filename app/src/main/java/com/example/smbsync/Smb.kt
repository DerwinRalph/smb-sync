package com.example.smbsync

import com.hierynomus.msdtyp.AccessMask
import com.hierynomus.msdtyp.FileTime
import com.hierynomus.msfscc.FileAttributes
import com.hierynomus.msfscc.fileinformation.FileBasicInformation
import com.hierynomus.mssmb2.SMB2CreateDisposition
import com.hierynomus.mssmb2.SMB2ShareAccess
import com.hierynomus.smbj.SMBClient
import com.hierynomus.smbj.SmbConfig
import com.hierynomus.smbj.auth.AuthenticationContext
import com.hierynomus.smbj.connection.Connection
import com.hierynomus.smbj.session.Session
import com.hierynomus.smbj.share.DiskShare
import com.rapid7.client.dcerpc.mssrvs.ServerService
import com.rapid7.client.dcerpc.transport.SMBTransportFactories
import java.io.Closeable
import java.io.InputStream
import java.io.InterruptedIOException
import java.io.OutputStream
import java.util.EnumSet
import java.util.concurrent.TimeUnit

/** A file or folder on either side. [path] is relative to the pair's root, '/' separated. */
class Meta(val path: String, val dir: Boolean, val size: Long, val mtime: Long, val hidden: Boolean = false) {
    val name: String get() = path.substringAfterLast('/')
}

const val TMP_SUFFIX = ".smbsync.tmp"

/** The PC side of a sync, as the engine sees it. Paths are relative to the pair's PC folder. */
interface Remote : Closeable {
    val isConnected: Boolean
    fun connect()
    fun baseExists(): Boolean
    fun list(relDir: String): List<Meta>
    fun stat(rel: String): Meta
    fun mkdirs(relDir: String)
    fun download(rel: String, out: OutputStream, onBytes: (Long) -> Unit, stopped: () -> Boolean)
    fun upload(rel: String, input: InputStream, mtime: Long, onBytes: (Long) -> Unit, stopped: () -> Boolean)
    fun deleteFile(rel: String)
    fun deleteEmptyDir(rel: String)
}

/** Thin wrapper over smbj for one pair's share + base folder. */
class Smb(private val pair: SyncPair) : Remote {
    private val client = SMBClient(
        SmbConfig.builder()
            .withTimeout(30, TimeUnit.SECONDS)
            .withSoTimeout(60, TimeUnit.SECONDS)
            .build()
    )
    private var conn: Connection? = null
    private lateinit var session: Session
    private lateinit var share: DiskShare
    private val base = cleanPath(pair.remotePath)

    override val isConnected: Boolean get() = conn?.isConnected == true

    override fun connect() {
        login()
        if (cleanShare(pair.share).isEmpty()) throw IllegalStateException("No share chosen")
        share = session.connectShare(cleanShare(pair.share)) as? DiskShare
            ?: throw IllegalStateException("'${pair.share}' is not a file share")
    }

    /** Names of the normal shared folders on the PC (hidden admin shares like C$ are left out). */
    fun listShares(): List<String> {
        val transport = SMBTransportFactories.SRVSVC.getTransport(session)
        return ServerService(transport).shares1.filter { it.type == 0 }.map { it.netName }
    }

    /** Connects and signs in without opening a share. */
    fun login() {
        val (host, port) = splitHostPort(pair.host)
        val c = client.connect(host, port)
        conn = c
        val user = pair.username.trim()
        val auth = when {
            user.isEmpty() -> AuthenticationContext.guest()
            user.contains('\\') -> AuthenticationContext(
                user.substringAfter('\\'), pair.password.toCharArray(), user.substringBefore('\\')
            )
            else -> AuthenticationContext(user, pair.password.toCharArray(), "")
        }
        session = c.authenticate(auth)
    }

    private fun full(rel: String): String {
        val r = cleanPath(rel)
        val p = when {
            base.isEmpty() -> r
            r.isEmpty() -> base
            else -> "$base/$r"
        }
        return p.replace('/', '\\')
    }

    override fun baseExists(): Boolean = base.isEmpty() || share.folderExists(full(""))

    override fun list(relDir: String): List<Meta> {
        val out = ArrayList<Meta>()
        for (e in share.list(full(relDir))) {
            val name = e.fileName
            if (name == "." || name == "..") continue
            val attrs = e.fileAttributes
            val isDir = attrs and FileAttributes.FILE_ATTRIBUTE_DIRECTORY.value != 0L
            val hidden = attrs and FileAttributes.FILE_ATTRIBUTE_HIDDEN.value != 0L
            val rel = if (relDir.isEmpty()) name else "$relDir/$name"
            out += Meta(rel, isDir, if (isDir) 0 else e.endOfFile, e.lastWriteTime.toEpochMillis(), hidden)
        }
        return out
    }

    override fun stat(rel: String): Meta {
        val info = share.getFileInformation(full(rel))
        return Meta(rel, false, info.standardInformation.endOfFile, info.basicInformation.lastWriteTime.toEpochMillis())
    }

    /** Creates [relDir] (and parents, including the base folder) if missing. */
    override fun mkdirs(relDir: String) {
        val parts = full(relDir).split('\\').filter { it.isNotEmpty() }
        var cur = ""
        for (p in parts) {
            cur = if (cur.isEmpty()) p else "$cur\\$p"
            if (!share.folderExists(cur)) share.mkdir(cur)
        }
    }

    override fun download(rel: String, out: OutputStream, onBytes: (Long) -> Unit, stopped: () -> Boolean) {
        share.openFile(
            full(rel), EnumSet.of(AccessMask.GENERIC_READ), null,
            SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OPEN, null
        ).use { f ->
            f.inputStream.use { copy(it, out, onBytes, stopped) }
        }
    }

    /** Uploads to a temp name, stamps the modified time, then renames over the target. */
    override fun upload(rel: String, input: InputStream, mtime: Long, onBytes: (Long) -> Unit, stopped: () -> Boolean) {
        val target = full(rel)
        val tmp = target + TMP_SUFFIX
        try {
            share.openFile(
                tmp, EnumSet.of(AccessMask.GENERIC_WRITE, AccessMask.DELETE), null,
                SMB2ShareAccess.ALL, SMB2CreateDisposition.FILE_OVERWRITE_IF, null
            ).use { f ->
                f.outputStream.use { copy(input, it, onBytes, stopped) }
                val keep = FileTime(0)
                f.setFileInformation(FileBasicInformation(keep, keep, FileTime.ofEpochMillis(mtime), keep, 0))
                f.rename(target, true)
            }
        } catch (e: Exception) {
            try {
                if (share.fileExists(tmp)) share.rm(tmp)
            } catch (_: Exception) {
            }
            throw e
        }
    }

    override fun deleteFile(rel: String) = share.rm(full(rel))
    override fun deleteEmptyDir(rel: String) = share.rmdir(full(rel), false)

    private fun copy(input: InputStream, out: OutputStream, onBytes: (Long) -> Unit, stopped: () -> Boolean) {
        val buf = ByteArray(1 shl 20)
        var total = 0L
        while (true) {
            if (stopped()) throw InterruptedIOException("Cancelled")
            val n = input.read(buf)
            if (n < 0) break
            out.write(buf, 0, n)
            total += n
            onBytes(total)
        }
    }

    override fun close() {
        try {
            conn?.close()
        } catch (_: Exception) {
        }
        try {
            client.close()
        } catch (_: Exception) {
        }
    }
}

/** Accepts "host" or "host:port"; the SMB default port is 445. */
fun splitHostPort(s: String): Pair<String, Int> {
    val t = s.trim()
    val m = Regex("^([^:]+):([0-9]{1,5})$").find(t) ?: return t to 445
    return m.groupValues[1] to m.groupValues[2].toInt()
}

/** Turns low-level network/SMB errors into something readable. */
fun friendlyError(e: Throwable): String {
    var t: Throwable? = e
    val chain = ArrayList<Throwable>()
    while (t != null && chain.size < 8) {
        chain += t
        t = t.cause
    }
    val text = chain.joinToString(" | ") { (it.javaClass.simpleName + ": " + (it.message ?: "")) }
    return when {
        chain.any {
            it is java.net.ConnectException || it is java.net.UnknownHostException ||
                it is java.net.NoRouteToHostException || it is java.net.SocketTimeoutException
        } -> "PC not reachable"
        "STATUS_LOGON_FAILURE" in text -> "Login failed – check username and password"
        "STATUS_ACCOUNT_DISABLED" in text && "'Guest'" in text -> "The PC doesn't allow guest access – enter your Windows username and password"
        "STATUS_ACCOUNT_DISABLED" in text -> "That Windows account is disabled"
        "STATUS_ACCOUNT_RESTRICTION" in text -> "Windows refused this account (accounts with no password can't be used for sharing)"
        "STATUS_BAD_NETWORK_NAME" in text -> "Share not found on the PC"
        "STATUS_ACCESS_DENIED" in text -> "Access denied by the PC"
        "STATUS_OBJECT_NAME_NOT_FOUND" in text || "STATUS_OBJECT_PATH_NOT_FOUND" in text -> "Folder not found on the PC"
        else -> e.message ?: e.javaClass.simpleName
    }
}

fun isUnreachable(msg: String) = msg == "PC not reachable"
