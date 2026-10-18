#pragma once

#include <stdint.h>

#define BIT(n)        (1u << (n))
#define GENMASK(h, l) (((~0u) - (1u << (l)) + 1) & (~0u >> (31 - (h))))
#define MT_LOWBIT(m)  ((uint32_t)(m) & (~(uint32_t)(m) + 1u))
#define MT_CTZ(m) ( \
	((MT_LOWBIT(m) & 0xffff0000u) ? 16u : 0u) | \
	((MT_LOWBIT(m) & 0xff00ff00u) ?  8u : 0u) | \
	((MT_LOWBIT(m) & 0xf0f0f0f0u) ?  4u : 0u) | \
	((MT_LOWBIT(m) & 0xccccccccu) ?  2u : 0u) | \
	((MT_LOWBIT(m) & 0xaaaaaaaau) ?  1u : 0u))
#define FIELD_PREP(m, v) (((uint32_t)(v) << MT_CTZ(m)) & (uint32_t)(m))
#define FIELD_GET(m, v)  (((uint32_t)(v) & (uint32_t)(m)) >> MT_CTZ(m))
#define ARRAY_SIZE(a)    (sizeof(a) / sizeof((a)[0]))

#define MT_VEND_TYPE_EEPROM BIT(31)
#define MT_VEND_TYPE_CFG    BIT(30)
#define MT_VEND_TYPE_MASK   (MT_VEND_TYPE_EEPROM | MT_VEND_TYPE_CFG)
#define CFG_ADDR(n)         (MT_VEND_TYPE_CFG | (n))
#define EEP_ADDR(n)         (MT_VEND_TYPE_EEPROM | (n))

#define MT_VEND_DEV_MODE     0x01
#define MT_VEND_WRITE        0x02
#define MT_VEND_MULTI_WRITE  0x06
#define MT_VEND_MULTI_READ   0x07
#define MT_VEND_READ_EEPROM  0x09
#define MT_VEND_WRITE_FCE    0x42
#define MT_VEND_WRITE_CFG    0x46
#define MT_VEND_READ_CFG     0x47

#define MT_EP_IN_PKT_RX      0x84
#define MT_EP_IN_CMD_RESP    0x85
#define MT_EP_OUT_INBAND_CMD 0x08

#define MT_ASIC_VERSION      0x0000
#define MT_CMB_CTRL          0x0020
#define MT_CMB_CTRL_XTAL_RDY BIT(22)
#define MT_CMB_CTRL_PLL_LD   BIT(23)
#define MT_EFUSE_CTRL        0x0024
#define MT_EFUSE_CTRL_SEL    BIT(31)
#define MT_COEXCFG0          0x0040
#define MT_COEXCFG3          0x004c
#define MT_LDO_CTRL_0        0x006c
#define MT_LDO_CTRL_1        0x0070
#define MT_WLAN_FUN_CTRL     0x0080
#define MT_WLAN_FUN_CTRL_WLAN_EN       BIT(0)
#define MT_WLAN_FUN_CTRL_WLAN_CLK_EN   BIT(1)
#define MT_WLAN_FUN_CTRL_WLAN_RESET_RF BIT(2)
#define MT_WLAN_FUN_CTRL_WLAN_RESET    BIT(3)
#define MT_WLAN_FUN_CTRL_FRC_WL_ANT_SEL BIT(5)
#define MT_WLAN_FUN_CTRL_GPIO_OUT_EN   GENMASK(31, 24)
#define MT_CSR_EE_CFG1       0x0104
#define MT_IOCFG_6           0x0124

#define MT_WPDMA_GLO_CFG     0x0208
#define MT_WPDMA_GLO_CFG_TX_DMA_BUSY BIT(1)
#define MT_WPDMA_GLO_CFG_RX_DMA_BUSY BIT(3)
#define MT_USB_DMA_CFG       0x0238
#define MT_USB_DMA_CFG_RX_BULK_AGG_TOUT GENMASK(7, 0)
#define MT_USB_DMA_CFG_UDMA_TX_WL_DROP BIT(16)
#define MT_USB_DMA_CFG_RX_DROP_OR_PAD  BIT(18)
#define MT_USB_DMA_CFG_RX_BULK_AGG_EN  BIT(21)
#define MT_USB_DMA_CFG_RX_BULK_EN      BIT(22)
#define MT_USB_DMA_CFG_TX_BULK_EN      BIT(23)
#define MT_USB_DMA_CFG_RX_BUSY         BIT(30)
#define MT_USB_DMA_CFG_TX_BUSY         BIT(31)
#define MT_WMM_CTRL          0x0230
#define MT_TSO_CTRL          0x0250
#define MT_HEADER_TRANS_CTRL_REG 0x0260
#define MT_US_CYC_CFG        0x02a4
#define MT_US_CYC_CNT        GENMASK(7, 0)

#define MT_PBF_SYS_CTRL      0x0400
#define MT_PBF_CFG           0x0404
#define MT_PBF_TX_MAX_PCNT   0x0408
#define MT_PBF_RX_MAX_PCNT   0x040c
#define MT_BCN_OFFSET_BASE   0x041c
#define MT_BCN_OFFSET(n)     (MT_BCN_OFFSET_BASE + ((n) << 2))
#define MT_RXQ_STA           0x0430

#define MT_RF_CSR_CFG        0x0500
#define MT_RF_BYPASS_0       0x0504
#define MT_RF_SETTING_0      0x050c
#define MT_RF_MISC           0x0518

#define MT_MCU_COM_REG0      0x0730
#define MT_FCE_PSE_CTRL      0x0800
#define MT_FCE_L2_STUFF      0x080c
#define MT_FCE_L2_STUFF_WR_MPDU_LEN_EN BIT(4)
#define MT_FCE_DMA_ADDR      0x0230
#define MT_FCE_DMA_LEN       0x0234
#define MT_TX_CPU_FROM_FCE_BASE_PTR  0x09a0
#define MT_TX_CPU_FROM_FCE_MAX_COUNT 0x09a4
#define MT_TX_CPU_FROM_FCE_CPU_DESC_IDX 0x09a8
#define MT_FCE_PDMA_GLOBAL_CONF      0x09c4
#define MT_FCE_SKIP_FS               0x0a6c

#define MT_MCU_MSG_LEN       GENMASK(15, 0)
#define MT_MCU_MSG_CMD_SEQ   GENMASK(19, 16)
#define MT_MCU_MSG_CMD_TYPE  GENMASK(26, 20)
#define MT_MCU_MSG_PORT      GENMASK(29, 27)
#define MT_MCU_MSG_TYPE_CMD  BIT(30)
#define MT_TXD_INFO_LEN      GENMASK(15, 0)
#define MT_TXD_INFO_DPORT    GENMASK(29, 27)
#define MT_RX_FCE_INFO_CMD_SEQ GENMASK(19, 16)
#define MT_RX_FCE_INFO_EVT_TYPE GENMASK(23, 20)
#define MT_EVT_CMD_DONE      0
#define CPU_TX_PORT          2

#define CMD_FUN_SET_OP       1
#define CMD_RANDOM_READ      10
#define CMD_RANDOM_WRITE     12
#define CMD_CALIBRATION_OP   31
#define Q_SELECT             1
#define BW_SETTING           2

#define MCU_CAL_R            1
#define MCU_CAL_RXDCOC       2
#define MCU_CAL_LC           6
#define MCU_CAL_VCO          16
#define MCU_CAL_FULL         0xff

#define MT_MCU_MEMMAP_WLAN   0x410000
#define MT_MCU_MEMMAP_RF     0x80000000u
#define MT_MCU_IVB_SIZE      0x40
#define MT_MCU_DLM_OFFSET    0x80000
#define MCU_FW_URB_MAX_PAYLOAD 0x38f8

#define MT_MAC_CSR0          0x1000
#define MT_MAC_SYS_CTRL      0x1004
#define MT_MAC_SYS_CTRL_RESET_CSR BIT(0)
#define MT_MAC_SYS_CTRL_RESET_BBP BIT(1)
#define MT_MAC_SYS_CTRL_ENABLE_TX BIT(2)
#define MT_MAC_SYS_CTRL_ENABLE_RX BIT(3)
#define MT_MAC_ADDR_DW0      0x1008
#define MT_MAC_ADDR_DW1      0x100c
#define MT_MAC_ADDR_DW1_U2ME_MASK GENMASK(23, 16)
#define MT_MAC_BSSID_DW0     0x1010
#define MT_MAC_BSSID_DW1     0x1014
#define MT_MAC_BSSID_DW1_ADDR      GENMASK(15, 0)
#define MT_MAC_BSSID_DW1_MBSS_MODE GENMASK(17, 16)
#define MT_MAC_BSSID_DW1_MBEACON_N GENMASK(20, 18)
#define MT_MAC_BSSID_DW1_MBSS_LOCAL_BIT BIT(21)
#define MT_MAC_APC_BSSID_L(n) (0x1090 + ((n) * 8))
#define MT_MAC_APC_BSSID_H(n) (0x1094 + ((n) * 8))
#define MT_LED_CFG           0x102c
#define MT_AMPDU_MAX_LEN_20M1S 0x1030
#define MT_XIFS_TIME_CFG     0x1100
#define MT_BKOFF_SLOT_CFG    0x1104
#define MT_MAC_STATUS        0x1200
#define MT_MAC_STATUS_TX     BIT(0)
#define MT_MAC_STATUS_RX     BIT(1)
#define MT_PWR_PIN_CFG       0x1204
#define MT_BB_PA_MODE_CFG1   0x1218
#define MT_RF_PA_MODE_CFG1   0x1220
#define MT_TX_PWR_CFG_0      0x1314
#define MT_TX_PWR_CFG_1      0x1318
#define MT_TX_PWR_CFG_2      0x131c
#define MT_TX_PWR_CFG_3      0x1320
#define MT_TX_PWR_CFG_4      0x1324
#define MT_TX_BAND_CFG       0x132c
#define MT_TX_BAND_CFG_UPPER_40M BIT(0)
#define MT_TX_BAND_CFG_5G    BIT(1)
#define MT_TX_BAND_CFG_2G    BIT(2)
#define MT_TX_SW_CFG0        0x1330
#define MT_TX_SW_CFG1        0x1334
#define MT_TX_SW_CFG2        0x1338
#define MT_TXOP_CTRL_CFG     0x1340
#define MT_TXOP_TRUN_EN      GENMASK(5, 0)
#define MT_TXOP_EXT_CCA_DLY  GENMASK(15, 8)
#define MT_TXOP_ED_CCA_EN    BIT(20)
#define MT_TX_RTS_CFG        0x1344
#define MT_TX_TIMEOUT_CFG    0x1348
#define MT_TX_RETRY_CFG      0x134c
#define MT_TX_LINK_CFG       0x1350
#define MT_VHT_HT_FBK_CFG1   0x1358
#define MT_MAX_LEN_CFG       0x1018
#define MT_CCK_PROT_CFG      0x1364
#define MT_OFDM_PROT_CFG     0x1368
#define MT_MM20_PROT_CFG     0x136c
#define MT_MM40_PROT_CFG     0x1370
#define MT_GF20_PROT_CFG     0x1374
#define MT_GF40_PROT_CFG     0x1378
#define MT_EXP_ACK_TIME      0x1380
#define MT_TX0_RF_GAIN_CORR  0x13a0
#define MT_TX0_RF_GAIN_ATTEN 0x13a8
#define MT_TX_ALC_CFG_0      0x13b0
#define MT_TX_ALC_CFG_1      0x13b4
#define MT_TX_ALC_CFG_4      0x13c0
#define MT_TX0_BB_GAIN_ATTEN 0x13c0
#define MT_TX_ALC_VGA3       0x13c8
#define MT_TX_PWR_CFG_7      0x13d4
#define MT_TX_PWR_CFG_8      0x13d8
#define MT_TX_PWR_CFG_9      0x13dc
#define MT_TX_PROT_CFG6      0x13e0
#define MT_TX_PROT_CFG7      0x13e4
#define MT_TX_PROT_CFG8      0x13e8
#define MT_RX_FILTR_CFG      0x1400
#define MT_RX_FILTR_CFG_CRC_ERR   BIT(0)
#define MT_RX_FILTR_CFG_PHY_ERR   BIT(1)
#define MT_RX_FILTR_CFG_PROMISC   BIT(2)
#define MT_RX_FILTR_CFG_OTHER_BSS BIT(3)
#define MT_AUTO_RSP_CFG      0x1404
#define MT_LEGACY_BASIC_RATE 0x1408
#define MT_HT_BASIC_RATE     0x140c
#define MT_HT_CTRL_CFG       0x1410
#define MT_EXT_CCA_CFG       0x141c
#define MT_PN_PAD_MODE       0x150c
#define MT_TXOP_HLDR_ET      0x1608

#define MT_BBP_CORE_BASE  0x2000
#define MT_BBP_IBI_BASE   0x2100
#define MT_BBP_AGC_BASE   0x2300
#define MT_BBP_TXC_BASE   0x2400
#define MT_BBP_RXC_BASE   0x2500
#define MT_BBP_TXO_BASE   0x2600
#define MT_BBP_TXBE_BASE  0x2700
#define MT_BBP_RXFE_BASE  0x2800
#define MT_BBP_RXO_BASE   0x2900
#define MT_BBP_CAL_BASE   0x2c00
#define MT_BBP(_type, _n) (MT_BBP_##_type##_BASE + ((_n) << 2))
#define MT_BBP_CORE_R1_BW GENMASK(4, 3)
#define MT_BBP_AGC_R0_CTRL_CHAN GENMASK(9, 8)
#define MT_BBP_AGC_R0_BW  GENMASK(14, 12)
#define MT_BBP_AGC_GAIN   GENMASK(14, 8)
#define MT_BBP_TXBE_R0_CTRL_CHAN GENMASK(1, 0)

#define MT_RXWI_LEN          32
#define MT_DMA_HDR_LEN       4
#define MT_RXWI_CTL_MPDU_LEN GENMASK(29, 16)
#define MT_RXINFO_CRCERR     BIT(8)
#define MT_RXINFO_L2PAD      BIT(14)
#define MT_RX_FCE_INFO_LEN   GENMASK(13, 0)

#define MT_EE_MAC_ADDR       0x04
#define MT_EE_NIC_CONF_0     0x34
#define MT76X0_EEPROM_SIZE   512
