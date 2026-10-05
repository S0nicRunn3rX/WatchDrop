package com.daniil.watchdrop

import android.Manifest
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.wear.compose.foundation.lazy.TransformingLazyColumn
import androidx.wear.compose.foundation.lazy.rememberTransformingLazyColumnState
import androidx.wear.compose.material3.AppScaffold
import androidx.wear.compose.material3.ButtonDefaults
import androidx.wear.compose.material3.ColorScheme as WearColorScheme
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight

class MainActivity : ComponentActivity() {
    private var uiState by mutableStateOf(MainUiState())

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        refreshState()
        if (PermissionUtils.hasBluetoothConnect(this) && AppPrefs.isReceiveEnabled(this)) {
            startListenerService()
        }
    }

    private val filePicker = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) openShareScreen(uris)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        refreshState()
        setContent {
            WatchDropRoot(
                state = uiState,
                onToggleReceiver = ::toggleReceiver,
                onPickFiles = { filePicker.launch(arrayOf("*/*")) },
                onBluetoothSettings = ::openBluetoothSettings,
                onRequestPermissions = ::requestRequiredPermissions
            )
        }
        requestRequiredPermissions()
    }

    override fun onResume() {
        super.onResume()
        refreshState()
        if (PermissionUtils.hasBluetoothConnect(this) && AppPrefs.isReceiveEnabled(this)) {
            startListenerService()
        }
    }

    private fun refreshState() {
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = manager?.adapter
        val isWatch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH
        uiState = MainUiState(
            isWatch = isWatch,
            bluetoothAvailable = adapter != null,
            bluetoothEnabled = try { adapter?.isEnabled == true } catch (_: SecurityException) { false },
            receiveEnabled = AppPrefs.isReceiveEnabled(this),
            bluetoothPermissionGranted = PermissionUtils.hasBluetoothConnect(this)
        )
    }

    private fun requestRequiredPermissions() {
        val needed = buildList {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !PermissionUtils.hasBluetoothConnect(this@MainActivity)) {
                add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU && !PermissionUtils.hasNotificationPermission(this@MainActivity)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isNotEmpty()) permissionLauncher.launch(needed.toTypedArray())
    }

    private fun toggleReceiver(enabled: Boolean) {
        if (!PermissionUtils.hasBluetoothConnect(this)) {
            requestRequiredPermissions()
            return
        }
        AppPrefs.setReceiveEnabled(this, enabled)
        val service = Intent(this, BluetoothTransferService::class.java).apply {
            action = if (enabled) BluetoothTransferService.ACTION_START_LISTENER
            else BluetoothTransferService.ACTION_STOP_LISTENER
        }
        ContextCompat.startForegroundService(this, service)
        refreshState()
    }

    private fun startListenerService() {
        val service = Intent(this, BluetoothTransferService::class.java).apply {
            action = BluetoothTransferService.ACTION_START_LISTENER
        }
        ContextCompat.startForegroundService(this, service)
    }

    private fun openShareScreen(uris: List<Uri>) {
        val intent = Intent(this, ShareActivity::class.java).apply {
            action = if (uris.size == 1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE
            type = "*/*"
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (uris.size == 1) putExtra(Intent.EXTRA_STREAM, uris.first())
            else putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris))
        }
        startActivity(intent)
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

data class MainUiState(
    val isWatch: Boolean = false,
    val bluetoothAvailable: Boolean = true,
    val bluetoothEnabled: Boolean = false,
    val receiveEnabled: Boolean = true,
    val bluetoothPermissionGranted: Boolean = false
)

@Composable
private fun WatchDropRoot(
    state: MainUiState,
    onToggleReceiver: (Boolean) -> Unit,
    onPickFiles: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    if (state.isWatch) {
        WearWatchDropTheme {
            WearMainScreen(state, onToggleReceiver, onPickFiles, onBluetoothSettings, onRequestPermissions)
        }
    } else {
        PhoneWatchDropTheme {
            PhoneMainScreen(state, onToggleReceiver, onPickFiles, onBluetoothSettings, onRequestPermissions)
        }
    }
}

@Composable
private fun WearWatchDropTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    WearMaterialTheme(colorScheme = dynamicColorScheme(context) ?: WearColorScheme(), content = content)
}

@Composable
private fun PhoneWatchDropTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
    } else {
        if (dark) darkColorScheme() else lightColorScheme()
    }
    MaterialTheme(colorScheme = colors, content = content)
}

@Composable
private fun WearMainScreen(
    state: MainUiState,
    onToggleReceiver: (Boolean) -> Unit,
    onPickFiles: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    AppScaffold {
        val listState = rememberTransformingLazyColumnState()
        val transformationSpec = rememberTransformationSpec()
        ScreenScaffold(scrollState = listState) { contentPadding ->
            TransformingLazyColumn(
                state = listState,
                contentPadding = contentPadding,
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                item {
                    ListHeader(
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec)
                    ) { WearText("WatchDrop") }
                }
                item {
                    WearText(
                        text = when {
                            !state.bluetoothAvailable -> "Bluetooth недоступен"
                            !state.bluetoothPermissionGranted -> "Нужен доступ к Bluetooth"
                            !state.bluetoothEnabled -> "Bluetooth выключен"
                            state.receiveEnabled -> "Готов к приёму файлов"
                            else -> "Приём файлов выключен"
                        },
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 10.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }
                item {
                    WearButton(
                        onClick = onPickFiles,
                        enabled = state.bluetoothPermissionGranted && state.bluetoothEnabled,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { WearText("Отправить файл") },
                        secondaryLabel = { WearText("Выбрать фото или документ") }
                    )
                }
                item {
                    WearFilledTonalButton(
                        onClick = { onToggleReceiver(!state.receiveEnabled) },
                        enabled = state.bluetoothPermissionGranted,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { WearText(if (state.receiveEnabled) "Выключить приём" else "Включить приём") },
                        secondaryLabel = { WearText("Bluetooth RFCOMM") }
                    )
                }
                if (!state.bluetoothPermissionGranted) {
                    item {
                        WearFilledTonalButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            label = { WearText("Разрешить Bluetooth") }
                        )
                    }
                }
                item {
                    WearFilledTonalButton(
                        onClick = onBluetoothSettings,
                        modifier = Modifier
                            .fillMaxWidth()
                            .transformedHeight(this, transformationSpec),
                        transformation = SurfaceTransformation(transformationSpec),
                        label = { WearText("Bluetooth") },
                        secondaryLabel = { WearText("Сопряжённые устройства") }
                    )
                }
                item {
                    WearText(
                        "Из других приложений: Поделиться → WatchDrop",
                        textAlign = TextAlign.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 12.dp, vertical = 8.dp)
                            .transformedHeight(this, transformationSpec)
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneMainScreen(
    state: MainUiState,
    onToggleReceiver: (Boolean) -> Unit,
    onPickFiles: () -> Unit,
    onBluetoothSettings: () -> Unit,
    onRequestPermissions: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("WatchDrop") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp)
        ) {
            Text(
                "Передача файлов между Android и Wear OS по Bluetooth",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainer)) {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Состояние", style = MaterialTheme.typography.titleMedium)
                    Text(
                        when {
                            !state.bluetoothAvailable -> "Bluetooth недоступен на устройстве"
                            !state.bluetoothPermissionGranted -> "Разрешите доступ к устройствам поблизости"
                            !state.bluetoothEnabled -> "Bluetooth выключен"
                            state.receiveEnabled -> "Приём файлов активен"
                            else -> "Приём файлов отключён"
                        }
                    )
                }
            }
            Button(
                onClick = onPickFiles,
                enabled = state.bluetoothPermissionGranted && state.bluetoothEnabled,
                modifier = Modifier.fillMaxWidth(),
                contentPadding = PaddingValues(vertical = 14.dp)
            ) { Text("Выбрать файлы и отправить") }
            Card {
                Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Приём файлов", style = MaterialTheme.typography.titleMedium)
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text("Фоновый Bluetooth-приёмник")
                        Switch(
                            checked = state.receiveEnabled,
                            onCheckedChange = onToggleReceiver,
                            enabled = state.bluetoothPermissionGranted
                        )
                    }
                }
            }
            if (!state.bluetoothPermissionGranted) {
                Button(onClick = onRequestPermissions, modifier = Modifier.fillMaxWidth()) {
                    Text("Разрешить доступ к Bluetooth")
                }
            }
            Button(onClick = onBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
                Text("Настройки Bluetooth")
            }
            Spacer(Modifier.height(4.dp))
            Text(
                "Для отправки из Галереи или файлового менеджера используйте: Поделиться → WatchDrop. " +
                    "Приложение должно быть установлено на обоих устройствах, а устройства — сопряжены.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
