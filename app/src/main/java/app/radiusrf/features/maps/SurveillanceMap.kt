package app.radiusrf.features.maps

data class SurveillanceSite(
    val id: Long,
    val lat: Double,
    val lon: Double,
    val kind: String,
    val name: String
)

object SurveillanceMap {
    private val element = Regex("\\{(?:[^{}]|\\{[^{}]*\\})*\\}")
    private val idField = Regex("\"id\"\\s*:\\s*(\\d+)")
    private val tagField = Regex("\"([^\"]+)\"\\s*:\\s*\"([^\"]*)\"")
    private val latField = Regex("\"lat\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")
    private val lonField = Regex("\"lon\"\\s*:\\s*(-?\\d+(?:\\.\\d+)?)")

    fun parseOverpass(json: String): List<SurveillanceSite> {
        val sites = ArrayList<SurveillanceSite>()
        val elements = json.substringAfter("\"elements\"", "")
        if (elements.isEmpty()) return emptyList()
        for (match in element.findAll(elements)) {
            val body = match.value
            if (!body.contains("\"lat\"")) continue
            val id = idField.find(body)?.groupValues?.get(1)?.toLongOrNull() ?: continue
            val lat = latField.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val lon = lonField.find(body)?.groupValues?.get(1)?.toDoubleOrNull() ?: continue
            val tags = tagField.findAll(body).associate { it.groupValues[1] to it.groupValues[2] }
            val kind = when {
                tags["surveillance:type"].equals("ALPR", true) ||
                    tags["surveillance"].equals("ALPR", true) ||
                    tags["highway"] == "speed_camera" -> "ALPR / speed"
                tags["man_made"] == "surveillance" -> "Camera"
                else -> continue
            }
            val name = tags["name"] ?: tags["operator"] ?: kind
            sites += SurveillanceSite(id, lat, lon, kind, name)
        }
        return sites.distinctBy { it.id }
    }

    fun query(south: Double, west: Double, north: Double, east: Double): String = """
        [out:json][timeout:25];
        (
          node["man_made"="surveillance"]($south,$west,$north,$east);
          node["highway"="speed_camera"]($south,$west,$north,$east);
          node["surveillance:type"="ALPR"]($south,$west,$north,$east);
        );
        out body;
    """.trimIndent()
}
