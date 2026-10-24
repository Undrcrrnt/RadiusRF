package app.radiusrf.features.sdr.domain

import android.hardware.usb.UsbManager

class SdrDetector(
    private val usbManager: UsbManager?
) {
    fun detect(): List<SdrDeviceInfo> {
        val devices = usbManager?.deviceList?.values ?: return emptyList()
        return devices.mapNotNull { device ->
            val entry = CATALOG[device.vendorId to device.productId] ?: return@mapNotNull null
            SdrDeviceInfo(
                vendorId = device.vendorId,
                productId = device.productId,
                deviceName = device.productName ?: device.deviceName,
                chipset = entry.chipset,
                isRtlSdr = entry.rtl,
                isHackrf = entry.hackrf,
                isAirspy = entry.airspy,
                isAirspyHf = entry.airspyHf,
                isHydra = entry.hydra
            )
        }
    }

    private data class Entry(
        val chipset: String,
        val family: String,
        val rtl: Boolean = false,
        val hackrf: Boolean = false,
        val airspy: Boolean = false,
        val airspyHf: Boolean = false,
        val hydra: Boolean = false
    )

    companion object {
        private fun rtl(name: String) = Entry(name, "RTL-SDR", rtl = true)
        private fun hack(name: String) = Entry(name, "HackRF", hackrf = true)
        private fun airspy(name: String) = Entry(name, "Airspy", airspy = true)
        private fun airspyHf(name: String) = Entry(name, "Airspy HF+", airspyHf = true)
        private fun hydra(name: String) = Entry(name, "HydraSDR", hydra = true)
        private fun sdr(name: String, family: String) = Entry(name, family)

        /**
         * USB identities of receivers Android SDR apps can open.
         * RTL-SDR ids are the osmocom librtlsdr table. HackRF One is 1d50:6089
         * and the Jawbreaker is 1d50:604b. Airspy R2 and Mini share 1d50:60a1.
         * A legacy HydraSDR that still uses that same id is opened as an Airspy.
         * Official HydraSDR RFOne is 38af:0001.
         */
        private val CATALOG: Map<Pair<Int, Int>, Entry> = mapOf(
            (0x0BDA to 0x2832) to rtl("RTL2832U"),
            (0x0BDA to 0x2838) to rtl("RTL-SDR Blog V3/V4"),
            (0x0BDA to 0x2831) to rtl("RTL2831U"),
            (0x0413 to 0x6680) to rtl("DigitalNow Quad DVB-T"),
            (0x0413 to 0x6F0F) to rtl("Leadtek WinFast DTV Dongle mini D"),
            (0x0458 to 0x707F) to rtl("Genius TVGo DVB-T03"),
            (0x0CCD to 0x00A9) to rtl("Terratec Cinergy T Stick Black"),
            (0x0CCD to 0x00B3) to rtl("Terratec NOXON DAB/DAB+"),
            (0x0CCD to 0x00B4) to rtl("Terratec Deutschlandradio DAB Stick"),
            (0x0CCD to 0x00B5) to rtl("Terratec NOXON DAB Stick"),
            (0x0CCD to 0x00B7) to rtl("Terratec Media Broadcast DAB Stick"),
            (0x0CCD to 0x00B8) to rtl("Terratec BR DAB Stick"),
            (0x0CCD to 0x00B9) to rtl("Terratec WDR DAB Stick"),
            (0x0CCD to 0x00C0) to rtl("Terratec MuellerVerlag DAB Stick"),
            (0x0CCD to 0x00C6) to rtl("Terratec Fraunhofer DAB Stick"),
            (0x0CCD to 0x00D3) to rtl("Terratec Cinergy T Stick RC"),
            (0x0CCD to 0x00D7) to rtl("Terratec T Stick PLUS"),
            (0x0CCD to 0x00E0) to rtl("Terratec NOXON DAB/DAB+ (rev 2)"),
            (0x1554 to 0x5020) to rtl("PixelView PV-DT235U"),
            (0x15F4 to 0x0131) to rtl("Astrometa DVB-T/T2"),
            (0x15F4 to 0x0133) to rtl("HanfTek DAB+FM+DVB-T"),
            (0x185B to 0x0620) to rtl("Compro Videomate U620F"),
            (0x185B to 0x0650) to rtl("Compro Videomate U650F"),
            (0x185B to 0x0680) to rtl("Compro Videomate U680F"),
            (0x1B80 to 0xD393) to rtl("GIGABYTE GT-U7300"),
            (0x1B80 to 0xD394) to rtl("DIKOM USB-DVBT HD"),
            (0x1B80 to 0xD395) to rtl("Peak 102569AGPK"),
            (0x1B80 to 0xD397) to rtl("KWorld KW-UB450-T"),
            (0x1B80 to 0xD398) to rtl("Zaapa ZT-MINDVBZP"),
            (0x1B80 to 0xD39D) to rtl("SVEON STV20"),
            (0x1B80 to 0xD3A4) to rtl("Twintech UT-40"),
            (0x1B80 to 0xD3A8) to rtl("ASUS U3100MINI_PLUS_V2"),
            (0x1B80 to 0xD3AF) to rtl("SVEON STV27"),
            (0x1B80 to 0xD3B0) to rtl("SVEON STV21"),
            (0x1D19 to 0x1101) to rtl("Dexatek Logilink VG0002A"),
            (0x1D19 to 0x1102) to rtl("MSI DigiVox mini II"),
            (0x1D19 to 0x1103) to rtl("Dexatek DK 5217"),
            (0x1D19 to 0x1104) to rtl("MSI DigiVox Micro HD"),
            (0x1F4D to 0xA803) to rtl("Sweex DVB-T USB"),
            (0x1F4D to 0xB803) to rtl("GTek T803"),
            (0x1F4D to 0xC803) to rtl("Lifeview LV5TDeluxe"),
            (0x1F4D to 0xD286) to rtl("MyGica TD312"),
            (0x1F4D to 0xD803) to rtl("PROlectrix DV107669"),

            (0x1D50 to 0x6089) to hack("HackRF One"),
            (0x1D50 to 0x604B) to hack("HackRF Jawbreaker"),
            (0x1D50 to 0xCC15) to hack("rad1o"),
            (0x1FC9 to 0x000C) to sdr("HackRF DFU bootloader", "HackRF"),

            (0x1D50 to 0x60A1) to airspy("Airspy R2 / Mini"),
            (0x03EB to 0x800C) to airspyHf("Airspy HF+"),
            (0x38AF to 0x0001) to hydra("HydraSDR RFOne"),

            (0x16D0 to 0x0F32) to sdr("SDRplay RSP1", "SDRplay"),
            (0x1DF7 to 0x2500) to sdr("SDRplay RSP1", "SDRplay"),
            (0x1DF7 to 0x3000) to sdr("SDRplay RSP1A", "SDRplay"),
            (0x1DF7 to 0x3010) to sdr("SDRplay RSP2", "SDRplay"),
            (0x1DF7 to 0x3020) to sdr("SDRplay RSPduo", "SDRplay"),
            (0x1DF7 to 0x3030) to sdr("SDRplay RSPdx", "SDRplay"),
            (0x1DF7 to 0x3050) to sdr("SDRplay RSPdx-R2", "SDRplay"),
            (0x1DF7 to 0x3060) to sdr("SDRplay RSP1B", "SDRplay"),

            (0x1D50 to 0x6108) to sdr("LimeSDR-USB", "LimeSDR"),

            (0x1D50 to 0x6066) to sdr("bladeRF", "bladeRF"),
            (0x2CF0 to 0x5246) to sdr("bladeRF", "bladeRF"),
            (0x2CF0 to 0x5250) to sdr("bladeRF 2.0 micro", "bladeRF"),

            (0x2500 to 0x0020) to sdr("USRP B200", "USRP"),
            (0x2500 to 0x0021) to sdr("USRP B200mini", "USRP"),
            (0x2500 to 0x0022) to sdr("USRP B205mini", "USRP"),
            (0x2500 to 0x0023) to sdr("USRP B206mini", "USRP"),
            (0x3923 to 0x7813) to sdr("USRP B200", "USRP"),
            (0x3923 to 0x7814) to sdr("USRP B210", "USRP"),

            (0x0456 to 0xB673) to sdr("ADALM-PLUTO", "ADALM-PLUTO")
        )

        /** Chipset name for each recognised USB id. */
        val KNOWN_DEVICES: Map<Pair<Int, Int>, String> =
            CATALOG.mapValues { it.value.chipset }

        /** Short names for the on-screen list. One entry per receiver family. */
        val FAMILIES: List<String> = CATALOG.values.map { it.family }.distinct()
    }
}
