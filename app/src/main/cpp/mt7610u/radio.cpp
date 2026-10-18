/* SPDX-License-Identifier: GPL-2.0-only */
/*
 * Receive-only MT7610U radio: power, MAC/BBP initvals, RF channel, monitor RX.
 * Sequences from Linux mt76x0 (usb.c, init.c, phy.c, usb_mcu.c).
 * 802.11 transmit URBs are never submitted.
 */

#include "internal.h"
#include "kernel_initvals.h"
#include "kernel_initvals_init.h"
#include "kernel_initvals_phy.h"

#include <android/log.h>
#include <atomic>
#include <memory>
#include <mutex>
#include <stdio.h>
#include <string.h>

#define TAG "mt7610u"

mt7610u_dev *g_mt = nullptr;

static std::mutex g_life;
static std::shared_ptr<mt7610u_dev> g_owner;
static std::atomic<int> g_epoch{0};
static void (*g_thread_exit)(void) = nullptr;

static std::shared_ptr<mt7610u_dev> retain()
{
	std::lock_guard<std::mutex> life(g_life);
	return g_owner;
}

static void wr_tab(mt7610u_dev *d, const struct mt76_reg_pair *tab, size_t n)
{
	for (size_t i = 0; i < n; i++)
		mt_wr(d, tab[i].reg, tab[i].value);
}

static int rf_tab(mt7610u_dev *d, const struct mt76_reg_pair *tab, size_t n)
{
	return mt_mcu_wr_rp(d, MT_MCU_MEMMAP_RF, tab, (int)n);
}

int mt_chip_onoff(mt7610u_dev *d, int enable, int reset)
{
	uint32_t val = mt_rr(d, MT_WLAN_FUN_CTRL);
	if (reset) {
		val |= MT_WLAN_FUN_CTRL_GPIO_OUT_EN;
		val &= ~MT_WLAN_FUN_CTRL_FRC_WL_ANT_SEL;
		if (val & MT_WLAN_FUN_CTRL_WLAN_EN) {
			val |= MT_WLAN_FUN_CTRL_WLAN_RESET | MT_WLAN_FUN_CTRL_WLAN_RESET_RF;
			mt_wr(d, MT_WLAN_FUN_CTRL, val);
			mt7610_usleep(20);
			val &= ~(MT_WLAN_FUN_CTRL_WLAN_RESET | MT_WLAN_FUN_CTRL_WLAN_RESET_RF);
		}
	}
	mt_wr(d, MT_WLAN_FUN_CTRL, val);
	mt7610_usleep(20);
	if (enable)
		val |= MT_WLAN_FUN_CTRL_WLAN_EN | MT_WLAN_FUN_CTRL_WLAN_CLK_EN;
	else
		val &= ~MT_WLAN_FUN_CTRL_WLAN_EN;
	mt_wr(d, MT_WLAN_FUN_CTRL, val);
	mt7610_usleep(20);
	if (enable) {
		uint32_t mask = MT_CMB_CTRL_XTAL_RDY | MT_CMB_CTRL_PLL_LD;
		/* Linux mt76x0_set_wlan_state polls up to ~2 s after enable. */
		if (!mt_poll(d, MT_CMB_CTRL, mask, mask, 2000 * 1000))
			__android_log_print(ANDROID_LOG_WARN, TAG, "PLL/XTAL check failed");
	}
	return 0;
}

int mt_eeprom_load(mt7610u_dev *d)
{
	int misses = 0;
	for (int i = 0; i < MT76X0_EEPROM_SIZE; i += 2) {
		uint8_t b[2] = {0, 0};
		int rc = mt_vendor_req(d, MT_VEND_READ_EEPROM,
		                       (uint8_t)((int)LIBUSB_ENDPOINT_IN |
		                                 (int)LIBUSB_REQUEST_TYPE_VENDOR |
		                                 (int)LIBUSB_RECIPIENT_DEVICE),
		                       (uint16_t)i, 0, b, 2, 1);
		if (rc == 2) {
			d->eeprom[i] = b[0];
			d->eeprom[i + 1] = b[1];
			misses = 0;
		} else if (++misses >= 4) {
			__android_log_print(ANDROID_LOG_WARN, TAG, "EEPROM read stopped at 0x%x", i);
			break;
		}
	}
	memcpy(d->mac, d->eeprom + MT_EE_MAC_ADDR, 6);
	uint16_t nic = (uint16_t)d->eeprom[MT_EE_NIC_CONF_0] |
	               ((uint16_t)d->eeprom[MT_EE_NIC_CONF_0 + 1] << 8);
	d->has_2g = 1;
	d->has_5g = 1;
	(void)nic;
	uint32_t dw0 = (uint32_t)d->mac[0] | ((uint32_t)d->mac[1] << 8) |
	               ((uint32_t)d->mac[2] << 16) | ((uint32_t)d->mac[3] << 24);
	uint32_t dw1 = (uint32_t)d->mac[4] | ((uint32_t)d->mac[5] << 8);
	mt_wr(d, MT_MAC_ADDR_DW0, dw0);
	mt_wr(d, MT_MAC_ADDR_DW1, dw1 | FIELD_PREP(MT_MAC_ADDR_DW1_U2ME_MASK, 0xff));
	mt_wr(d, MT_MAC_BSSID_DW0, dw0);
	mt_wr(d, MT_MAC_BSSID_DW1, dw1 |
	      FIELD_PREP(MT_MAC_BSSID_DW1_MBSS_MODE, 3) |
	      MT_MAC_BSSID_DW1_MBSS_LOCAL_BIT);
	mt_rmw(d, MT_MAC_BSSID_DW1, MT_MAC_BSSID_DW1_MBEACON_N,
	       FIELD_PREP(MT_MAC_BSSID_DW1_MBEACON_N, 7));
	for (int i = 0; i < 8; i++) {
		mt_wr(d, MT_MAC_APC_BSSID_L(i), 0);
		mt_wr(d, MT_MAC_APC_BSSID_H(i), 0);
	}
	return 0;
}

int mt_hw_init(mt7610u_dev *d)
{
	if (!mt_wait_for_mac(d))
		return -1;
	mt_wr(d, MT_MAC_SYS_CTRL, MT_MAC_SYS_CTRL_RESET_CSR | MT_MAC_SYS_CTRL_RESET_BBP);
	mt7610_usleep(200 * 1000);
	mt_clear(d, MT_MAC_SYS_CTRL, MT_MAC_SYS_CTRL_RESET_CSR | MT_MAC_SYS_CTRL_RESET_BBP);
	if (mt_mcu_funsel(d, Q_SELECT, 1))
		__android_log_print(ANDROID_LOG_WARN, TAG, "Q_SELECT failed");

	wr_tab(d, common_mac_reg_table, ARRAY_SIZE(common_mac_reg_table));
	wr_tab(d, mt76x0_mac_reg_table, ARRAY_SIZE(mt76x0_mac_reg_table));
	mt_clear(d, MT_MAC_SYS_CTRL, 0x3);
	mt_set(d, MT_EXT_CCA_CFG, 0xf000);
	mt_clear(d, MT_FCE_L2_STUFF, MT_FCE_L2_STUFF_WR_MPDU_LEN_EN);
	mt_rmw(d, MT_WMM_CTRL, 0x3ff, 0x201);

	for (int i = 0; i < 20; i++) {
		uint32_t v = mt_rr(d, MT_BBP(CORE, 0));
		if (v && v != ~0u)
			break;
		if (i == 19)
			return -1;
		mt7610_usleep(1000);
	}
	wr_tab(d, mt76x0_bbp_init_tab, ARRAY_SIZE(mt76x0_bbp_init_tab));
	for (size_t i = 0; i < ARRAY_SIZE(mt76x0_bbp_switch_tab); i++) {
		const auto *item = &mt76x0_bbp_switch_tab[i];
		if (((RF_G_BAND | RF_BW_20) & item->bw_band) == (RF_G_BAND | RF_BW_20))
			mt_wr(d, item->reg_pair.reg, item->reg_pair.value);
	}
	wr_tab(d, mt76x0_dcoc_tab, ARRAY_SIZE(mt76x0_dcoc_tab));
	/* Skip full EEPROM walk — it is optional and was stalling bring-up. */
	memset(d->mac, 0, sizeof d->mac);
	mt_wr(d, MT_MAC_ADDR_DW0, 0);
	mt_wr(d, MT_MAC_ADDR_DW1, FIELD_PREP(MT_MAC_ADDR_DW1_U2ME_MASK, 0xff));
	mt_wr(d, MT_MAC_BSSID_DW0, 0);
	mt_wr(d, MT_MAC_BSSID_DW1,
	      FIELD_PREP(MT_MAC_BSSID_DW1_MBSS_MODE, 3) |
	      MT_MAC_BSSID_DW1_MBSS_LOCAL_BIT);
	for (int i = 0; i < 8; i++) {
		mt_wr(d, MT_MAC_APC_BSSID_L(i), 0);
		mt_wr(d, MT_MAC_APC_BSSID_H(i), 0);
	}
	return 0;
}

int mt_phy_init(mt7610u_dev *d)
{
	rf_tab(d, mt76x0_rf_central_tab, ARRAY_SIZE(mt76x0_rf_central_tab));
	rf_tab(d, mt76x0_rf_2g_channel_0_tab, ARRAY_SIZE(mt76x0_rf_2g_channel_0_tab));
	rf_tab(d, mt76x0_rf_5g_channel_0_tab, ARRAY_SIZE(mt76x0_rf_5g_channel_0_tab));
	rf_tab(d, mt76x0_rf_vga_channel_0_tab, ARRAY_SIZE(mt76x0_rf_vga_channel_0_tab));
	for (size_t i = 0; i < ARRAY_SIZE(mt76x0_rf_bw_switch_tab); i++) {
		const auto *item = &mt76x0_rf_bw_switch_tab[i];
		if (item->bw_band == RF_BW_20 ||
		    ((RF_G_BAND | RF_BW_20) & item->bw_band) == (RF_G_BAND | RF_BW_20))
			mt_rf_wr(d, item->rf_bank_reg, item->value);
	}
	for (size_t i = 0; i < ARRAY_SIZE(mt76x0_rf_band_switch_tab); i++) {
		if (mt76x0_rf_band_switch_tab[i].bw_band & RF_G_BAND)
			mt_rf_wr(d, mt76x0_rf_band_switch_tab[i].rf_bank_reg,
			         mt76x0_rf_band_switch_tab[i].value);
	}
	mt_rf_wr(d, MT_RF(0, 22), 0x14);
	mt_rf_wr(d, MT_RF(0, 73), 0x80);
	mt_rf_wr(d, MT_RF(0, 4), 0x80);
	return 0;
}

static void phy_set_band(mt7610u_dev *d, int is_5g)
{
	if (!is_5g) {
		rf_tab(d, mt76x0_rf_2g_channel_0_tab, ARRAY_SIZE(mt76x0_rf_2g_channel_0_tab));
		mt_rf_wr(d, MT_RF(5, 0), 0x45);
		mt_rf_wr(d, MT_RF(6, 0), 0x44);
		mt_wr(d, MT_TX_ALC_VGA3, 0x00050007);
		mt_wr(d, MT_TX0_RF_GAIN_CORR, 0x003E0002);
		mt_set(d, MT_TX_BAND_CFG, MT_TX_BAND_CFG_2G);
		mt_clear(d, MT_TX_BAND_CFG, MT_TX_BAND_CFG_5G);
	} else {
		rf_tab(d, mt76x0_rf_5g_channel_0_tab, ARRAY_SIZE(mt76x0_rf_5g_channel_0_tab));
		mt_rf_wr(d, MT_RF(5, 0), 0x44);
		mt_rf_wr(d, MT_RF(6, 0), 0x45);
		mt_wr(d, MT_TX_ALC_VGA3, 0x00000005);
		mt_wr(d, MT_TX0_RF_GAIN_CORR, 0x01010102);
		mt_clear(d, MT_TX_BAND_CFG, MT_TX_BAND_CFG_2G);
		mt_set(d, MT_TX_BAND_CFG, MT_TX_BAND_CFG_5G);
	}
}

static void phy_set_chan_rf(mt7610u_dev *d, int channel)
{
	int sdm = 0;
	for (size_t i = 0; i < ARRAY_SIZE(mt76x0_sdm_channel); i++) {
		if (channel == mt76x0_sdm_channel[i]) {
			sdm = 1;
			break;
		}
	}
	const struct mt76x0_freq_item *freq = nullptr;
	uint16_t rf_band = (channel <= 14) ? RF_G_BAND : RF_A_BAND;
	for (size_t i = 0; i < ARRAY_SIZE(mt76x0_frequency_plan); i++) {
		if (channel != mt76x0_frequency_plan[i].channel)
			continue;
		rf_band = (uint16_t)mt76x0_frequency_plan[i].band;
		freq = sdm ? &mt76x0_sdm_frequency_plan[i] : &mt76x0_frequency_plan[i];
		uint8_t r32 = (uint8_t)((freq->pllR32_b7b5 & 0xe0) | (freq->pllR32_b4b0 & 0x1f));
		uint8_t r31 = (uint8_t)((freq->pllR31_b7b5 & 0xe0) | (freq->pllR31_b4b0 & 0x1f));
		uint8_t r30 = (uint8_t)((freq->pllR30_b7 & 0x80) |
		                        (freq->pllR30_b6b2 & 0x7c) |
		                        ((freq->pllR30_b1 << 1) & 0x02) |
		                        ((freq->pll_n >> 8) & 0x01));
		uint8_t r28 = (uint8_t)((freq->pllR28_b7b6 & 0xc0) |
		                        (freq->pllR28_b5b4 & 0x30) |
		                        (freq->pllR28_b3b2 & 0x0c) |
		                        ((freq->pll_sdm_k >> 16) & 0x03));
		struct mt76_reg_pair pll[] = {
			{ MT_RF(0, 37), freq->pllR37 },
			{ MT_RF(0, 36), freq->pllR36 },
			{ MT_RF(0, 35), freq->pllR35 },
			{ MT_RF(0, 34), freq->pllR34 },
			{ MT_RF(0, 33), freq->pllR33 },
			{ MT_RF(0, 32), r32 },
			{ MT_RF(0, 31), r31 },
			{ MT_RF(0, 30), sdm ? (uint32_t)(r30 | 0x80) : r30 },
			{ MT_RF(0, 29), (uint8_t)(freq->pll_n & 0xff) },
			{ MT_RF(0, 28), r28 },
			{ MT_RF(0, 26), (uint8_t)(freq->pll_sdm_k & 0xff) },
			{ MT_RF(0, 27), (uint8_t)((freq->pll_sdm_k >> 8) & 0xff) },
			{ MT_RF(0, 24), (uint8_t)(freq->pllR24_b1b0 & 0x03) },
			{ MT_RF(0, 4), 0x80 },
		};
		mt_mcu_wr_rp(d, MT_MCU_MEMMAP_RF, pll, (int)ARRAY_SIZE(pll));
		break;
	}
	(void)rf_band;
}

static void phy_set_chan_bbp(mt7610u_dev *d, uint16_t rf_bw_band)
{
	for (size_t i = 0; i < ARRAY_SIZE(mt76x0_bbp_switch_tab); i++) {
		const auto *item = &mt76x0_bbp_switch_tab[i];
		if ((rf_bw_band & item->bw_band) != rf_bw_band)
			continue;
		uint32_t val = item->reg_pair.value;
		if (item->reg_pair.reg == MT_BBP(AGC, 8)) {
			uint8_t gain = (uint8_t)FIELD_GET(MT_BBP_AGC_GAIN, val);
			gain = (uint8_t)(gain - (uint8_t)(d->lna_gain * 2));
			val = (val & ~MT_BBP_AGC_GAIN) | FIELD_PREP(MT_BBP_AGC_GAIN, gain);
		}
		mt_wr(d, item->reg_pair.reg, val);
	}
}

int mt_phy_set_channel(mt7610u_dev *d, int channel)
{
	if (channel <= 0 || channel > 196)
		return -1;
	int is_5g = channel > 14;
	uint16_t rf_bw_band = (uint16_t)((is_5g ? RF_A_BAND : RF_G_BAND) | RF_BW_20);
	if (d->last_5g != is_5g) {
		mt_mcu_funsel(d, BW_SETTING, 0);
		phy_set_band(d, is_5g);
		phy_set_chan_bbp(d, rf_bw_band);
		d->last_5g = is_5g;
	}
	phy_set_chan_rf(d, channel);
	d->chan = channel;
	return 0;
}

int mt_init_usb_dma(mt7610u_dev *d)
{
	/* Linux mt76x0_init_usb_dma: bulk on, agg off, pulse DROP then leave it clear. */
	uint32_t val = mt_rr(d, MT_USB_DMA_CFG);
	val |= MT_USB_DMA_CFG_RX_BULK_EN | MT_USB_DMA_CFG_TX_BULK_EN;
	val &= ~MT_USB_DMA_CFG_RX_BULK_AGG_EN;
	mt_wr(d, MT_USB_DMA_CFG, val);
	val = mt_rr(d, MT_USB_DMA_CFG);
	mt_wr(d, MT_USB_DMA_CFG, val | MT_USB_DMA_CFG_RX_DROP_OR_PAD);
	mt_wr(d, MT_USB_DMA_CFG, val & ~MT_USB_DMA_CFG_RX_DROP_OR_PAD);
	return 0;
}

int mt_phy_calibrate(mt7610u_dev *d, int power_on)
{
	int is_5g = d->chan > 14;
	if (power_on) {
		mt_mcu_calibrate(d, MCU_CAL_R, 0);
		mt_mcu_calibrate(d, MCU_CAL_VCO, (uint32_t)d->chan);
		mt7610_usleep(20);
	}
	uint32_t tx_alc = mt_rr(d, MT_TX_ALC_CFG_0);
	mt_wr(d, MT_TX_ALC_CFG_0, 0);
	mt7610_usleep(600);
	uint32_t ibi = mt_rr(d, MT_BBP(IBI, 9));
	mt_wr(d, MT_BBP(IBI, 9), 0xffffff7e);
	uint32_t val = 0x600;
	if (is_5g) {
		if (d->chan < 100)
			val = 0x701;
		else if (d->chan < 140)
			val = 0x801;
		else
			val = 0x901;
	}
	mt_mcu_calibrate(d, MCU_CAL_FULL, val);
	mt_mcu_calibrate(d, MCU_CAL_LC, (uint32_t)is_5g);
	mt7610_usleep(15000);
	mt_wr(d, MT_BBP(IBI, 9), ibi);
	mt_wr(d, MT_TX_ALC_CFG_0, tx_alc);
	mt_mcu_calibrate(d, MCU_CAL_RXDCOC, 1);
	return 0;
}

void mt_kick_rx(mt7610u_dev *d)
{
	if (!d || !d->h)
		return;
	libusb_clear_halt(d->h, d->ep_rx);
	mt_init_usb_dma(d);
	mt_set_monitor_filter(d);
	mt_wr(d, MT_MAC_SYS_CTRL, MT_MAC_SYS_CTRL_ENABLE_TX | MT_MAC_SYS_CTRL_ENABLE_RX);
}

int mt_mac_start(mt7610u_dev *d)
{
	mt_wr(d, MT_US_CYC_CFG, (mt_rr(d, MT_US_CYC_CFG) & ~MT_US_CYC_CNT) | 0x1e);
	mt_wr(d, MT_TXOP_CTRL_CFG,
	      FIELD_PREP(MT_TXOP_TRUN_EN, 0x3f) | FIELD_PREP(MT_TXOP_EXT_CCA_DLY, 0x58));
	/* MAC TX enable is required for the DMA engine. No 802.11 TX URBs. */
	mt_wr(d, MT_MAC_SYS_CTRL, MT_MAC_SYS_CTRL_ENABLE_TX);
	mt7610_usleep(50);
	mt_set_monitor_filter(d);
	mt_wr(d, MT_MAC_SYS_CTRL, MT_MAC_SYS_CTRL_ENABLE_TX | MT_MAC_SYS_CTRL_ENABLE_RX);
	return 0;
}

void mt_set_monitor_filter(mt7610u_dev *d)
{
	/* Bit set = drop. Write 0 so other-BSS / unicast mgmt (deauth) pass.
	 * Selective 0x17f97-style filters slowed hops when reapplied per channel
	 * and blocked some sticks from delivering attack frames. */
	mt_wr(d, MT_RX_FILTR_CFG, 0);
	mt_wr(d, MT_AUTO_RSP_CFG, 0);
}

int mt_rx_parse(const uint8_t *buf, int n, const uint8_t **frame, int *len, int *rssi)
{
	if (n < MT_DMA_HDR_LEN + MT_RXWI_LEN)
		return -1;
	const uint8_t *rxwi = buf + MT_DMA_HDR_LEN;
	uint32_t rxinfo = (uint32_t)rxwi[0] | ((uint32_t)rxwi[1] << 8) |
	                  ((uint32_t)rxwi[2] << 16) | ((uint32_t)rxwi[3] << 24);
	uint32_t ctl = (uint32_t)rxwi[4] | ((uint32_t)rxwi[5] << 8) |
	               ((uint32_t)rxwi[6] << 16) | ((uint32_t)rxwi[7] << 24);
	int pad = (rxinfo & MT_RXINFO_L2PAD) ? 2 : 0;
	int mpdu = (int)FIELD_GET(MT_RXWI_CTL_MPDU_LEN, ctl);
	int avail = n - MT_DMA_HDR_LEN - MT_RXWI_LEN - pad;
	if (mpdu > avail)
		mpdu = avail;
	if (mpdu < 24)
		return -1;
	*frame = rxwi + MT_RXWI_LEN + pad;
	*len = mpdu;
	*rssi = (int)(int8_t)rxwi[12];
	return 0;
}

static int want_frame(const uint8_t *data, int len)
{
	if (!data || len < 24)
		return 0;
	if ((data[0] & 0x03) != 0)
		return 0;
	int type = (data[0] >> 2) & 0x3;
	int subtype = (data[0] >> 4) & 0xf;
	/* Beacon, probe response, disassoc, deauth. Data frames stay on the adapter. */
	if (type != 0 || (subtype != 5 && subtype != 8 && subtype != 10 && subtype != 12))
		return 0;
	if (subtype == 10 || subtype == 12) {
		if (len < 26)
			return 0;
		int reason = (int)data[24] | ((int)data[25] << 8);
		if (reason == 0 || reason > 64)
			return 0;
	}
	return 1;
}

static void deliver_frame(mt7610u_dev *d, const uint8_t *frame, int len, int rssi)
{
	if (!d->cb || !want_frame(frame, len))
		return;
	int subtype = (frame[0] >> 4) & 0xf;
	if (subtype == 10 || subtype == 12)
		d->rx_hits++;
	int n = len > 2048 ? 2048 : len;
	d->cb(frame, n, rssi, d->chan, d->cb_user);
}

static void rx_handle(mt7610u_dev *d, const uint8_t *buf, int n)
{
	int off = 0;
	while (off + MT_DMA_HDR_LEN + MT_RXWI_LEN + 26 <= n) {
		const uint8_t *frame = nullptr;
		int len = 0, rssi = 0;
		if (!mt_rx_parse(buf + off, n - off, &frame, &len, &rssi))
			deliver_frame(d, frame, len, rssi);
		uint32_t fce = (uint32_t)buf[off] | ((uint32_t)buf[off + 1] << 8) |
		               ((uint32_t)buf[off + 2] << 16) | ((uint32_t)buf[off + 3] << 24);
		int flen = (int)FIELD_GET(MT_RX_FCE_INFO_LEN, fce);
		int step = (flen + 3) & ~3;
		if (step < 40)
			break;
		off += step;
	}
}

void mt_rx_ingest(mt7610u_dev *d, const uint8_t *buf, int n)
{
	if (!d || !buf || n <= 0)
		return;
	d->rx_urb++;
	d->rx_bytes += (unsigned)n;
	rx_handle(d, buf, n);
}

static void rx_loop(mt7610u_dev *d)
{
	uint8_t buf[4096];
	uint8_t ep = d->ep_rx;
	d->rx_ep_used = ep;
	while (d->running.load()) {
		int n = 0;
		d->rx_try++;
		int rc = mt_bulk(d, ep, buf, (int)sizeof buf, &n, 40);
		d->rx_last = rc;
		if (rc == LIBUSB_ERROR_NO_DEVICE)
			return;
		if (rc == LIBUSB_ERROR_PIPE && d->h) {
			libusb_clear_halt(d->h, ep);
			continue;
		}
		if (rc || n <= 0)
			continue;
		mt_rx_ingest(d, buf, n);
	}
}

static void set_err(char *err, size_t errlen, const char *msg)
{
	if (err && errlen)
		snprintf(err, errlen, "%s", msg);
}

static void close_handle(mt7610u_dev *d)
{
	if (!d->h)
		return;
	for (int i = 0; i < 8; i++)
		libusb_release_interface(d->h, i);
	libusb_close(d->h);
	d->h = nullptr;
	if (d->owns_ctx && d->ctx) {
		libusb_exit(d->ctx);
		d->ctx = nullptr;
	}
}

static void shutdown_dev(mt7610u_dev *d)
{
	if (!d)
		return;
	d->running = false;
	std::thread rx;
	{
		std::lock_guard<std::recursive_mutex> io(d->io);
		if (d->rx.joinable())
			rx = std::move(d->rx);
	}
	if (rx.joinable())
		rx.join();
	std::lock_guard<std::recursive_mutex> io(d->io);
	if (!d->h)
		return;
	mt_clear(d, MT_MAC_SYS_CTRL,
	         MT_MAC_SYS_CTRL_ENABLE_RX | MT_MAC_SYS_CTRL_ENABLE_TX);
	/* Leave WLAN powered down so the next open gets a clean chip_onoff. */
	mt_chip_onoff(d, 0, 0);
	close_handle(d);
}

static void destroy_dev(mt7610u_dev *d)
{
	shutdown_dev(d);
	delete d;
}

static void close_dev(mt7610u_dev *d)
{
	if (!d)
		return;
	d->running = false;
	{
		std::lock_guard<std::recursive_mutex> io(d->io);
		close_handle(d);
	}
	delete d;
}

int mt7610u_open(int fd, const char *fw_path, char *err, size_t errlen)
{
	{
		std::lock_guard<std::mutex> life(g_life);
		if (g_owner) {
			set_err(err, errlen, "MT7610U already open");
			return -1;
		}
	}
	if (fd < 0 || !fw_path) {
		set_err(err, errlen, "invalid fd or firmware path");
		return -1;
	}
	const int epoch = g_epoch.load();
	auto *d = new mt7610u_dev();
	(void)libusb_set_option(nullptr, LIBUSB_OPTION_NO_DEVICE_DISCOVERY);
	if (libusb_init(&d->ctx) < 0) {
		delete d;
		set_err(err, errlen, "libusb_init failed");
		return -1;
	}
	int r = libusb_wrap_sys_device(d->ctx, (intptr_t)fd, &d->h);
	if (r < 0 || !d->h) {
		libusb_exit(d->ctx);
		delete d;
		set_err(err, errlen, "libusb_wrap_sys_device failed");
		return -1;
	}
	libusb_set_auto_detach_kernel_driver(d->h, 1);
	int niface = 1;
	int wifi = 0;
	if (libusb_get_device(d->h)) {
		libusb_config_descriptor *idesc = nullptr;
		if (libusb_get_active_config_descriptor(libusb_get_device(d->h), &idesc) == 0 &&
		    idesc) {
			niface = idesc->bNumInterfaces > 0 ? idesc->bNumInterfaces : 1;
			for (int i = 0; i < idesc->bNumInterfaces; i++) {
				if (idesc->interface[i].num_altsetting < 1)
					continue;
				const auto *alt = &idesc->interface[i].altsetting[0];
				if (alt->bInterfaceClass == LIBUSB_CLASS_VENDOR_SPEC)
					wifi = alt->bInterfaceNumber;
			}
			libusb_free_config_descriptor(idesc);
		}
	}
	int last_claim = LIBUSB_ERROR_OTHER;
	int claimed = 0;
	for (int i = 0; i < niface; i++) {
		int iface = (i == 0) ? wifi : (i == wifi ? 0 : i);
		int rc = libusb_claim_interface(d->h, iface);
		/* BUSY: Android already owns this fd after UsbManager.openDevice. */
		if (rc == 0 || rc == LIBUSB_ERROR_BUSY) {
			if (!claimed)
				d->iface = iface;
			claimed = 1;
		} else {
			last_claim = rc;
		}
	}
	if (!claimed) {
		char msg[96];
		snprintf(msg, sizeof msg, "USB claim failed: %s",
		         libusb_error_name(last_claim));
		close_dev(d);
		set_err(err, errlen, msg);
		return -1;
	}
	mt_discover_eps(d);

	/* Do not libusb_set_configuration here: Android already configured
	 * the device and that call can hang the USB stack. */
	/* Power the WLAN core before clearing halts or reading ASIC ID —
	 * a cold or wedged stick often returns 0/~0 until chip_onoff. */
	int mac_ready = 0;
	for (int attempt = 0; attempt < 3 && !mac_ready; attempt++) {
		mt_chip_onoff(d, 1, attempt == 0 ? 1 : 1);
		mt7610_usleep(50 * 1000);
		libusb_clear_halt(d->h, d->ep_rx);
		libusb_clear_halt(d->h, d->ep_cmd);
		mac_ready = mt_wait_for_mac(d);
		if (!mac_ready) {
			__android_log_print(ANDROID_LOG_WARN, TAG,
			                    "MAC not ready (try %d), resetting WLAN", attempt + 1);
			mt_chip_onoff(d, 0, 0);
			mt7610_usleep(100 * 1000);
		}
	}
	if (!mac_ready) {
		close_dev(d);
		set_err(err, errlen, "MAC did not come ready");
		return -1;
	}
	d->rev = mt_rr(d, MT_ASIC_VERSION);
	if ((d->rev >> 16) != 0x7610) {
		char msg[96];
		snprintf(msg, sizeof msg, "not MT7610U (ASIC 0x%08x)", d->rev);
		close_dev(d);
		set_err(err, errlen, msg);
		return -1;
	}
	if (mt_fw_load(d, fw_path)) {
		close_dev(d);
		set_err(err, errlen, "mt7610u.bin failed to start");
		return -1;
	}
	mt_init_usb_dma(d);
	if (mt_hw_init(d)) {
		close_dev(d);
		set_err(err, errlen, "MAC/BBP init failed");
		return -1;
	}
	if (mt_phy_init(d)) {
		close_dev(d);
		set_err(err, errlen, "RF init failed");
		return -1;
	}
	mt_mac_start(d);
	{
		std::lock_guard<std::mutex> life(g_life);
		if (g_epoch.load() != epoch || g_owner) {
			close_dev(d);
			set_err(err, errlen, "MT7610U open cancelled");
			return -1;
		}
		g_owner.reset(d, destroy_dev);
		g_mt = d;
	}
	__android_log_print(ANDROID_LOG_INFO, TAG, "open ASIC 0x%08x rx=%02x cmd=%02x mcu=%02x",
	                    d->rev, d->ep_rx, d->ep_cmd, d->ep_mcu);
	return 0;
}

int mt7610u_start_rx(int channel, mt7610u_frame_cb cb, void *user,
                     char *err, size_t errlen)
{
	auto d = retain();
	if (!d) {
		set_err(err, errlen, "MT7610U not open");
		return -1;
	}
	std::lock_guard<std::recursive_mutex> io(d->io);
	if (!d->h) {
		set_err(err, errlen, "MT7610U not open");
		return -1;
	}
	d->cb = cb;
	d->cb_user = user;
	d->chan = channel <= 0 ? 1 : channel;
	libusb_clear_halt(d->h, d->ep_rx);
	d->running = true;
	d->rx_ep_used = d->ep_rx;
	mt_phy_set_channel(d.get(), d->chan);
	mt_phy_calibrate(d.get(), 1);
	mt_init_usb_dma(d.get());
	mt_mac_start(d.get());
	return 0;
}

void mt7610u_start_pump(void)
{
	auto d = retain();
	if (!d)
		return;
	std::lock_guard<std::recursive_mutex> io(d->io);
	if (!d->h || !d->running.load() || d->rx.joinable())
		return;
	d->rx = std::thread([d]() {
		rx_loop(d.get());
		if (g_thread_exit)
			g_thread_exit();
	});
}

void mt7610u_set_thread_exit_hook(void (*fn)(void))
{
	g_thread_exit = fn;
}

int mt7610u_push_rx(const uint8_t *buf, int n)
{
	auto d = retain();
	if (!d || !d->running.load())
		return -1;
	d->rx_try++;
	d->rx_last = 0;
	mt_rx_ingest(d.get(), buf, n);
	return 0;
}

int mt7610u_set_channel(int channel)
{
	auto d = retain();
	if (!d || !d->running.load())
		return -1;
	std::lock_guard<std::recursive_mutex> io(d->io);
	if (!d->h || !d->running.load())
		return -1;
	return mt_phy_set_channel(d.get(), channel);
}

void mt7610u_rx_poll(int last_rc)
{
	auto d = retain();
	if (!d)
		return;
	d->rx_try++;
	d->rx_last = last_rc;
}

void mt7610u_kick_rx(void)
{
	auto d = retain();
	if (!d || !d->running.load())
		return;
	std::lock_guard<std::recursive_mutex> io(d->io);
	if (d->h)
		mt_kick_rx(d.get());
}

void mt7610u_rx_stats(unsigned *urb, unsigned *bytes, unsigned *hits)
{
	auto d = retain();
	if (!d) {
		if (urb) *urb = 0;
		if (bytes) *bytes = 0;
		if (hits) *hits = 0;
		return;
	}
	if (urb) *urb = d->rx_urb.load();
	if (bytes) *bytes = d->rx_bytes.load();
	if (hits) *hits = d->rx_hits.load();
}

void mt7610u_rx_diag(char *buf, size_t len)
{
	if (!buf || !len)
		return;
	unsigned urb = 0, bytes = 0, hits = 0, tries = 0, ep = 0;
	int last = 0;
	if (auto d = retain()) {
		urb = d->rx_urb.load();
		bytes = d->rx_bytes.load();
		hits = d->rx_hits.load();
		tries = d->rx_try.load();
		last = d->rx_last.load();
		ep = d->rx_ep_used.load();
	}
	snprintf(buf, len,
	         "MT7610U RX urb=%u bytes=%u deauth=%u try=%u bulk=%d ep=%02x",
	         urb, bytes, hits, tries, last, ep);
}

int mt7610u_rx_endpoint(void)
{
	auto d = retain();
	if (!d)
		return MT_EP_IN_PKT_RX;
	return d->ep_rx;
}

int mt7610u_is_open(void)
{
	return retain() ? 1 : 0;
}

void mt7610u_stop(void)
{
	g_epoch.fetch_add(1);
	std::shared_ptr<mt7610u_dev> d;
	{
		std::lock_guard<std::mutex> life(g_life);
		d = std::move(g_owner);
		g_mt = nullptr;
	}
	if (d)
		shutdown_dev(d.get());
}
