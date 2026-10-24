package app.radiusrf.features.maps

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineMapStoreTest {

    @Test
    fun `accepted tile archive extensions`() {
        assertTrue(OfflineMapStore.isSupported("town.mbtiles"))
        assertTrue(OfflineMapStore.isSupported("area.ZIP"))
        assertTrue(OfflineMapStore.isSupported("cache.sqlite"))
        assertTrue(OfflineMapStore.isSupported("pack.gemf"))
        assertFalse(OfflineMapStore.isSupported("notes.txt"))
        assertFalse(OfflineMapStore.isSupported("map.kml"))
    }

    @Test
    fun `sanitize keeps a safe archive name`() {
        assertEquals("Hunt_Area.mbtiles", OfflineMapStore.sanitizeName("Hunt Area.mbtiles"))
        assertEquals("tiles.mbtiles", OfflineMapStore.sanitizeName(""))
        assertEquals("file.zip", OfflineMapStore.sanitizeName("C:\\maps\\folder/file.zip"))
        assertEquals("noext.mbtiles", OfflineMapStore.sanitizeName("noext"))
    }

    @Test
    fun `byte sizes are readable`() {
        assertEquals("512 B", OfflineMapStore.formatBytes(512))
        assertEquals("12 KB", OfflineMapStore.formatBytes(12_000))
        assertEquals("2.5 MB", OfflineMapStore.formatBytes(2_500_000))
    }
}
