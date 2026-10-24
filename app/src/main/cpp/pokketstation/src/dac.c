/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "dac.h"

void dac_init(dac_t *dac) {
    dac->ctrl = 0;
    dac->data = 0;
    dac->current_sample = 0;
    dac->cycle_accumulator = 0;
    dac->iop_muted = 0;
    dac->sample_write_pos = 0;
    dac->sample_read_pos = 0;
    dac->sample_count = 0;
}

void dac_set_iop_muted(dac_t *dac, int muted) {
    dac->iop_muted = muted;
}

uint8_t dac_read8(dac_t *dac, uint32_t offset) {
    uint32_t word_index = offset / 4u;
    uint32_t shift = (offset % 4u) * 8u;
    uint32_t value = (word_index == 0u) ? dac->ctrl : (word_index == 1u ? dac->data : 0u);
    return (uint8_t)(value >> shift);
}

void dac_write8(dac_t *dac, uint32_t offset, uint8_t value) {
    uint32_t word_index = offset / 4u;
    uint32_t shift = (offset % 4u) * 8u;

    if (word_index == 0u) { /* ctrl */
        dac->ctrl = (dac->ctrl & ~(0xFFu << shift)) | ((uint32_t)value << shift);
    } else if (word_index == 1u) { /* data */
        dac->data = (dac->data & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        /* Get the signed 10-bit DACV field (bits 6-15), and extend the sign from bit 9.
           Then multiply by 64, to change the range from +-512 to the full int16 range.
           -512 * 64 is -32768, which is exactly the negative limit. */
        int32_t raw10 = (int32_t)((dac->data >> 6) & 0x3FFu);
        if (raw10 & 0x200) {
            raw10 -= 0x400;
        }
        dac->current_sample = (int16_t)(raw10 * 64);
    }
}

void dac_tick(dac_t *dac, uint32_t cycles) {
    dac->cycle_accumulator += cycles;
    while (dac->cycle_accumulator >= DAC_CYCLES_PER_SAMPLE) {
        dac->cycle_accumulator -= DAC_CYCLES_PER_SAMPLE;
        if (dac->sample_count < DAC_SAMPLE_BUFFER_SIZE) {
            int16_t sample = ((dac->ctrl & 1u) && !dac->iop_muted) ? dac->current_sample : 0;
            dac->sample_buffer[dac->sample_write_pos] = sample;
            dac->sample_write_pos = (dac->sample_write_pos + 1u) % DAC_SAMPLE_BUFFER_SIZE;
            dac->sample_count++;
        }
        /* If not: the buffer is full, because the frontend does not drain it at sufficient speed.
           Discard the sample. Do not write over the samples that the frontend has not read. */
    }
}

uint32_t dac_read_samples(dac_t *dac, int16_t *buf, uint32_t max_samples) {
    uint32_t count = (max_samples < dac->sample_count) ? max_samples : dac->sample_count;
    uint32_t i;
    for (i = 0; i < count; i++) {
        buf[i] = dac->sample_buffer[dac->sample_read_pos];
        dac->sample_read_pos = (dac->sample_read_pos + 1u) % DAC_SAMPLE_BUFFER_SIZE;
    }
    dac->sample_count -= count;
    return count;
}
