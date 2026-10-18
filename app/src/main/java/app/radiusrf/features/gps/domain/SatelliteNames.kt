package app.radiusrf.features.gps.domain

// Data snapshot 2026-08-23 from navcen.uscg.gov/gps-constellation (GPS), Wikipedia "List of Galileo
// satellites" (Galileo), sys.qzss.go.jp/dod/en/constellation.html (QZSS), and Wikipedia "NavIC" (IRNSS);
// PRN/slot assignments shift over time so this table should be re-verified against those sources when stale.
// GLONASS orbital slots and SBAS PRNs rotate too often to verify reliably here and are intentionally omitted
// rather than guessed — a wrong satellite name is worse than none.
object SatelliteNames {

    fun lookup(constellationType: Int, svid: Int): String? = when (constellationType) {
        1 -> gps[svid]
        4 -> qzss[svid]
        6 -> galileo[svid]
        7 -> irnss[svid]
        else -> null
    }

    private val gps = mapOf(
        1 to "NAVSTAR 80", 2 to "NAVSTAR 61", 3 to "NAVSTAR 69", 4 to "NAVSTAR 74",
        5 to "NAVSTAR 50", 6 to "NAVSTAR 67", 7 to "NAVSTAR 48", 8 to "NAVSTAR 72",
        9 to "NAVSTAR 68", 10 to "NAVSTAR 73", 11 to "NAVSTAR 78", 12 to "NAVSTAR 58",
        13 to "NAVSTAR 43", 14 to "NAVSTAR 77", 15 to "NAVSTAR 55", 16 to "NAVSTAR 56",
        17 to "NAVSTAR 53", 18 to "NAVSTAR 75", 19 to "NAVSTAR 59", 20 to "NAVSTAR 82",
        21 to "NAVSTAR 81", 22 to "NAVSTAR 44", 23 to "NAVSTAR 76", 24 to "NAVSTAR 65",
        25 to "NAVSTAR 62", 26 to "NAVSTAR 71", 27 to "NAVSTAR 66", 28 to "NAVSTAR 79",
        29 to "NAVSTAR 57", 30 to "NAVSTAR 64", 31 to "NAVSTAR 52", 32 to "NAVSTAR 70"
    )

    private val qzss = mapOf(
        194 to "Michibiki-2 (QZS-2)",
        195 to "Michibiki-4 (QZS-4)",
        196 to "Michibiki-1R (QZS-1R)",
        199 to "Michibiki-3 (QZS-3)",
        200 to "Michibiki-6 (QZS-6)",
        201 to "Michibiki-7 (QZS-7)"
    )

    private val galileo = mapOf(
        2 to "Alizée (GSAT0211)", 3 to "Lisa (GSAT0212)", 4 to "Kimberley (GSAT0213)",
        5 to "Tijmen (GSAT0214)", 6 to "GSAT0227", 7 to "Antonianna (GSAT0207)",
        8 to "Andriana (GSAT0208)", 9 to "Liene (GSAT0209)", 10 to "Shriya (GSAT0224)",
        11 to "Thijs (GSAT0101)", 12 to "Natalia (GSAT0102)", 13 to "Samuel (GSAT0220)",
        15 to "Anna (GSAT0221)", 16 to "GSAT0232", 19 to "David (GSAT0103)",
        21 to "Nicole (GSAT0215)", 23 to "GSAT0226", 25 to "Zofia (GSAT0216)",
        26 to "Adam (GSAT0203)", 27 to "Alexandre (GSAT0217)", 28 to "GSAT0233",
        29 to "GSAT0225", 30 to "Oriana (GSAT0206)", 31 to "Irina (GSAT0218)",
        32 to "GSAT0234", 33 to "Ellen (GSAT0222)", 34 to "Nikolina (GSAT0223)",
        36 to "Tara (GSAT0219)"
    )

    private val irnss = mapOf(
        1 to "IRNSS-1A", 2 to "IRNSS-1B", 3 to "IRNSS-1C", 4 to "IRNSS-1D",
        5 to "IRNSS-1E", 6 to "IRNSS-1F", 7 to "IRNSS-1G", 9 to "IRNSS-1I",
        10 to "NVS-01"
    )
}
