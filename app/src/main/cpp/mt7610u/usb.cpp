/* SPDX-License-Identifier: GPL-2.0-only */
/* MediaTek MT76 USB vendor register access. Wire format matches mt76/usb.c. */

#include "internal.h"

#include <string.h>

#define REQ_IN  ((uint8_t)LIBUSB_ENDPOINT_IN | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | \
                 (uint8_t)LIBUSB_RECIPIENT_DEVICE)
#define REQ_OUT ((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | \
                 (uint8_t)LIBUSB_RECIPIENT_DEVICE)
#define CTRL_TIMEOUT_MS 150

void mt7610_usleep(unsigned us)
{
	std::this_thread::sleep_for(std::chrono::microseconds(us));
}

static uint64_t now_us(void)
{
	return (uint64_t)std::chrono::duration_cast<std::chrono::microseconds>(
	           std::chrono::steady_clock::now().time_since_epoch()).count();
}

int mt_vendor_req(mt7610u_dev *d, uint8_t req, uint8_t type, uint16_t val,
                  uint16_t idx, void *buf, size_t len, int tries)
{
	int rc = LIBUSB_ERROR_OTHER;
	if (!d)
		return LIBUSB_ERROR_NO_DEVICE;
	if (tries < 1)
		tries = 1;
	d->io.lock();
	if (!d->h) {
		d->io.unlock();
		return LIBUSB_ERROR_NO_DEVICE;
	}
	for (int i = 0; i < tries; i++) {
		rc = libusb_control_transfer(d->h, type, req, val, idx,
		                             (unsigned char *)buf, (uint16_t)len,
		                             CTRL_TIMEOUT_MS);
		if (rc >= 0 || rc == LIBUSB_ERROR_NO_DEVICE)
			break;
		mt7610_usleep(2000);
	}
	d->io.unlock();
	return rc;
}

static uint8_t rd_req(uint32_t addr)
{
	if (addr & MT_VEND_TYPE_EEPROM) return MT_VEND_READ_EEPROM;
	if (addr & MT_VEND_TYPE_CFG) return MT_VEND_READ_CFG;
	return MT_VEND_MULTI_READ;
}

static uint8_t wr_req(uint32_t addr)
{
	if (addr & MT_VEND_TYPE_CFG) return MT_VEND_WRITE_CFG;
	return MT_VEND_MULTI_WRITE;
}

int mt_rr_chk(mt7610u_dev *d, uint32_t addr, uint32_t *val)
{
	uint8_t b[4] = {0};
	uint32_t a = addr & ~MT_VEND_TYPE_MASK;
	if (mt_vendor_req(d, rd_req(addr), REQ_IN, (uint16_t)(a >> 16), (uint16_t)a,
	                  b, 4) != 4) {
		d->io_err++;
		return -1;
	}
	*val = (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
	       ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
	return 0;
}

uint32_t mt_rr(mt7610u_dev *d, uint32_t addr)
{
	uint32_t v = ~0u;
	return mt_rr_chk(d, addr, &v) ? ~0u : v;
}

void mt_wr(mt7610u_dev *d, uint32_t addr, uint32_t val)
{
	uint8_t b[4] = {
		(uint8_t)(val), (uint8_t)(val >> 8),
		(uint8_t)(val >> 16), (uint8_t)(val >> 24)
	};
	uint32_t a = addr & ~MT_VEND_TYPE_MASK;
	if (mt_vendor_req(d, wr_req(addr), REQ_OUT, (uint16_t)(a >> 16),
	                  (uint16_t)a, b, 4) != 4)
		d->io_err++;
}

int mt_rmw(mt7610u_dev *d, uint32_t addr, uint32_t mask, uint32_t val)
{
	uint32_t cur;
	if (mt_rr_chk(d, addr, &cur))
		return -1;
	mt_wr(d, addr, (cur & ~mask) | val);
	return 0;
}

void mt_set(mt7610u_dev *d, uint32_t addr, uint32_t bits)
{
	mt_rmw(d, addr, bits, bits);
}

void mt_clear(mt7610u_dev *d, uint32_t addr, uint32_t bits)
{
	mt_rmw(d, addr, bits, 0);
}

int mt_poll(mt7610u_dev *d, uint32_t addr, uint32_t mask, uint32_t val, int timeout_us)
{
	uint64_t deadline = now_us() + (uint64_t)(timeout_us < 0 ? 0 : timeout_us);
	for (;;) {
		uint32_t cur;
		if (mt_rr_chk(d, addr, &cur))
			return 0;
		if ((cur & mask) == val)
			return 1;
		if (now_us() >= deadline)
			return 0;
		mt7610_usleep(1000);
	}
}

void mt_single_wr(mt7610u_dev *d, uint8_t req, uint16_t off, uint32_t val)
{
	if (mt_vendor_req(d, req, REQ_OUT, (uint16_t)(val & 0xffff), off, nullptr, 0) < 0)
		d->io_err++;
	if (mt_vendor_req(d, req, REQ_OUT, (uint16_t)(val >> 16),
	                  (uint16_t)(off + 2), nullptr, 0) < 0)
		d->io_err++;
}

int mt_bulk(mt7610u_dev *d, uint8_t ep, void *buf, int len, int *xfered, unsigned timeout_ms)
{
	int n = 0;
	if (!d || !d->h)
		return LIBUSB_ERROR_NO_DEVICE;
	int rc = libusb_bulk_transfer(d->h, ep, (unsigned char *)buf, len, &n, timeout_ms);
	if (xfered)
		*xfered = n;
	return rc;
}

int mt_wait_for_mac(mt7610u_dev *d)
{
	/* After WLAN_EN / reset, MAC_CSR0 can take >400 ms on some sticks. */
	for (int i = 0; i < 400; i++) {
		uint8_t b[4] = {0};
		int rc = mt_vendor_req(d, MT_VEND_MULTI_READ, REQ_IN, 0,
		                       (uint16_t)MT_MAC_CSR0, b, 4, 1);
		if (rc == 4) {
			uint32_t v = (uint32_t)b[0] | ((uint32_t)b[1] << 8) |
			             ((uint32_t)b[2] << 16) | ((uint32_t)b[3] << 24);
			if (v && v != ~0u)
				return 1;
		}
		mt7610_usleep(5000);
	}
	return 0;
}

int mt_discover_eps(mt7610u_dev *d)
{
	libusb_device *dev = libusb_get_device(d->h);
	if (!dev)
		return 0;
	libusb_config_descriptor *cfg = nullptr;
	if (libusb_get_active_config_descriptor(dev, &cfg) != 0 || !cfg)
		return 0;
	uint8_t first_in = 0, second_in = 0, third_in = 0, first_out = 0;
	uint8_t found_rx = 0, found_cmd = 0, found_mcu = 0;
	d->n_ep_in = 0;
	for (int i = 0; i < cfg->bNumInterfaces; i++) {
		const libusb_interface *itf = &cfg->interface[i];
		for (int a = 0; a < itf->num_altsetting; a++) {
			const libusb_interface_descriptor *alt = &itf->altsetting[a];
			for (int e = 0; e < alt->bNumEndpoints; e++) {
				const libusb_endpoint_descriptor *ep = &alt->endpoint[e];
				if ((ep->bmAttributes & 0x3) != LIBUSB_TRANSFER_TYPE_BULK)
					continue;
				uint8_t addr = ep->bEndpointAddress;
				if (addr & LIBUSB_ENDPOINT_IN) {
					if (d->n_ep_in < 4)
						d->ep_in[d->n_ep_in++] = addr;
					if (!first_in)
						first_in = addr;
					else if (!second_in)
						second_in = addr;
					else if (!third_in)
						third_in = addr;
					if (addr == MT_EP_IN_PKT_RX)
						found_rx = addr;
					if (addr == MT_EP_IN_CMD_RESP)
						found_cmd = addr;
				} else {
					if (!first_out)
						first_out = addr;
					if (addr == MT_EP_OUT_INBAND_CMD)
						found_mcu = addr;
				}
			}
		}
	}
	libusb_free_config_descriptor(cfg);
	auto pick_data_in = [&](uint8_t a) {
		return a && a != MT_EP_IN_CMD_RESP;
	};
	if (found_rx)
		d->ep_rx = found_rx;
	else if (pick_data_in(first_in))
		d->ep_rx = first_in;
	else if (pick_data_in(second_in))
		d->ep_rx = second_in;
	else if (pick_data_in(third_in))
		d->ep_rx = third_in;
	else
		d->ep_rx = MT_EP_IN_PKT_RX;
	d->ep_cmd = found_cmd ? found_cmd : (second_in ? second_in : MT_EP_IN_CMD_RESP);
	d->ep_mcu = found_mcu ? found_mcu : (first_out ? first_out : MT_EP_OUT_INBAND_CMD);
	return 0;
}
