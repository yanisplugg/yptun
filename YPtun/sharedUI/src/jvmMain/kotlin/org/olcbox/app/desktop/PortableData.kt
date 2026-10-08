package org.olcbox.app.desktop

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The portable build keeps the app's own data (subscriptions/locations, settings, device identity) in ONE
 * encrypted file next to the .exe — `YPtun.data` — so it travels with the exe.
 *
 *  - The file exists and opens: the app works from a private copy of it (never from `%APPDATA%\YPtun`, which
 *    an installed YPtun on the same PC may be using), and every change is written back next to the exe.
 *  - The file is not there (first run, or a stick that was never written to): the data lives in
 *    `%APPDATA%\YPtun` exactly as before, and the file is created from it as soon as there is something
 *    to save — the next start then takes it from the file.
 *  - Next to the exe is read-only: nothing can be written back; the app still runs.
 *
 * Only what the user would hate to lose goes in (see [included]); caches, logs and unpacked natives stay out.
 *
 * The encryption keeps the file from being read or edited casually (it holds subscription links); the key
 * is built into the app — there is deliberately no password, the file has to open on any PC — so it is not
 * protection against someone who goes and reads the app's code.
 */
internal object PortableData {

    const val FILE_NAME = "YPtun.data"
    private val MAGIC = "YPD1".toByteArray(Charsets.US_ASCII)
    private const val SAVE_INTERVAL_MS = 10_000L

    /** What the bundle carries, relative to the data dir: every top-level *.json, the identity file, settings/. */
    internal fun included(relative: String): Boolean {
        val name = relative.replace('\\', '/')
        return when {
            name.contains("..") || name.startsWith("/") -> false
            name == "device_identity" -> true
            !name.contains('/') -> name.endsWith(".json")
            name.startsWith("settings/") -> name.count { it == '/' } == 1
            else -> false
        }
    }

    @Volatile private var active: Path? = null

    /**
     * The directory the app must use for its data. Decided once (it is the first thing anything asks for);
     * [standard] is where the data lives when the portable bundle is not in play.
     */
    fun resolve(standard: () -> Path): Path = active ?: synchronized(this) { active ?: decide(standard).also { active = it } }

    private fun decide(standard: () -> Path): Path {
        val std = standard()
        if (!DesktopRuntimeMode.isPortable) return std
        val exe = DesktopRuntimeMode.portableExecutable() ?: return std
        val bundle = exe.toAbsolutePath().parent?.resolve(FILE_NAME) ?: return std
        var dir = std
        if (Files.isRegularFile(bundle)) {
            val restored = runCatching {
                val work = privateCopyDir()
                clearIncluded(work)
                unpackInto(work, open(Files.readAllBytes(bundle)))
                work
            }
            if (restored.isSuccess) {
                dir = restored.getOrThrow()
            } else {
                // Unreadable (damaged, or from a future version): keep it for inspection, carry on from the
                // regular data dir — the next save then writes a fresh bundle.
                runCatching { Files.move(bundle, bundle.resolveSibling("$FILE_NAME.bad"), StandardCopyOption.REPLACE_EXISTING) }
            }
        }
        startSaver(dir, bundle)
        return dir
    }

    private fun privateCopyDir(): Path {
        val local = System.getenv("LOCALAPPDATA")?.takeIf { it.isNotBlank() }
            ?.let { Path.of(it) } ?: Path.of(System.getProperty("user.home"), "AppData", "Local")
        return local.resolve("YPtun").resolve("portable-data").also { Files.createDirectories(it) }
    }

    private fun startSaver(dir: Path, bundle: Path) {
        val saver = Saver(dir, bundle)
        Thread {
            while (true) {
                runCatching { Thread.sleep(SAVE_INTERVAL_MS) }
                saver.saveIfChanged()
            }
        }.apply { isDaemon = true; name = "portable-data-saver"; start() }
        Runtime.getRuntime().addShutdownHook(Thread { saver.saveIfChanged() })
    }

    internal class Saver(private val dir: Path, private val bundle: Path) {
        private var lastSaved: String? = null

        @Synchronized
        fun saveIfChanged() {
            runCatching {
                val signature = signature(dir)
                if (signature == lastSaved || signature.isEmpty()) return
                val sealed = seal(pack(dir))
                val tmp = bundle.resolveSibling("$FILE_NAME.tmp")
                Files.write(tmp, sealed)
                try {
                    Files.move(tmp, bundle, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
                } catch (_: Exception) {
                    Files.move(tmp, bundle, StandardCopyOption.REPLACE_EXISTING)
                }
                lastSaved = signature
            } // read-only media / file in use: try again at the next change
        }
    }

    /** Cheap change detector: name + size + mtime of every included file. */
    internal fun signature(dir: Path): String = included(dir).joinToString("|") { (rel, path) ->
        val attrs = runCatching { Files.readAttributes(path, java.nio.file.attribute.BasicFileAttributes::class.java) }.getOrNull()
        "$rel:${attrs?.size()}:${attrs?.lastModifiedTime()?.toMillis()}"
    }

    private fun included(dir: Path): List<Pair<String, Path>> {
        if (!Files.isDirectory(dir)) return emptyList()
        return Files.walk(dir, 2).use { walk ->
            walk.filter { Files.isRegularFile(it) }
                .map { dir.relativize(it).toString().replace('\\', '/') to it }
                .filter { (rel, _) -> included(rel) }
                .toList()
                .sortedBy { it.first }
        }
    }

    private fun clearIncluded(dir: Path) {
        included(dir).forEach { (_, path) -> runCatching { Files.deleteIfExists(path) } }
    }

    /** The included files of [dir] as a zip. */
    internal fun pack(dir: Path): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { zip ->
            included(dir).forEach { (rel, path) ->
                zip.putNextEntry(ZipEntry(rel))
                zip.write(Files.readAllBytes(path))
                zip.closeEntry()
            }
        }
        return out.toByteArray()
    }

    /** Writes the zip's entries under [dir]; anything that is not an included relative path is refused. */
    internal fun unpackInto(dir: Path, zipBytes: ByteArray) {
        val root = dir.toAbsolutePath().normalize()
        ZipInputStream(ByteArrayInputStream(zipBytes)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (entry.isDirectory) continue
                require(included(entry.name)) { "unexpected entry in the portable data: ${entry.name}" }
                val target = root.resolve(entry.name).normalize()
                require(target.startsWith(root)) { "entry escapes the data dir: ${entry.name}" }
                Files.createDirectories(target.parent)
                Files.write(target, zip.readBytes())
            }
        }
    }

    private fun key(): SecretKeySpec =
        SecretKeySpec(MessageDigest.getInstance("SHA-256").digest("YPtun/portable-data/v1/9f3c1b7a".toByteArray()), "AES")

    /** magic | 12-byte nonce | AES-256-GCM(payload), the magic as associated data. */
    internal fun seal(payload: ByteArray): ByteArray {
        val nonce = ByteArray(12).also { SecureRandom().nextBytes(it) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            updateAAD(MAGIC)
        }
        return MAGIC + nonce + cipher.doFinal(payload)
    }

    /** Inverse of [seal]; throws on a foreign, damaged or tampered file. */
    internal fun open(file: ByteArray): ByteArray {
        require(file.size > MAGIC.size + 12 + 16 && file.copyOfRange(0, MAGIC.size).contentEquals(MAGIC)) { "not a YPtun data file" }
        val nonce = file.copyOfRange(MAGIC.size, MAGIC.size + 12)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, nonce))
            updateAAD(MAGIC)
        }
        return cipher.doFinal(file, MAGIC.size + 12, file.size - MAGIC.size - 12)
    }
}
