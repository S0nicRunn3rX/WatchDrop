package com.daniil.watchdrop

import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.Environment
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import java.io.File
import java.util.Locale

class BuiltInFilePickerActivity : ComponentActivity() {
    companion object {
        const val EXTRA_SELECTED_URIS = "selected_uris"
    }

    private val storageRoot: File by lazy { Environment.getExternalStorageDirectory() }
    private var currentDirectory by mutableStateOf<File?>(null)
    private var entries by mutableStateOf<List<File>>(emptyList())
    private var accessGranted by mutableStateOf(false)
    private val selectedFiles = mutableStateListOf<File>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        currentDirectory = storageRoot
        refreshAccessAndFiles()
        setContent {
            PickerTheme {
                FilePickerScreen(
                    directory = currentDirectory ?: storageRoot,
                    root = storageRoot,
                    entries = entries,
                    selected = selectedFiles.toSet(),
                    accessGranted = accessGranted,
                    onRequestAccess = ::refreshAccessAndFiles,
                    onEntry = ::handleEntry,
                    onToggleFile = ::toggleFile,
                    onUp = ::goUp,
                    onCancel = ::finish,
                    onConfirm = ::returnSelection
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refreshAccessAndFiles()
    }

    private fun hasStorageAccess(): Boolean = Environment.isExternalStorageManager()

    private fun refreshAccessAndFiles() {
        accessGranted = hasStorageAccess()
        if (accessGranted) loadDirectory(currentDirectory ?: storageRoot)
    }

    private fun loadDirectory(directory: File) {
        val safeDirectory = directory.takeIf { it.exists() && it.isDirectory } ?: storageRoot
        currentDirectory = safeDirectory
        entries = runCatching {
            safeDirectory.listFiles().orEmpty()
                .asSequence()
                .filter { !it.isHidden && (it.isDirectory || it.isFile) }
                .sortedWith(
                    compareBy<File> { !it.isDirectory }
                        .thenBy { it.name.lowercase(Locale.getDefault()) }
                )
                .toList()
        }.getOrDefault(emptyList())
    }

    private fun handleEntry(file: File) {
        if (file.isDirectory) loadDirectory(file) else toggleFile(file)
    }

    private fun toggleFile(file: File) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file)
        } else if (selectedFiles.size < Protocol.MAX_FILES_PER_TRANSFER) {
            selectedFiles.add(file)
        }
    }

    private fun goUp() {
        val directory = currentDirectory ?: storageRoot
        if (directory.absolutePath == storageRoot.absolutePath) return
        directory.parentFile?.takeIf {
            it.absolutePath.startsWith(storageRoot.absolutePath)
        }?.let(::loadDirectory)
    }

    private fun returnSelection() {
        if (selectedFiles.isEmpty()) return
        val uris = ArrayList(selectedFiles.map { file ->
            FileProvider.getUriForFile(this, "$packageName.files", file)
        })
        setResult(RESULT_OK, Intent().putParcelableArrayListExtra(EXTRA_SELECTED_URIS, uris))
        finish()
    }
}

@Composable
private fun PickerTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val scheme = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@Composable
private fun FilePickerScreen(
    directory: File,
    root: File,
    entries: List<File>,
    selected: Set<File>,
    accessGranted: Boolean,
    onRequestAccess: () -> Unit,
    onEntry: (File) -> Unit,
    onToggleFile: (File) -> Unit,
    onUp: () -> Unit,
    onCancel: () -> Unit,
    onConfirm: () -> Unit
) {
    Scaffold { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("Выбор файлов", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)

            if (!accessGranted) {
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text("Нужен доступ к файлам", fontWeight = FontWeight.SemiBold)
                        Text(
                            "На Wear OS это разрешение выдаётся через ADB:\n" +
                                "adb shell appops set com.daniil.watchdrop " +
                                "MANAGE_EXTERNAL_STORAGE allow"
                        )
                        Button(onClick = onRequestAccess, modifier = Modifier.fillMaxWidth()) {
                            Text("Проверить доступ")
                        }
                    }
                }
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedButton(
                        onClick = onUp,
                        enabled = directory.absolutePath != root.absolutePath
                    ) { Text("Вверх") }
                    Text(
                        directory.absolutePath.removePrefix(root.absolutePath).ifBlank { "/" },
                        modifier = Modifier.weight(1f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis
                    )
                }

                LazyColumn(
                    modifier = Modifier.weight(1f),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(entries, key = { it.absolutePath }) { file ->
                        FileEntryRow(
                            file = file,
                            checked = selected.contains(file),
                            onOpen = { onEntry(file) },
                            onToggle = { onToggleFile(file) }
                        )
                    }
                }
            }

            Text("Выбрано: ${selected.size} из ${Protocol.MAX_FILES_PER_TRANSFER}")
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = onCancel, modifier = Modifier.weight(1f)) { Text("Отмена") }
                Button(
                    onClick = onConfirm,
                    enabled = selected.isNotEmpty(),
                    modifier = Modifier.weight(1f)
                ) { Text("Далее") }
            }
        }
    }
}

@Composable
private fun FileEntryRow(
    file: File,
    checked: Boolean,
    onOpen: () -> Unit,
    onToggle: () -> Unit
) {
    Card(modifier = Modifier.fillMaxWidth().clickable(onClick = onOpen)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(if (file.isDirectory) "Папка" else "Файл", modifier = Modifier.padding(end = 10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(file.name, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (file.isDirectory) "Открыть папку" else formatFileSize(file.length()),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            if (file.isFile) Checkbox(checked = checked, onCheckedChange = { onToggle() })
        }
    }
}

private fun formatFileSize(bytes: Long): String = when {
    bytes >= 1024L * 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f ГБ", bytes / (1024.0 * 1024.0 * 1024.0))
    bytes >= 1024L * 1024L -> String.format(Locale.getDefault(), "%.1f МБ", bytes / (1024.0 * 1024.0))
    bytes >= 1024L -> String.format(Locale.getDefault(), "%.1f КБ", bytes / 1024.0)
    else -> "$bytes Б"
}
