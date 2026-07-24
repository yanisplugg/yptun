package org.olcbox.app.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * A tiny projection of [LocationBundleV4] carrying ONLY what the location list needs to paint a row:
 * the storage id, display name, subscription grouping + metadata (traffic/expiry/name) and the engine
 * / provider / transport labels. The heavy connection payloads (proxy / proxy2 / raw Xray & sing-box
 * configs / AmneziaWG / vkturn / dnstt / fakedns) are deliberately LEFT OUT.
 *
 * Why it exists: with hundreds of saved configs the full kotlinx.serialization decode of the bundle
 * takes a couple of seconds on a cold start, during which the list sat blank/spinning. This index is
 * orders of magnitude smaller, so it decodes in a few milliseconds — the UI renders the full list
 * INSTANTLY from it, then the authoritative full decode runs in the background and swaps in the real
 * configs (needed for pinging/connecting). It is rewritten on every bundle save, so it never drifts;
 * a missing/corrupt index simply falls back to the old "decode then show" behaviour.
 */
@Serializable
data class LocationViewIndex(
    @SerialName("active_location_id")
    val activeLocationId: String? = null,
    val items: List<Item> = emptyList()
) {
    @Serializable
    data class Item(
        @SerialName("storage_id")
        val storageId: String,
        val name: String = "",
        @SerialName("subscription_url")
        val subscriptionUrl: String? = null,
        val engine: EngineType = EngineType.Stealth,
        @SerialName("auth_provider")
        val authProvider: String = "",
        val transport: String = "",
        // Lightweight proxy display fields (type + server) so the cold-start seed paints the REAL
        // subtitle ("VLESS · host") instead of a bare "Proxy" placeholder until the full decode lands.
        // These are just short strings — NOT the heavy proxy payload (uuid/keys/raw configs).
        @SerialName("proxy_type")
        val proxyType: String = "",
        @SerialName("proxy_server")
        val proxyServer: String = "",
        val metadata: LocationMetadata? = null
    )

    companion object {
        /** Cheap projection of an (already normalized) bundle — no IO, no heavy field copies. */
        fun from(bundle: LocationBundleV4): LocationViewIndex = LocationViewIndex(
            activeLocationId = bundle.activeLocationId,
            items = bundle.locations.map { entry ->
                Item(
                    storageId = entry.storageId,
                    name = entry.name,
                    subscriptionUrl = entry.subscriptionUrl ?: entry.legacySubscriptionUrl,
                    engine = entry.engine ?: EngineType.Stealth,
                    authProvider = entry.authProvider.orEmpty(),
                    transport = entry.transport?.type.orEmpty(),
                    proxyType = entry.location.proxy?.type.orEmpty(),
                    proxyServer = entry.location.proxy?.server.orEmpty(),
                    metadata = entry.metadata
                )
            }
        )
    }
}
