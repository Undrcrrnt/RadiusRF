package app.radiusrf.features.wardriving.domain

import java.nio.charset.StandardCharsets

object SurveyExport {

    fun bytes(records: List<WardrivingRecord>, format: ExportFormat): ByteArray = when (format) {
        ExportFormat.WIGLE -> WigleExporter.export(records).toByteArray(StandardCharsets.UTF_8)
        ExportFormat.KISMET_CSV -> KismetExporter.csv(records).toByteArray(StandardCharsets.UTF_8)
        ExportFormat.KISMET_NETXML -> KismetExporter.netxml(records).toByteArray(StandardCharsets.UTF_8)
        ExportFormat.AIRODUMP -> AirodumpExporter.export(records).toByteArray(StandardCharsets.UTF_8)
        ExportFormat.PCAPNG -> SurveyPcap.write(records)
        ExportFormat.OBSERVATIONS -> observations(records).toByteArray(StandardCharsets.UTF_8)
    }

    private fun observations(records: List<WardrivingRecord>): String {
        val sb = StringBuilder()
        sb.appendLine("radio,id,name,rssi,frequency,details,latitude,longitude,altitude_m,accuracy_m,time_ms")
        records.forEach { record ->
            sb.appendLine(
                listOf(
                    record.radio,
                    record.bssid,
                    record.ssid.orEmpty(),
                    record.rssi.toString(),
                    record.frequency.toString(),
                    record.capabilities.orEmpty(),
                    record.lat.toString(),
                    record.lng.toString(),
                    plainNumber(record.altitude),
                    plainNumber(record.accuracy),
                    record.timestamp.toString()
                ).joinToString(",") { csvField(it) }
            )
        }
        return sb.toString()
    }
}
