/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_DAC_H
#define PSEMU_DAC_H

#include <stdint.h>

#include "psemu/psemu.h" /* PSEMU_REFERENCE_CLOCK_HZ */

/* Sony PocketStation DAC (audio output). Confirmed against real hardware. See
   docs/hardware-notes.md.

   The DAC has two 32-bit registers at 0x0D800010.
   ctrl is at +0x0. Bit 0 enables the audio output.
   data is at +0x4. Bits 6-15 hold DACV, a signed 10-bit two's-complement output level.
   The maximum positive value of DACV is 0x1FF. Its center value is 0. Its maximum negative value is
   0x200.
   Bits 0-5 of data must stay at zero.

   Audio also needs bit 5 of IOP_STOP and IOP_START (see iop.h).
   Both gates, DAC ctrl bit 0 and IOP bit 5, must be open before sound plays.

   Real hardware has no square-wave generator, no noise generator, and no sound DMA channel.
   Software makes each tone when it writes new DACV levels to DAC_DATA at audio rates.
   Timer1 and IRQ-8 usually cause these writes.
   Thus the DAC is a raw device that the CPU controls bit by bit, one sample at a time. It has no
   fixed sample rate of its own.

   dac_tick supplies this data through a usual fixed-rate audio interface.
   It resamples the output level that the DAC holds into a ring buffer, at the fixed rate
   PSEMU_DAC_SAMPLE_RATE_HZ.
   The resample operation uses a zero-order hold: the most recent value stays in effect until the
   next write. */
#define DAC_REG_SPAN 0x10u

/* Real hardware operates at a variable clock rate. CLK_MODE controls this rate (see clk.h).
   The clock gets to approximately 8MHz at mode 8 and above.

   PSEMU_ASSUMED_CPU_HZ is the fixed reference rate for the cycle budget of psemu_run.
   This value agrees with the real-time pacing in frontends/desktop/main.c: 33000 cycles for each
   32Hz frame (33000 * 32 = 1056000).

   dac_tick and rtc_tick always receive cycles that are already converted to this reference rate.
   This conversion occurs for all values of CLK_MODE.
   See the comment on psemu_run in psemu.c for the reason.

   If the psemu_run() cycle count in main.c changes, change this value to agree with it. If the two
   values do not agree, the audio pitch and tempo become different from the on-screen timing.

   RTC_TICK_CYCLES_RUN in rtc.h holds this same reference rate. The two agree by derivation, and not
   by coincidence. Each one has its own independent confirmation (see rtc.h).

   DO NOT TUNE THIS VALUE BY EAR. The value is 33000u * 32u, and the pacing of the frontend confirms
   it. A value tuned to an audio pitch has no hardware frequency reference, and it conceals a
   cycle-timing fault in a different file. If the audio pitch is incorrect, find that fault instead.
   arm_exec.c and thumb_exec.c are the candidates.

   PSEMU_REFERENCE_CLOCK_HZ (psemu/psemu.h) supplies the number. A frontend needs the rate to pace
   psemu_run, thus the public header carries it. This name continues here, because 23 lines in 5
   files of core/src use it. */
#define PSEMU_ASSUMED_CPU_HZ PSEMU_REFERENCE_CLOCK_HZ
#define PSEMU_DAC_SAMPLE_RATE_HZ 8000u
#define DAC_CYCLES_PER_SAMPLE (PSEMU_ASSUMED_CPU_HZ / PSEMU_DAC_SAMPLE_RATE_HZ) /* = 132 */

#define DAC_SAMPLE_BUFFER_SIZE 8192u

typedef struct dac {
    uint32_t ctrl;
    uint32_t data;
    int16_t current_sample; /* the output level that the DAC holds, scaled to the full int16 range */
    uint32_t cycle_accumulator;
    int iop_muted; /* the same value as !iop_sound_enabled(). See iop.h. */

    int16_t sample_buffer[DAC_SAMPLE_BUFFER_SIZE];
    uint32_t sample_write_pos;
    uint32_t sample_read_pos;
    uint32_t sample_count; /* the number of samples in the buffer now, DAC_SAMPLE_BUFFER_SIZE or less */
} dac_t;

void dac_init(dac_t *dac);
uint8_t dac_read8(dac_t *dac, uint32_t offset);
void dac_write8(dac_t *dac, uint32_t offset, uint8_t value);

/* Copies the state of the IOP_STOP and IOP_START bit 5 gate (see iop.h) into the DAC.
   dac_tick gives samples that are not silence only when this gate and the enable bit of ctrl are
   both set. */
void dac_set_iop_muted(dac_t *dac, int muted);

/* Advances the DAC by `cycles`.
   Resamples the DAC output level into the sample buffer, at PSEMU_DAC_SAMPLE_RATE_HZ.
   Gives silence while the enable bit of ctrl is clear, or while the IOP mutes the DAC (see
   dac_set_iop_muted). */
void dac_tick(dac_t *dac, uint32_t cycles);

/* Moves a maximum of max_samples samples into buf. The samples are mono, signed 16-bit.
   Returns the number of samples that it wrote.
   This number is less than max_samples if the buffer holds less samples than the caller requested. */
uint32_t dac_read_samples(dac_t *dac, int16_t *buf, uint32_t max_samples);

#endif
