/* SPDX-License-Identifier: GPL-2.0-only */
/* In-band MCU: firmware chunks, CMD_RANDOM_WRITE for RF, calibrate, BW. */

#include "internal.h"

#include <string.h>

#define MCU_RESP_URB_SIZE 1024
#define MCU_MSG_MAX 192

static void put_le32(uint8_t *p, uint32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
	p[3] = (uint8_t)(v >> 24);
}

static uint32_t get_le32(const uint8_t *p)
{
	return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
	       ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static int mcu_wait_resp(mt7610u_dev *d, uint8_t seq)
{
	uint8_t buf[MCU_RESP_URB_SIZE];
	int len = 0;
	for (int i = 0; i < 5; i++) {
		int rc = mt_bulk(d, d->ep_cmd, buf, sizeof buf, &len, 300);
		if (rc == LIBUSB_ERROR_TIMEOUT)
			continue;
		if (rc || len < 4)
			break;
		uint32_t rxfce = get_le32(buf);
		if (FIELD_GET(MT_RX_FCE_INFO_CMD_SEQ, rxfce) == seq &&
		    FIELD_GET(MT_RX_FCE_INFO_EVT_TYPE, rxfce) == MT_EVT_CMD_DONE) {
			int copy = len < (int)sizeof(d->mcu_resp) ? len : (int)sizeof(d->mcu_resp);
			memcpy(d->mcu_resp, buf, (size_t)copy);
			d->mcu_resp_len = copy;
			return 0;
		}
	}
	d->mcu_stale = 1;
	d->mcu_resp_len = 0;
	return -1;
}

int mt_mcu_send(mt7610u_dev *d, int cmd, const void *data, int len, int wait_resp)
{
	uint8_t buf[4 + MCU_MSG_MAX + 8];
	if (len < 0 || len > MCU_MSG_MAX)
		return -1;

	d->io.lock();
	if (wait_resp && d->mcu_stale) {
		uint8_t stale[MCU_RESP_URB_SIZE];
		int got = 0;
		for (int n = 0; n < 4; n++) {
			int brc = mt_bulk(d, d->ep_cmd, stale, sizeof stale, &got, 2);
			if (brc == LIBUSB_ERROR_TIMEOUT) {
				d->mcu_stale = 0;
				break;
			}
			if (brc || got < 4)
				break;
		}
	}

	uint8_t seq = 0;
	if (wait_resp) {
		seq = ++d->mcu_seq & 0xf;
		if (!seq)
			seq = ++d->mcu_seq & 0xf;
	}

	int rlen = (len + 3) & ~3;
	uint32_t info = FIELD_PREP(MT_MCU_MSG_LEN, (uint32_t)rlen) |
	                FIELD_PREP(MT_MCU_MSG_PORT, CPU_TX_PORT) |
	                FIELD_PREP(MT_MCU_MSG_CMD_SEQ, seq) |
	                FIELD_PREP(MT_MCU_MSG_CMD_TYPE, (uint32_t)cmd) |
	                MT_MCU_MSG_TYPE_CMD;
	put_le32(buf, info);
	if (len && data)
		memcpy(buf + 4, data, (size_t)len);
	if (rlen > len)
		memset(buf + 4 + len, 0, (size_t)(rlen - len));
	memset(buf + 4 + rlen, 0, 4);

	int total = 4 + rlen + 4;
	int rc = mt_bulk(d, d->ep_mcu, buf, total, nullptr, 20);
	int ok = 0;
	if (!rc && wait_resp)
		ok = mcu_wait_resp(d, seq) == 0;
	else
		ok = rc == 0;
	d->io.unlock();
	return ok ? 0 : -1;
}

int mt_mcu_calibrate(mt7610u_dev *d, int type, uint32_t param)
{
	uint8_t msg[8];
	put_le32(msg, (uint32_t)type);
	put_le32(msg + 4, param);
	int rc = mt_mcu_send(d, CMD_CALIBRATION_OP, msg, 8, 0);
	mt7610_usleep(2000);
	return rc;
}

int mt_mcu_funsel(mt7610u_dev *d, int fun, uint32_t val)
{
	uint8_t msg[8];
	put_le32(msg, (uint32_t)fun);
	put_le32(msg + 4, val);
	return mt_mcu_send(d, CMD_FUN_SET_OP, msg, 8, 0);
}

int mt_mcu_wr_rp(mt7610u_dev *d, uint32_t base, const struct mt76_reg_pair *p, int n)
{
	const int max_vals = MCU_MSG_MAX / 8;
	uint8_t msg[MCU_MSG_MAX];
	while (n > 0) {
		int cnt = n < max_vals ? n : max_vals;
		for (int i = 0; i < cnt; i++) {
			put_le32(msg + i * 8, base + p[i].reg);
			put_le32(msg + i * 8 + 4, p[i].value);
		}
		/* Do not wait for CMD_DONE. A wrong CMD EP must not skip RF. */
		if (mt_mcu_send(d, CMD_RANDOM_WRITE, msg, cnt * 8, 0))
			return -1;
		p += cnt;
		n -= cnt;
	}
	return 0;
}

int mt_rf_wr(mt7610u_dev *d, uint32_t offset, uint8_t val)
{
	struct mt76_reg_pair pair = {offset, val};
	return mt_mcu_wr_rp(d, MT_MCU_MEMMAP_RF, &pair, 1);
}

int mt_rf_rr(mt7610u_dev *d, uint32_t offset)
{
	(void)d;
	(void)offset;
	return -1;
}

int mt_rf_rmw(mt7610u_dev *d, uint32_t offset, uint8_t mask, uint8_t val)
{
	(void)mask;
	return mt_rf_wr(d, offset, val);
}

int mt_rf_or(mt7610u_dev *d, uint32_t offset, uint8_t bits)
{
	return mt_rf_wr(d, offset, bits);
}

int mt_rf_andnot(mt7610u_dev *d, uint32_t offset, uint8_t bits)
{
	(void)bits;
	return mt_rf_wr(d, offset, 0);
}
