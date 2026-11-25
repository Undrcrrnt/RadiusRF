package app.radiusrf.features.connection_log.domain

data class ConnectionRow(
    val id: String,
    val app: String,
    val host: String,
    val protocol: String,
    val port: Int,
    val sent: Long,
    val received: Long
)
