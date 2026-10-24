/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_RTC_H
#define PSEMU_RTC_H

#include <stdint.h>

#include "dac.h" /* PSEMU_ASSUMED_CPU_HZ: the reference rate of the `cycles` argument of rtc_tick */

struct intc;

#define RTC_REG_SPAN 16u

/* Sony PocketStation RTC.
   The register behavior agrees with the available register table. See docs/hardware-notes.md for the
   full comparison.

   This emulator prevents two known errors:
   - It does not use a fixed 1999-01-01 for the time and the date. The real BIOS sets itself to
     January 1999, which is a well-known quirk. But that action is a software action, and it is not
     the real power-on-reset value.
   - It does not operate the RTC timer at 1Hz while the RTC is "paused". The real interrupt operates
     at approximately 4096Hz while the RTC is paused. This rate lets a "set the clock" screen move
     through an adjustment. The user does not have to wait one second for each step.

   The RTC has four 32-bit registers at 0x0B800000:
   - mode (+0x0)
   - control (+0x4)
   - time (+0x8, read-only to software)
   - date (+0xC, read-only)

   time and date each hold BCD bytes, from the LSB to the MSB:
   - time: seconds, minutes, hours, day of the week (1 is Sunday, 7 is Saturday)
   - date: day, month, year (2-digit BCD)

   The highest byte of date is not a "year-high" field or a century field.
   The available description gives it as "Unknown? (this is NOT used as century)".
   The real century value is in battery-backed kernel RAM.
   Only the GetBcdDate SWI reads the century value back. This register does not supply it.

   Bit 0 of mode (PRGSEL) selects the mode of the RTC:
   - Run (0): the RTC ticks at 1Hz and advances the clock at each tick.
   - Program or pause (1): the RTC ticks at approximately 4096Hz and does not advance the clock.
   Program mode has one function: it lets RTC_ADJUST writes change one field at a time, while the
   clock stays constant.

   Bits 1-3 of mode (CNTSEL) select the BCD field that a control-register write changes:
   0 is sec, 1 is min, 2 is hour, 3 is dow, 4 is day, 5 is month, 6 is year, and 7 is none.

   A write of 1 to control while control holds 1 increases the field that CNTSEL selects. It then sets
   control to 0. A write to control while control holds 0 only stores the value.
   This is the real "write 1 two times" method for one increment.
   The behavior of real hardware and independent community reports confirm this. This emulator does
   the same operation.

   The automatic advance at each tick cascades from seconds, to minutes, to hours, to the day of the
   week. At midnight it continues into the day, the month, and the year (see rtc_advance_date).
   It uses the same BCD carry arithmetic as the CNTSEL = 0 (seconds) condition.

   History: this cascade stopped at the day of the week before, and never changed RTC_DATE. Thus an
   emulated device stayed at one date permanently, while its day of the week continued to advance.
   This file recorded that condition as a gap from earlier work, because no independent source gave
   the real mechanism. A direct test on a real unit has since confirmed that the date does roll over.
   Thus the condition was a fault, and not an unknown. The month lengths and the leap-year rule that
   rtc_advance_date uses are still unconfirmed. See the comment on that function. */
typedef struct rtc {
    uint32_t mode;
    uint32_t control;
    uint32_t time;
    uint32_t date;
    uint32_t tick_accumulator;
    int int_line;
} rtc_t;

/* The cycle count between interrupt-line TRANSITIONS. Two transitions make one full pulse. While the
   RTC runs, the clock advances one second for each full pulse (see rtc_tick).

   **The 1Hz and 4096Hz figures are waveform rates. They are not transition rates.** This difference
   is important, because this emulator used the figures incorrectly before: it used them as
   transition rates. Thus its line operated at one half of the real frequency, in both modes.

   MEASURED ON REAL HARDWARE, by screen 14 of pk_timing_bench: while the RTC is paused, the line
   makes 8192 transitions each second. This is 4096 full pulses, which is exactly the expected
   figure. The measurement gave 0.031250s for 256 transitions, to the tick. This result also confirms
   the frequency of CLK_MODE 7 (3,997,696Hz). Before this measurement, the timing table had that
   frequency only from documentation.

   ALSO MEASURED, by the same screen: while the RTC runs, the line makes two transitions each second.
   This is a 1Hz waveform, which is again exactly the expected figure. Four transitions gave exactly
   2.000 seconds. An earlier run of that measurement read 11% fast. That run sampled one pulse
   immediately after the RTC left program mode, while the divider of the RTC was still
   resynchronizing. If you discard one pulse first, the error does not occur.

   Arithmetic also gives the 1Hz running rate, independently of a measurement. `cycles` arrives at
   rtc_tick already converted to real elapsed time, at the fixed PSEMU_ASSUMED_CPU_HZ reference rate
   (see psemu_run). A wall clock must advance one second for each real second, whatever the line
   does.

   History: this value was 4000000. That value was selected only to be "fast enough that a
   wait-for-pulse loop completes in a reasonable instruction budget". Nobody compared it against a
   real reference. That value gives 3.79 reference-seconds for each transition. Thus the clock of the
   emulated PocketStation operated almost 4 times too slowly: 60 seconds of real time advanced it by
   15 seconds, a loss of approximately 45 minutes each hour. This value satisfies the wait-for-pulse
   condition better, because pulses now occur sooner. */
#define RTC_TICK_CYCLES_RUN (PSEMU_ASSUMED_CPU_HZ / 2u)
/* This value is rounded, not truncated. The exact value is 128.9. A truncation to 128 makes the
   paused line 0.7% fast against the 8192 transitions each second that the hardware measurement
   gives. A round to 129 is 0.07% slow, which is better. */
#define RTC_TICK_CYCLES_PAUSED ((RTC_TICK_CYCLES_RUN + 2048u) / 4096u)

void rtc_init(rtc_t *rtc);
uint8_t rtc_read8(rtc_t *rtc, uint32_t offset);
void rtc_write8(rtc_t *rtc, uint32_t offset, uint8_t value);

/* Advances the RTC by `cycles`.
   The interrupt line of the real RTC changes state at approximately 1Hz while the RTC runs, or at
   approximately 4096Hz while the RTC is paused (mode bit 0). This emulator asserts that line as
   INT_RTC through `intc`.
   Real BIOS code waits for a full pulse: a rise and then a fall. Thus a constant "always ready" value
   is not sufficient.
   While the RTC runs, each change of state also advances the clock (see the top comment of rtc.h). */
void rtc_tick(rtc_t *rtc, struct intc *intc, uint32_t cycles);

#endif
