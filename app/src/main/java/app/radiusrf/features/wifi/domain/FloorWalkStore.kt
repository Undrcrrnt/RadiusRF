package app.radiusrf.features.wifi.domain

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/** Saved floor walks, stored as files on this phone. Nothing is uploaded. */
@Singleton
class FloorWalkStore @Inject constructor(
    @ApplicationContext context: Context
) {
    private val dir = File(context.filesDir, "floor-walks").apply { mkdirs() }

    fun list(): List<FloorWalkRecord> = synchronized(dir) {
        dir.listFiles { file -> file.extension == "json" }
            .orEmpty()
            .mapNotNull { file -> runCatching { FloorWalkMath.decode(file.readText()) }.getOrNull() }
            .sortedByDescending { it.startedAt }
    }

    fun save(walk: FloorWalkRecord) = synchronized(dir) {
        val text = FloorWalkMath.encode(walk)
        val dest = File(dir, "${walk.id}.json")
        val tmp = File(dir, "${walk.id}.json.tmp")
        tmp.writeText(text)
        if (dest.exists()) dest.delete()
        if (!tmp.renameTo(dest)) {
            dest.writeText(text)
            tmp.delete()
        }
        val extra = list().drop(MAX_WALKS)
        extra.forEach { delete(it.id) }
    }

    fun delete(id: String) = synchronized(dir) {
        File(dir, "$id.json").delete()
        File(dir, "$id.json.tmp").delete()
    }

    private companion object {
        const val MAX_WALKS = 40
    }
}
