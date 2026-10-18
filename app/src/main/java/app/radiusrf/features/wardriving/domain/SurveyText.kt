package app.radiusrf.features.wardriving.domain

internal fun csvField(value: String): String {
    if (value.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return value
    return "\"" + value.replace("\"", "\"\"") + "\""
}

internal fun xmlText(value: String): String = buildString {
    value.forEach { ch ->
        when (ch) {
            '&' -> append("&amp;")
            '<' -> append("&lt;")
            '>' -> append("&gt;")
            '"' -> append("&quot;")
            else -> append(ch)
        }
    }
}

internal fun plainNumber(value: Double): String =
    if (value % 1.0 == 0.0) value.toLong().toString() else value.toString()

/** MCC+MNC, TAC/LAC, and cell id, in the form Netseer's WiGLE sample uses. */
fun cellIdentity(mcc: Int?, mnc: Int?, lac: Int?, cid: Long?): String {
    val mccText = (mcc ?: 0).toString().padStart(3, '0')
    val mncText = (mnc ?: 0).toString().padStart(3, '0')
    return "${mccText}${mncText}_${lac ?: 0}_${cid ?: 0}"
}

fun plmn(mcc: Int?, mnc: Int?): String {
    val mccText = (mcc ?: 0).toString().padStart(3, '0')
    val mncText = (mnc ?: 0).toString().padStart(3, '0')
    return "$mccText$mncText"
}
