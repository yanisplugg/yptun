package org.olcbox.app.migration

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import org.olcbox.app.security.IntegrityGuard
import java.io.File
import java.io.FileNotFoundException

/**
 * Переезд на новый applicationId.
 *
 * Android НЕ УМЕЕТ переименовывать пакет установленного приложения: сборка с другим applicationId —
 * это для системы другое приложение, встать обновлением поверх старого она не может ни при каких
 * условиях. Поэтому переезд идёт в два релиза:
 *
 *  1. МОСТ (этот код, applicationId ещё старый): в сборке появляется [LegacyExportProvider]. Он
 *     ничего не делает, пока его никто не спросит, но с этого релиза старая установка УМЕЕТ отдать
 *     свои данные. Обновление обычное, пользователь ничего не замечает.
 *  2. ПЕРЕЕЗД (`olcbox.applicationId` в gradle.properties меняется на новый): апдейтер старого
 *     приложения скачивает APK как обычно, система ставит его как НОВОЕ приложение рядом. При первом
 *     запуске новое читает данные старого через провайдер (в [runIfNeeded], до того как что-либо
 *     тронет DataStore) и предлагает удалить старое.
 *
 * Поэтому мост обязан выйти в релиз РАНЬШЕ смены id: у кого не будет промежуточной версии, тому
 * переносить будет нечего.
 */
object LegacyMigration {

    /** applicationId, с которого переезжаем. Не менять — по нему новый билд находит старую установку. */
    const val LEGACY_PACKAGE = "org.olcbox.app"

    private const val AUTHORITY = "$LEGACY_PACKAGE.migration"
    private const val MARKER = "legacy_migration_done"
    private const val UNINSTALL_PROMPTED = "legacy_uninstall_prompted"

    /**
     * Всё состояние приложения: JSON-файлы в filesDir + единственный DataStore со ВСЕМИ настройками
     * (см. VpnPrefDataStore). Формат .preferences_pb от пакета не зависит, поэтому файл переносится
     * как есть. Пути указаны относительно filesDir; чего нет — пропускается.
     */
    internal val FILES = listOf(
        "locations_v4.json",
        "locations_v3.json",
        "locations_view_index.json",
        "active_location.json",
        "datastore/vpn_preferences.preferences_pb"
    )

    /** Старая установка ещё на устройстве (и это действительно она, а не однофамилец). */
    fun legacyAppPresent(context: Context): Boolean =
        context.packageName != LEGACY_PACKAGE && providerIsAuthentic(context)

    /**
     * Переносит данные, если это новый билд, старая установка на месте, а своих данных ещё нет.
     * Зовётся из Application.onCreate СИНХРОННО и до первого обращения к DataStore — иначе DataStore
     * закеширует пустые настройки в памяти и подложенный файл увидят только со следующего запуска.
     * Любая ошибка = просто нет переноса: пользователь настроит заново, ронять старт нельзя.
     */
    fun runIfNeeded(context: Context): Boolean {
        if (context.packageName == LEGACY_PACKAGE) return false
        val marker = File(context.filesDir, MARKER)
        if (marker.exists()) return false
        if (File(context.filesDir, "locations_v4.json").exists()) return false
        if (!providerIsAuthentic(context)) return false

        var copied = 0
        FILES.forEach { name ->
            runCatching {
                val target = File(context.filesDir, name)
                target.parentFile?.mkdirs()
                context.contentResolver
                    .openInputStream(Uri.parse("content://$AUTHORITY/file/$name"))
                    ?.use { input -> target.outputStream().use { input.copyTo(it) } }
                    ?: return@runCatching
                copied++
            }
        }
        runCatching { marker.writeText(copied.toString()) }
        return copied > 0
    }

    /**
     * Один раз после удачного переноса показывает системный диалог удаления старой установки. Свой
     * экран с объяснением не нужен: системный диалог и так называет приложение по имени, а держать
     * две копии VPN на устройстве вредно - обе цепляются к одному VpnService-слоту.
     * Отказ не повторяем: удалить руками пользователь всегда успеет.
     */
    fun promptLegacyUninstallOnce(context: Context) {
        if (!migrationHappened(context) || !legacyAppPresent(context)) return
        val marker = File(context.filesDir, UNINSTALL_PROMPTED)
        if (marker.exists()) return
        runCatching { context.startActivity(uninstallLegacyIntent()) }
            .onSuccess { runCatching { marker.writeText("1") } }
    }

    fun uninstallLegacyIntent(): Intent =
        Intent(Intent.ACTION_DELETE, Uri.parse("package:$LEGACY_PACKAGE"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    fun migrationHappened(context: Context): Boolean =
        File(context.filesDir, MARKER)
            .let { it.exists() && (runCatching { it.readText().toIntOrNull() }.getOrNull() ?: 0) > 0 }

    /**
     * Провайдер защищён signature-правом, то есть ЧИТАТЬ его может только наш подписанный билд. Но
     * это ничего не говорит о том, у КОГО мы читаем: авторитет `org.olcbox.app.migration` на
     * устройстве без старой версии может занять кто угодно и скормить нам свои конфиги (а конфиг =
     * куда пойдёт весь трафик). Поэтому проверяем и владельца авторитета, и его подпись.
     */
    private fun providerIsAuthentic(context: Context): Boolean = runCatching {
        val owner = context.packageManager.resolveContentProvider(AUTHORITY, 0)?.packageName
        owner == LEGACY_PACKAGE && IntegrityGuard.isOfficialPackage(context, LEGACY_PACKAGE)
    }.getOrDefault(false)
}

/**
 * Отдаёт файлы состояния новому билду (см. [LegacyMigration]). Экспортирован, но закрыт
 * signature-правом: прочитать может только APK, подписанный тем же ключом.
 */
class LegacyExportProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val name = uri.pathSegments.drop(1).joinToString("/")
        if (name !in LegacyMigration.FILES) throw FileNotFoundException("not exported: $name")
        val context = context ?: throw FileNotFoundException("no context")
        val file = File(context.filesDir, name)
        if (!file.exists()) throw FileNotFoundException(name)
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String = "application/octet-stream"
    override fun query(u: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun update(u: Uri, v: ContentValues?, s: String?, a: Array<out String>?): Int = 0
    override fun delete(uri: Uri, s: String?, a: Array<out String>?): Int = 0
}
