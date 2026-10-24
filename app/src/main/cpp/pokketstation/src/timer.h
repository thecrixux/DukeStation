/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_TIMER_H
#define PSEMU_TIMER_H

#include <stdint.h>

struct intc;

/* The real PocketStation timer block: 3 independent timers.
   Confirmed against real hardware behavior. See docs/hardware-notes.md.

   Each timer uses 16 bytes at 0x0A800000 + n*0x10:
   - period (+0x0)
   - count (+0x4)
   - control (+0x8)

   At expiry, count loads the value of period again.
   The timer then asserts INT_TIMERn through the interrupt controller.

   Bits 0-1 of control select a clock divisor: 0 or 3 gives /2, 1 gives /32, and 2 gives /512.
   This agrees with the timer-start behavior of real hardware, and with the description
   "0 = Div2, 1 = Div32, 2 = Div512, 3 = Div2 also".
   Bit 2 of control starts the timer.

   History: an earlier version of this file did not model the divisor. It decreased count by the raw
   cycles, for all divisors. This was a confirmed fault: each timer that used a slower divisor
   expired much more frequently than a timer on real hardware.

   Not modeled: a decrease of the count of a running timer by 1, as a side effect of a software read.
   The available description gives only "current value (decrementing)" reads. It gives no read-side
   side effect.
   This behavior looks like an unconfirmed quirk, and not confirmed real hardware behavior.
   This emulator does not model it. */
#define TIMER_COUNT 3u
#define TIMER_BLOCK_SIZE 0x10u
#define TIMER_REG_SPAN (TIMER_COUNT * TIMER_BLOCK_SIZE)
#define TIMER_CTRL_ENABLE (1u << 2)
#define TIMER_CTRL_DIVIDER_MASK 0x3u

/* period and count are 16-bit registers. They are not 32-bit registers.
   Confirmed against real hardware: each raw `count` value from a real unit had its upper 16
   bits at zero. Also, a counter read after a long loop gave a larger number than a read
   before the loop. This can occur only if the counter wraps and reloads at a 16-bit
   boundary. See docs/hardware-notes.md, "Timers".

   History: this emulator modeled both registers as a full uint32_t. That model broke each
   app whose timer setup wrote a value of more than 16 bits, and gave no error. The upper
   bits stayed in the register and made the period very long, in place of a discard.
   One music app confirms this. Its FIQ-driven audio timer (Timer2) held period 0x03240353
   under the 32-bit model. That period is approximately 52.6 million ticks, thus the audio
   interrupt never occurred. The app played in full silence, with its DAC gate open for the
   full time. A mask to the real 16-bit width gives period 0x0353 (851). This value agrees
   with the 0x34F (847) that the app programs into Timer1 at the same time, and the music
   then plays.
   See test_timer_registers_are_16_bit. */
#define TIMER_REG_MASK 0xFFFFu

typedef struct {
    uint32_t period;
    uint32_t count;
    uint32_t control;
    uint32_t cycle_accumulator; /* the raw cycles that the divisor has not yet used */
} single_timer_t;

/* The name of this type is psemu_timer_t, and not timer_t. POSIX declares timer_t in
   <sys/types.h>, through <time.h> and <stdlib.h>. Thus the shorter name causes a
   redefinition error on each target that is not Windows. MSVC has no POSIX timer_t. This is
   why the shorter name broke only the Linux, macOS, Android, and webOS CI jobs, and never
   the Windows jobs. */
typedef struct timer {
    single_timer_t timers[TIMER_COUNT];
} psemu_timer_t;

void timer_init(psemu_timer_t *timer);
uint8_t timer_read8(psemu_timer_t *timer, uint32_t offset);
void timer_write8(psemu_timer_t *timer, uint32_t offset, uint8_t value);

/* Advances each running timer by `cycles`.
   It asserts the applicable INT_TIMERn line through `intc` at each expiry. */
void timer_tick(psemu_timer_t *timer, struct intc *intc, uint32_t cycles);

#endif
