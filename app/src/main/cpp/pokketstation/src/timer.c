/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "timer.h"

#include "intc.h"

void timer_init(psemu_timer_t *timer) {
    uint32_t i;
    for (i = 0; i < TIMER_COUNT; i++) {
        timer->timers[i].period = 0;
        timer->timers[i].count = 0;
        timer->timers[i].control = 0;
        timer->timers[i].cycle_accumulator = 0;
    }
}

/* Bits 0-1 of control: 0 or 3 gives /2, 1 gives /32, and 2 gives /512.
   These values agree with the timer-start behavior of real hardware and with the recorded divider
   values. */
static uint32_t timer_divisor(uint32_t control) {
    switch (control & TIMER_CTRL_DIVIDER_MASK) {
    case 1:
        return 32u;
    case 2:
        return 512u;
    default: /* 0 and 3 */
        return 2u;
    }
}

uint8_t timer_read8(psemu_timer_t *timer, uint32_t offset) {
    uint32_t index = offset / TIMER_BLOCK_SIZE;
    uint32_t local = offset % TIMER_BLOCK_SIZE;
    uint32_t word_index = local / 4u;
    uint32_t shift = (local % 4u) * 8u;
    uint32_t value;

    if (index >= TIMER_COUNT) {
        return 0;
    }
    switch (word_index) {
    case 0:
        value = timer->timers[index].period;
        break;
    case 1:
        value = timer->timers[index].count;
        break;
    case 2:
        value = timer->timers[index].control;
        break;
    default:
        value = 0;
        break;
    }
    return (uint8_t)(value >> shift);
}

void timer_write8(psemu_timer_t *timer, uint32_t offset, uint8_t value) {
    uint32_t index = offset / TIMER_BLOCK_SIZE;
    uint32_t local = offset % TIMER_BLOCK_SIZE;
    uint32_t word_index = local / 4u;
    uint32_t shift = (local % 4u) * 8u;
    uint32_t *reg;

    if (index >= TIMER_COUNT) {
        return;
    }
    switch (word_index) {
    case 0:
        reg = &timer->timers[index].period;
        break;
    case 1:
        reg = &timer->timers[index].count;
        break;
    case 2: {
        uint32_t was_enabled = timer->timers[index].control & TIMER_CTRL_ENABLE;
        reg = &timer->timers[index].control;
        *reg = (*reg & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        /* Real hardware starts the prescaler again at each write to control.
           This agrees with the timer-start logic of this emulator, which executes again at each
           control write.
           Discard any incomplete divisor progress. Thus a mode change or an enable change starts
           at a clean edge. */
        timer->timers[index].cycle_accumulator = 0;
        /* When this code arms a timer, the timer loads its counter from PERIOD. The timer-set helper
           of a real IR app writes only PERIOD and then CONTROL. It never writes COUNT. That helper
           disables the timer, writes the period, and enables the timer again. See the routine that
           the IR receive handler of that app calls to arm Timer2 again between pulses.
           Without a load at the enable edge, a newly armed timer continues to count down from its old
           value. Then each interval that the app measures is incorrect.
           A disassembly of that helper confirms this. The arithmetic of the receive handler also
           confirms it: the handler reads the elapsed time as (armed period - current count). */
        if (!was_enabled && (timer->timers[index].control & TIMER_CTRL_ENABLE)) {
            timer->timers[index].count = timer->timers[index].period & TIMER_REG_MASK;
        }
        return;
    }
    default:
        return;
    }
    /* On real hardware, period and count are 16-bit registers (see TIMER_REG_MASK in timer.h).
       Thus this code discards the upper half of a wider store. It does not keep the value. */
    *reg = ((*reg & ~(0xFFu << shift)) | ((uint32_t)value << shift)) & TIMER_REG_MASK;
}

void timer_tick(psemu_timer_t *timer, struct intc *intc, uint32_t cycles) {
    static const uint32_t int_lines[TIMER_COUNT] = {INT_TIMER0, INT_TIMER1, INT_TIMER2};
    uint32_t i;

    for (i = 0; i < TIMER_COUNT; i++) {
        single_timer_t *t = &timer->timers[i];
        uint32_t divisor;
        uint32_t ticks;

        if (!(t->control & TIMER_CTRL_ENABLE) || t->period == 0) {
            continue;
        }

        /* The count of the timer decreases one time for each `divisor` raw cycles.
           Bits 0-1 of control select /2, /32, or /512.
           This agrees with the timer-start behavior of real hardware and with the recorded divider
           table.
           History: an earlier version of this function decreased count by the raw cycles directly,
           and did not use the divisor. Thus each timer with a slower divisor expired much more
           frequently than a timer on real hardware. */
        divisor = timer_divisor(t->control);
        t->cycle_accumulator += cycles;
        ticks = t->cycle_accumulator / divisor;
        t->cycle_accumulator %= divisor;

        /* A timer with period P expires each P+1 ticks. It does not expire each P ticks.
           The counter goes from P, to P-1, down to 1, and then to 0. It reloads at the tick AFTER
           it gets to zero. Thus zero is a state of the counter.

           A direct measurement on real hardware confirms this. See screen 6 of pk_timing_bench, with
           the results in pk_timing_bench/VERIFICATION.md.
           Timer2 at period 1016 over 256 reloads, and at period 2032 over 128 reloads, both gave
           exactly 1 tick for each reload more than a period of P ticks predicts.
           The extra time is the same absolute value at both periods. This removes a rate error and a
           divisor error as causes, and gives a fixed error of one tick for each period.

           This code used exactly `count` ticks for each reload before. Thus each timer expired one
           tick early. */
        while (ticks > 0) {
            if (ticks > t->count) {
                ticks -= t->count + 1u;
                t->count = t->period & TIMER_REG_MASK;
                intc_set_line(intc, int_lines[i], 1);
            } else {
                t->count -= ticks;
                ticks = 0;
            }
        }
    }
}
