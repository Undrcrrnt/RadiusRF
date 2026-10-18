package app.radiusrf.features.celltower.domain

// Snapshot taken 2026-08-24 from Wikipedia's "Mobile network codes in ITU region 4xx (Asia)"
// (India section), "...2xx (Europe)" and "...3xx (North America)" articles, cross-checked against
// each country's regulator/GSMA-derived entries on those pages. Carrier mergers and rebrands (e.g.
// India's Vodafone+Idea -> Vi) happen periodically, so this table WILL go stale — re-derive from the
// same Wikipedia "Mobile network codes in ITU region Nxx" family of articles when refreshing.
object CarrierLookup {

    fun lookup(mcc: Int?, mnc: Int?): String? {
        if (mcc == null || mnc == null) return null
        return carriers[mcc]?.get(mnc)
    }

    private const val VI = "Vi (Vodafone Idea)"

    // India (MCC 404 and 405). MNC assignment here is unusually fragmented — many circle-specific
    // codes predate the Vodafone-Idea merger and Reliance Jio's rollout, so entries are mapped to the
    // brand that currently owns them (per Wikipedia's Operator column), not the brand a code launched
    // under.
    private val india404: Map<Int, String> = mapOf(
        1 to VI, 4 to VI, 5 to VI, 7 to VI, 11 to VI, 12 to VI, 13 to VI, 14 to VI, 15 to VI,
        19 to VI, 20 to VI, 22 to VI, 24 to VI, 27 to VI, 30 to VI, 43 to VI, 44 to VI, 46 to VI,
        56 to VI, 60 to VI, 78 to VI, 82 to VI, 84 to VI, 86 to VI, 87 to VI, 88 to VI, 89 to VI,
        2 to "Airtel", 3 to "Airtel", 10 to "Airtel", 16 to "Airtel", 31 to "Airtel", 40 to "Airtel",
        45 to "Airtel", 49 to "Airtel", 70 to "Airtel", 90 to "Airtel", 92 to "Airtel", 93 to "Airtel",
        94 to "Airtel", 95 to "Airtel", 96 to "Airtel", 97 to "Airtel", 98 to "Airtel",
        34 to "BSNL", 38 to "BSNL", 51 to "BSNL", 53 to "BSNL", 54 to "BSNL", 55 to "BSNL",
        57 to "BSNL", 58 to "BSNL", 59 to "BSNL", 62 to "BSNL", 64 to "BSNL", 66 to "BSNL",
        71 to "BSNL", 72 to "BSNL", 73 to "BSNL", 74 to "BSNL", 75 to "BSNL", 76 to "BSNL",
        77 to "BSNL", 79 to "BSNL", 80 to "BSNL", 81 to "BSNL",
        68 to "MTNL", 69 to "MTNL",
        9 to "Reliance Communications", 18 to "Reliance Communications", 36 to "Reliance Communications",
        50 to "Reliance Communications", 52 to "Reliance Communications", 83 to "Reliance Communications",
        85 to "Reliance Communications"
    )

    private val india405: Map<Int, String> = mapOf(
        3 to "Reliance Communications", 4 to "Reliance Communications", 5 to "Reliance Communications",
        6 to "Reliance Communications", 7 to "Reliance Communications", 8 to "Reliance Communications",
        9 to "Reliance Communications", 10 to "Reliance Communications", 11 to "Reliance Communications",
        12 to "Reliance Communications", 13 to "Reliance Communications", 14 to "Reliance Communications",
        15 to "Reliance Communications", 17 to "Reliance Communications", 18 to "Reliance Communications",
        19 to "Reliance Communications", 20 to "Reliance Communications", 21 to "Reliance Communications",
        22 to "Reliance Communications", 23 to "Reliance Communications",
        48 to "Indian Railways (GSM-R)",
        51 to "Airtel", 52 to "Airtel", 53 to "Airtel", 54 to "Airtel", 55 to "Airtel", 56 to "Airtel",
        66 to VI, 67 to VI, 70 to VI, 750 to VI, 751 to VI, 752 to VI, 753 to VI, 754 to VI,
        755 to VI, 756 to VI, 799 to VI, 845 to VI, 846 to VI, 847 to VI, 848 to VI, 849 to VI,
        850 to VI, 851 to VI, 852 to VI, 853 to VI, 908 to VI, 909 to VI, 910 to VI, 911 to VI,
        840 to "Jio", 854 to "Jio", 855 to "Jio", 856 to "Jio", 857 to "Jio", 858 to "Jio",
        859 to "Jio", 860 to "Jio", 861 to "Jio", 862 to "Jio", 863 to "Jio", 864 to "Jio",
        865 to "Jio", 866 to "Jio", 867 to "Jio", 868 to "Jio", 869 to "Jio", 870 to "Jio",
        871 to "Jio", 872 to "Jio", 873 to "Jio", 874 to "Jio"
    )

    private val unitedStates310: Map<Int, String> = mapOf(
        4 to "Verizon", 6 to "Verizon", 10 to "Verizon", 12 to "Verizon",
        150 to "AT&T", 410 to "AT&T",
        260 to "T-Mobile"
    )

    private val canada302: Map<Int, String> = mapOf(
        220 to "Telus", 221 to "Telus", 222 to "Telus", 420 to "Telus",
        320 to "Rogers", 370 to "Rogers (Fido)", 720 to "Rogers", 721 to "Rogers",
        490 to "Freedom Mobile", 491 to "Freedom Mobile",
        610 to "Bell", 690 to "Bell"
    )

    private val unitedKingdom234: Map<Int, String> = mapOf(
        2 to "O2 (UK)", 10 to "O2 (UK)", 11 to "O2 (UK)",
        15 to "Vodafone UK",
        20 to "Three (UK)",
        30 to "EE", 33 to "EE", 34 to "EE",
        0 to "BT", 76 to "BT"
    )

    private val germany262: Map<Int, String> = mapOf(
        1 to "Deutsche Telekom",
        2 to "Vodafone Germany",
        3 to "O2 Germany (Telefónica)", 7 to "O2 Germany (Telefónica)"
    )

    private val france208: Map<Int, String> = mapOf(
        1 to "Orange", 2 to "Orange",
        9 to "SFR", 10 to "SFR", 13 to "SFR",
        15 to "Free Mobile", 16 to "Free Mobile",
        20 to "Bouygues Telecom"
    )

    private val italy222: Map<Int, String> = mapOf(
        1 to "TIM", 43 to "TIM",
        10 to "Vodafone Italy",
        50 to "Iliad Italy",
        88 to "Wind Tre", 99 to "Wind Tre"
    )

    private val spain214: Map<Int, String> = mapOf(
        1 to "Vodafone Spain",
        3 to "Orange Spain",
        7 to "Movistar"
    )

    private val china460: Map<Int, String> = mapOf(
        0 to "China Mobile",
        1 to "China Unicom",
        11 to "China Telecom"
    )

    private val japan440: Map<Int, String> = mapOf(
        10 to "NTT docomo",
        11 to "Rakuten Mobile",
        20 to "SoftBank", 21 to "SoftBank",
        50 to "au (KDDI)"
    )

    private val australia505: Map<Int, String> = mapOf(
        1 to "Telstra",
        2 to "Optus",
        3 to "Vodafone Australia"
    )

    private val uae424: Map<Int, String> = mapOf(
        2 to "Etisalat",
        3 to "du"
    )

    private val carriers: Map<Int, Map<Int, String>> = mapOf(
        404 to india404,
        405 to india405,
        310 to unitedStates310,
        302 to canada302,
        234 to unitedKingdom234,
        262 to germany262,
        208 to france208,
        222 to italy222,
        214 to spain214,
        460 to china460,
        440 to japan440,
        505 to australia505,
        424 to uae424
    )
}
