#pragma once

#include "mt7610u.h"
#include "phy.h"

#include <libusb.h>
#include <atomic>
#include <chrono>
#include <mutex>
#include <thread>
#include <string>

struct mt7610u_dev {
	libusb_context *ctx = nullptr;
	libusb_device_handle *h = nullptr;
	int owns_ctx = 1;
	int iface = 0;
	uint8_t ep_rx = MT_EP_IN_PKT_RX;
	uint8_t ep_cmd = MT_EP_IN_CMD_RESP;
	uint8_t ep_mcu = MT_EP_OUT_INBAND_CMD;
	uint8_t ep_in[4]{};
	int n_ep_in = 0;
	uint32_t rev = 0;
	uint8_t eeprom[MT76X0_EEPROM_SIZE]{};
	uint8_t mac[6]{};
	uint8_t mcu_seq = 0;
	uint8_t mcu_stale = 0;
	uint8_t mcu_resp[1024]{};
	int mcu_resp_len = 0;
	int8_t lna_gain = 0;
	int chan = 1;
	int last_5g = -1;
	int has_2g = 1;
	int has_5g = 1;
	unsigned io_err = 0;
	std::atomic<unsigned> rx_urb{0};
	std::atomic<unsigned> rx_bytes{0};
	std::atomic<unsigned> rx_hits{0};
	std::atomic<unsigned> rx_try{0};
	std::atomic<int> rx_last{0};
	std::atomic<unsigned> rx_ep_used{0};
	std::recursive_mutex io;
	std::atomic<bool> running{false};
	std::thread rx;
	mt7610u_frame_cb cb = nullptr;
	void *cb_user = nullptr;
};

extern mt7610u_dev *g_mt;

void mt7610_usleep(unsigned us);
int mt_vendor_req(mt7610u_dev *d, uint8_t req, uint8_t type, uint16_t val,
                  uint16_t idx, void *buf, size_t len, int tries = 3);
int mt_rr_chk(mt7610u_dev *d, uint32_t addr, uint32_t *val);
uint32_t mt_rr(mt7610u_dev *d, uint32_t addr);
void mt_wr(mt7610u_dev *d, uint32_t addr, uint32_t val);
int mt_rmw(mt7610u_dev *d, uint32_t addr, uint32_t mask, uint32_t val);
void mt_set(mt7610u_dev *d, uint32_t addr, uint32_t bits);
void mt_clear(mt7610u_dev *d, uint32_t addr, uint32_t bits);
int mt_poll(mt7610u_dev *d, uint32_t addr, uint32_t mask, uint32_t val, int timeout_us);
void mt_single_wr(mt7610u_dev *d, uint8_t req, uint16_t off, uint32_t val);
int mt_bulk(mt7610u_dev *d, uint8_t ep, void *buf, int len, int *xfered, unsigned timeout_ms);
int mt_wait_for_mac(mt7610u_dev *d);
int mt_discover_eps(mt7610u_dev *d);

int mt_mcu_send(mt7610u_dev *d, int cmd, const void *data, int len, int wait_resp);
int mt_mcu_calibrate(mt7610u_dev *d, int type, uint32_t param);
int mt_mcu_funsel(mt7610u_dev *d, int fun, uint32_t val);
int mt_mcu_wr_rp(mt7610u_dev *d, uint32_t base, const struct mt76_reg_pair *p, int n);

int mt_rf_wr(mt7610u_dev *d, uint32_t offset, uint8_t val);
int mt_rf_rr(mt7610u_dev *d, uint32_t offset);
int mt_rf_rmw(mt7610u_dev *d, uint32_t offset, uint8_t mask, uint8_t val);
int mt_rf_or(mt7610u_dev *d, uint32_t offset, uint8_t bits);
int mt_rf_andnot(mt7610u_dev *d, uint32_t offset, uint8_t bits);

int mt_fw_load(mt7610u_dev *d, const char *path);
int mt_chip_onoff(mt7610u_dev *d, int enable, int reset);
int mt_hw_init(mt7610u_dev *d);
int mt_eeprom_load(mt7610u_dev *d);
int mt_phy_init(mt7610u_dev *d);
int mt_phy_set_channel(mt7610u_dev *d, int channel);
int mt_init_usb_dma(mt7610u_dev *d);
int mt_phy_calibrate(mt7610u_dev *d, int power_on);
int mt_mac_start(mt7610u_dev *d);
void mt_kick_rx(mt7610u_dev *d);
void mt_set_monitor_filter(mt7610u_dev *d);
int mt_rx_parse(const uint8_t *buf, int n, const uint8_t **frame, int *len, int *rssi);
void mt_rx_ingest(mt7610u_dev *d, const uint8_t *buf, int n);
