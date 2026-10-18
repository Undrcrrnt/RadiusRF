package app.radiusrf.core.util

fun ByteArray.toHexString(): String =
    joinToString(":") { "%02X".format(it) }
