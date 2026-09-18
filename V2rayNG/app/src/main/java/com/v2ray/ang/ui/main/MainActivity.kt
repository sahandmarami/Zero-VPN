package com.v2ray.ang.ui.main

import android.content.Intent
import android.net.Uri
import android.net.VpnService
import android.os.Build
import android.os.Bundle
import android.view.KeyEvent
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.v2ray.ang.AngApplication
import com.v2ray.ang.AppConfig
import com.v2ray.ang.R
import com.v2ray.ang.core.LauncherManager
import com.v2ray.ang.dto.entities.ProfileItem
import com.v2ray.ang.enums.EConfigType
import com.v2ray.ang.enums.PermissionType
import com.v2ray.ang.extension.toast
import com.v2ray.ang.extension.toastError
import com.v2ray.ang.extension.toastSuccess
import com.v2ray.ang.handler.AngConfigManager
import com.v2ray.ang.handler.CoreUpdateManager
import com.v2ray.ang.handler.MmkvManager
import com.v2ray.ang.handler.SettingsChangeManager
import com.v2ray.ang.handler.SettingsManager
import com.v2ray.ang.handler.UpdateCheckerManager
import com.v2ray.ang.ui.AboutActivity
import com.v2ray.ang.ui.backup.BackupActivity
import com.v2ray.ang.ui.base.HelperBaseComponentActivity
import com.v2ray.ang.ui.checkupdate.CheckUpdateActivity
import com.v2ray.ang.ui.logcat.LogcatActivity
import com.v2ray.ang.ui.perappproxy.PerAppProxyActivity
import com.v2ray.ang.ui.routing.RoutingSettingActivity
import com.v2ray.ang.ui.server.ProfileEditorResult
import com.v2ray.ang.ui.server.ServerCustomConfigActivity
import com.v2ray.ang.ui.server.ServerGroupActivity
import com.v2ray.ang.ui.server.ServerHttpActivity
import com.v2ray.ang.ui.server.ServerHysteria2Activity
import com.v2ray.ang.ui.server.ServerProxyChainActivity
import com.v2ray.ang.ui.server.ServerShadowsocksActivity
import com.v2ray.ang.ui.server.ServerSocksActivity
import com.v2ray.ang.ui.server.ServerTrojanActivity
import com.v2ray.ang.ui.server.ServerVlessActivity
import com.v2ray.ang.ui.server.ServerVmessActivity
import com.v2ray.ang.ui.server.ServerWireguardActivity
import com.v2ray.ang.ui.settings.SettingsActivity
import com.v2ray.ang.ui.subscription.SubSettingActivity
import com.v2ray.ang.ui.userasset.UserAssetActivity
import com.v2ray.ang.util.HttpUtil
import com.v2ray.ang.dto.UrlContentRequest
import com.v2ray.ang.util.LogUtil
import com.v2ray.ang.util.Utils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.max

private const val SPLASH_MIN_MILLIS = 1800L
private const val SPLASH_FADE_MILLIS = 120L

class MainActivity : HelperBaseComponentActivity() {

    private val mainViewModel: MainViewModel by viewModels {
        MainViewModel.Factory(application, MainRepository(application as AngApplication))
    }

    // Zero VPN: automatic update state (checked on every app launch).
    private val zeroAppUpdate = MutableStateFlow<Pair<String, String>?>(null)
    private val zeroCoreUpdate = MutableStateFlow<CoreUpdateManager.CoreUpdateResult?>(null)

    // Zero VPN: launch loading screen (checks/downloads updates before opening).
    private val zeroSplash = MutableStateFlow(ZeroSplashState())
    private var zeroUpdateApkFile: File? = null

    private val requestVpnPermission =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            if (it.resultCode == RESULT_OK) startV2Ray()
        }

    private val profileEditorLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode != RESULT_OK) return@registerForActivityResult
            val data = result.data ?: return@registerForActivityResult
            val action = data.getStringExtra(ProfileEditorResult.EXTRA_ACTION)
                ?: return@registerForActivityResult
            if (action != ProfileEditorResult.ACTION_SAVED &&
                action != ProfileEditorResult.ACTION_DELETED
            ) return@registerForActivityResult
            val restartService = data.getBooleanExtra(
                ProfileEditorResult.EXTRA_RESTART_SERVICE, false
            )
            val selectedProfileSaved = action == ProfileEditorResult.ACTION_SAVED &&
                    data.getStringExtra(ProfileEditorResult.EXTRA_GUID) == mainViewModel.uiState.value.selectedGuid
            mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService || selectedProfileSaved) LauncherManager.restartService(this)
        }

    private val settingsActivityLauncher =
        registerForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            val restartService = SettingsChangeManager.consumeRestartService()
            val refreshGroups = SettingsChangeManager.consumeSetupGroupTab()
            mainViewModel.refreshUiSettings()
            if (refreshGroups) mainViewModel.onAction(MainAction.RefreshGroups)
            if (restartService) LauncherManager.restartService(this)
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        mainViewModel.onAction(MainAction.Initialize)

        checkAndRequestPermission(PermissionType.POST_NOTIFICATIONS) {}

        // Zero VPN: launch loading screen — on every open it checks the
        // official sources (Zero VPN release + official Xray-core release);
        // if a newer build exists it is downloaded in-place (it carries the
        // newest official core) and can be installed, then the app opens.
        lifecycleScope.launch {
            val startedAt = System.currentTimeMillis()
            val autoUpdate = MmkvManager.decodeSettingsBool(AppConfig.PREF_AUTO_UPDATE, true)

            val appUpdateDeferred = lifecycleScope.async {
                try {
                    UpdateCheckerManager.checkForUpdate(false)
                } catch (e: Exception) {
                    LogUtil.d(AppConfig.TAG, "App update check skipped: ${e.message}")
                    null
                }
            }
            val coreUpdateDeferred = lifecycleScope.async {
                try {
                    CoreUpdateManager.checkCoreUpdate()
                } catch (e: Exception) {
                    LogUtil.d(AppConfig.TAG, "Core update check skipped: ${e.message}")
                    null
                }
            }

            val appUpdate = appUpdateDeferred.await()
            val coreUpdate = coreUpdateDeferred.await()

            if (appUpdate?.hasUpdate == true && !appUpdate.latestVersion.isNullOrBlank()) {
                zeroAppUpdate.value = Pair(appUpdate.latestVersion, appUpdate.downloadUrl.orEmpty())
            }
            if (coreUpdate != null) {
                zeroCoreUpdate.value = coreUpdate
                zeroSplash.value = zeroSplash.value.copy(
                    coreVersion = coreUpdate.currentVersion ?: coreUpdate.latestVersion,
                    coreUpToDate = coreUpdate.currentVersion?.let { !coreUpdate.hasUpdate }
                )
            }

            // In-place download of the newest build (carries the newest core).
            if (autoUpdate && appUpdate?.hasUpdate == true && !appUpdate.downloadUrl.isNullOrBlank()) {
                zeroSplash.value = zeroSplash.value.copy(
                    phase = ZeroSplashPhase.Downloading,
                    downloadProgress = 0
                )
                try {
                    val target = File(cacheDir, "zero_update_${appUpdate.latestVersion}.apk")
                    val ok = HttpUtil.downloadToFile(
                        UrlContentRequest(url = appUpdate.downloadUrl, timeout = 30_000),
                        target
                    ) { percent, _, _ ->
                        zeroSplash.value = zeroSplash.value.copy(downloadProgress = percent)
                    }
                    if (ok && target.length() > 0L) {
                        zeroUpdateApkFile = target
                        zeroSplash.value = zeroSplash.value.copy(downloadedVersion = appUpdate.latestVersion)
                    } else {
                        target.delete()
                    }
                } catch (e: Exception) {
                    LogUtil.e(AppConfig.TAG, "Update download failed", e)
                }
            }

            // Keep the loading screen readable, then open the app.
            val elapsed = System.currentTimeMillis() - startedAt
            delay(max(0L, SPLASH_MIN_MILLIS - elapsed))
            zeroSplash.value = zeroSplash.value.copy(phase = ZeroSplashPhase.Ready)
            delay(SPLASH_FADE_MILLIS)
            zeroSplash.value = zeroSplash.value.copy(visible = false)
        }
    }

    /** Installs the update APK downloaded by the launch loading screen. */
    private fun installZeroUpdate() {
        val apk = zeroUpdateApkFile ?: return
        if (!apk.exists()) return
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O &&
                !packageManager.canRequestPackageInstalls()
            ) {
                startActivity(
                    Intent(
                        android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:$packageName")
                    )
                )
                return
            }
            val uri = FileProvider.getUriForFile(this, "$packageName.cache", apk)
            val intent = Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK
            }
            startActivity(intent)
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Cannot launch update installer", e)
            toastError(R.string.toast_failure)
        }
    }

    @Composable
    override fun ScreenContent() {
        BackHandler { moveTaskToBack(false) }
        val appUpdate by zeroAppUpdate.collectAsState()
        val coreUpdate by zeroCoreUpdate.collectAsState()
        val splash by zeroSplash.collectAsState()
        Box(modifier = Modifier.fillMaxSize()) {
            MainScreen(
                mainViewModel = mainViewModel,
                selectedServerName = mainViewModel.selectedServerName.collectAsState().value,
                selectedServerGeo = mainViewModel.selectedServerGeo.collectAsState().value,
                zeroAppUpdate = appUpdate,
                zeroCoreUpdate = coreUpdate,
                onOpenZeroUpdate = {
                    val url = appUpdate?.second
                    if (!url.isNullOrBlank()) {
                        Utils.openUri(this@MainActivity, url)
                    } else {
                        startActivity(Intent(this@MainActivity, CheckUpdateActivity::class.java))
                    }
                },
                onDismissZeroUpdate = {
                    zeroAppUpdate.value = null
                    zeroCoreUpdate.value = null
                },
                onAction = { action ->
                    when (action) {
                        MainAction.ToggleService -> handleFabAction()
                        MainAction.TestCurrentServer -> handleLayoutTestClick()
                        MainAction.ImportQRcode -> importQRcode()
                        MainAction.ImportClipboard -> importClipboard()
                        MainAction.ImportConfigLocal -> importConfigLocal()
                        is MainAction.ImportManually -> importManually(action.type)
                        MainAction.RestartService -> LauncherManager.restartServiceOrStart(this@MainActivity, ::requestServiceStart)
                        MainAction.LocateSelectedServer -> mainViewModel.triggerLocateSelectedServer()
                        is MainAction.SelectServer -> setSelectServer(action.guid)
                        is MainAction.EditServer -> editServer(action.guid, action.profile)
                        is MainAction.ShareClipboard -> shareToClipboard(action.guid)
                        is MainAction.ShareFullContent -> shareFullContentAsync(action.guid)
                        else -> mainViewModel.onAction(action)
                    }
                },
                onNavigate = { route -> navigateTo(route) },
            )

            // Launch loading screen sits above everything until it is done.
            androidx.compose.animation.AnimatedVisibility(
                visible = splash.visible,
                enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(200)),
                exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(320))
            ) {
                ZeroSplashScreen(
                    state = splash,
                    onInstallUpdate = { installZeroUpdate() },
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
    }

    private fun shareToClipboard(guid: String): Boolean =
        AngConfigManager.share2Clipboard(this, guid) == 0

    private fun shareFullContentAsync(guid: String) {
        lifecycleScope.launch(Dispatchers.IO) {
            val result = AngConfigManager.shareFullContent2Clipboard(this@MainActivity, guid)
            withContext(Dispatchers.Main) {
                if (result == 0) toastSuccess(R.string.toast_success)
                else toastError(R.string.toast_failure)
            }
        }
    }

    private fun navigateTo(destination: MainDestination) {
        val intent = when (destination) {
            MainDestination.Subscriptions -> Intent(this, SubSettingActivity::class.java)
            MainDestination.PerAppProxy -> Intent(this, PerAppProxyActivity::class.java)
            MainDestination.Routing -> Intent(this, RoutingSettingActivity::class.java)
            MainDestination.UserAssets -> Intent(this, UserAssetActivity::class.java)
            MainDestination.Settings -> Intent(this, SettingsActivity::class.java)
            MainDestination.Logcat -> Intent(this, LogcatActivity::class.java)
            MainDestination.CheckUpdate -> Intent(this, CheckUpdateActivity::class.java)
            MainDestination.BackupRestore -> Intent(this, BackupActivity::class.java)
            MainDestination.About -> Intent(this, AboutActivity::class.java)
        }
        settingsActivityLauncher.launch(intent)
    }

    private fun handleFabAction() {
        if (mainViewModel.uiState.value.isRunning) {
            LauncherManager.stopService(this)
        } else {
            requestServiceStart()
        }
    }

    private fun requestServiceStart() {
        if (!SettingsManager.isVpnMode()) {
            startV2Ray()
            return
        }
        val intent = VpnService.prepare(this)
        if (intent == null) startV2Ray() else requestVpnPermission.launch(intent)
    }

    private fun handleLayoutTestClick() {
        if (mainViewModel.uiState.value.isRunning) {
            mainViewModel.testCurrentServerRealPing()
        }
    }

    private fun startV2Ray() {
        if (mainViewModel.uiState.value.selectedGuid.isNullOrEmpty()) {
            toast(R.string.title_file_chooser)
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.CINNAMON_BUN &&
            MmkvManager.decodeSettingsBool(AppConfig.PREF_PROXY_SHARING)
        ) {
            checkAndRequestPermission(PermissionType.ACCESS_LOCAL_NETWORK) {}
        }
        LauncherManager.startService(this)
    }

    private fun importManually(createConfigType: Int) {
        val intent = when (createConfigType) {
            EConfigType.POLICYGROUP.value -> Intent(this, ServerGroupActivity::class.java)
            EConfigType.PROXYCHAIN.value -> Intent(this, ServerProxyChainActivity::class.java)
            EConfigType.VMESS.value -> Intent(this, ServerVmessActivity::class.java)
            EConfigType.VLESS.value -> Intent(this, ServerVlessActivity::class.java)
            EConfigType.SHADOWSOCKS.value -> Intent(this, ServerShadowsocksActivity::class.java)
            EConfigType.SOCKS.value -> Intent(this, ServerSocksActivity::class.java)
            EConfigType.HTTP.value -> Intent(this, ServerHttpActivity::class.java)
            EConfigType.TROJAN.value -> Intent(this, ServerTrojanActivity::class.java)
            EConfigType.WIREGUARD.value -> Intent(this, ServerWireguardActivity::class.java)
            EConfigType.HYSTERIA2.value -> Intent(this, ServerHysteria2Activity::class.java)
            else -> Intent(this, ServerHttpActivity::class.java).apply {
                putExtra("createConfigType", createConfigType)
            }
        }.apply {
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun importQRcode() {
        launchQRCodeScanner { scanResult ->
            if (scanResult != null) {
                mainViewModel.onAction(MainAction.ImportBatchConfig(scanResult))
            }
        }
    }

    private fun importClipboard() {
        try {
            val text = Utils.getClipboard(this)
            mainViewModel.onAction(MainAction.ImportBatchConfig(text))
        } catch (e: Exception) {
            LogUtil.e(AppConfig.TAG, "Failed to import config from clipboard", e)
        }
    }

    private fun importConfigLocal() {
        launchFileChooser { uri ->
            if (uri == null) return@launchFileChooser
            try {
                contentResolver.openInputStream(uri)?.bufferedReader()?.use { reader ->
                    mainViewModel.onAction(MainAction.ImportBatchConfig(reader.readText()))
                }
            } catch (e: Exception) {
                LogUtil.e(AppConfig.TAG, "Failed to read content from URI", e)
            }
        }
    }

    private fun editServer(guid: String, profile: ProfileItem) {
        val activityClass = when (profile.configType) {
            EConfigType.CUSTOM -> ServerCustomConfigActivity::class.java
            EConfigType.POLICYGROUP -> ServerGroupActivity::class.java
            EConfigType.PROXYCHAIN -> ServerProxyChainActivity::class.java
            EConfigType.VMESS -> ServerVmessActivity::class.java
            EConfigType.VLESS -> ServerVlessActivity::class.java
            EConfigType.SHADOWSOCKS -> ServerShadowsocksActivity::class.java
            EConfigType.SOCKS -> ServerSocksActivity::class.java
            EConfigType.HTTP -> ServerHttpActivity::class.java
            EConfigType.TROJAN -> ServerTrojanActivity::class.java
            EConfigType.WIREGUARD -> ServerWireguardActivity::class.java
            EConfigType.HYSTERIA2 -> ServerHysteria2Activity::class.java
            else -> ServerHttpActivity::class.java
        }
        val intent = Intent(this, activityClass).apply {
            putExtra("guid", guid)
            putExtra("isRunning", mainViewModel.uiState.value.isRunning)
            putExtra("createConfigType", profile.configType.value)
            putExtra("subscriptionId", mainViewModel.uiState.value.selectedGroupId)
        }
        profileEditorLauncher.launch(intent)
    }

    private fun setSelectServer(guid: String) {
        val selected = mainViewModel.uiState.value.selectedGuid
        if (guid != selected) {
            mainViewModel.updateSelectedGuid(guid)
            LauncherManager.restartService(this)
        }
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (keyCode == KeyEvent.KEYCODE_BUTTON_B) {
            moveTaskToBack(false)
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
}
