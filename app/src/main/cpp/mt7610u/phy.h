#pragma once

#include "regs.h"
#include <stdint.h>

typedef uint8_t u8;
typedef uint16_t u16;
typedef uint32_t u32;
typedef int8_t s8;

#define RF_G_BAND     0x0100
#define RF_A_BAND     0x0200
#define RF_A_BAND_LB  0x0400
#define RF_A_BAND_MB  0x0800
#define RF_A_BAND_HB  0x1000
#define RF_A_BAND_11J 0x2000
#define RF_BW_20      1
#define RF_BW_40      2
#define RF_BW_10      4
#define RF_BW_80      8

#define MT_RF(bank, reg) (((uint32_t)(bank) << 16) | (uint32_t)(reg))
#define MT_RF_BANK(offset) ((offset) >> 16)
#define MT_RF_REG(offset)  ((offset) & 0xff)

#define MT_RF_PLL_DEN_MASK        GENMASK(4, 0)
#define MT_RF_PLL_K_MASK          GENMASK(4, 0)
#define MT_RF_SDM_RESET_MASK      BIT(7)
#define MT_RF_SDM_MASH_PRBS_MASK  GENMASK(6, 2)
#define MT_RF_SDM_BP_MASK         BIT(1)
#define MT_RF_ISI_ISO_MASK        GENMASK(7, 6)
#define MT_RF_PFD_DLY_MASK        GENMASK(5, 4)
#define MT_RF_CLK_SEL_MASK        GENMASK(3, 2)
#define MT_RF_XO_DIV_MASK         GENMASK(1, 0)

struct mt76_reg_pair {
	uint32_t reg;
	uint32_t value;
};

struct mt76x0_bbp_switch_item {
	uint16_t bw_band;
	struct mt76_reg_pair reg_pair;
};

struct mt76x0_rf_switch_item {
	uint32_t rf_bank_reg;
	uint16_t bw_band;
	uint8_t value;
};

struct mt76x0_freq_item {
	uint8_t channel;
	uint32_t band;
	uint8_t pllR37;
	uint8_t pllR36;
	uint8_t pllR35;
	uint8_t pllR34;
	uint8_t pllR33;
	uint8_t pllR32_b7b5;
	uint8_t pllR32_b4b0;
	uint8_t pllR31_b7b5;
	uint8_t pllR31_b4b0;
	uint8_t pllR30_b7;
	uint8_t pllR30_b6b2;
	uint8_t pllR30_b1;
	uint16_t pll_n;
	uint8_t pllR28_b7b6;
	uint8_t pllR28_b5b4;
	uint8_t pllR28_b3b2;
	uint32_t pll_sdm_k;
	uint8_t pllR24_b1b0;
};
