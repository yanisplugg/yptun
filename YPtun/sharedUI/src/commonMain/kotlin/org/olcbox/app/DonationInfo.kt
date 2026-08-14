package org.olcbox.app

/**
 * Where donations go.
 *
 * One TON wallet takes all three: USDT (as a TON jetton), TON itself and GRAM. Kept as a single
 * constant so the address exists in exactly one place — a wallet address that drifts between the
 * Android and desktop UIs is money sent nowhere.
 */
object DonationInfo {

    /** TON user-friendly address (48 characters, base64url — the trailing `-` is part of it). */
    const val TON_ADDRESS = "UQAPC9J9UY8oaYV4AwjEAYIIJMswo7qVzJDkf4pzY8kVtzJ-"

    /** What the address accepts, shown under the row. */
    const val ASSETS = "USDT · TON · GRAM"
}
