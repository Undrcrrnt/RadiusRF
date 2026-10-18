package app.radiusrf.features.gps.domain

// NMEA 0183 sentence format: $<talkerId><sentenceType>,<field1>,<field2>,...*<checksum>
object NmeaParser {

    private val talkerNames = mapOf(
        "GP" to "GPS", "GL" to "GLONASS", "GA" to "Galileo",
        "GB" to "BeiDou", "BD" to "BeiDou", "GQ" to "QZSS",
        "GI" to "IRNSS", "GN" to "GNSS"
    )

    private val fixQualityNames = mapOf(
        0 to "Invalid", 1 to "GPS", 2 to "DGPS", 3 to "PPS",
        4 to "RTK Fixed", 5 to "RTK Float", 6 to "Estimated", 7 to "Manual", 8 to "Simulation"
    )

    fun parse(raw: String): String {
        val body = raw.substringBefore('*').removePrefix("$")
        if (body.length < 6) return raw
        val talker = body.substring(0, 2)
        val type = body.substring(2, 5)
        val fields = body.substring(5).removePrefix(",").split(",")
        val constellation = talkerNames[talker] ?: talker

        return try {
            when (type) {
                "GGA" -> parseGga(constellation, fields)
                "RMC" -> parseRmc(constellation, fields)
                "GSA" -> parseGsa(constellation, fields)
                "GSV" -> parseGsv(constellation, fields)
                "VTG" -> parseVtg(constellation, fields)
                "GLL" -> parseGll(constellation, fields)
                else -> raw
            }
        } catch (_: Exception) {
            raw
        }
    }

    private fun parseGga(constellation: String, f: List<String>): String {
        val fixQuality = f[5].toIntOrNull()
        val fixName = fixQualityNames[fixQuality] ?: "Unknown"
        val position = formatLatLon(f[1], f[2], f[3], f[4])
        return "GGA ($constellation)  ${formatTime(f[0])}  $position  Fix:$fixName  Sats:${f[6]}  HDOP:${f[7]}  Alt:${f[8]}${f[9]}"
    }

    private fun parseRmc(constellation: String, f: List<String>): String {
        val status = if (f[1] == "A") "Valid" else "Void"
        val position = formatLatLon(f[2], f[3], f[4], f[5])
        val speedKmh = f[6].toDoubleOrNull()?.times(1.852)
        val speedText = speedKmh?.let { "%.1f".format(it) } ?: f[6]
        return "RMC ($constellation)  ${formatTime(f[0])}  Status:$status  $position  Speed:${speedText}km/h  Course:${f[7]}°"
    }

    private fun parseGsa(constellation: String, f: List<String>): String {
        val fixType = when (f[1]) {
            "1" -> "No Fix"
            "2" -> "2D"
            "3" -> "3D"
            else -> "Unknown"
        }
        val usedSats = f.subList(2, 14).filter { it.isNotBlank() }
        val satsText = if (usedSats.isEmpty()) "none" else usedSats.joinToString(",")
        return "GSA ($constellation)  Fix:$fixType  Sats used:$satsText  PDOP:${f[14]}  HDOP:${f[15]}  VDOP:${f[16]}"
    }

    private fun parseGsv(constellation: String, f: List<String>): String {
        // NMEA 4.10+ appends a trailing Signal ID field after the last satellite block, which is
        // not part of a satellite quadruplet — only read i..i+3 groups that are fully in bounds.
        val lastQuadStart = f.size - 4
        val entries = (3..lastQuadStart step 4)
            .mapNotNull { i ->
                val prn = f[i].takeIf { it.isNotBlank() } ?: return@mapNotNull null
                "#$prn El:${f[i + 1]}° Az:${f[i + 2]}° SNR:${f[i + 3]}dB"
            }
        return "GSV ($constellation)  Msg ${f[1]}/${f[0]}  In view:${f[2]}  ${entries.joinToString("  ")}"
    }

    private fun parseVtg(constellation: String, f: List<String>): String {
        return "VTG ($constellation)  Course:${f[0]}° True  Speed:${f[6]}km/h"
    }

    private fun parseGll(constellation: String, f: List<String>): String {
        val status = if (f[5] == "A") "Valid" else "Void"
        val position = formatLatLon(f[0], f[1], f[2], f[3])
        return "GLL ($constellation)  ${formatTime(f[4])}  $position  Status:$status"
    }

    private fun formatTime(raw: String): String {
        if (raw.length < 6) return raw
        return "${raw.substring(0, 2)}:${raw.substring(2, 4)}:${raw.substring(4, 6)} UTC"
    }

    private fun formatLatLon(latRaw: String, latDir: String, lonRaw: String, lonDir: String): String {
        if (latRaw.isBlank() || lonRaw.isBlank()) return "no fix"
        val lat = toDecimalDegrees(latRaw, 2)
        val lon = toDecimalDegrees(lonRaw, 3)
        return "%.5f°%s %.5f°%s".format(lat, latDir, lon, lonDir)
    }

    // NMEA lat/lon are degrees+minutes (ddmm.mmmm / dddmm.mmmm), not decimal degrees
    private fun toDecimalDegrees(raw: String, degreeDigits: Int): Double {
        val degrees = raw.take(degreeDigits).toDouble()
        val minutes = raw.drop(degreeDigits).toDouble()
        return degrees + minutes / 60.0
    }
}
