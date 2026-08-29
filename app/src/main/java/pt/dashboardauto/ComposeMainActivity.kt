package pt.dashboardauto

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.content.pm.ResolveInfo
import android.app.DownloadManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Build
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.List
import androidx.compose.material.icons.rounded.Place
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.util.Locale

class ComposeMainActivity : ComponentActivity() {
    private val prefs by lazy { getSharedPreferences("dashboard_auto", MODE_PRIVATE) }
    private var permissionRevision by mutableIntStateOf(0)
    private var updateInfo by mutableStateOf<UpdateChecker.UpdateInfo?>(null)
    private var lastUpdateCheckAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { DashboardApp() }
    }

    override fun onResume() {
        super.onResume()
        permissionRevision++
        val now = System.currentTimeMillis()
        if (now - lastUpdateCheckAt >= 30_000L) {
            lastUpdateCheckAt = now
            UpdateChecker.check(this) { info -> runOnUiThread { updateInfo = info } }
        }
    }

    private fun apps(): List<AppItem> {
        val query = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val unique = linkedMapOf<String, AppItem>()
        for (info: ResolveInfo in packageManager.queryIntentActivities(query, 0)) {
            val packageName = info.activityInfo.packageName
            if (packageName == packageNameOfApp()) continue
            unique[packageName] = AppItem(info.loadLabel(packageManager).toString(), packageName)
        }
        return unique.values.sortedBy { it.label.lowercase(Locale.ROOT) }
    }

    private fun packageNameOfApp() = packageName

    private fun installedVersion(): String = try {
        @Suppress("DEPRECATION")
        packageManager.getPackageInfo(packageName, 0).versionName
            ?.takeIf { it.isNotBlank() } ?: BuildConfig.VERSION_NAME
    } catch (_: Exception) {
        BuildConfig.VERSION_NAME
    }

    private fun launchPackage(packageName: String) {
        if (packageName.isBlank()) return
        packageManager.getLaunchIntentForPackage(packageName)?.let { startActivity(it) }
    }

    private fun pairedBluetooth(): List<BluetoothItem> {
        if (!PermissionManager.canConnectBluetooth(this)) return emptyList()
        val manager = getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager ?: return emptyList()
        return try {
            manager.adapter?.bondedDevices.orEmpty()
                .sortedBy { it.name ?: it.address }
                .map { BluetoothItem(it.name?.takeIf(String::isNotBlank) ?: "Dispositivo Bluetooth", it.address) }
        } catch (_: SecurityException) {
            emptyList()
        }
    }

    private fun startCarMode() {
        val missing = missingPermissionLabels()
        if (missing.isNotEmpty()) {
            Toast.makeText(this, "Ativa primeiro: ${missing.joinToString(", ")}", Toast.LENGTH_LONG).show()
            openFirstMissingPermission()
            return
        }
        val service = Intent(this, OverlayService::class.java).putExtra("launch_apps", true).putExtra("launch_mode", "car_mode")
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(service) else startService(service)
        } catch (_: RuntimeException) {
            Toast.makeText(this, "Não foi possível iniciar o Car Mode.", Toast.LENGTH_LONG).show()
        }
    }

    private fun missingPermissionLabels(): List<String> = buildList {
        if (!PermissionManager.canDrawOverlay(this@ComposeMainActivity)) add("overlay")
        if (!PermissionManager.canConnectBluetooth(this@ComposeMainActivity)) add("Bluetooth")
        if (!PermissionManager.canPostNotifications(this@ComposeMainActivity)) add("notificações")
        if (!PermissionManager.isMediaAccessEnabled(this@ComposeMainActivity)) add("controlos de música")
        if (!PermissionManager.isIslandAccessibilityEnabled(this@ComposeMainActivity)) add("toque na Dynamic Island")
    }

    private fun openFirstMissingPermission() {
        when {
            !PermissionManager.canDrawOverlay(this) -> PermissionManager.openOverlaySettings(this)
            !PermissionManager.canConnectBluetooth(this) -> PermissionManager.requestBluetooth(this)
            !PermissionManager.canPostNotifications(this) -> PermissionManager.requestNotifications(this)
            !PermissionManager.isMediaAccessEnabled(this) -> PermissionManager.openMediaSettings(this)
            !PermissionManager.isIslandAccessibilityEnabled(this) -> PermissionManager.openAccessibilitySettings(this)
        }
    }

    private fun applyAccent(value: String) {
        prefs.edit().putString("accent_color", value).apply()
        rebuildOverlay()
    }

    private fun rebuildOverlay() {
        if (!OverlayService.isActive()) return
        val rebuild = Intent(this, OverlayService::class.java)
            .setAction("pt.dashboardauto.action.REBUILD_LAYOUT")
        try {
            if (Build.VERSION.SDK_INT >= 26) startForegroundService(rebuild) else startService(rebuild)
        } catch (_: RuntimeException) { }
    }

    @Composable
    private fun DashboardApp() {
        val currentPermissionRevision = permissionRevision
        val allApps = remember(currentPermissionRevision) { apps() }
        val bluetooth = remember(currentPermissionRevision) { pairedBluetooth() }
        var navigation by remember { mutableStateOf(prefs.getString("navigation_app", "") ?: "") }
        var music by remember { mutableStateOf(prefs.getString("music_app", "") ?: "") }
        var bluetoothAddress by remember { mutableStateOf(prefs.getString("bluetooth_device_address", "") ?: "") }
        var firstRun by remember { mutableStateOf(!prefs.getBoolean("onboarding_complete", false)) }
        var accentKey by remember { mutableStateOf(prefs.getString("accent_color", "blue") ?: "blue") }
        DashboardTheme(accentColor(accentKey)) {
            var dismissedUpdateVersion by remember { mutableStateOf("") }
            updateInfo?.let { info ->
                val dismissedVersion = prefs.getString(UpdateChecker.DISMISSED_UPDATE_ALERT_VERSION, "")
                if (dismissedUpdateVersion != info.version && dismissedVersion != info.version) {
                AlertDialog(
                    onDismissRequest = {
                        prefs.edit().putString(UpdateChecker.DISMISSED_UPDATE_ALERT_VERSION, info.version).apply()
                        dismissedUpdateVersion = info.version
                    },
                    title = { Text("Nova versão disponível") },
                    text = { Text("DriveDeck ${info.version} está disponível.\n\n${info.notes.take(500)}") },
                    confirmButton = {
                        TextButton(onClick = {
                            prefs.edit().putString(UpdateChecker.DISMISSED_UPDATE_ALERT_VERSION, info.version).apply()
                            dismissedUpdateVersion = info.version
                            if (info.downloadUrl != null) downloadUpdate(info) else openRelease(info.releaseUrl)
                        }) { Text(if (info.downloadUrl != null) "Descarregar" else "Ver release") }
                    },
                    dismissButton = { TextButton(onClick = {
                        prefs.edit().putString(UpdateChecker.DISMISSED_UPDATE_ALERT_VERSION, info.version).apply()
                        dismissedUpdateVersion = info.version
                    }) { Text("Agora não") } }
                )
                }
            }
            if (firstRun) {
                OnboardingScreen(
                    apps = allApps,
                    bluetooth = bluetooth,
                    navigation = navigation,
                    music = music,
                    bluetoothAddress = bluetoothAddress,
                    permissionRevision = currentPermissionRevision,
                    onNavigation = { navigation = it; prefs.edit().putString("navigation_app", it).apply() },
                    onMusic = { music = it; prefs.edit().putString("music_app", it).apply() },
                    onBluetooth = { bluetoothAddress = it; prefs.edit().putString("bluetooth_device_address", it).apply() },
                    onFinish = { prefs.edit().putBoolean("onboarding_complete", true).apply(); firstRun = false }
                )
            } else {
                HomeScreen(
                    apps = allApps,
                    bluetooth = bluetooth,
                    navigation = navigation,
                    music = music,
                    bluetoothAddress = bluetoothAddress,
                    onNavigation = { navigation = it; prefs.edit().putString("navigation_app", it).apply() },
                    onMusic = { music = it; prefs.edit().putString("music_app", it).apply() },
                    onBluetooth = { bluetoothAddress = it; prefs.edit().putString("bluetooth_device_address", it).apply() },
                    onStart = ::startCarMode,
                    updateInfo = updateInfo,
                    onOpenUpdate = {
                        dismissedUpdateVersion = ""
                        prefs.edit().remove(UpdateChecker.DISMISSED_UPDATE_ALERT_VERSION).apply()
                    },
                    accentKey = accentKey,
                    onAccent = { accentKey = it; applyAccent(it) }
                )
            }
        }
    }

    private fun openRelease(url: String) {
        try { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (_: RuntimeException) { }
    }

    private fun downloadUpdate(info: UpdateChecker.UpdateInfo) {
        val url = info.downloadUrl ?: return
        val manager = getSystemService(DOWNLOAD_SERVICE) as? DownloadManager ?: return
        val previousDownload = prefs.getLong(UpdateChecker.PENDING_UPDATE_DOWNLOAD_ID, -1L)
        if (previousDownload >= 0L) manager.remove(previousDownload)
        UpdateChecker.clearPendingDownload(this)
        UpdateChecker.cleanupTemporaryDownloads(this)
        UpdateChecker.setStatus(this, "downloading", "A descarregar DriveDeck ${info.version}")
        val request = DownloadManager.Request(Uri.parse(url))
            .setTitle("DriveDeck ${info.version}")
            .setDescription("A descarregar atualização")
            .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
            .setDestinationInExternalFilesDir(this, android.os.Environment.DIRECTORY_DOWNLOADS, "drivedeck-${info.version}.apk")
            .setMimeType("application/vnd.android.package-archive")
        val downloadId = manager.enqueue(request)
        UpdateChecker.markDownloadStarted(this, info.version, downloadId)
        Toast.makeText(this, "Atualização a ser descarregada", Toast.LENGTH_LONG).show()
    }

    @Composable
    private fun OnboardingScreen(
        apps: List<AppItem>,
        bluetooth: List<BluetoothItem>,
        navigation: String,
        music: String,
        bluetoothAddress: String,
        permissionRevision: Int,
        onNavigation: (String) -> Unit,
        onMusic: (String) -> Unit,
        onBluetooth: (String) -> Unit,
        onFinish: () -> Unit
    ) {
        @Suppress("UNUSED_VARIABLE")
        val permissionStateRevision = permissionRevision
        var autoBluetooth by remember { mutableStateOf(prefs.getBoolean("auto_bluetooth", false)) }
        Scaffold(containerColor = Color(0xFF0A0A0E)) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Image(
                    painter = painterResource(R.drawable.drivedeck_logo),
                    contentDescription = "DriveDeck",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().height(150.dp)
                )
                Text("CAR MODE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                Text("Vamos preparar tudo.", color = Color.White, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                Text("Define as apps e trata das permissões antes de começares a conduzir.", color = Color(0xFFAAAAB4), style = MaterialTheme.typography.bodyLarge)
                Text("1. Apps principais", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                AppPicker("Navegação", apps, navigation, onNavigation, AppCategory.NAVIGATION)
                AppPicker("Música", apps, music, onMusic, AppCategory.MUSIC)
                BluetoothPicker(bluetooth, bluetoothAddress, onBluetooth)
                SettingsRow(Icons.Rounded.Place, "Iniciar automaticamente ao ligar Bluetooth", autoBluetooth) {
                    if (!PermissionManager.canConnectBluetooth(this@ComposeMainActivity)) {
                        PermissionManager.requestBluetooth(this@ComposeMainActivity)
                    } else if (bluetoothAddress.isBlank() || bluetooth.none { it.address == bluetoothAddress }) {
                        Toast.makeText(this@ComposeMainActivity, "Escolhe um Bluetooth do carro disponível.", Toast.LENGTH_SHORT).show()
                    } else {
                        autoBluetooth = !autoBluetooth
                        prefs.edit().putBoolean("auto_bluetooth", autoBluetooth).apply()
                    }
                }
                Text("2. Permissões", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.titleMedium)
                PermissionButton("Notificações", PermissionManager.canPostNotifications(this@ComposeMainActivity)) { PermissionManager.requestNotifications(this@ComposeMainActivity) }
                PermissionButton("Bluetooth", PermissionManager.canConnectBluetooth(this@ComposeMainActivity)) { PermissionManager.requestBluetooth(this@ComposeMainActivity) }
                PermissionButton("Estado do telefone", PermissionManager.canReadPhoneState(this@ComposeMainActivity)) { PermissionManager.requestPhoneState(this@ComposeMainActivity) }
                PermissionButton("Fotos dos contactos", PermissionManager.canReadContacts(this@ComposeMainActivity)) { PermissionManager.requestContacts(this@ComposeMainActivity) }
                PermissionButton("Controlos de música", PermissionManager.isMediaAccessEnabled(this@ComposeMainActivity)) { PermissionManager.openMediaSettings(this@ComposeMainActivity) }
                PermissionButton("Overlay sobre outras apps", PermissionManager.canDrawOverlay(this@ComposeMainActivity)) { PermissionManager.openOverlaySettings(this@ComposeMainActivity) }
                PermissionButton("Toque na Dynamic Island", PermissionManager.isIslandAccessibilityEnabled(this@ComposeMainActivity)) { PermissionManager.openAccessibilitySettings(this@ComposeMainActivity) }
                OutlinedButton(onClick = { PermissionManager.openAssistantSettings(this@ComposeMainActivity) }, modifier = Modifier.fillMaxWidth()) { Text("Escolher Gemini/assistente do telemóvel") }
                Button(
                    onClick = onFinish,
                    enabled = navigation.isNotBlank() && music.isNotBlank()
                        && apps.any { it.packageName == navigation }
                        && apps.any { it.packageName == music },
                    modifier = Modifier.fillMaxWidth().height(56.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
                ) {
                    Text(if (navigation.isBlank() || music.isBlank()) "Escolhe as duas apps"
                    else if (apps.none { it.packageName == navigation } || apps.none { it.packageName == music }) "Escolhe apps instaladas"
                    else "Concluir configuração")
                }
            }
        }
    }

    @Composable
    @OptIn(ExperimentalMaterial3Api::class)
    private fun HomeScreen(apps: List<AppItem>, bluetooth: List<BluetoothItem>, navigation: String, music: String, bluetoothAddress: String, onNavigation: (String) -> Unit, onMusic: (String) -> Unit, onBluetooth: (String) -> Unit, onStart: () -> Unit, updateInfo: UpdateChecker.UpdateInfo?, onOpenUpdate: () -> Unit, accentKey: String, onAccent: (String) -> Unit) {
        var settings by remember { mutableStateOf(false) }
        val navigationAvailable = apps.any { it.packageName == navigation }
        val musicAvailable = apps.any { it.packageName == music }
        val navLabel = apps.firstOrNull { it.packageName == navigation }?.label ?: "Escolher navegação"
        val musicLabel = apps.firstOrNull { it.packageName == music }?.label ?: "Escolher música"
        val bluetoothReady = bluetoothAddress.isNotBlank() && bluetooth.any { it.address == bluetoothAddress }
        val setupReady = navigationAvailable && musicAvailable && bluetoothReady
            && PermissionManager.canDrawOverlay(this@ComposeMainActivity)
            && PermissionManager.canConnectBluetooth(this@ComposeMainActivity)
            && PermissionManager.canPostNotifications(this@ComposeMainActivity)
            && PermissionManager.isMediaAccessEnabled(this@ComposeMainActivity)
            && PermissionManager.isIslandAccessibilityEnabled(this@ComposeMainActivity)
        Scaffold(
            containerColor = Color(0xFF0A0A0E),
            topBar = {
                TopAppBar(
                    title = { Text(if (settings) "Definições avançadas" else "DriveDeck", fontWeight = FontWeight.Bold) },
                    navigationIcon = {
                        if (settings) IconButton(onClick = { settings = false }) {
                            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Voltar")
                        }
                    },
                    actions = {
                        if (!settings) IconButton(onClick = { settings = true }) {
                            Icon(Icons.Rounded.Settings, "Definições avançadas")
                        }
                    }
                )
            }
        ) { padding ->
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(padding).padding(horizontal = 20.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!settings) {
                    Image(
                        painter = painterResource(R.drawable.drivedeck_logo),
                        contentDescription = "DriveDeck",
                        contentScale = ContentScale.Fit,
                        modifier = Modifier.fillMaxWidth().height(120.dp)
                    )
                    Text("CAR MODE", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                    Text("Pronto para conduzir", color = Color.White, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                    Card(
                        colors = CardDefaults.cardColors(containerColor = if (setupReady) Color(0xFF123522) else Color(0xFF382B1A)),
                        modifier = Modifier.fillMaxWidth().clickable { settings = true }
                    ) {
                        Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                if (setupReady) Icons.Rounded.Check else Icons.Rounded.Settings,
                                contentDescription = null,
                                tint = if (setupReady) Color(0xFF30D158) else Color(0xFFFFB340),
                                modifier = Modifier.size(24.dp)
                            )
                            Column(Modifier.padding(start = 10.dp)) {
                                Text(if (setupReady) "Tudo pronto para conduzir" else "Completar configuração", color = Color.White, fontWeight = FontWeight.SemiBold)
                                Text(
                                    if (setupReady) "Apps, Bluetooth e permissões essenciais ativos" else "Toque para abrir as definições avançadas",
                                    color = Color(0xFFCACAD2),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                    ActionCard(Icons.Rounded.Place, "Abrir navegação", navLabel, { launchPackage(navigation) })
                    ActionCard(Icons.Rounded.PlayArrow, "Abrir música", musicLabel, { launchPackage(music) })
                    if (!navigationAvailable || !musicAvailable) {
                        Text("Uma das apps configuradas já não está instalada. Abre as definições avançadas para escolher novamente.", color = Color(0xFFFFB4C1), style = MaterialTheme.typography.bodySmall)
                    }
                    Button(onClick = onStart, enabled = navigationAvailable && musicAvailable, modifier = Modifier.fillMaxWidth().height(58.dp), colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)) { Icon(Icons.Rounded.Home, null); Spacer(Modifier.size(8.dp)); Text("Iniciar Car Mode") }
                } else {
                    SettingsPanel(
                        apps = apps,
                        bluetooth = bluetooth,
                        navigation = navigation,
                        music = music,
                        bluetoothAddress = bluetoothAddress,
                        onNavigation = onNavigation,
                        onMusic = onMusic,
                        onBluetooth = onBluetooth,
                        updateInfo = updateInfo,
                        onOpenUpdate = onOpenUpdate,
                        onRefreshDiagnostics = {
                            permissionRevision++
                            UpdateChecker.check(this@ComposeMainActivity) { info -> runOnUiThread { this@ComposeMainActivity.updateInfo = info } }
                        },
                        accentKey = accentKey,
                        onAccent = onAccent
                    )
                }
            }
        }
    }

    @Composable
    private fun SettingsPanel(
        apps: List<AppItem>,
        bluetooth: List<BluetoothItem>,
        navigation: String,
        music: String,
        bluetoothAddress: String,
        onNavigation: (String) -> Unit,
        onMusic: (String) -> Unit,
        onBluetooth: (String) -> Unit,
        updateInfo: UpdateChecker.UpdateInfo?,
        onOpenUpdate: () -> Unit,
        onRefreshDiagnostics: () -> Unit,
        accentKey: String,
        onAccent: (String) -> Unit
    ) {
        var bluetoothMode by remember { mutableStateOf(prefs.getString("bluetooth_launch_mode", "car_mode") ?: "car_mode") }
        var autoplay by remember { mutableStateOf(prefs.getBoolean("auto_play_music_on_car_mode", true)) }
        var autoBluetooth by remember { mutableStateOf(prefs.getBoolean("auto_bluetooth", false)) }
        var closeOnDisconnect by remember { mutableStateOf(prefs.getBoolean("close_on_bluetooth_disconnect", true)) }
        var pauseOnDisconnect by remember { mutableStateOf(prefs.getBoolean("pause_music_on_bluetooth_disconnect", false)) }
        var controlSize by remember { mutableStateOf(prefs.getString("overlay_control_size", "normal") ?: "normal") }
        var islandPosition by remember { mutableStateOf(prefs.getString("overlay_position", "center") ?: "center") }
        var returnNavigationDuringCall by remember { mutableStateOf(prefs.getBoolean("return_navigation_during_call", true)) }
        var automationOpen by remember { mutableStateOf(false) }
        var playerOpen by remember { mutableStateOf(false) }
        var callsOpen by remember { mutableStateOf(false) }
        val selectedBluetoothAvailable = bluetoothAddress.isNotBlank() && bluetooth.any { it.address == bluetoothAddress }
        val missingSetup = buildList {
            if (!apps.any { it.packageName == navigation }) add("escolher navegação")
            if (!apps.any { it.packageName == music }) add("escolher música")
            if (!selectedBluetoothAvailable) add("selecionar Bluetooth do carro")
            if (!PermissionManager.canDrawOverlay(this@ComposeMainActivity)) add("autorizar overlay")
            if (!PermissionManager.canConnectBluetooth(this@ComposeMainActivity)) add("autorizar Bluetooth")
            if (!PermissionManager.canPostNotifications(this@ComposeMainActivity)) add("autorizar notificações")
            if (!PermissionManager.isMediaAccessEnabled(this@ComposeMainActivity)) add("ativar controlos de música")
            if (!PermissionManager.isIslandAccessibilityEnabled(this@ComposeMainActivity)) add("ativar toque na Dynamic Island")
        }
        val setupReady = missingSetup.isEmpty()
        val setupSubtitle = if (setupReady) {
            "Apps, Bluetooth e overlay configurados"
        } else {
            "Falta: " + missingSetup.joinToString(", ")
        }
        Card(colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B22)), modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("Definições", color = Color.White, style = MaterialTheme.typography.titleLarge)
                Text(
                    "Versão instalada ${installedVersion()}",
                    color = Color(0xFF777780),
                    style = MaterialTheme.typography.labelSmall
                )
                var permissionsOpen by remember { mutableStateOf(true) }
                SettingsSectionHeader("Permissões essenciais", "Acesso necessário para o player, Bluetooth e ilha", permissionsOpen) { permissionsOpen = !permissionsOpen }
                AnimatedVisibility(permissionsOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PermissionButton("Overlay sobre outras apps", PermissionManager.canDrawOverlay(this@ComposeMainActivity)) { PermissionManager.openOverlaySettings(this@ComposeMainActivity) }
                        PermissionButton("Bluetooth", PermissionManager.canConnectBluetooth(this@ComposeMainActivity)) { PermissionManager.requestBluetooth(this@ComposeMainActivity) }
                        PermissionButton("Notificações", PermissionManager.canPostNotifications(this@ComposeMainActivity)) { PermissionManager.requestNotifications(this@ComposeMainActivity) }
                        PermissionButton("Controlos de música", PermissionManager.isMediaAccessEnabled(this@ComposeMainActivity)) { PermissionManager.openMediaSettings(this@ComposeMainActivity) }
                        PermissionButton("Toque na Dynamic Island", PermissionManager.isIslandAccessibilityEnabled(this@ComposeMainActivity)) { PermissionManager.openAccessibilitySettings(this@ComposeMainActivity) }
                        PermissionButton("Estado do telefone", PermissionManager.canReadPhoneState(this@ComposeMainActivity)) { PermissionManager.requestPhoneState(this@ComposeMainActivity) }
                        PermissionButton("Fotos dos contactos", PermissionManager.canReadContacts(this@ComposeMainActivity)) { PermissionManager.requestContacts(this@ComposeMainActivity) }
                        PermissionButton("Otimização da bateria", PermissionManager.isIgnoringBatteryOptimizations(this@ComposeMainActivity)) { PermissionManager.openBatterySettings(this@ComposeMainActivity) }
                        if (missingSetup.isNotEmpty()) Text("Ativa as permissões em falta antes de iniciar o Car Mode para garantir o funcionamento do player e da automação.", color = Color(0xFFFFB340), style = MaterialTheme.typography.bodySmall)
                    }
                }
                Card(
                    colors = CardDefaults.cardColors(containerColor = if (setupReady) Color(0xFF123522) else Color(0xFF382B1A)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            if (setupReady) Icons.Rounded.Check else Icons.Rounded.Settings,
                            contentDescription = null,
                            tint = if (setupReady) Color(0xFF30D158) else Color(0xFFFFB340),
                            modifier = Modifier.size(24.dp)
                        )
                        Column(Modifier.padding(start = 10.dp)) {
                            Text(if (setupReady) "Tudo pronto para conduzir" else "Configuração incompleta", color = Color.White, fontWeight = FontWeight.SemiBold)
                            Text(
                                setupSubtitle,
                                color = Color(0xFFCACAD2),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }
                var diagnosticsOpen by remember { mutableStateOf(false) }
                var diagnosticsRefresh by remember { mutableIntStateOf(0) }
                SettingsSectionHeader("Diagnóstico", "Verifica rapidamente o estado real da app", diagnosticsOpen) { diagnosticsOpen = !diagnosticsOpen }
                AnimatedVisibility(diagnosticsOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    val bluetoothConnected = prefs.getBoolean("selected_bluetooth_connected", false)
                    val bluetoothEvent = prefs.getString("bluetooth_last_event", "")
                        ?.replace("connected:", "Ligado · ")
                        ?.replace("disconnected:", "Desligado · ")
                        ?.ifBlank { "Nenhum evento recebido" } ?: "Nenhum evento recebido"
                    val updateStatus = prefs.getString(UpdateChecker.LAST_UPDATE_STATUS_DETAIL, "") ?: ""
                    val updateStatusKey = prefs.getString(UpdateChecker.LAST_UPDATE_STATUS, "") ?: ""
                    val updateHealthy = updateStatusKey != "check_failed" && updateStatusKey != "failed"
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        DiagnosticRow("Configuração base", setupReady, if (setupReady) "Pronta para conduzir" else "Faltam opções ou permissões")
                        DiagnosticRow("Bluetooth selecionado", selectedBluetoothAvailable, if (bluetoothConnected) "Ligado" else "Selecionado, não ligado")
                        Text("Último evento Bluetooth: $bluetoothEvent", color = Color(0xFFAAAAB4), style = MaterialTheme.typography.labelSmall)
                        DiagnosticRow("Sessão de música", MusicController.hasUsableSession(this@ComposeMainActivity), "MediaSession detetada", "Nenhuma MediaSession disponível")
                        DiagnosticRow("Bateria em segundo plano", PermissionManager.isIgnoringBatteryOptimizations(this@ComposeMainActivity), "Sem restrições", "Pode suspender o Bluetooth")
                        DiagnosticRow("Atualizações", updateHealthy, if (updateInfo == null) "Sem atualização pendente" else "Nova versão disponível", "Verificação falhou")
                        if (updateStatus.isNotBlank()) Text("Última atualização: $updateStatus", color = Color(0xFFAAAAB4), style = MaterialTheme.typography.labelSmall)
                        OutlinedButton(onClick = {
                            diagnosticsRefresh++
                            onRefreshDiagnostics()
                            OverlayService.rebuildIfActive(this@ComposeMainActivity)
                        }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Rounded.Build, contentDescription = null)
                            Spacer(Modifier.size(8.dp))
                            Text(if (diagnosticsRefresh == 0) "Testar novamente" else "Diagnóstico atualizado")
                        }
                    }
                }
                Text("Aparência", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                var drivingProfile by remember { mutableStateOf(prefs.getString("driving_profile", "standard") ?: "standard") }
                Text("Perfil de condução", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                ProfilePicker(drivingProfile) { profile ->
                    drivingProfile = profile
                    val (profileAccent, profileSize, profilePosition) = when (profile) {
                        "night" -> Triple("purple", "compact", "center")
                        "tablet" -> Triple("blue", "large", "center")
                        else -> Triple("blue", "normal", "center")
                    }
                    controlSize = profileSize
                    islandPosition = profilePosition
                    prefs.edit().putString("driving_profile", profile).putString("overlay_control_size", profileSize).putString("overlay_position", profilePosition).apply()
                    onAccent(profileAccent)
                }
                AccentPicker(accentKey, onAccent)
                updateInfo?.let { info ->
                    OutlinedButton(onClick = onOpenUpdate, modifier = Modifier.fillMaxWidth()) {
                        Icon(Icons.Rounded.Notifications, contentDescription = null)
                        Spacer(Modifier.size(8.dp))
                        Text("Nova versão disponível", modifier = Modifier.weight(1f))
                    }
                    Text("DriveDeck ${info.version} pronta para descarregar", color = Color(0xFFFFB4C1), style = MaterialTheme.typography.bodySmall)
                }
                Text("Apps e ligação ao carro", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                AppPicker("Navegação", apps, navigation, onNavigation, AppCategory.NAVIGATION)
                AppPicker("Música", apps, music, onMusic, AppCategory.MUSIC)
                BluetoothPicker(bluetooth, bluetoothAddress, onBluetooth)
                if (bluetoothAddress.isNotBlank() && !selectedBluetoothAvailable) {
                    Text("O dispositivo guardado já não está emparelhado. Escolhe outro para ativar a automação.", color = Color(0xFFFFB4C1), style = MaterialTheme.typography.bodySmall)
                }
                SettingsSectionHeader("Automação", "Bluetooth, música e favoritos", automationOpen) { automationOpen = !automationOpen }
                AnimatedVisibility(automationOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        AudioFavoritesPicker(apps)
                        SettingsRow(Icons.Rounded.Notifications, "Controlos de música", PermissionManager.isMediaAccessEnabled(this@ComposeMainActivity)) { PermissionManager.openMediaSettings(this@ComposeMainActivity) }
                        SettingsRow(Icons.Rounded.Settings, "Toque na Dynamic Island", PermissionManager.isIslandAccessibilityEnabled(this@ComposeMainActivity)) { PermissionManager.openAccessibilitySettings(this@ComposeMainActivity) }
                        SettingsRow(Icons.Rounded.Place, "Bluetooth automático", autoBluetooth && selectedBluetoothAvailable) {
                            if (!PermissionManager.canConnectBluetooth(this@ComposeMainActivity)) PermissionManager.requestBluetooth(this@ComposeMainActivity)
                            else if (bluetoothAddress.isBlank()) Toast.makeText(this@ComposeMainActivity, "Escolhe primeiro o Bluetooth do carro.", Toast.LENGTH_SHORT).show()
                            else { autoBluetooth = !autoBluetooth; prefs.edit().putBoolean("auto_bluetooth", autoBluetooth).apply() }
                        }
                        SettingsRow(Icons.Rounded.PlayArrow, "Reproduzir música ao abrir", autoplay) { autoplay = !autoplay; prefs.edit().putBoolean("auto_play_music_on_car_mode", autoplay).apply() }
                        SettingsRow(Icons.Rounded.Close, "Fechar ao desligar Bluetooth", closeOnDisconnect) { closeOnDisconnect = !closeOnDisconnect; prefs.edit().putBoolean("close_on_bluetooth_disconnect", closeOnDisconnect).apply() }
                        SettingsRow(Icons.Rounded.PlayArrow, "Pausar música ao desligar Bluetooth", pauseOnDisconnect) { pauseOnDisconnect = !pauseOnDisconnect; prefs.edit().putBoolean("pause_music_on_bluetooth_disconnect", pauseOnDisconnect).apply() }
                    }
                }
                SettingsSectionHeader("Player", "Posição, tamanho e aparência", playerOpen) { playerOpen = !playerOpen }
                AnimatedVisibility(playerOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("Posição da Dynamic Island", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        IslandPositionPicker(islandPosition) { islandPosition = it; prefs.edit().putString("overlay_position", it).apply(); rebuildOverlay() }
                        Text("Tamanho dos controlos", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        ControlSizePicker(controlSize) { controlSize = it; prefs.edit().putString("overlay_control_size", it).apply(); rebuildOverlay() }
                    }
                }
                SettingsSectionHeader("Chamadas e permissões", "Acesso ao telefone, contactos e assistente", callsOpen) { callsOpen = !callsOpen }
                AnimatedVisibility(callsOpen, enter = fadeIn() + expandVertically(), exit = fadeOut() + shrinkVertically()) {
                    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        SettingsRow(Icons.Rounded.Place, "Voltar à navegação durante chamadas", returnNavigationDuringCall) {
                            if (!PermissionManager.canReadPhoneState(this@ComposeMainActivity)) PermissionManager.requestPhoneState(this@ComposeMainActivity)
                            else { returnNavigationDuringCall = !returnNavigationDuringCall; prefs.edit().putBoolean("return_navigation_during_call", returnNavigationDuringCall).apply() }
                        }
                        Text("Ao ligar Bluetooth", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
                        ModePicker(bluetoothMode) { bluetoothMode = it; prefs.edit().putString("bluetooth_launch_mode", it).apply() }
                        OutlinedButton(onClick = { PermissionManager.requestNotifications(this@ComposeMainActivity) }, modifier = Modifier.fillMaxWidth()) { Text(if (PermissionManager.canPostNotifications(this@ComposeMainActivity)) "✓ Notificações autorizadas" else "Permitir notificações") }
                        OutlinedButton(onClick = { PermissionManager.requestPhoneState(this@ComposeMainActivity) }, modifier = Modifier.fillMaxWidth()) { Text(if (PermissionManager.canReadPhoneState(this@ComposeMainActivity)) "✓ Chamadas autorizadas" else "Permitir retoma da navegação em chamadas") }
                        OutlinedButton(onClick = { PermissionManager.requestContacts(this@ComposeMainActivity) }, modifier = Modifier.fillMaxWidth()) { Text(if (PermissionManager.canReadContacts(this@ComposeMainActivity)) "✓ Fotos dos contactos autorizadas" else "Permitir fotos dos contactos") }
                        OutlinedButton(onClick = { PermissionManager.openOverlaySettings(this@ComposeMainActivity) }, modifier = Modifier.fillMaxWidth()) { Text(if (PermissionManager.canDrawOverlay(this@ComposeMainActivity)) "✓ Overlay autorizado · Gerir" else "Permitir overlay sobre outras apps") }
                        OutlinedButton(onClick = { PermissionManager.openAccessibilitySettings(this@ComposeMainActivity) }, modifier = Modifier.fillMaxWidth()) { Text(if (PermissionManager.isIslandAccessibilityEnabled(this@ComposeMainActivity)) "✓ Toque na Dynamic Island ativo" else "Ativar toque na Dynamic Island") }
                        OutlinedButton(onClick = { PermissionManager.openAssistantSettings(this@ComposeMainActivity) }, modifier = Modifier.fillMaxWidth()) { Text("Escolher Gemini/assistente do telemóvel") }
                    }
                }
                OutlinedButton(onClick = {
                    prefs.edit().remove("overlay_x").remove("overlay_y").apply()
                    val resetIntent = Intent(this@ComposeMainActivity, OverlayService::class.java)
                        .setAction("pt.dashboardauto.action.RESET_LAYOUT")
                    try {
                        if (Build.VERSION.SDK_INT >= 26) startForegroundService(resetIntent) else startService(resetIntent)
                    } catch (_: RuntimeException) { }
                    Toast.makeText(this@ComposeMainActivity, "Layout reposto", Toast.LENGTH_SHORT).show()
                }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Rounded.Home, null)
                    Spacer(Modifier.size(8.dp))
                    Text("Repor Dynamic Island")
                }
            }
        }
    }

    @Composable
    private fun AudioFavoritesPicker(apps: List<AppItem>) {
        // Se a deteção automática não reconhecer a app (rádio, player OEM, etc.),
        // continua disponível através da lista completa de apps instaladas.
        val suggestedAudioApps = apps.filter { matchesCategory(it, AppCategory.MUSIC) }
        var favorites by remember {
            mutableStateOf(prefs.getString("audio_favorites", "").orEmpty().split(",").filter(String::isNotBlank).toSet())
        }
        var showAll by remember { mutableStateOf(false) }
        val selectedOutsideSuggestions = apps.filter { it.packageName in favorites && it !in suggestedAudioApps }
        val audioApps = if (showAll || suggestedAudioApps.isEmpty()) {
            apps
        } else {
            (suggestedAudioApps + selectedOutsideSuggestions).distinctBy { it.packageName }
        }.sortedWith(compareByDescending<AppItem> { it.packageName in favorites }.thenBy { it.label.lowercase(Locale.ROOT) })
        if (audioApps.isEmpty()) return
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Apps de áudio favoritas", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            Text("Escolhe várias para trocar rapidamente no player expandido.", color = Color(0xFFAAAAB4), style = MaterialTheme.typography.bodySmall)
            if (suggestedAudioApps.isNotEmpty() && suggestedAudioApps.size < apps.size) {
                TextButton(onClick = { showAll = !showAll }, modifier = Modifier.fillMaxWidth()) {
                    Text(if (showAll) "Mostrar apenas sugestões" else "Ver todas as apps instaladas")
                }
            }
            audioApps.forEach { app ->
                SettingsRow(Icons.Rounded.PlayArrow, app.label, favorites.contains(app.packageName)) {
                    favorites = if (favorites.contains(app.packageName)) favorites - app.packageName else favorites + app.packageName
                    prefs.edit().putString("audio_favorites", favorites.joinToString(",")).apply()
                }
            }
        }
    }

    @Composable
    private fun BluetoothPicker(devices: List<BluetoothItem>, selected: String, onSelected: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        val selectedLabel = devices.firstOrNull { it.address == selected }?.name
            ?: if (selected.isBlank()) "Escolher Bluetooth do carro" else "Dispositivo não encontrado — escolher novamente"
        Column {
            Text("Bluetooth do carro", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = {
                if (!PermissionManager.canConnectBluetooth(this@ComposeMainActivity)) PermissionManager.requestBluetooth(this@ComposeMainActivity)
                else expanded = true
            }, modifier = Modifier.fillMaxWidth()) {
                Text(selectedLabel, modifier = Modifier.weight(1f))
                Icon(Icons.Rounded.KeyboardArrowDown, null)
            }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                if (devices.isEmpty()) {
                    DropdownMenuItem(text = { Text("Nenhum dispositivo emparelhado") }, onClick = { expanded = false })
                } else {
                    devices.forEach { device ->
                        DropdownMenuItem(
                            text = { Text(device.name) },
                            trailingIcon = { if (device.address == selected) Icon(Icons.Rounded.Check, "Selecionado") },
                            onClick = { onSelected(device.address); expanded = false }
                        )
                    }
                }
            }
        }
    }

    @Composable
    private fun AppPicker(title: String, apps: List<AppItem>, selected: String, onSelected: (String) -> Unit, category: AppCategory) {
        var expanded by remember { mutableStateOf(false) }
        var showAll by remember { mutableStateOf(false) }
        var query by remember { mutableStateOf("") }
        val label = apps.firstOrNull { it.packageName == selected }?.label ?: "Escolher app"
        val suggestions = apps.filter { matchesCategory(it, category) }
        val sourceApps = if (showAll || suggestions.isEmpty()) apps else suggestions
        val visibleApps = sourceApps.filter {
            query.isBlank() || (it.label + " " + it.packageName).contains(query.trim(), ignoreCase = true)
        }
        Column {
            Text(title + if (suggestions.isEmpty()) " · todas as apps" else if (showAll) " · todas" else " · sugestões", color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelMedium)
            OutlinedButton(onClick = { expanded = true; query = "" }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.weight(1f)); Icon(Icons.Rounded.KeyboardArrowDown, null) }
            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                OutlinedTextField(
                    value = query,
                    onValueChange = { query = it },
                    singleLine = true,
                    label = { Text("Procurar app") },
                    modifier = Modifier.padding(8.dp).fillMaxWidth()
                )
                if (visibleApps.isEmpty()) {
                    DropdownMenuItem(text = { Text("Nenhuma app encontrada") }, onClick = { })
                } else {
                    visibleApps.forEach { app -> DropdownMenuItem(text = { Text(app.label) }, onClick = { onSelected(app.packageName); expanded = false }) }
                }
                if (suggestions.isNotEmpty()) {
                    DropdownMenuItem(text = { Text(if (showAll) "Mostrar sugestões" else "Mostrar todas as apps") }, onClick = { showAll = !showAll })
                }
            }
        }
    }

    @Composable
    private fun PermissionButton(label: String, granted: Boolean, onClick: () -> Unit) {
        OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
            Icon(if (granted) Icons.Rounded.Check else Icons.Rounded.Settings, null)
            Spacer(Modifier.size(8.dp))
            Text(if (granted) "$label · permitido" else "Permitir $label", modifier = Modifier.weight(1f))
        }
    }

    @Composable
    private fun ModePicker(value: String, onChange: (String) -> Unit) {
        var expanded by remember { mutableStateOf(false) }
        val label = if (value == "music_only") "Iniciar apenas a música" else "Abrir Car Mode completo"
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) { Text(label, modifier = Modifier.weight(1f)); Icon(Icons.Rounded.KeyboardArrowDown, null) }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            DropdownMenuItem(text = { Text("Abrir Car Mode completo") }, onClick = { onChange("car_mode"); expanded = false })
            DropdownMenuItem(text = { Text("Iniciar apenas a música") }, onClick = { onChange("music_only"); expanded = false })
        }
    }

    @Composable
    private fun AccentPicker(value: String, onChange: (String) -> Unit) {
        var menuExpanded by remember { mutableStateOf(false) }
        val label = accentLabel(value)
        OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Escolher cor")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            listOf("blue", "pink", "green", "purple", "amber").forEach { key ->
                DropdownMenuItem(
                    text = { Text(accentLabel(key)) },
                    leadingIcon = { Icon(Icons.Rounded.Check, contentDescription = null, tint = accentColor(key)) },
                    onClick = { onChange(key); menuExpanded = false }
                )
            }
        }
    }

    @Composable
    private fun ProfilePicker(value: String, onChange: (String) -> Unit) {
        var menuExpanded by remember { mutableStateOf(false) }
        val label = when (value) {
            "night" -> "Noite — discreto e compacto"
            "tablet" -> "Tablet — controlos maiores"
            else -> "Normal — equilíbrio para condução"
        }
        OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Escolher perfil de condução")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("Normal — equilíbrio para condução") }, onClick = { onChange("standard"); menuExpanded = false })
            DropdownMenuItem(text = { Text("Noite — discreto e compacto") }, onClick = { onChange("night"); menuExpanded = false })
            DropdownMenuItem(text = { Text("Tablet — controlos maiores") }, onClick = { onChange("tablet"); menuExpanded = false })
        }
    }

    private fun accentLabel(key: String): String = when (key) {
        "pink" -> "Rosa — DriveDeck"
        "green" -> "Verde — calmo"
        "purple" -> "Roxo — noturno"
        "amber" -> "Âmbar — alto contraste"
        else -> "Azul — navegação"
    }

    private fun accentColor(key: String): Color = when (key) {
        "pink" -> Color(0xFFFF375F)
        "green" -> Color(0xFF30D158)
        "purple" -> Color(0xFFBF5AF2)
        "amber" -> Color(0xFFFFB340)
        else -> Color(0xFF0A84FF)
    }

    @Composable
    private fun IslandPositionPicker(value: String, onChange: (String) -> Unit) {
        var menuExpanded by remember { mutableStateOf(false) }
        val label = when (value) {
            "left" -> "Esquerda — junto à margem"
            "right" -> "Direita — junto à margem"
            else -> "Centro — adaptado à câmara"
        }
        OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Escolher posição da Dynamic Island")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("Centro — adaptado à câmara") }, onClick = { onChange("center"); menuExpanded = false })
            DropdownMenuItem(text = { Text("Esquerda — junto à margem") }, onClick = { onChange("left"); menuExpanded = false })
            DropdownMenuItem(text = { Text("Direita — junto à margem") }, onClick = { onChange("right"); menuExpanded = false })
        }
    }

    @Composable
    private fun ControlSizePicker(value: String, onChange: (String) -> Unit) {
        var menuExpanded by remember { mutableStateOf(false) }
        val label = when (value) {
            "compact" -> "Compacto — ocupa menos espaço"
            "large" -> "Grande — mais fácil de tocar"
            else -> "Normal — equilíbrio entre espaço e acesso"
        }
        OutlinedButton(onClick = { menuExpanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f))
            Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Escolher tamanho dos controlos")
        }
        DropdownMenu(expanded = menuExpanded, onDismissRequest = { menuExpanded = false }) {
            DropdownMenuItem(text = { Text("Normal — equilíbrio entre espaço e acesso") }, onClick = { onChange("normal"); menuExpanded = false })
            DropdownMenuItem(text = { Text("Compacto — ocupa menos espaço") }, onClick = { onChange("compact"); menuExpanded = false })
            DropdownMenuItem(text = { Text("Grande — mais fácil de tocar") }, onClick = { onChange("large"); menuExpanded = false })
        }
    }

    @Composable
    private fun ActionCard(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
        Card(onClick = onClick, colors = CardDefaults.cardColors(containerColor = Color(0xFF1B1B22)), modifier = Modifier.fillMaxWidth()) {
            Row(Modifier.padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(32.dp))
                Column(Modifier.padding(start = 16.dp)) { Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium); Text(subtitle, color = Color(0xFFAAAAB4), style = MaterialTheme.typography.bodyMedium) }
            }
        }
    }

    @Composable
    private fun SettingsRow(icon: ImageVector, title: String, checked: Boolean, onClick: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(24.dp))
            Text(title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 12.dp))
            Switch(checked = checked, onCheckedChange = null)
        }
    }

    @Composable
    private fun DiagnosticRow(title: String, ok: Boolean, okLabel: String, errorLabel: String = "Requer atenção") {
        Row(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(
                if (ok) Icons.Rounded.Check else Icons.Rounded.Settings,
                contentDescription = null,
                tint = if (ok) Color(0xFF30D158) else Color(0xFFFFB340),
                modifier = Modifier.size(20.dp)
            )
            Text(title, color = Color.White, modifier = Modifier.weight(1f).padding(horizontal = 10.dp))
            Text(if (ok) okLabel else errorLabel, color = if (ok) Color(0xFF9BE9AD) else Color(0xFFFFC56B), style = MaterialTheme.typography.labelSmall)
        }
    }

    @Composable
    private fun SettingsSectionHeader(title: String, subtitle: String, expanded: Boolean, onClick: () -> Unit) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF252530)),
            modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(Modifier.weight(1f)) {
                    Text(title, color = Color.White, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text(subtitle, color = Color(0xFFAAAAB4), style = MaterialTheme.typography.bodySmall)
                }
                Icon(
                    Icons.Rounded.KeyboardArrowDown,
                    contentDescription = if (expanded) "Recolher" else "Abrir",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp).graphicsLayer { rotationZ = if (expanded) 180f else 0f }
                )
            }
        }
    }

    @Composable
    private fun FeatureRow(icon: ImageVector, title: String, subtitle: String) { Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) { Icon(icon, null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(28.dp)); Column(Modifier.padding(start = 14.dp)) { Text(title, color = Color.White, fontWeight = FontWeight.SemiBold); Text(subtitle, color = Color(0xFFAAAAB4)) } } }

    private enum class AppCategory { NAVIGATION, MUSIC }

    private fun matchesCategory(app: AppItem, category: AppCategory): Boolean {
        val value = (app.label + " " + app.packageName).lowercase(Locale.ROOT)
        return when (category) {
            AppCategory.NAVIGATION -> listOf("waze", "google maps", "maps", "here wego", "tomtom", "sygic", "navigation", "navega").any(value::contains)
            AppCategory.MUSIC -> listOf("spotify", "youtube music", "apple music", "poweramp", "tidal", "deezer", "music", "música", "radio", "podcast", "vlc").any(value::contains)
        }
    }

    private data class AppItem(val label: String, val packageName: String)
    private data class BluetoothItem(val name: String, val address: String)

    @Composable
    private fun DashboardTheme(accent: Color, content: @Composable () -> Unit) {
        MaterialTheme(colorScheme = darkColorScheme(primary = accent, background = Color(0xFF0A0A0E), surface = Color(0xFF1B1B22)), content = content)
    }
}
