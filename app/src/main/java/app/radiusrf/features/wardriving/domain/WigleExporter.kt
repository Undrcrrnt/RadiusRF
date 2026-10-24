package app.radiusrf.features.wardriving.domain

import app.radiusrf.core.util.FrequencyUtils
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object WigleExporter {

    fun export(records: List<WardrivingRecord>): String {
        val sb = StringBuilder()
        sb.appendLine("WigleWifi-1.6,appRelease=RadiusRf,model=Android,release=1.0,device=RadiusRf,display=RadiusRf,board=,brand=")
        sb.appendLine("MAC,SSID,AuthMode,FirstSeen,Channel,Frequency,RSSI,CurrentLatitude,CurrentLongitude,AltitudeMeters,AccuracyMeters,RCOIs,MfgrId,Type")

        val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.US)
        records.forEach { record ->
            val date = dateFormat.format(Date(record.timestamp))
            val (channel, frequency) = channelAndFrequency(record)
            val auth = if (record.isClient) {
                "client of ${record.associatedBssid.orEmpty()}"
            } else {
                record.capabilities.orEmpty()
            }
            sb.appendLine(
                listOf(
                    record.bssid,
                    record.ssid.orEmpty(),
                    auth,
                    date,
                    channel,
                    frequency,
                    record.rssi.toString(),
                    record.lat.toString(),
                    record.lng.toString(),
                    plainNumber(record.altitude),
                    plainNumber(record.accuracy),
                    "",
                    "",
                    record.radio
                ).joinToString(",") { csvField(it) }
            )
        }
        return sb.toString()
    }

    private fun channelAndFrequency(record: WardrivingRecord): Pair<String, String> = when (record.radio) {
        SurveyRadio.WIFI, SurveyRadio.CLIENT -> {
            val channel = if (record.frequency <= 0) 0 else {
                val parsed = FrequencyUtils.frequencyToChannel(record.frequency)
                if (parsed < 0) 0 else parsed
            }
            channel.toString() to if (record.frequency > 0) record.frequency.toString() else ""
        }
        SurveyRadio.BLE, SurveyRadio.BT -> "" to ""
        else -> {
            val arfcn = if (record.frequency > 0) record.frequency.toString() else ""
            arfcn to arfcn
        }
    }
}
