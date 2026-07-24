package org.olcbox.app.security

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.content.pm.Signature
import android.os.Build
import java.io.File
import java.security.MessageDigest

/**
 * Tamper-EVIDENT signing-certificate check. Reads the X.509 certificate the running app (or a
 * downloaded APK) is signed with and compares its SHA-256 against the official YPtun release key.
 *
 * Two uses:
 *  1. Warn the user when the INSTALLED build isn't ours — catches casual repackaging (someone strips
 *     the app, injects ads/spyware, re-signs and redistributes it). It is NOT tamper-PROOF: whoever
 *     repackaged the apk can also patch this check out — it's an informational signal for end users
 *     who received a modified build, not a security boundary.
 *  2. Vet a downloaded update APK BEFORE handing it to the installer — this one is a real hardening:
 *     it stops a man-in-the-middle on the update channel from slipping in a malicious APK.
 */
object IntegrityGuard {

    /**
     * SHA-256 (lowercase hex, no separators) of the official YPtun release signing certificate
     * (`yptun-release.jks`, alias `yptun`, CN=YPtun). keytool prints it as
     * `EA:6B:8C:6B:13:53:D9:C1:…:4E:D0`.
     */
    const val OFFICIAL_SIGNING_SHA256 =
        "ea6b8c6b1353d9c11086cd7e75259210b26a1b11a6930292270eecccb19f4ed0"

    /** SHA-256 of the cert the INSTALLED app is signed with (lowercase hex), or null if unreadable. */
    fun installedSigningSha256(context: Context): String? =
        signaturesOf(packageInfo(context.packageManager, context.packageName))
            .firstNotNullOfOrNull { sha256Hex(it.toByteArray()) }

    /** SHA-256 of the cert an APK FILE is signed with — used to vet a downloaded update. */
    fun apkSigningSha256(context: Context, apk: File): String? =
        signaturesOf(archiveInfo(context.packageManager, apk.absolutePath))
            .firstNotNullOfOrNull { sha256Hex(it.toByteArray()) }

    /** True when the installed build carries the official signature. Unreadable signature → false. */
    fun isOfficialInstalled(context: Context): Boolean =
        installedSigningSha256(context).matchesOfficial()

    /** True when [apk] is signed with the official key. Unreadable signature → false (reject). */
    fun isOfficialApk(context: Context, apk: File): Boolean =
        apkSigningSha256(context, apk).matchesOfficial()

    // ──────────────────────────────────────────────────────────────────────

    private fun String?.matchesOfficial(): Boolean =
        this != null && this.equals(OFFICIAL_SIGNING_SHA256, ignoreCase = true)

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun packageInfo(pm: PackageManager, pkg: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageInfo(pkg, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION", "PackageManagerGetSignatures")
    private fun archiveInfo(pm: PackageManager, path: String): PackageInfo? = runCatching {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNING_CERTIFICATES)
        } else {
            pm.getPackageArchiveInfo(path, PackageManager.GET_SIGNATURES)
        }
    }.getOrNull()

    @Suppress("DEPRECATION")
    private fun signaturesOf(info: PackageInfo?): Array<Signature> {
        info ?: return emptyArray()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val signingInfo = info.signingInfo ?: return emptyArray()
            if (signingInfo.hasMultipleSigners()) signingInfo.apkContentsSigners
            else signingInfo.signingCertificateHistory
        } else {
            info.signatures
        } ?: emptyArray()
    }

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
}
