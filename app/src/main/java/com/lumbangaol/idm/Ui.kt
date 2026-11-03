package com.lumbangaol.idm

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@Composable
fun IdmTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val context = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= Build.VERSION_CODES.S ->
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    engine: DownloadEngine,
    sharedText: String?,
    onSharedConsumed: () -> Unit
) {
    val context = LocalContext.current
    val all by engine.items.collectAsStateWithLifecycle()
    var tab by remember { mutableIntStateOf(0) }
    var addText by remember { mutableStateOf<String?>(null) }
    var toDelete by remember { mutableStateOf<DownloadItem?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { }

    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(sharedText) {
        if (sharedText != null) {
            addText = sharedText
            onSharedConsumed()
        }
    }

    val running = all.filter { it.status != Status.COMPLETED }.sortedBy { it.createdAt }
    val done = all.filter { it.status == Status.COMPLETED }.sortedByDescending { it.createdAt }
    val anyActive = running.any { it.status == Status.DOWNLOADING || it.status == Status.QUEUED }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("IDM Android") },
                actions = {
                    if (anyActive) {
                        TextButton(onClick = { engine.pauseAll() }) { Text("Jeda semua") }
                    }
                }
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { addText = "" }) {
                Text("+ Tambah link")
            }
        }
    ) { padding ->
        Column(Modifier.padding(padding).fillMaxSize()) {
            TabRow(selectedTabIndex = tab) {
                Tab(
                    selected = tab == 0,
                    onClick = { tab = 0 },
                    text = { Text("Berjalan (${running.size})") }
                )
                Tab(
                    selected = tab == 1,
                    onClick = { tab = 1 },
                    text = { Text("Selesai (${done.size})") }
                )
            }

            val list = if (tab == 0) running else done
            if (list.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = if (tab == 0) {
                            "Belum ada unduhan.\nTekan \"+ Tambah link\" untuk mulai."
                        } else {
                            "Belum ada file yang selesai."
                        },
                        textAlign = TextAlign.Center,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            } else {
                LazyColumn(
                    contentPadding = PaddingValues(12.dp, 12.dp, 12.dp, 96.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    items(list, key = { it.id }) { item ->
                        DownloadCard(
                            item = item,
                            onPause = { engine.pause(item.id) },
                            onResume = { engine.resume(item.id) },
                            onOpen = { openFile(context, item) },
                            onDelete = { toDelete = item }
                        )
                    }
                }
            }
        }
    }

    addText?.let { initial ->
        AddDialog(
            initial = initial,
            onAdd = { urls ->
                engine.add(urls)
                tab = 0
                addText = null
            },
            onDismiss = { addText = null }
        )
    }

    toDelete?.let { item ->
        DeleteDialog(
            item = item,
            onConfirm = { alsoFile ->
                engine.remove(item.id, alsoFile)
                toDelete = null
            },
            onDismiss = { toDelete = null }
        )
    }
}

@Composable
fun DownloadCard(
    item: DownloadItem,
    onPause: () -> Unit,
    onResume: () -> Unit,
    onOpen: () -> Unit,
    onDelete: () -> Unit
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp)) {
            Text(
                text = item.fileName,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = hostOf(item.url),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )

            if (item.status != Status.COMPLETED) {
                Spacer(Modifier.height(10.dp))
                if (item.totalBytes > 0) {
                    val fraction = (item.downloadedBytes.toFloat() / item.totalBytes).coerceIn(0f, 1f)
                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.fillMaxWidth()
                    )
                } else if (item.status == Status.DOWNLOADING) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                } else {
                    LinearProgressIndicator(progress = { 0f }, modifier = Modifier.fillMaxWidth())
                }
            }

            Spacer(Modifier.height(6.dp))
            Text(
                text = statusLine(item),
                style = MaterialTheme.typography.bodySmall,
                color = if (item.status == Status.FAILED) {
                    MaterialTheme.colorScheme.error
                } else {
                    MaterialTheme.colorScheme.onSurfaceVariant
                }
            )

            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                when (item.status) {
                    Status.DOWNLOADING, Status.QUEUED ->
                        TextButton(onClick = onPause) { Text("Jeda") }
                    Status.PAUSED, Status.FAILED ->
                        TextButton(onClick = onResume) { Text("Lanjut") }
                    Status.COMPLETED ->
                        TextButton(onClick = onOpen) { Text("Buka") }
                }
                TextButton(onClick = onDelete) { Text("Hapus") }
            }
        }
    }
}

private fun progressText(item: DownloadItem): String {
    val got = formatBytes(item.downloadedBytes)
    return if (item.totalBytes > 0) {
        val percent = item.downloadedBytes * 100 / item.totalBytes
        "$got / ${formatBytes(item.totalBytes)} · $percent%"
    } else {
        got
    }
}

private fun statusLine(item: DownloadItem): String = when (item.status) {
    Status.QUEUED -> "Dalam antrean"
    Status.DOWNLOADING -> buildString {
        append(progressText(item))
        if (item.speedBps > 0) {
            append(" · ${formatBytes(item.speedBps)}/dtk")
            if (item.totalBytes > 0) {
                val remaining = item.totalBytes - item.downloadedBytes
                append(" · sisa ${formatEta(remaining / item.speedBps)}")
            }
        }
    }
    Status.PAUSED -> buildString {
        append("Dijeda · ")
        append(progressText(item))
        if (item.probed && !item.resumable) append(" · server tidak mendukung lanjut, akan mulai ulang")
    }
    Status.FAILED -> "Gagal: ${item.error ?: "penyebab tidak diketahui"}"
    Status.COMPLETED -> "Selesai · ${formatBytes(item.totalBytes)} · tersimpan di Download/IDM"
}

@Composable
fun AddDialog(
    initial: String,
    onAdd: (List<String>) -> Unit,
    onDismiss: () -> Unit
) {
    var text by remember { mutableStateOf(initial) }
    var error by remember { mutableStateOf<String?>(null) }
    val clipboard = LocalClipboardManager.current

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Tambah unduhan") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = {
                        text = it
                        error = null
                    },
                    label = { Text("Link (boleh lebih dari satu)") },
                    minLines = 2,
                    maxLines = 6,
                    isError = error != null,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    modifier = Modifier.fillMaxWidth()
                )
                val message = error
                if (message != null) {
                    Text(
                        text = message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(top = 4.dp)
                    )
                }
                TextButton(onClick = {
                    val pasted = clipboard.getText()?.text
                    if (!pasted.isNullOrBlank()) {
                        text = pasted
                        error = null
                    }
                }) { Text("Tempel dari clipboard") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val urls = parseUrls(text)
                if (urls.isEmpty()) error = "Link tidak valid" else onAdd(urls)
            }) { Text("Unduh") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

@Composable
fun DeleteDialog(
    item: DownloadItem,
    onConfirm: (Boolean) -> Unit,
    onDismiss: () -> Unit
) {
    var alsoFile by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Hapus unduhan?") },
        text = {
            Column {
                Text(item.fileName, maxLines = 2, overflow = TextOverflow.Ellipsis)
                if (item.status == Status.COMPLETED) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(top = 8.dp)
                    ) {
                        Checkbox(checked = alsoFile, onCheckedChange = { alsoFile = it })
                        Text("Hapus juga file dari penyimpanan")
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(alsoFile) }) { Text("Hapus") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Batal") } }
    )
}

private fun openFile(context: Context, item: DownloadItem) {
    val saved = item.savedUri ?: return
    val intent = Intent(Intent.ACTION_VIEW)
        .setDataAndType(Uri.parse(saved), item.mimeType ?: "*/*")
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
    try {
        context.startActivity(intent)
    } catch (e: ActivityNotFoundException) {
        Toast.makeText(context, "Tidak ada aplikasi untuk membuka file ini", Toast.LENGTH_SHORT).show()
    }
}
