@file:OptIn(ExperimentalMaterial3Api::class)

package com.example.smbsync

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.format.DateUtils
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.horizontalScroll
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private var hasStorage by mutableStateOf(true)
    private var hasLan by mutableStateOf(true)

    private val askPerms = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { checkPerms() }

    /** Android 17+ blocks connections to devices on the local network until this is granted. */
    private fun checkPerms() {
        hasStorage = Environment.isExternalStorageManager()
        hasLan = android.os.Build.VERSION.SDK_INT < 37 ||
            checkSelfPermission(LAN_PERMISSION) == android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun grantLan() {
        if (shouldShowRequestPermissionRationale(LAN_PERMISSION) || !askedLan) {
            askedLan = true
            askPerms.launch(arrayOf(LAN_PERMISSION))
        } else {
            startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private var askedLan = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        PairStore.init(this)
        if (savedInstanceState == null) askPerms.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS, LAN_PERMISSION))
        setContent {
            val dark = isSystemInDarkTheme()
            val scheme = if (android.os.Build.VERSION.SDK_INT >= 31) {
                if (dark) dynamicDarkColorScheme(this) else dynamicLightColorScheme(this)
            } else if (dark) darkColorScheme() else lightColorScheme()
            MaterialTheme(colorScheme = scheme) { App(hasStorage, ::openStorageSettings, hasLan, ::grantLan) }
        }
    }

    override fun onResume() {
        super.onResume()
        checkPerms()
    }

    private fun openStorageSettings() {
        try {
            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
        } catch (_: Exception) {
            startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
        }
    }
}

private const val LAN_PERMISSION = "android.permission.ACCESS_LOCAL_NETWORK"

private sealed interface Screen {
    data object Home : Screen
    data class Edit(val id: String?) : Screen
    data class Log(val id: String) : Screen
}

@Composable
private fun App(hasStorage: Boolean, grantStorage: () -> Unit, hasLan: Boolean, grantLan: () -> Unit) {
    var screen by remember { mutableStateOf<Screen>(Screen.Home) }
    BackHandler(screen != Screen.Home) { screen = Screen.Home }
    when (val s = screen) {
        Screen.Home -> HomeScreen(hasStorage, grantStorage, hasLan, grantLan, { screen = Screen.Edit(it) }, { screen = Screen.Log(it) })
        is Screen.Edit -> EditScreen(s.id) { screen = Screen.Home }
        is Screen.Log -> LogScreen(s.id) { screen = Screen.Home }
    }
}

// ---------------------------------------------------------------- Home

@Composable
private fun HomeScreen(hasStorage: Boolean, grantStorage: () -> Unit, hasLan: Boolean, grantLan: () -> Unit, onEdit: (String?) -> Unit, onLog: (String) -> Unit) {
    val ctx = LocalContext.current
    val pairs by PairStore.pairs.collectAsState()
    val running by SyncStatus.running.collectAsState()
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("SMB Sync") }, actions = {
                if (pairs.size > 1) TextButton(onClick = { pairs.forEach { Scheduler.syncNow(ctx, it.id) } }) { Text("Sync all") }
            })
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = { onEdit(null) },
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Add folder pair") },
            )
        },
    ) { pad ->
        LazyColumn(
            Modifier.fillMaxSize().padding(pad),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 8.dp, bottom = 96.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (!hasStorage) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Storage permission needed", fontWeight = FontWeight.Bold)
                        Text("To read and write your folders, allow \"All files access\" for SMB Sync.")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = grantStorage) { Text("Open settings") }
                    }
                }
            }
            if (!hasLan) item {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Local network permission needed", fontWeight = FontWeight.Bold)
                        Text("Android won't let SMB Sync reach your PC until you allow \"Nearby devices\". Without it every sync says \"PC not reachable\".")
                        Spacer(Modifier.height(8.dp))
                        Button(onClick = grantLan) { Text("Allow") }
                    }
                }
            }
            if (pairs.isEmpty()) item {
                Text(
                    "No folder pairs yet.\n\nShare a folder on your PC (right-click → Properties → Sharing), " +
                        "then tap \"Add folder pair\" to link it with a folder on this phone.",
                    Modifier.padding(top = 48.dp, start = 8.dp, end = 8.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(pairs, key = { it.id }) { p ->
                PairCard(p, running[p.id], { Scheduler.syncNow(ctx, p.id) }, { Scheduler.stop(ctx, p.id) }, { onEdit(p.id) }, { onLog(p.id) })
            }
        }
    }
}

@Composable
private fun PairCard(p: SyncPair, progress: Progress?, onSync: () -> Unit, onStop: () -> Unit, onEdit: () -> Unit, onLog: () -> Unit) {
    val h24 = android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp)) {
            Text(p.name.ifBlank { "Unnamed pair" }, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            Text("Phone  ${p.localPath}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${p.mode.arrow}  ${p.mode.label} · ${p.scheduleText(h24)}" + if (p.syncDeletions) " · deletions on" else "",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            )
            Text("PC  ${p.remoteDisplay}", style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(10.dp))
            if (progress != null) {
                TransferStats(progress)
            } else if (p.lastSync > 0) {
                Text(
                    "${p.lastResult} · ${DateUtils.getRelativeTimeSpanString(p.lastSync)}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (p.lastOk) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.error,
                )
            } else {
                Text("Never synced", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (progress != null) OutlinedButton(onClick = onStop) { Text("Stop") }
                else Button(onClick = onSync) { Text("Sync now") }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = onLog) { Text("Log") }
                TextButton(onClick = onEdit) { Text("Edit") }
            }
        }
    }
}

/** Overall and per-file progress, amount transferred, speed and time left. */
@Composable
private fun TransferStats(pr: Progress) {
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    if (pr.count == 0) {
        LinearProgressIndicator(Modifier.fillMaxWidth())
        Spacer(Modifier.height(4.dp))
        Text(pr.phase, style = small)
        return
    }
    val pct = (pr.fraction * 100).toInt()
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Total  $pct%", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.weight(1f))
        Text("${pr.index} of ${pr.count} items", style = small, color = dim)
    }
    Spacer(Modifier.height(4.dp))
    LinearProgressIndicator(progress = { pr.fraction }, modifier = Modifier.fillMaxWidth())
    Spacer(Modifier.height(4.dp))
    if (pr.totalBytes > 0) {
        val left = pr.totalBytes - pr.totalDone
        val eta = if (pr.speed > 0) left / pr.speed else -1
        Row {
            Text("${fmtBytes(pr.totalDone)} of ${fmtBytes(pr.totalBytes)}", style = small, color = dim)
            Spacer(Modifier.weight(1f))
            Text(
                (if (pr.speed > 0) "${fmtBytes(pr.speed)}/s" else "– /s") + (if (eta >= 0) " · ${fmtDuration(eta)} left" else ""),
                style = small, color = dim,
            )
        }
    }
    Spacer(Modifier.height(8.dp))
    Text(pr.phase, style = small, maxLines = 2, overflow = TextOverflow.Ellipsis)
    if (pr.fileSize > 0) {
        Spacer(Modifier.height(4.dp))
        LinearProgressIndicator(
            progress = { pr.fileFraction }, modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.tertiary,
        )
        Spacer(Modifier.height(2.dp))
        Text(
            "This file  ${(pr.fileFraction * 100).toInt()}% · ${fmtBytes(pr.fileDone)} of ${fmtBytes(pr.fileSize)}",
            style = small, color = dim,
        )
    }
}

private fun fmtDuration(sec: Long): String = when {
    sec >= 3600 -> "${sec / 3600} h ${sec % 3600 / 60} min"
    sec >= 60 -> "${sec / 60} min ${sec % 60} s"
    else -> "$sec s"
}

// ---------------------------------------------------------------- Edit

@Composable
private fun EditScreen(id: String?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val existing = remember { id?.let { PairStore.get(it) } }
    var p by remember {
        mutableStateOf(
            existing ?: PairStore.pairs.value.lastOrNull().let { last ->
                // Reuse the PC details from the previous pair so they don't have to be retyped.
                SyncPair(host = last?.host ?: "", share = last?.share ?: "", username = last?.username ?: "", password = last?.password ?: "")
            }
        )
    }
    var pickLocal by remember { mutableStateOf(false) }
    var pickRemote by remember { mutableStateOf(false) }
    var pickHost by remember { mutableStateOf(false) }
    var pickShare by remember { mutableStateOf(false) }
    var excludeFrom by remember { mutableStateOf(0) } // 1 = pick on phone, 2 = pick on PC
    var excludeName by remember { mutableStateOf<String?>(null) }
    var showPw by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    var testResult by remember { mutableStateOf<Pair<Boolean, String>?>(null) }
    var confirmDelete by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val storageRoot = remember { Environment.getExternalStorageDirectory().absolutePath }

    fun save() {
        val clean = p.copy(
            name = p.name.trim().ifEmpty { File(p.localPath).name.ifEmpty { "Folder pair" } },
            host = p.host.trim(), share = cleanShare(p.share), remotePath = cleanPath(p.remotePath),
            localPath = p.localPath.trim().trimEnd('/'),
        )
        error = when {
            clean.host.isEmpty() -> "Enter the PC's name or IP address"
            clean.share.isEmpty() -> "Enter the share name"
            !clean.localPath.startsWith("/") -> "Choose a phone folder"
            else -> null
        }
        if (error != null) return
        PairStore.upsert(clean)
        Scheduler.apply(ctx, clean)
        onDone()
    }

    Scaffold(topBar = {
        TopAppBar(
            title = { Text(if (existing == null) "New folder pair" else "Edit folder pair") },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                if (existing != null) IconButton(onClick = { confirmDelete = true }) { Icon(Icons.Filled.Delete, "Delete") }
                TextButton(onClick = ::save) { Text("Save") }
            },
        )
    }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).imePadding().verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Field("Name", p.name, "e.g. Camera photos") { p = p.copy(name = it) }

            Section("PC (SMB share)")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("PC name or IP address", p.host, "e.g. 192.168.1.20") { p = p.copy(host = it) } }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { pickHost = true }) { Text("Find") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("Share name", p.share, "tap Browse to pick") { p = p.copy(share = it) } }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { pickShare = true }) { Text("Browse") }
            }
            Field("Username", p.username, "Windows account (blank = guest)") { p = p.copy(username = it) }
            OutlinedTextField(
                p.password, { p = p.copy(password = it) }, Modifier.fillMaxWidth(), label = { Text("Password") },
                singleLine = true,
                visualTransformation = if (showPw) VisualTransformation.None else PasswordVisualTransformation(),
                trailingIcon = { TextButton(onClick = { showPw = !showPw }) { Text(if (showPw) "Hide" else "Show") } },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("Folder inside the share", p.remotePath, "blank = whole share") { p = p.copy(remotePath = it) } }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { pickRemote = true }) { Text("Browse") }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(enabled = !testing, onClick = {
                    testing = true
                    testResult = null
                    scope.launch {
                        testResult = withContext(Dispatchers.IO) {
                            try {
                                Smb(p).use { smb ->
                                    if (cleanShare(p.share).isEmpty()) {
                                        smb.login()
                                        val n = try { smb.listShares().size } catch (_: Exception) { -1 }
                                        true to (if (n >= 0) "Login OK – $n shared folders found." else "Login OK.") +
                                            " Now pick a share with Browse."
                                    } else if (run { smb.connect(); smb.baseExists() }) true to "Connected – ${smb.list("").size} items in folder"
                                    else true to "Connected – folder doesn't exist yet, it will be created"
                                }
                            } catch (e: Exception) {
                                false to friendlyError(e)
                            }
                        }
                        testing = false
                    }
                }) { Text("Test connection") }
                Spacer(Modifier.width(12.dp))
                if (testing) CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                testResult?.let { (ok, msg) ->
                    Text(msg, color = if (ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
            }

            Section("Phone folder")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { Field("Folder on phone", p.localPath, "$storageRoot/…") { p = p.copy(localPath = it) } }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(onClick = { pickLocal = true }) { Text("Browse") }
            }

            Section("Sync direction")
            SyncMode.entries.forEach { m ->
                Row(Modifier.fillMaxWidth().clickable { p = p.copy(mode = m) }, verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(selected = p.mode == m, onClick = { p = p.copy(mode = m) })
                    Column {
                        Text(m.label)
                        Text(m.desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            Toggle(
                "Sync deletions",
                when (p.mode) {
                    SyncMode.TWO_WAY -> "Deleting a file on one side deletes it on the other. Off: deleted files come back from the other side."
                    SyncMode.TO_PC -> "Deleting a file on the phone also deletes it on the PC."
                    SyncMode.TO_PHONE -> "Deleting a file on the PC also deletes it on the phone."
                },
                p.syncDeletions,
            ) { p = p.copy(syncDeletions = it) }
            if (p.mode == SyncMode.TWO_WAY) {
                Picker("If a file changed on both sides", p.conflict.label, ConflictPolicy.entries.map { it.label }) {
                    p = p.copy(conflict = ConflictPolicy.entries[it])
                }
            }

            Section("When to sync")
            ScheduleEditor(p) { p = it }
            Toggle("Only on Wi-Fi", "Skip automatic sync on mobile data.", p.wifiOnly) { p = p.copy(wifiOnly = it) }
            Toggle("Only while charging", null, p.chargingOnly) { p = p.copy(chargingOnly = it) }

            Section("Files")
            Toggle("Include subfolders", null, p.subfolders) { p = p.copy(subfolders = it) }
            Toggle("Skip hidden files", "Ignores files starting with a dot and Windows hidden files.", p.skipHidden) { p = p.copy(skipHidden = it) }

            Section("Excluded from this pair")
            Text(
                "Folders and names listed here are ignored on both sides: never copied, never deleted.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            p.excludes.forEach { e ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if (e.startsWith("/")) e.drop(1) else e)
                        Text(
                            if (e.startsWith("/")) "Folder" else "Any file or folder with this name",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { p = p.copy(excludes = p.excludes - e) }) { Text("Remove") }
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { excludeFrom = 1 }, modifier = Modifier.weight(1f)) { Text("+ Phone folder") }
                OutlinedButton(onClick = { excludeFrom = 2 }, modifier = Modifier.weight(1f)) { Text("+ PC folder") }
            }
            OutlinedButton(onClick = { excludeName = "" }, modifier = Modifier.fillMaxWidth()) { Text("+ Name or pattern (e.g. __Previews, *.tmp)") }

            if (existing != null) {
                TextButton(onClick = {
                    PairStore.resetState(existing.id)
                    testResult = true to "Sync memory cleared"
                }) { Text("Reset sync memory (treat next sync as the first)") }
            }
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(onClick = ::save, modifier = Modifier.fillMaxWidth()) { Text("Save") }
            Spacer(Modifier.height(24.dp))
        }
    }

    if (pickLocal) FolderPicker(
        "Choose phone folder",
        p.localPath.removePrefix(storageRoot).trim('/'),
        { rel ->
            (File(storageRoot, rel).listFiles() ?: throw IllegalStateException("Can't read this folder. Grant \"All files access\" first."))
                .filter { it.isDirectory && !it.name.startsWith(".") }.map { it.name }.sortedBy { it.lowercase() }
        },
        { rel -> p = p.copy(localPath = if (rel.isEmpty()) storageRoot else "$storageRoot/$rel"); pickLocal = false },
        { pickLocal = false },
        create = { rel ->
            val d = File(storageRoot, rel)
            if (d.exists()) throw IllegalStateException("a folder with that name already exists")
            if (!d.mkdirs()) throw IllegalStateException("the phone refused (is \"All files access\" granted?)")
        },
    )
    if (pickRemote) FolderPicker(
        "Choose PC folder",
        cleanPath(p.remotePath),
        { rel ->
            Smb(p.copy(remotePath = "")).use { smb ->
                try {
                    smb.connect()
                    smb.list(rel).filter { it.dir && !it.hidden }.map { it.name }.sortedBy { it.lowercase() }
                } catch (e: Exception) {
                    throw IllegalStateException(friendlyError(e))
                }
            }
        },
        { rel -> p = p.copy(remotePath = rel); pickRemote = false },
        { pickRemote = false },
        create = { rel ->
            Smb(p.copy(remotePath = "")).use { smb ->
                try {
                    smb.connect()
                    smb.mkdirs(rel)
                } catch (e: Exception) {
                    throw IllegalStateException(friendlyError(e))
                }
            }
        },
    )
    if (pickShare) FolderPicker(
        "Shared folders on the PC",
        "",
        { _ ->
            if (p.host.isBlank()) throw IllegalStateException("Enter the PC address first (or tap Find).")
            Smb(p).use { smb ->
                try {
                    smb.login()
                    smb.listShares().sortedBy { it.lowercase() }.ifEmpty {
                        throw IllegalStateException("This PC has no shared folders. On the PC: right-click a folder → Properties → Sharing → Share.")
                    }
                } catch (e: IllegalStateException) {
                    throw e
                } catch (e: Exception) {
                    throw IllegalStateException(friendlyError(e))
                }
            }
        },
        { share -> if (share.isNotEmpty()) { p = p.copy(share = share, remotePath = ""); testResult = null }; pickShare = false },
        { pickShare = false },
        pickOnTap = true,
    )
    fun addExclude(e: String) {
        if (e.isNotBlank() && e != "/" && e !in p.excludes) p = p.copy(excludes = p.excludes + e)
    }
    if (excludeFrom == 1) FolderPicker(
        "Exclude a phone folder",
        "",
        { rel ->
            if (!p.localPath.startsWith("/")) throw IllegalStateException("Choose the phone folder for this pair first.")
            (File(p.localPath, rel).listFiles() ?: throw IllegalStateException("Can't read this folder."))
                .filter { it.isDirectory }.map { it.name }.sortedBy { it.lowercase() }
        },
        { rel -> addExclude("/$rel"); excludeFrom = 0 },
        { excludeFrom = 0 },
        confirmLabel = "Exclude this folder",
    )
    if (excludeFrom == 2) FolderPicker(
        "Exclude a PC folder",
        "",
        { rel ->
            Smb(p).use { smb ->
                try {
                    smb.connect()
                    smb.list(rel).filter { it.dir }.map { it.name }.sortedBy { it.lowercase() }
                } catch (e: Exception) {
                    throw IllegalStateException(friendlyError(e))
                }
            }
        },
        { rel -> addExclude("/$rel"); excludeFrom = 0 },
        { excludeFrom = 0 },
        confirmLabel = "Exclude this folder",
    )
    excludeName?.let { name ->
        AlertDialog(
            onDismissRequest = { excludeName = null },
            title = { Text("Exclude by name") },
            text = {
                Column {
                    Text(
                        "Every file or folder with this name is skipped, wherever it is. Use * for \"anything\", " +
                            "for example *.tmp or Screenshot_*.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(name, { excludeName = it }, Modifier.fillMaxWidth(), singleLine = true, label = { Text("Name or pattern") })
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank() && !name.contains('/'), onClick = { addExclude(name.trim()); excludeName = null }) { Text("Add") }
            },
            dismissButton = { TextButton(onClick = { excludeName = null }) { Text("Cancel") } },
        )
    }
    if (pickHost) HostPicker({ p = p.copy(host = it); testResult = null; pickHost = false }, { pickHost = false })
    if (confirmDelete && existing != null) AlertDialog(
        onDismissRequest = { confirmDelete = false },
        title = { Text("Remove this folder pair?") },
        text = { Text("Syncing stops. No files are deleted on the phone or the PC.") },
        confirmButton = {
            TextButton(onClick = {
                Scheduler.remove(ctx, existing.id)
                PairStore.delete(existing.id)
                onDone()
            }) { Text("Remove") }
        },
        dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text("Cancel") } },
    )
}

@Composable
private fun Section(title: String) {
    Text(title, Modifier.padding(top = 12.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary)
}

@Composable
private fun Field(label: String, value: String, hint: String, onChange: (String) -> Unit) {
    OutlinedTextField(
        value, onChange, Modifier.fillMaxWidth(), label = { Text(label) }, placeholder = { Text(hint) }, singleLine = true,
        keyboardOptions = KeyboardOptions(autoCorrectEnabled = false),
    )
}

@Composable
private fun Toggle(title: String, desc: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().clickable { onChange(!checked) }, verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title)
            if (desc != null) Text(desc, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.width(12.dp))
        Switch(checked, onChange)
    }
}

/** Automatic sync: off, repeating at any interval, or at a set time on chosen weekdays. */
@Composable
private fun ScheduleEditor(p: SyncPair, onChange: (SyncPair) -> Unit) {
    val ctx = LocalContext.current
    val h24 = android.text.format.DateFormat.is24HourFormat(ctx)
    val small = MaterialTheme.typography.bodySmall
    val dim = MaterialTheme.colorScheme.onSurfaceVariant
    Picker("Automatic sync", p.schedType.label, SchedType.entries.map { it.label }) { onChange(p.copy(schedType = SchedType.entries[it])) }
    when (p.schedType) {
        SchedType.MANUAL -> Text("Syncs only when you tap Sync now.", style = small, color = dim)
        SchedType.INTERVAL -> {
            val units = listOf("minutes" to 1L, "hours" to 60L, "days" to 1440L)
            var unit by remember { mutableStateOf(if (p.intervalMin % 1440 == 0L) 2 else if (p.intervalMin % 60 == 0L) 1 else 0) }
            var text by remember { mutableStateOf((p.intervalMin / units[unit].second).toString()) }
            fun push() {
                val n = text.toLongOrNull() ?: return
                onChange(p.copy(intervalMin = (n * units[unit].second).coerceIn(15, 60L * 24 * 30)))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Every")
                Spacer(Modifier.width(12.dp))
                OutlinedTextField(
                    text, { v -> text = v.filter { it.isDigit() }.take(4); push() }, Modifier.width(96.dp), singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Spacer(Modifier.width(12.dp))
                Box(Modifier.weight(1f)) { Picker("", units[unit].first, units.map { it.first }) { unit = it; push() } }
            }
            val asked = (text.toLongOrNull() ?: 0) * units[unit].second
            Text(
                if (asked < 15) "Android won't run background sync more often than every 15 minutes, so 15 minutes is used."
                else "Runs about every ${fmtInterval(p.intervalMin)}. Android may shift it by a few minutes to save battery.",
                style = small, color = if (asked < 15) MaterialTheme.colorScheme.error else dim,
            )
        }
        SchedType.DAILY -> {
            var pickTime by remember { mutableStateOf(false) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Time")
                Spacer(Modifier.width(12.dp))
                OutlinedButton(onClick = { pickTime = true }) { Text(fmtClock(p.dailyAt, h24)) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                DAY_LETTERS.forEachIndexed { i, letter ->
                    val on = p.days and (1 shl i) != 0
                    FilterChip(
                        selected = on, onClick = { onChange(p.copy(days = p.days xor (1 shl i))) },
                        label = { Text(letter, Modifier.fillMaxWidth(), textAlign = TextAlign.Center) }, modifier = Modifier.weight(1f),
                    )
                }
            }
            Text(
                if (p.days and ALL_DAYS == 0) "Pick at least one day, otherwise it never runs."
                else "${p.scheduleText(h24)}. If the phone is off or away from Wi-Fi at that time, it syncs as soon as it can.",
                style = small, color = if (p.days and ALL_DAYS == 0) MaterialTheme.colorScheme.error else dim,
            )
            if (pickTime) {
                val st = rememberTimePickerState(p.dailyAt / 60, p.dailyAt % 60, h24)
                AlertDialog(
                    onDismissRequest = { pickTime = false },
                    text = { TimePicker(st) },
                    confirmButton = { TextButton(onClick = { onChange(p.copy(dailyAt = st.hour * 60 + st.minute)); pickTime = false }) { Text("OK") } },
                    dismissButton = { TextButton(onClick = { pickTime = false }) { Text("Cancel") } },
                )
            }
        }
    }
}

@Composable
private fun Picker(title: String, current: String, options: List<String>, onPick: (Int) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Column {
        if (title.isNotEmpty()) Text(title)
        Box {
            OutlinedButton(onClick = { open = true }, modifier = Modifier.fillMaxWidth()) { Text(current) }
            DropdownMenu(open, { open = false }) {
                options.forEachIndexed { i, o ->
                    DropdownMenuItem(text = { Text(o) }, onClick = { open = false; onPick(i) })
                }
            }
        }
    }
}

/**
 * Simple folder browser used for both the phone and the PC side. Paths are '/' separated and relative.
 * When [create] is given, a "New folder" option makes a subfolder of the folder being shown.
 */
@Composable
private fun FolderPicker(
    title: String, initial: String, load: suspend (String) -> List<String>,
    onPick: (String) -> Unit, onDismiss: () -> Unit, pickOnTap: Boolean = false,
    create: (suspend (String) -> Unit)? = null, confirmLabel: String = "Use this folder",
) {
    val scope = rememberCoroutineScope()
    var path by remember { mutableStateOf(initial) }
    var items by remember { mutableStateOf<List<String>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var newName by remember { mutableStateOf<String?>(null) }
    var creating by remember { mutableStateOf(false) }
    LaunchedEffect(path) {
        items = null
        error = null
        try {
            items = withContext(Dispatchers.IO) { load(path) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                if (!pickOnTap) {
                    Text("/$path", style = MaterialTheme.typography.labelLarge)
                    HorizontalDivider(Modifier.padding(vertical = 6.dp))
                }
                if (create != null) {
                    val name = newName
                    if (name == null) {
                        TextButton(onClick = { newName = "" }) { Text("+  New folder here") }
                    } else {
                        val valid = name.isNotBlank() && name.none { it in "/\\:*?\"<>|" } && name.trim() != "." && name.trim() != ".."
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            OutlinedTextField(
                                name, { newName = it }, Modifier.weight(1f), label = { Text("New folder name") },
                                singleLine = true, isError = name.isNotEmpty() && !valid,
                            )
                            TextButton(enabled = valid && !creating, onClick = {
                                val target = if (path.isEmpty()) name.trim() else "$path/${name.trim()}"
                                creating = true
                                scope.launch {
                                    try {
                                        withContext(Dispatchers.IO) { create(target) }
                                        newName = null
                                        path = target
                                    } catch (e: CancellationException) {
                                        throw e
                                    } catch (e: Exception) {
                                        error = "Couldn't create the folder: " + (e.message ?: e.toString())
                                    }
                                    creating = false
                                }
                            }) { Text("Create") }
                        }
                    }
                }
                Box(Modifier.height(320.dp).fillMaxWidth()) {
                    LazyColumn(Modifier.fillMaxSize()) {
                        if (path.isNotEmpty()) item {
                            Text("↑  Up one level", Modifier.fillMaxWidth().clickable { path = path.substringBeforeLast('/', "") }.padding(12.dp))
                        }
                        val list = items
                        when {
                            error != null -> item { Text(error!!, Modifier.padding(12.dp), color = MaterialTheme.colorScheme.error) }
                            list == null -> item { CircularProgressIndicator(Modifier.padding(12.dp)) }
                            list.isEmpty() -> item { Text("No subfolders", Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                            else -> items(list) { name ->
                                Text(name, Modifier.fillMaxWidth().clickable { if (pickOnTap) onPick(name) else path = if (path.isEmpty()) name else "$path/$name" }.padding(12.dp))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = { if (!pickOnTap) TextButton(onClick = { onPick(path) }) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Scans the network for computers with file sharing on and lets the user tap one. */
@Composable
private fun HostPicker(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    var round by remember { mutableStateOf(0) }
    var hosts by remember { mutableStateOf<List<FoundHost>?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(round) {
        hosts = null
        error = null
        try {
            hosts = Discovery.scan(ctx).sortedBy { it.ip.substringAfterLast('.').toInt() }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error = e.message ?: e.toString()
        }
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("PCs with file sharing") },
        text = {
            Column(Modifier.fillMaxWidth()) {
                val list = hosts
                when {
                    error != null -> Text(error!!, color = MaterialTheme.colorScheme.error)
                    list == null -> Row(verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.height(20.dp).width(20.dp), strokeWidth = 2.dp)
                        Spacer(Modifier.width(12.dp))
                        Text("Scanning your network…")
                    }
                    list.isEmpty() -> Text(
                        "Nothing found. Make sure the PC is on, on the same Wi-Fi, its network is set to Private, " +
                            "and file sharing is turned on. \"Nearby devices\" must also be allowed for this app."
                    )
                    else -> list.forEach { h ->
                        Column(Modifier.fillMaxWidth().clickable { onPick(h.ip) }.padding(vertical = 10.dp)) {
                            Text(h.name ?: h.ip, style = MaterialTheme.typography.titleMedium)
                            if (h.name != null) Text(h.ip, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(enabled = hosts != null || error != null, onClick = { round++ }) { Text("Scan again") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

// ---------------------------------------------------------------- Log

private class LogRun(val start: LogEntry?, val end: LogEntry?, val rows: List<LogEntry>) {
    val id: Long get() = (start ?: rows.firstOrNull() ?: end)?.time ?: 0
    val time: Long get() = id
    val failed: Boolean get() = end?.type == LogType.FAILED
    fun count(vararg t: LogType) = rows.count { it.type in t }
    val bytes: Long get() = rows.filter { it.type == LogType.UP || it.type == LogType.DOWN || it.type == LogType.CONFLICT }.sumOf { it.size }
    /** A finished run in which nothing was copied, deleted or went wrong. */
    val quiet: Boolean get() = end != null && !failed && rows.none { it.type != LogType.INFO }
}

private sealed interface LogItem {
    class One(val run: LogRun) : LogItem
    /** Several back-to-back checks that found nothing to do, folded into one line. */
    class Quiet(val runs: List<LogRun>) : LogItem
}

/** Splits the flat log into sync runs, newest first, folding consecutive no-change runs together. */
private fun groupRuns(entries: List<LogEntry>): List<LogItem> {
    val runs = ArrayList<LogRun>()
    var start: LogEntry? = null
    var rows = ArrayList<LogEntry>()
    fun close(end: LogEntry?) {
        if (start != null || rows.isNotEmpty() || end != null) runs += LogRun(start, end, rows)
        start = null
        rows = ArrayList()
    }
    for (e in entries) when (e.type) {
        LogType.START -> { close(null); start = e }
        LogType.END, LogType.FAILED -> close(e)
        else -> rows += e
    }
    close(null)
    val out = ArrayList<LogItem>()
    var quiet = ArrayList<LogRun>()
    for (r in runs.asReversed()) {
        if (r.quiet) quiet += r else {
            if (quiet.isNotEmpty()) { out += LogItem.Quiet(quiet); quiet = ArrayList() }
            out += LogItem.One(r)
        }
    }
    if (quiet.isNotEmpty()) out += LogItem.Quiet(quiet)
    return out
}

private val SUMMARY_SHAPE =
    Regex("""^(Stopped – )?(\d+ (sent to PC|received|deleted|conflicts?|errors?)(, )?)+( \(.*\))?$""")

private enum class LogFilter(val label: String, val types: Set<LogType>) {
    ALL("All", emptySet()),
    SENT("Sent", setOf(LogType.UP)),
    RECEIVED("Received", setOf(LogType.DOWN)),
    DELETED("Deleted", setOf(LogType.DEL_PHONE, LogType.DEL_PC)),
    PROBLEMS("Problems", setOf(LogType.ERROR, LogType.CONFLICT, LogType.FAILED)),
}

private fun dayLabel(time: Long): String {
    val clock = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault()).format(java.util.Date(time))
    val day = when {
        DateUtils.isToday(time) -> "Today"
        DateUtils.isToday(time + DateUtils.DAY_IN_MILLIS) -> "Yesterday"
        else -> java.text.SimpleDateFormat("EEE d MMM", java.util.Locale.getDefault()).format(java.util.Date(time))
    }
    return "$day, $clock"
}

@Composable
private fun LogScreen(id: String, onDone: () -> Unit) {
    val running by SyncStatus.running.collectAsState()
    var items by remember { mutableStateOf<List<LogItem>?>(null) }
    var version by remember { mutableStateOf(0) }
    var filter by remember { mutableStateOf(LogFilter.ALL) }
    val isRunning = running[id] != null
    LaunchedEffect(isRunning, version) {
        do {
            items = withContext(Dispatchers.IO) { groupRuns(SyncLog(PairStore.logFile(id)).read()) }
            if (isRunning) kotlinx.coroutines.delay(1500)
        } while (isRunning)
    }
    Scaffold(topBar = {
        TopAppBar(
            title = { Text("Log – ${PairStore.get(id)?.name ?: ""}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
            navigationIcon = { IconButton(onClick = onDone) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            actions = {
                TextButton(enabled = !isRunning, onClick = { SyncLog(PairStore.logFile(id)).clear(); version++ }) { Text("Clear") }
            },
        )
    }) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            Row(
                Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                LogFilter.entries.forEach { f ->
                    FilterChip(selected = filter == f, onClick = { filter = f }, label = { Text(f.label) })
                }
            }
            val shown = (items ?: emptyList()).filter { item ->
                when {
                    filter == LogFilter.ALL -> true
                    item is LogItem.One -> item.run.rows.any { it.type in filter.types } || (filter == LogFilter.PROBLEMS && item.run.failed)
                    else -> false
                }
            }
            LazyColumn(
                Modifier.fillMaxSize(),
                contentPadding = PaddingValues(start = 12.dp, end = 12.dp, top = 8.dp, bottom = 24.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                if (items != null && shown.isEmpty()) item {
                    Text(
                        if (filter == LogFilter.ALL) "Nothing logged yet." else "Nothing in this category.",
                        Modifier.padding(12.dp), color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                itemsIndexed(shown, key = { _, it -> if (it is LogItem.One) it.run.id else -(it as LogItem.Quiet).runs.first().id }) { index, item ->
                    when (item) {
                        is LogItem.One -> RunCard(item.run, filter, startExpanded = index == 0)
                        is LogItem.Quiet -> QuietLine(item.runs)
                    }
                }
            }
        }
    }
}

@Composable
private fun QuietLine(runs: List<LogRun>) {
    val newest = runs.first().time
    val oldest = runs.last().time
    Text(
        if (runs.size == 1) "✓  ${dayLabel(newest)} · checked, nothing to sync"
        else "✓  ${runs.size} checks with nothing to sync · ${dayLabel(oldest)} → ${dayLabel(newest)}",
        Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 2.dp),
        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

/** One coloured count such as "↑ 8 sent"; hidden when the count is zero. */
@Composable
private fun CountPill(symbol: String, n: Int, word: String, color: androidx.compose.ui.graphics.Color) {
    if (n == 0) return
    Surface(color = color.copy(alpha = 0.14f), shape = MaterialTheme.shapes.small) {
        Text("$symbol $n $word", Modifier.padding(horizontal = 8.dp, vertical = 4.dp), color = color, style = MaterialTheme.typography.labelLarge)
    }
}

@Composable
private fun RunCard(run: LogRun, filter: LogFilter, startExpanded: Boolean) {
    val cs = MaterialTheme.colorScheme
    var expanded by remember { mutableStateOf(startExpanded || filter != LogFilter.ALL) }
    val problems = run.rows.filter { it.type == LogType.ERROR || (it.type == LogType.CONFLICT && it.size == 0L) }
    // Files are what people look for; "folder created" rows and the plan line are bookkeeping.
    val files = run.rows.filter {
        it.type in setOf(LogType.UP, LogType.DOWN, LogType.DEL_PHONE, LogType.DEL_PC) || (it.type == LogType.CONFLICT && it.size > 0)
    }.filter { filter == LogFilter.ALL || it.type in filter.types }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(dayLabel(run.time), style = MaterialTheme.typography.titleMedium)
                Spacer(Modifier.weight(1f))
                val bits = ArrayList<String>()
                run.start?.text?.substringAfterLast(" · ", "")?.takeIf { it.isNotEmpty() }?.let { bits += it }
                if (run.start != null && run.end != null) bits += fmtDuration(maxOf(0, (run.end.time - run.start.time) / 1000))
                Text(bits.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            Spacer(Modifier.height(8.dp))
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                CountPill("↑", run.count(LogType.UP), "sent", cs.primary)
                CountPill("↓", run.count(LogType.DOWN), "received", cs.tertiary)
                CountPill("✕", run.count(LogType.DEL_PHONE, LogType.DEL_PC), "deleted", cs.error)
                CountPill("≠", run.count(LogType.CONFLICT), "conflict", cs.error)
                CountPill("!", run.count(LogType.ERROR), "failed", cs.error)
            }
            val sub = ArrayList<String>()
            if (run.bytes > 0) sub += fmtBytes(run.bytes)
            val folders = run.count(LogType.FOLDER)
            if (folders > 0) sub += if (folders == 1) "1 folder created" else "$folders folders created"
            if (run.end == null) sub += "in progress…"
            if (sub.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                Text(sub.joinToString(" · "), style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
            }
            // A run that merely had failed files is already described by the counts above;
            // only whole-run failures ("PC not reachable", "Safety stop…") need their message shown.
            if (run.failed && !SUMMARY_SHAPE.matches(run.end!!.text)) {
                Spacer(Modifier.height(8.dp))
                Text(run.end!!.text, color = cs.error, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
            }
            // Problems are always visible, never hidden behind "show files".
            problems.forEach { e ->
                Spacer(Modifier.height(6.dp))
                Text((if (e.type == LogType.ERROR) "!  " else "≠  ") + e.text, color = cs.error, style = MaterialTheme.typography.bodySmall)
            }
            if (files.isNotEmpty()) {
                TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
                    Text(if (expanded) "Hide files" else "Show ${files.size} file" + if (files.size == 1) "" else "s")
                }
                if (expanded) {
                    val limit = 200
                    files.take(limit).groupBy { it.text.trimEnd('/').substringBeforeLast('/', "") }.forEach { (folder, list) ->
                        Text(
                            if (folder.isEmpty()) "(top folder)" else folder.replace("/", " › "),
                            Modifier.padding(top = 8.dp, bottom = 2.dp),
                            style = MaterialTheme.typography.labelMedium, color = cs.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis,
                        )
                        list.forEach { FileLine(it) }
                    }
                    if (files.size > limit) Text("…and ${files.size - limit} more", style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
                }
            }
        }
    }
}

/** One file on one line: direction, name, size. */
@Composable
private fun FileLine(e: LogEntry) {
    val cs = MaterialTheme.colorScheme
    val (symbol, color) = when (e.type) {
        LogType.UP -> "↑" to cs.primary
        LogType.DOWN -> "↓" to cs.tertiary
        LogType.CONFLICT -> "≠" to cs.error
        else -> "✕" to cs.error
    }
    val where = when (e.type) {
        LogType.DEL_PHONE -> "from phone"
        LogType.DEL_PC -> "from PC"
        LogType.CONFLICT -> "kept both"
        else -> if (e.size > 0) fmtBytes(e.size) else ""
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 2.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(symbol, Modifier.width(22.dp), color = color, fontWeight = FontWeight.Bold)
        Text(
            e.text.trimEnd('/').substringAfterLast('/'), Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.MiddleEllipsis,
        )
        Spacer(Modifier.width(8.dp))
        Text(where, style = MaterialTheme.typography.bodySmall, color = cs.onSurfaceVariant)
    }
}
