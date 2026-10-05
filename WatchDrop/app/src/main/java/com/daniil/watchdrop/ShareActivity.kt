package com.daniil.watchdrop

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
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
import androidx.wear.compose.material3.Button as WearButton
import androidx.wear.compose.material3.ColorScheme as WearColorScheme
import androidx.wear.compose.material3.FilledTonalButton as WearFilledTonalButton
import androidx.wear.compose.material3.ListHeader
import androidx.wear.compose.material3.MaterialTheme as WearMaterialTheme
import androidx.wear.compose.material3.ScreenScaffold
import androidx.wear.compose.material3.SurfaceTransformation
import androidx.wear.compose.material3.Text as WearText
import androidx.wear.compose.material3.dynamicColorScheme
import androidx.wear.compose.material3.lazy.rememberTransformationSpec
import androidx.wear.compose.material3.lazy.transformedHeight
import java.util.UUID

class ShareActivity : ComponentActivity() {
    private var uiState by mutableStateOf(ShareUiState())
    private var sharedUris = arrayListOf<Uri>()
    private val transferId = UUID.randomUUID().toString()
    private var receiverRegistered = false

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted || PermissionUtils.hasBluetoothConnect(this)) {
            loadDevices()
        } else {
            uiState = uiState.copy(
                loading = false,
                error = "Без разрешения Bluetooth нельзя выбрать устройство"
            )
        }
    }

    private val statusReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothTransferService.ACTION_TRANSFER_STATUS) return
            if (intent.getStringExtra(BluetoothTransferService.EXTRA_TRANSFER_ID) != transferId) return

            val status = intent.getStringExtra(BluetoothTransferService.EXTRA_STATUS)
            val message = intent.getStringExtra(BluetoothTransferService.EXTRA_MESSAGE).orEmpty()
            uiState = when (status) {
                BluetoothTransferService.STATUS_SUCCESS -> uiState.copy(
                    transferring = false,
                    success = true,
                    error = null,
                    message = if (message.isBlank()) "Передача завершена" else message
                )
                BluetoothTransferService.STATUS_ERROR -> uiState.copy(
                    transferring = false,
                    success = false,
                    error = if (message.isBlank()) "Ошибка передачи" else message,
                    message = ""
                )
                else -> uiState.copy(
                    transferring = true,
                    error = null,
                    message = if (message.isBlank()) "Передача…" else message
                )
            }
            if (status == BluetoothTransferService.STATUS_SUCCESS) {
                Handler(Looper.getMainLooper()).postDelayed({
                    if (!isFinishing) finish()
                }, 900)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        sharedUris = extractUris(intent)
        val isWatch = packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH) ||
            (resources.configuration.uiMode and Configuration.UI_MODE_TYPE_MASK) == Configuration.UI_MODE_TYPE_WATCH
        uiState = uiState.copy(isWatch = isWatch, fileCount = sharedUris.size)

        setContent {
            if (uiState.isWatch) {
                WearShareTheme {
                    WearShareScreen(
                        state = uiState,
                        onDevice = ::sendTo,
                        onBluetoothSettings = ::openBluetoothSettings,
                        onPermission = ::requestBluetoothPermission,
                        onClose = ::finish
                    )
                }
            } else {
                PhoneShareTheme {
                    PhoneShareScreen(
                        state = uiState,
                        onDevice = ::sendTo,
                        onBluetoothSettings = ::openBluetoothSettings,
                        onPermission = ::requestBluetoothPermission,
                        onClose = ::finish
                    )
                }
            }
        }

        if (sharedUris.isEmpty()) {
            uiState = uiState.copy(loading = false, error = "Не удалось получить выбранные файлы")
        } else if (!PermissionUtils.hasBluetoothConnect(this)) {
            requestBluetoothPermission()
        } else {
            loadDevices()
        }
    }

    override fun onStart() {
        super.onStart()
        registerStatusReceiver()
    }

    override fun onStop() {
        unregisterStatusReceiver()
        super.onStop()
    }

    private fun requestBluetoothPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && !PermissionUtils.hasBluetoothConnect(this)) {
            permissionLauncher.launch(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            loadDevices()
        }
    }

    private fun loadDevices() {
        if (!PermissionUtils.hasBluetoothConnect(this)) return
        val adapter = (getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager)?.adapter
        if (adapter == null) {
            uiState = uiState.copy(loading = false, bluetoothEnabled = false, error = "Bluetooth недоступен")
            return
        }
        val enabled = try { adapter.isEnabled } catch (_: SecurityException) { false }
        if (!enabled) {
            uiState = uiState.copy(loading = false, bluetoothEnabled = false, error = "Включите Bluetooth")
            return
        }

        val preferred = AppPrefs.getPreferredDevice(this)
        val devices = try {
            adapter.bondedDevices
                .map { device ->
                    ShareDevice(
                        name = safeName(device),
                        address = device.address,
                        preferred = device.address == preferred
                    )
                }
                .sortedWith(compareByDescending<ShareDevice> { it.preferred }.thenBy { it.name.lowercase() })
        } catch (_: SecurityException) {
            emptyList()
        }

        uiState = uiState.copy(
            loading = false,
            bluetoothEnabled = true,
            devices = devices,
            error = if (devices.isEmpty()) "Нет сопряжённых Bluetooth-устройств" else null
        )
    }

    private fun sendTo(device: ShareDevice) {
        if (uiState.transferring || sharedUris.isEmpty()) return
        uiState = uiState.copy(
            transferring = true,
            selectedAddress = device.address,
            error = null,
            message = "Подключение к ${device.name}…"
        )

        val service = Intent(this, BluetoothTransferService::class.java).apply {
            action = BluetoothTransferService.ACTION_SEND_FILES
            putExtra(BluetoothTransferService.EXTRA_DEVICE_ADDRESS, device.address)
            putParcelableArrayListExtra(BluetoothTransferService.EXTRA_URIS, ArrayList(sharedUris))
            putExtra(BluetoothTransferService.EXTRA_TRANSFER_ID, transferId)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        ContextCompat.startForegroundService(this, service)
    }

    private fun extractUris(source: Intent?): ArrayList<Uri> {
        val result = linkedSetOf<Uri>()
        val clip = source?.clipData
        if (clip != null) {
            for (i in 0 until clip.itemCount) clip.getItemAt(i).uri?.let(result::add)
        }
        if (source?.action == Intent.ACTION_SEND_MULTIPLE) {
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                source.getParcelableArrayListExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                source.getParcelableArrayListExtra<Uri>(Intent.EXTRA_STREAM)
            }
            list?.let(result::addAll)
        } else {
            val uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                source?.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
            } else {
                @Suppress("DEPRECATION")
                source?.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
            }
            uri?.let(result::add)
        }
        return ArrayList(result)
    }

    private fun registerStatusReceiver() {
        if (receiverRegistered) return
        val filter = IntentFilter(BluetoothTransferService.ACTION_TRANSFER_STATUS)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(statusReceiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            @Suppress("DEPRECATION")
            registerReceiver(statusReceiver, filter)
        }
        receiverRegistered = true
    }

    private fun unregisterStatusReceiver() {
        if (!receiverRegistered) return
        runCatching { unregisterReceiver(statusReceiver) }
        receiverRegistered = false
    }

    private fun safeName(device: BluetoothDevice): String = try {
        device.name?.takeIf { it.isNotBlank() } ?: device.address
    } catch (_: SecurityException) {
        device.address
    }

    private fun openBluetoothSettings() {
        runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
            .onFailure { startActivity(Intent(Settings.ACTION_SETTINGS)) }
    }
}

data class ShareDevice(
    val name: String,
    val address: String,
    val preferred: Boolean
)

data class ShareUiState(
    val isWatch: Boolean = false,
    val fileCount: Int = 0,
    val loading: Boolean = true,
    val bluetoothEnabled: Boolean = true,
    val devices: List<ShareDevice> = emptyList(),
    val selectedAddress: String? = null,
    val transferring: Boolean = false,
    val success: Boolean = false,
    val message: String = "",
    val error: String? = null
)

@Composable
private fun WearShareTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    WearMaterialTheme(colorScheme = dynamicColorScheme(context) ?: WearColorScheme(), content = content)
}

@Composable
private fun PhoneShareTheme(content: @Composable () -> Unit) {
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
private fun WearShareScreen(
    state: ShareUiState,
    onDevice: (ShareDevice) -> Unit,
    onBluetoothSettings: () -> Unit,
    onPermission: () -> Unit,
    onClose: () -> Unit
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
                    ) {
                        WearText(if (state.transferring) "Отправка" else "Отправить на")
                    }
                }

                if (state.transferring || state.success) {
                    item {
                        WearText(
                            text = state.message.ifBlank { "Передача…" },
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 8.dp)
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                } else if (state.loading) {
                    item {
                        WearText(
                            "Поиск сопряжённых устройств…",
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp)
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                } else {
                    item {
                        WearText(
                            "${state.fileCount} ${fileWord(state.fileCount)}",
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    state.devices.forEach { device ->
                        item(key = device.address) {
                            WearButton(
                                onClick = { onDevice(device) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .transformedHeight(this, transformationSpec),
                                transformation = SurfaceTransformation(transformationSpec),
                                label = { WearText(device.name) },
                                secondaryLabel = {
                                    WearText(if (device.preferred) "Последнее устройство" else device.address)
                                }
                            )
                        }
                    }
                }

                state.error?.let { error ->
                    item {
                        WearText(
                            error,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                                .transformedHeight(this, transformationSpec)
                        )
                    }
                    if (!PermissionUtils.hasBluetoothConnect(LocalContext.current)) {
                        item {
                            WearFilledTonalButton(
                                onClick = onPermission,
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
                            label = { WearText("Настройки Bluetooth") }
                        )
                    }
                }

                if (!state.transferring) {
                    item {
                        WearFilledTonalButton(
                            onClick = onClose,
                            modifier = Modifier
                                .fillMaxWidth()
                                .transformedHeight(this, transformationSpec),
                            transformation = SurfaceTransformation(transformationSpec),
                            label = { WearText("Отмена") }
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PhoneShareScreen(
    state: ShareUiState,
    onDevice: (ShareDevice) -> Unit,
    onBluetoothSettings: () -> Unit,
    onPermission: () -> Unit,
    onClose: () -> Unit
) {
    Scaffold(topBar = { TopAppBar(title = { Text("Отправить через WatchDrop") }) }) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                if (state.transferring) state.message.ifBlank { "Передача…" }
                else "Выберите сопряжённое устройство",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.SemiBold
            )
            if (!state.transferring) {
                Text(
                    "${state.fileCount} ${fileWord(state.fileCount)}",
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (!state.transferring) {
                state.devices.forEach { device ->
                    Card(
                        onClick = { onDevice(device) },
                        modifier = Modifier.fillMaxWidth(),
                        colors = CardDefaults.cardColors(
                            containerColor = if (device.preferred) MaterialTheme.colorScheme.secondaryContainer
                            else MaterialTheme.colorScheme.surfaceContainer
                        )
                    ) {
                        Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text(device.name, style = MaterialTheme.typography.titleMedium)
                            Text(
                                if (device.preferred) "Последнее устройство · ${device.address}" else device.address,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            state.error?.let { error ->
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                    Text(
                        error,
                        modifier = Modifier.padding(18.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                if (!PermissionUtils.hasBluetoothConnect(LocalContext.current)) {
                    Button(onClick = onPermission, modifier = Modifier.fillMaxWidth()) {
                        Text("Разрешить Bluetooth")
                    }
                }
                Button(onClick = onBluetoothSettings, modifier = Modifier.fillMaxWidth()) {
                    Text("Настройки Bluetooth")
                }
            }

            if (!state.transferring) {
                OutlinedButton(
                    onClick = onClose,
                    modifier = Modifier.fillMaxWidth(),
                    contentPadding = PaddingValues(vertical = 12.dp)
                ) { Text("Отмена") }
            }
        }
    }
}

private fun fileWord(count: Int): String {
    val mod100 = count % 100
    val mod10 = count % 10
    return when {
        mod100 in 11..14 -> "файлов"
        mod10 == 1 -> "файл"
        mod10 in 2..4 -> "файла"
        else -> "файлов"
    }
}
