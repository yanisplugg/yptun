package org.olcbox.app

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import androidx.annotation.RequiresApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.olcbox.app.vpn.VpnStatus
import org.olcbox.app.vpn.service.OlcboxVpnState

/**
 * Android Quick Settings tile that toggles the Olcbox VPN on/off.
 *
 * Appears in the notification shade. Tap it to start or stop the VPN.
 * If VPN permission hasn't been granted yet, opens the main app instead.
 *
 * Requires Android 7.0+ (API 24). Works correctly with minSdk = 23 because
 * the system only binds this service on devices that support Quick Settings tiles.
 */
@RequiresApi(Build.VERSION_CODES.N)
class QuickSettingsTileService : TileService() {

    private var scope: CoroutineScope? = null

    // ──────────────────────────────────────────────────────────────────────
    // Lifecycle
    // ──────────────────────────────────────────────────────────────────────

    override fun onStartListening() {
        super.onStartListening()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

        // Keep the tile in sync with VPN status while it is visible.
        OlcboxVpnState.status
            .onEach { status -> updateTile(status) }
            .launchIn(scope!!)
    }

    override fun onStopListening() {
        super.onStopListening()
        scope?.cancel()
        scope = null
    }

    // ──────────────────────────────────────────────────────────────────────
    // Click handling
    // ──────────────────────────────────────────────────────────────────────

    override fun onClick() {
        super.onClick()

        val isConnected = OlcboxVpnState.isConnected.value

        if (isConnected) {
            VpnServiceControl.stop(applicationContext)
        } else if (!VpnServiceControl.start(applicationContext)) {
            // Permission not yet granted — open the main app so the dialog can appear. Otherwise the
            // active location connects in the background: after an "Auto = fastest" pick it IS the chosen
            // fastest server, so the tile silently reconnects it (re-running Auto is an in-app action).
            openMainApp()
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // VPN control
    // ──────────────────────────────────────────────────────────────────────

    private fun openMainApp() {
        val intent = Intent(applicationContext, AppActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(
                applicationContext,
                0,
                intent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            startActivityAndCollapse(pending)
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    // ──────────────────────────────────────────────────────────────────────
    // Tile appearance
    // ──────────────────────────────────────────────────────────────────────

    private fun updateTile(status: VpnStatus) {
        val tile = qsTile ?: return
        // Our kitten + "yptun" silhouette instead of the default tile icon (system tints it).
        tile.icon = android.graphics.drawable.Icon.createWithResource(this, R.drawable.ic_qs_tile_cat)
        when (status) {
            is VpnStatus.Connected -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.qs_tile_label)
                tile.contentDescription = getString(R.string.qs_tile_connected)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_connected)
                }
            }
            is VpnStatus.Connecting, is VpnStatus.Reconnecting -> {
                tile.state = Tile.STATE_ACTIVE
                tile.label = getString(R.string.qs_tile_label)
                tile.contentDescription = getString(R.string.qs_tile_connecting)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_connecting)
                }
            }
            else -> {
                tile.state = Tile.STATE_INACTIVE
                tile.label = getString(R.string.qs_tile_label)
                tile.contentDescription = getString(R.string.qs_tile_disconnected)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    tile.subtitle = getString(R.string.qs_tile_disconnected)
                }
            }
        }
        tile.updateTile()
    }
}
