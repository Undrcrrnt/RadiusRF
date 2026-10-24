package app.radiusrf.features.maps

import android.content.Context
import android.net.Uri
import app.radiusrf.core.prefs.AppSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import org.osmdroid.config.Configuration
import org.osmdroid.tileprovider.modules.ArchiveFileFactory
import java.io.File
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton

data class MapArchive(
    val name: String,
    val path: String,
    val bytes: Long,
    val detail: String
)

/** Imported OpenStreetMap tile archives used by Locate, Fox Hunt, and Field Map. */
@Singleton
class OfflineMapStore @Inject constructor(
    @ApplicationContext private val context: Context,
    private val settings: AppSettings
) {
    private val _archives = MutableStateFlow(scan())
    val archives: StateFlow<List<MapArchive>> = _archives.asStateFlow()

    val tilesDir: File
        get() = File(File(context.filesDir, "osmdroid"), "tiles").apply { mkdirs() }

    fun configureOsmdroid() {
        val base = File(context.filesDir, "osmdroid").apply { mkdirs() }
        tilesDir
        Configuration.getInstance().apply {
            load(context, context.getSharedPreferences("osmdroid", Context.MODE_PRIVATE))
            userAgentValue = "${context.packageName}/${app.radiusrf.BuildConfig.VERSION_NAME}"
            osmdroidBasePath = base
            osmdroidTileCache = tilesDir
        }
    }

    fun refresh() {
        _archives.value = scan()
    }

    suspend fun importFrom(uri: Uri, suggestedName: String): String? = withContext(Dispatchers.IO) {
        val name = sanitizeName(suggestedName)
        if (!isSupported(name)) {
            return@withContext "Use an .mbtiles, .zip, .sqlite, or .gemf tile archive"
        }
        val dest = File(tilesDir, name)
        val copied = runCatching {
            context.contentResolver.openInputStream(uri)?.use { input ->
                dest.outputStream().use { input.copyTo(it) }
            }
        }.getOrNull()
        if (copied == null) {
            dest.delete()
            return@withContext "Could not read that file"
        }
        val error = validateArchive(dest)
        if (error != null) {
            dest.delete()
            return@withContext error
        }
        settings.setActiveMapArchive(name)
        refresh()
        null
    }

    fun select(name: String) {
        if (_archives.value.any { it.name == name }) settings.setActiveMapArchive(name)
    }

    fun delete(name: String) {
        File(tilesDir, name).takeIf { it.isFile }?.delete()
        if (settings.activeMapArchive.value == name) {
            settings.setActiveMapArchive(scan().firstOrNull()?.name)
        }
        refresh()
    }

    private fun scan(): List<MapArchive> {
        val files = tilesDir.listFiles().orEmpty()
            .filter { it.isFile && isSupported(it.name) }
            .sortedBy { it.name.lowercase(Locale.US) }
        return files.map { file ->
            MapArchive(file.name, file.absolutePath, file.length(), describe(file))
        }
    }

    companion object {
        private val EXTENSIONS = setOf("mbtiles", "zip", "sqlite", "gemf")

        fun isSupported(name: String): Boolean = extension(name) in EXTENSIONS

        fun sanitizeName(raw: String): String {
            val trimmed = raw.substringAfterLast('/').substringAfterLast('\\').ifBlank { "tiles.mbtiles" }
            val cleaned = buildString(trimmed.length) {
                trimmed.forEach { ch ->
                    append(if (ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_') ch else '_')
                }
            }.trim('.')
            return if (isSupported(cleaned)) cleaned else "$cleaned.mbtiles"
        }

        fun extension(name: String): String = name.substringAfterLast('.', "").lowercase(Locale.US)

        fun validateArchive(file: File): String? {
            val archive = runCatching { ArchiveFileFactory.getArchiveFile(file) }.getOrNull()
                ?: return "That file is not a readable tile archive"
            return try {
                null
            } finally {
                runCatching { archive.close() }
            }
        }

        fun describe(file: File): String {
            val size = formatBytes(file.length())
            if (extension(file.name) != "mbtiles") return size
            val extra = readMbtilesMeta(file) ?: return size
            return if (extra.isBlank()) size else "$size · $extra"
        }

        fun formatBytes(bytes: Long): String = when {
            bytes >= 1_000_000_000 -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
            bytes >= 1_000_000 -> String.format(Locale.US, "%.1f MB", bytes / 1_000_000.0)
            bytes >= 1_000 -> "${bytes / 1_000} KB"
            else -> "$bytes B"
        }

        private fun readMbtilesMeta(file: File): String? = runCatching {
            val db = android.database.sqlite.SQLiteDatabase.openDatabase(
                file.absolutePath,
                null,
                android.database.sqlite.SQLiteDatabase.OPEN_READONLY
            )
            db.use { database ->
                val values = mutableMapOf<String, String>()
                database.rawQuery("SELECT name, value FROM metadata", null).use { cursor ->
                    while (cursor.moveToNext()) {
                        values[cursor.getString(0).orEmpty().lowercase(Locale.US)] = cursor.getString(1).orEmpty()
                    }
                }
                val title = values["name"]?.takeIf { it.isNotBlank() }
                val bounds = values["bounds"]?.takeIf { it.isNotBlank() }
                listOfNotNull(title, bounds).joinToString(" · ").ifBlank { null }
            }
        }.getOrNull()
    }
}
