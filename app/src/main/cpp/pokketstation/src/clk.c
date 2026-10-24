/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "clk.h"

#define CLK_STEADY 0x10u

/* The argument table for PMFrequency, CLK_MODE, and SWI 04h SetCpuSpeed.

   Indices 1-8 use the exact recorded Hz values.
   See the CLK_MODE section of docs/hardware-notes.md for the full comparison and verification.
   These values are not an approximation to powers of two.
   The table is not a sequence of doubled values: the step from index 5 to index 6 is approximately
   1.97 times, and not 2 times. An approximation with doubled values is measurably incorrect at
   several indices.
   Indices 9-15 use the rate of index 8. The recorded table also gives this.

   Index 0 keeps its earlier value, 32768. A test by ear against real hardware confirms this value.
   This emulator uses index 0 as the idle default.
   This value does not come from the available documentation. That documentation gives index 0 as
   "hangs hardware", which is an invalid or reserved PLL setting, and it gives no frequency. The
   documentation has no other numeric value for this index.
   In practice this selection has no bad effect: a real-BIOS trace of 20 million instructions never
   writes CLK_MODE = 0. It writes only 7, 4, or 3 (see docs/hardware-notes.md, "CLK_MODE").

   History: a still earlier version of this table used values that were approximately 2 times too
   high at each index. For example, "mode 7" gave approximately 7.995MHz.
   A comparison test on real hardware showed independently that those values were incorrect. That
   test has no relation to the documentation comparison above. */
static const uint32_t CPU_FREQ[16] = {
    32768u,   63488u,   126976u,  253952u,  507904u,  1015808u, 1998848u, 3997696u,
    7995392u, 7995392u, 7995392u, 7995392u, 7995392u, 7995392u, 7995392u, 7995392u,
};

void clk_init(clk_t *clk) {
    clk->mode = 0;
    clk->control = 0;
    clk->mode_write_scratch = 0;
    clk->control_write_scratch = 0;
}

uint8_t clk_read8(clk_t *clk, uint32_t offset) {
    uint32_t shift = (offset % 4u) * 8u;
    /* BOTH words read back as CLK_MODE with the steady bit. `control` (+0x4) does not read back the
       value that software wrote to it.

       Measured on real hardware, by screen 13 of pk_timing_bench: the app wrote 1 to +0x4 and then
       woke again. A read of +0x4 then returned 0x17. This value is exactly the CLK_MODE value that
       the app set (7), with the steady bit (0x10) ORed in. There is only one data point. Thus "the
       two words have the same value at a read" is the simplest explanation, but it is not proven. A
       return of the stored `control` value was definitely incorrect: no hardware reads back a 1
       there.

       This is also why screen 13 cannot show whether the stop bit clears itself. There is no
       readable stop status. The same run does show that the CPU started again and continued to
       operate. Thus the stop does not continue after a wake, whatever the internal implementation
       is. */
    uint32_t value = clk->mode | CLK_STEADY;
    return (uint8_t)(value >> shift);
}

void clk_write8(clk_t *clk, uint32_t offset, uint8_t value) {
    uint32_t word_index = offset / 4u;
    uint32_t shift = (offset % 4u) * 8u;

    if (word_index == 0u) {
        clk->mode_write_scratch = (clk->mode_write_scratch & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        if (shift == 24u) {
            clk->mode = clk->mode_write_scratch;
        }
    } else {
        clk->control_write_scratch = (clk->control_write_scratch & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        if (shift == 24u) {
            clk->control = clk->control_write_scratch;
        }
    }
}

uint32_t clk_current_hz(const clk_t *clk) {
    return CPU_FREQ[clk->mode & 0x0Fu];
}

int clk_stop_requested(const clk_t *clk) {
    return (clk->control & 1u) != 0;
}

void clk_clear_stop(clk_t *clk) {
    clk->control &= ~1u;
}
