/* SPDX-License-Identifier: GPL-2.0-only */
/* mt7610u.bin ILM/DLM upload. Sequence from mt76x0/usb_mcu.c. */

#include "internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define REQ_OUT ((uint8_t)LIBUSB_ENDPOINT_OUT | (uint8_t)LIBUSB_REQUEST_TYPE_VENDOR | \
                 (uint8_t)LIBUSB_RECIPIENT_DEVICE)

struct fw_hdr {
	uint32_t ilm_len;
	uint32_t dlm_len;
	uint16_t build_ver;
	uint16_t fw_ver;
	uint8_t pad[4];
	char build_time[16];
};

static uint32_t le32(const uint8_t *p)
{
	return (uint32_t)p[0] | ((uint32_t)p[1] << 8) |
	       ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

static void put_le32(uint8_t *p, uint32_t v)
{
	p[0] = (uint8_t)v;
	p[1] = (uint8_t)(v >> 8);
	p[2] = (uint8_t)(v >> 16);
	p[3] = (uint8_t)(v >> 24);
}

static int fw_send_chunk(mt7610u_dev *d, uint8_t *scratch, const uint8_t *src,
                         int len, uint32_t dst)
{
	uint32_t info = FIELD_PREP(MT_MCU_MSG_PORT, CPU_TX_PORT) |
	                FIELD_PREP(MT_MCU_MSG_LEN, (uint32_t)len) |
	                MT_MCU_MSG_TYPE_CMD;
	put_le32(scratch, info);
	memcpy(scratch + 4, src, (size_t)len);
	int rlen = (len + 3) & ~3;
	memset(scratch + 4 + len, 0, (size_t)(rlen - len + 4));
	mt_single_wr(d, MT_VEND_WRITE_FCE, MT_FCE_DMA_ADDR, dst);
	mt_single_wr(d, MT_VEND_WRITE_FCE, MT_FCE_DMA_LEN, (uint32_t)rlen << 16);
	int rc = mt_bulk(d, d->ep_mcu, scratch, 4 + rlen + 4, nullptr, 1000);
	if (rc)
		return -1;
	uint32_t idx = mt_rr(d, MT_TX_CPU_FROM_FCE_CPU_DESC_IDX) + 1;
	mt_wr(d, MT_TX_CPU_FROM_FCE_CPU_DESC_IDX, idx);
	return 0;
}

static int fw_send_data(mt7610u_dev *d, const uint8_t *data, int data_len,
                        uint32_t offset)
{
	int max_len = MCU_FW_URB_MAX_PAYLOAD - 8;
	uint8_t *scratch = (uint8_t *)malloc(MCU_FW_URB_MAX_PAYLOAD + 16);
	if (!scratch)
		return -1;
	int pos = 0;
	int rc = 0;
	while (data_len > 0) {
		int len = data_len < max_len ? data_len : max_len;
		rc = fw_send_chunk(d, scratch, data + pos, len, offset + (uint32_t)pos);
		if (rc)
			break;
		data_len -= len;
		pos += len;
		mt7610_usleep(400);
	}
	free(scratch);
	return rc;
}

int mt_fw_load(mt7610u_dev *d, const char *path)
{
	FILE *f = fopen(path, "rb");
	if (!f)
		return -1;
	fseek(f, 0, SEEK_END);
	long n = ftell(f);
	fseek(f, 0, SEEK_SET);
	if (n < (long)sizeof(fw_hdr)) {
		fclose(f);
		return -1;
	}
	uint8_t *fw = (uint8_t *)malloc((size_t)n);
	if (!fw || fread(fw, 1, (size_t)n, f) != (size_t)n) {
		free(fw);
		fclose(f);
		return -1;
	}
	fclose(f);

	uint32_t ilm = le32(fw);
	uint32_t dlm = le32(fw + 4);
	if (ilm <= MT_MCU_IVB_SIZE ||
	    (size_t)n != sizeof(fw_hdr) + ilm + dlm) {
		free(fw);
		return -1;
	}

	/* Linux mt76x0u_load_firmware writes DMA enable before the
	 * already-running check so a reused chip still gets RX bulk. */
	mt_wr(d, MT_USB_DMA_CFG, MT_USB_DMA_CFG_RX_BULK_EN | MT_USB_DMA_CFG_TX_BULK_EN);
	if (mt_rr(d, MT_MCU_COM_REG0) == 1) {
		mt_wr(d, MT_FCE_PSE_CTRL, 1);
		mt_wr(d, MT_TX_CPU_FROM_FCE_BASE_PTR, 0x400230);
		mt_wr(d, MT_TX_CPU_FROM_FCE_MAX_COUNT, 1);
		mt_wr(d, MT_FCE_PDMA_GLOBAL_CONF, 0x44);
		mt_wr(d, MT_FCE_SKIP_FS, 3);
		free(fw);
		return 0;
	}

	mt_wr(d, 0x1004, 0x2c);
	mt_set(d, MT_USB_DMA_CFG,
	       MT_USB_DMA_CFG_RX_BULK_EN | MT_USB_DMA_CFG_TX_BULK_EN |
	       FIELD_PREP(MT_USB_DMA_CFG_RX_BULK_AGG_TOUT, 0x20));
	mt_vendor_req(d, MT_VEND_DEV_MODE, REQ_OUT, 0x1, 0, nullptr, 0);
	mt7610_usleep(5000);

	mt_wr(d, MT_FCE_PSE_CTRL, 1);
	mt_wr(d, MT_TX_CPU_FROM_FCE_BASE_PTR, 0x400230);
	mt_wr(d, MT_TX_CPU_FROM_FCE_MAX_COUNT, 1);
	mt_wr(d, MT_FCE_PDMA_GLOBAL_CONF, 0x44);
	mt_wr(d, MT_FCE_SKIP_FS, 3);

	uint32_t dma = mt_rr(d, MT_USB_DMA_CFG);
	mt_wr(d, MT_USB_DMA_CFG, dma | MT_USB_DMA_CFG_UDMA_TX_WL_DROP);
	mt_wr(d, MT_USB_DMA_CFG, dma & ~MT_USB_DMA_CFG_UDMA_TX_WL_DROP);

	const uint8_t *payload = fw + sizeof(fw_hdr);
	int rc = fw_send_data(d, payload + MT_MCU_IVB_SIZE,
	                      (int)(ilm - MT_MCU_IVB_SIZE), MT_MCU_IVB_SIZE);
	if (!rc)
		rc = fw_send_data(d, payload + ilm, (int)dlm, MT_MCU_DLM_OFFSET);
	if (!rc) {
		rc = mt_vendor_req(d, MT_VEND_DEV_MODE, REQ_OUT, 0x12, 0,
		                   (void *)payload, MT_MCU_IVB_SIZE);
		if (rc >= 0)
			rc = 0;
	}
	if (!rc && !mt_poll(d, MT_MCU_COM_REG0, 1, 1, 1000 * 1000))
		rc = -1;

	mt_wr(d, MT_FCE_PSE_CTRL, 1);
	free(fw);
	return rc;
}
