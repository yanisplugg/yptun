package org.olcbox.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import org.olcbox.app.data.datasource.LocationsDataSourceImpl
import org.olcbox.app.data.datasource.LocationsRepositoryImpl
import org.olcbox.app.data.exporter.AndroidLogExporter
import org.olcbox.app.data.identity.PersistentDeviceIdentityProvider
import org.olcbox.app.data.importer.AndroidConfigImporter
import org.olcbox.app.ui.activities.AndroidMainScreen
import org.olcbox.app.ui.features.home.HomeScreenViewModel
import org.olcbox.app.ui.features.locations.LocationViewModel
import org.olcbox.app.ui.theme.AppTheme
import org.olcbox.app.update.AppUpdateService
import org.olcbox.app.vpn.AndroidVpnManager

class AppActivity : ComponentActivity() {

    private lateinit var vpnManager: AndroidVpnManager
    private lateinit var viewModel: HomeScreenViewModel
    private lateinit var locationViewModel: LocationViewModel

    private val requestPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { _ ->
        // Permission handled
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Request notification permission for Android 13+
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            requestPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }

        vpnManager = AndroidVpnManager(this)
        val locationsDataSource = LocationsDataSourceImpl(this)
        val locationsRepository = LocationsRepositoryImpl(locationsDataSource)
        val configImporter = AndroidConfigImporter(this)
        val logExporter = AndroidLogExporter(this)
        val updateService = AppUpdateService(
            deviceIdentityProvider = PersistentDeviceIdentityProvider(locationsDataSource)
        )

        viewModel = HomeScreenViewModel(
            vpnManager = vpnManager,
            locationsRepository = locationsRepository,
            configImporter = configImporter,
            logExporter = logExporter
        )
        locationViewModel = LocationViewModel(
            locationsRepository = locationsRepository
        )

        enableEdgeToEdge()
        setContent {
            val dynamicThemeEnabled by vpnManager.dynamicThemeEnabled.collectAsState()

            AppTheme(useDynamicColor = dynamicThemeEnabled) {
                AndroidMainScreen(
                    viewModel = viewModel,
                    locationViewModel = locationViewModel,
                    vpnManager = vpnManager,
                    appUpdateService = updateService
                )
            }
        }

        handleDeepLink(intent)
        maybeWarnUnofficialBuild()
    }

    /**
     * One-time warning when the running build is NOT signed with the official YPtun release key —
     * i.e. someone repackaged and re-signed the app (ad/spyware injection, "стырили сборку"). This is
     * tamper-evident only: a repacker can patch this out. Shown once per install; acknowledged flag
     * persisted. Official builds short-circuit immediately.
     */
    private fun maybeWarnUnofficialBuild() {
        if (org.olcbox.app.security.IntegrityGuard.isOfficialInstalled(this)) return
        val prefs = getSharedPreferences("yptun_integrity", MODE_PRIVATE)
        if (prefs.getBoolean("unofficial_ack", false)) return
        android.app.AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("⚠ Неофициальная сборка / Unofficial build")
            .setMessage(
                "Эта копия YPtun подписана не ключом разработчика — она могла быть изменена " +
                    "третьими лицами (реклама, слежка, вредонос). Скачайте оригинал:\n" +
                    "github.com/yanisplugg/olcvpn-client\n\n" +
                    "This YPtun build is NOT signed by the developer and may have been modified by a " +
                    "third party. Get the official app from:\n" +
                    "github.com/yanisplugg/olcvpn-client"
            )
            .setCancelable(false)
            .setPositiveButton("OK") { dialog, _ ->
                prefs.edit().putBoolean("unofficial_ack", true).apply()
                dialog.dismiss()
            }
            .show()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLink(intent)
    }

    /** Handles yptun:// deep links: import/{payload} and control/{start|stop|restart}. */
    private fun handleDeepLink(intent: Intent?) {
        val uri: Uri = intent?.data ?: return
        if (!uri.scheme.equals("yptun", ignoreCase = true)) return

        when (uri.host?.lowercase()) {
            "import" -> {
                val raw = uri.toString().substringAfter("yptun://import/", "").trim()
                val payload = runCatching { Uri.decode(raw) }.getOrNull()?.takeIf { it.isNotBlank() }
                    ?: raw.takeIf { it.isNotBlank() }
                    ?: return
                viewModel.onImportFullConfig(
                    payload,
                    onComplete = {
                        locationViewModel.loadLocations { viewModel.loadCurrentConfig() }
                        Toast.makeText(this, "Импортировано", Toast.LENGTH_SHORT).show()
                    },
                    onError = { message ->
                        Toast.makeText(this, message, Toast.LENGTH_LONG).show()
                    }
                )
            }

            "control" -> {
                when (uri.lastPathSegment?.lowercase()) {
                    "start" -> if (!viewModel.state.value.isVpnConnected) viewModel.ToggleVpn()
                    "stop" -> if (viewModel.state.value.isVpnConnected) viewModel.ToggleVpn()
                    "restart" -> viewModel.restartVpnIfRunning()
                    // Widget "Auto" button: raise the signal the Home screen consumes to run the
                    // fastest-server search (needs the app foreground to ping every node + show progress).
                    "auto" -> org.olcbox.app.widget.WidgetAutoSignal.request()
                }
            }
        }
    }
}
