package com.example.smbsync

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import kotlinx.coroutines.flow.MutableStateFlow
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

enum class SyncMode(val label: String, val arrow: String, val desc: String) {
    TWO_WAY("Two-way", "⇄", "Anything added or changed on either side is copied to the other."),
    TO_PC("Phone → PC only", "→", "Only what you add or change on the phone is sent to the PC. PC changes are ignored."),
    TO_PHONE("PC → Phone only", "←", "Only what you add or change on the PC is brought to the phone. Phone changes are ignored."),
}

enum class ConflictPolicy(val label: String) {
    KEEP_BOTH("Keep both (rename phone copy)"),
    NEWER_WINS("Newer file wins"),
    PHONE_WINS("Phone version wins"),
    PC_WINS("PC version wins"),
    SKIP("Skip the file"),
}

enum class SchedType(val label: String) {
    MANUAL("Manual only"),
    INTERVAL("Repeat every…"),
    DAILY("At a set time"),
}

val DAY_LETTERS = listOf("M", "T", "W", "T", "F", "S", "S")
private val DAY_NAMES = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
const val ALL_DAYS = 127

fun fmtInterval(min: Long): String = when {
    min % 1440 == 0L -> if (min == 1440L) "day" else "${min / 1440} days"
    min % 60 == 0L -> if (min == 60L) "hour" else "${min / 60} hours"
    else -> "$min min"
}

fun fmtClock(minutesOfDay: Int, h24: Boolean = true): String {
    val h = minutesOfDay / 60
    val m = minutesOfDay % 60
    return if (h24) String.format(java.util.Locale.US, "%02d:%02d", h, m)
    else String.format(java.util.Locale.US, "%d:%02d %s", if (h % 12 == 0) 12 else h % 12, m, if (h < 12) "AM" else "PM")
}

/**
 * Next moment (epoch millis, strictly after [now]) that falls on an enabled weekday at [dailyAt]
 * minutes past midnight. [days] is a bit mask, bit 0 = Monday … bit 6 = Sunday. Null if no day is enabled.
 */
fun nextDailyTime(now: Long, dailyAt: Int, days: Int, zone: java.time.ZoneId = java.time.ZoneId.systemDefault()): Long? {
    if (days and ALL_DAYS == 0) return null
    val today = java.time.Instant.ofEpochMilli(now).atZone(zone).toLocalDate()
    for (offset in 0..7) {
        val date = today.plusDays(offset.toLong())
        if (days and (1 shl (date.dayOfWeek.value - 1)) == 0) continue
        val t = date.atTime(dailyAt / 60, dailyAt % 60).atZone(zone).toInstant().toEpochMilli()
        if (t > now) return t
    }
    return null
}

data class SyncPair(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val host: String = "",
    val share: String = "",
    val username: String = "",
    val password: String = "",
    /** Folder inside the share, '/' separated, no leading slash. Empty = share root. */
    val remotePath: String = "",
    /** Absolute path on the phone. */
    val localPath: String = "",
    val mode: SyncMode = SyncMode.TWO_WAY,
    val syncDeletions: Boolean = false,
    val conflict: ConflictPolicy = ConflictPolicy.KEEP_BOTH,
    val schedType: SchedType = SchedType.INTERVAL,
    /** For INTERVAL: minutes between runs (Android's minimum is 15). */
    val intervalMin: Long = 60,
    /** For DAILY: minutes past midnight, and a weekday bit mask (bit 0 = Monday). */
    val dailyAt: Int = 22 * 60,
    val days: Int = ALL_DAYS,
    /**
     * Things left out of the sync. An entry starting with '/' is a folder path relative to the pair's
     * root; anything else is a name pattern (* and ? wildcards) matched against every file and folder name.
     */
    val excludes: List<String> = emptyList(),
    val wifiOnly: Boolean = true,
    val chargingOnly: Boolean = false,
    val subfolders: Boolean = true,
    val skipHidden: Boolean = true,
    val lastSync: Long = 0,
    val lastResult: String = "",
    val lastOk: Boolean = true,
) {
    val scheduleLabel: String get() = scheduleText(true)

    /** Short description of the schedule; [h24] chooses 22:30 or 10:30 PM. */
    fun scheduleText(h24: Boolean): String = when (schedType) {
            SchedType.MANUAL -> "Manual"
            SchedType.INTERVAL -> "Every ${fmtInterval(intervalMin)}"
            SchedType.DAILY -> when (days and ALL_DAYS) {
                ALL_DAYS -> "Daily"
                0 -> "No days"
                31 -> "Weekdays"
                96 -> "Weekends"
                else -> (0..6).filter { days and (1 shl it) != 0 }.joinToString(" ") { DAY_NAMES[it] }
            } + " at ${fmtClock(dailyAt, h24)}"
        }

    val remoteDisplay: String
        get() = "\\\\" + host.trim() + "\\" + cleanShare(share) +
            if (remotePath.isBlank()) "" else "\\" + cleanPath(remotePath).replace('/', '\\')
}

fun cleanShare(s: String) = s.trim().trim('/', '\\')
fun cleanPath(s: String) = s.trim().replace('\\', '/').split('/').filter { it.isNotBlank() }.joinToString("/")

private inline fun <reified T : Enum<T>> enumOr(name: String, def: T): T =
    enumValues<T>().firstOrNull { it.name == name } ?: def

private fun SyncPair.toJson() = JSONObject().apply {
    put("id", id); put("name", name); put("host", host); put("share", share)
    put("username", username); put("password", Crypto.enc(password))
    put("remotePath", remotePath); put("localPath", localPath)
    put("mode", mode.name); put("syncDeletions", syncDeletions); put("conflict", conflict.name)
    put("schedType", schedType.name); put("intervalMin", intervalMin); put("dailyAt", dailyAt); put("days", days)
    put("excludes", JSONArray(excludes))
    put("wifiOnly", wifiOnly); put("chargingOnly", chargingOnly)
    put("subfolders", subfolders); put("skipHidden", skipHidden)
    put("lastSync", lastSync); put("lastResult", lastResult); put("lastOk", lastOk)
}

private fun pairFromJson(o: JSONObject) = SyncPair(
    id = o.getString("id"), name = o.optString("name"), host = o.optString("host"),
    share = o.optString("share"), username = o.optString("username"),
    password = Crypto.dec(o.optString("password")),
    remotePath = o.optString("remotePath"), localPath = o.optString("localPath"),
    mode = enumOr(o.optString("mode"), SyncMode.TWO_WAY),
    syncDeletions = o.optBoolean("syncDeletions", false),
    conflict = enumOr(o.optString("conflict"), ConflictPolicy.KEEP_BOTH),
    // "schedule" is the fixed-choice setting from the first versions of the app.
    schedType = if (o.has("schedType")) enumOr(o.optString("schedType"), SchedType.INTERVAL)
    else if (o.optString("schedule") == "MANUAL") SchedType.MANUAL else SchedType.INTERVAL,
    intervalMin = if (o.has("intervalMin")) o.optLong("intervalMin", 60) else when (o.optString("schedule")) {
        "M15" -> 15L; "M30" -> 30L; "H6" -> 360L; "D1" -> 1440L; else -> 60L
    },
    dailyAt = o.optInt("dailyAt", 22 * 60), days = o.optInt("days", ALL_DAYS),
    excludes = o.optJSONArray("excludes")?.let { a -> (0 until a.length()).map { a.getString(it) } } ?: emptyList(),
    wifiOnly = o.optBoolean("wifiOnly", true), chargingOnly = o.optBoolean("chargingOnly", false),
    subfolders = o.optBoolean("subfolders", true), skipHidden = o.optBoolean("skipHidden", true),
    lastSync = o.optLong("lastSync"), lastResult = o.optString("lastResult"),
    lastOk = o.optBoolean("lastOk", true),
)

/** All configured folder pairs, persisted as JSON in the app's private storage. */
object PairStore {
    private lateinit var dir: File
    val pairs = MutableStateFlow<List<SyncPair>>(emptyList())

    @Synchronized
    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = ctx.applicationContext.filesDir
        val f = File(dir, "pairs.json")
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                pairs.value = (0 until arr.length()).map { pairFromJson(arr.getJSONObject(it)) }
            } catch (_: Exception) {
            }
        }
    }

    fun get(id: String) = pairs.value.firstOrNull { it.id == id }
    fun stateFile(id: String) = File(dir, "state_$id.tsv")
    fun logFile(id: String) = File(dir, "log_$id.txt")

    @Synchronized
    fun upsert(p: SyncPair) {
        val old = get(p.id)
        // The remembered "what was synced last time" only makes sense for the same two folders.
        if (old != null && (old.host != p.host || cleanShare(old.share) != cleanShare(p.share) ||
                cleanPath(old.remotePath) != cleanPath(p.remotePath) || old.localPath != p.localPath)
        ) resetState(p.id)
        pairs.value = if (old == null) pairs.value + p else pairs.value.map { if (it.id == p.id) p else it }
        save()
    }

    @Synchronized
    fun update(id: String, f: (SyncPair) -> SyncPair) {
        pairs.value = pairs.value.map { if (it.id == id) f(it) else it }
        save()
    }

    @Synchronized
    fun delete(id: String) {
        pairs.value = pairs.value.filter { it.id != id }
        save()
        resetState(id)
        logFile(id).delete()
    }

    fun resetState(id: String) {
        stateFile(id).delete()
    }

    private fun save() {
        val arr = JSONArray()
        pairs.value.forEach { arr.put(it.toJson()) }
        val tmp = File(dir, "pairs.json.tmp")
        tmp.writeText(arr.toString())
        tmp.renameTo(File(dir, "pairs.json"))
    }
}

/** Encrypts the SMB password with a key held in the Android Keystore. */
object Crypto {
    private const val ALIAS = "smbsync_pw"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(
                KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build()
            )
        }.generateKey()
    }

    fun enc(s: String): String {
        if (s.isEmpty()) return ""
        val c = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        return Base64.encodeToString(c.iv + c.doFinal(s.toByteArray()), Base64.NO_WRAP)
    }

    fun dec(s: String): String {
        if (s.isEmpty()) return ""
        return try {
            val raw = Base64.decode(s, Base64.NO_WRAP)
            val c = Cipher.getInstance("AES/GCM/NoPadding")
            c.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, raw, 0, 12))
            String(c.doFinal(raw, 12, raw.size - 12))
        } catch (_: Exception) {
            ""
        }
    }
}

class LogEntry(val time: Long, val type: LogType, val size: Long, val text: String)

/** Per-pair activity log: one tab-separated line per event (time, type, size, text). */
class SyncLog(private val file: File) {
    @Synchronized
    fun add(type: LogType, text: String, size: Long = 0) {
        try {
            file.appendText("${System.currentTimeMillis()}\t${type.name}\t$size\t${text.replace('\n', ' ').replace('\t', ' ')}\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun trim(max: Int = 3000) {
        try {
            val lines = file.readLines()
            if (lines.size > max) file.writeText(lines.takeLast(max).joinToString("\n") + "\n")
        } catch (_: Exception) {
        }
    }

    @Synchronized
    fun read(): List<LogEntry> = try {
        file.readLines().mapNotNull { line ->
            val p = line.split('\t', limit = 4)
            val type = if (p.size == 4) LogType.entries.firstOrNull { it.name == p[1] } else null
            val time = p[0].toLongOrNull()
            if (type != null && time != null) LogEntry(time, type, p[2].toLongOrNull() ?: 0, p[3]) else null
        }
    } catch (_: Exception) {
        emptyList()
    }

    @Synchronized
    fun clear() {
        file.delete()
    }
}
