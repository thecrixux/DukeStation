/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "rtc.h"

#include "intc.h"

void rtc_init(rtc_t *rtc) {
    rtc->mode = 0;
    rtc->control = 0;
    /* The power-on-reset values of real hardware, from the reset columns of RTCClock and RTCCalendar:
       the day of the week is BCD 4, and the date is 1998-01-01.
       A value of 1999-01-01 agrees with the well-known "the BIOS resets itself to January 1999"
       software quirk. That quirk is a software action. It is not the real hardware reset value.
       Thus this emulator uses the real power-on-reset values. */
    rtc->time = 0x04000000u;
    rtc->date = 0x00980101u;
    rtc->tick_accumulator = 0;
    rtc->int_line = 0;
}

static uint32_t *reg_ptr(rtc_t *rtc, uint32_t reg_index) {
    switch (reg_index % 4u) {
    case 0:
        return &rtc->mode;
    case 1:
        return &rtc->control;
    case 2:
        return &rtc->time;
    default:
        return &rtc->date;
    }
}

uint8_t rtc_read8(rtc_t *rtc, uint32_t offset) {
    uint32_t *reg = reg_ptr(rtc, offset / 4u);
    return (uint8_t)(*reg >> ((offset % 4u) * 8u));
}

/* Increases the BCD field that mode>>1 selects.
   The wraparound arithmetic agrees with the register-write behavior of real hardware.
   Case 7 (year-high) has no effect. */
static void rtc_increment_field(rtc_t *rtc) {
    switch (rtc->mode >> 1) {
    case 0: /* seconds */
        rtc->time += 0x00000001u;
        if ((rtc->time & 0x0000000Fu) == 0x0000000Au) {
            rtc->time &= 0xFFFFFFF0u;
            rtc->time += 0x00000010u;
            if ((rtc->time & 0x000000FFu) == 0x00000060u) {
                rtc->time &= 0xFFFFFF00u;
            }
        }
        break;
    case 1: /* minutes */
        rtc->time += 0x00000100u;
        if ((rtc->time & 0x00000F00u) == 0x00000A00u) {
            rtc->time &= 0xFFFFF0FFu;
            rtc->time += 0x00001000u;
            if ((rtc->time & 0x0000FF00u) == 0x00006000u) {
                rtc->time &= 0xFFFF00FFu;
            }
        }
        break;
    case 2: /* hours */
        rtc->time += 0x00010000u;
        if ((rtc->time & 0x00FF0000u) == 0x00240000u) {
            rtc->time &= 0xFF00FFFFu;
        } else if ((rtc->time & 0x000F0000u) == 0x000A0000u) {
            rtc->time &= 0xFFF0FFFFu;
            rtc->time += 0x00100000u;
        }
        break;
    case 3: /* day of week */
        rtc->time += 0x01000000u;
        if ((rtc->time & 0x0F000000u) == 0x08000000u) {
            rtc->time &= 0xF0FFFFFFu;
            rtc->time |= 0x01000000u;
        }
        break;
    case 4: /* day */
        rtc->date += 0x00000001u;
        if ((rtc->date & 0x000000FFu) == 0x00000032u) {
            rtc->date &= 0xFFFFFF00u;
        } else if ((rtc->date & 0x0000000Fu) == 0x0000000Au) {
            rtc->date &= 0xFFFFFFF0u;
            rtc->date += 0x00000010u;
        }
        break;
    case 5: /* month */
        rtc->date += 0x00000100u;
        if ((rtc->date & 0x0000FF00u) == 0x00001300u) {
            rtc->date &= 0xFFFFFF00u;
            rtc->date |= 0x00000001u;
        } else if ((rtc->date & 0x00000F00u) == 0x00000A00u) {
            rtc->date &= 0xFFFFF0FFu;
            rtc->date += 0x00001000u;
        }
        break;
    case 6: /* year, low BCD byte */
        rtc->date += 0x00010000u;
        if ((rtc->date & 0x000F0000u) == 0x000A0000u) {
            rtc->date &= 0xFFF0FFFFu;
            rtc->date += 0x00100000u;
            if ((rtc->date & 0x00F00000u) == 0x00A00000u) {
                rtc->date &= 0xFF00FFFFu;
            }
        }
        break;
    default: /* year, high BCD byte: documented no-op */
        break;
    }
}

void rtc_write8(rtc_t *rtc, uint32_t offset, uint8_t value) {
    uint32_t reg_index = (offset / 4u) % 4u;
    uint32_t shift = (offset % 4u) * 8u;

    if (reg_index != 1u) { /* mode, time, date: plain store */
        uint32_t *reg = reg_ptr(rtc, reg_index);
        *reg = (*reg & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        return;
    }

    /* control: in the observed real use, only byte 0 has a function.
       A write of 1 while control holds 1 causes the increment, and then sets control to 0.
       A write while control holds 0 only stores the value.
       Real code writes 1 two times for one increment. See docs/hardware-notes.md. */
    if (shift == 0u) {
        if (rtc->control == 1u && value == 1u) {
            rtc_increment_field(rtc);
            rtc->control = 0;
        } else if (rtc->control == 0u) {
            rtc->control = value;
        }
    }
}

/* An unconditional cascade from seconds, to minutes, to hours, to the day of the week.
   This is the RTC automatic-advance logic of this emulator. It is not the logic that a write to the
   control register causes.
   This function does not cascade into the date at a day rollover.
   This is a gap from the earlier history of this codebase. No independent source explains it.
   No independent source gives the real date-rollover mechanism either. Thus this gap comes from
   earlier work. This project did not create it. */
static uint32_t bcd_to_bin(uint32_t bcd) {
    return (bcd >> 4) * 10u + (bcd & 0x0Fu);
}

static uint32_t bin_to_bcd(uint32_t bin) {
    return ((bin / 10u) << 4) | (bin % 10u);
}

/* The length of `month` (1-12) in the two-digit `year` of the RTC.

   The leap-year rule can only be year % 4. RTC_DATE has no century field. The century is in
   battery-backed kernel RAM, and only GetBcdDate supplies it (see rtc.h). Thus the hardware cannot
   apply the 100-year and 400-year exceptions. Year 00 is therefore a leap year. This result is
   correct for 2000 and incorrect for 1900. */
static uint32_t rtc_days_in_month(uint32_t month, uint32_t year) {
    static const uint8_t lengths[12] = {31u, 28u, 31u, 30u, 31u, 30u, 31u, 31u, 30u, 31u, 30u, 31u};
    if (month < 1u || month > 12u) {
        return 31u; /* a corrupt month field must not index out of bounds */
    }
    if (month == 2u && (year % 4u) == 0u) {
        return 29u;
    }
    return lengths[month - 1u];
}

/* Advances RTC_DATE by one day. The advance cascades into the month and the year.

   CONFIRMED on real hardware: the date does roll over at midnight. This emulator did not do this
   before. The cascade for each second stopped at the day of the week. Thus an emulated device stayed
   at the same date permanently, while its day of the week continued to advance.

   ALSO CONFIRMED on real hardware: the device applies leap years. This result also gives the month
   lengths. A design that rolls each month at 31 days cannot have a leap year, because the knowledge
   that February has 28 or 29 days is the same knowledge as the lengths of the other months. This
   code first used an assumption, because a date of "31 February" is clearly incorrect, and correct
   lengths are not. The assumption was correct.

   The leap-year rule is still an inference, not a measurement. But it can be only year % 4 (see
   rtc_days_in_month): the hardware has no century, thus it cannot apply the 100-year and 400-year
   exceptions.

   This code does not change byte 3. That byte is unused and unidentified. It is not a century
   field. */
static void rtc_advance_date(rtc_t *rtc) {
    uint32_t day = bcd_to_bin(rtc->date & 0xFFu);
    uint32_t month = bcd_to_bin((rtc->date >> 8) & 0xFFu);
    uint32_t year = bcd_to_bin((rtc->date >> 16) & 0xFFu);

    day++;
    if (day > rtc_days_in_month(month, year)) {
        day = 1u;
        month++;
        if (month > 12u) {
            month = 1u;
            year = (year + 1u) % 100u;
        }
    }
    rtc->date = (rtc->date & 0xFF000000u) | (bin_to_bcd(year) << 16) | (bin_to_bcd(month) << 8)
        | bin_to_bcd(day);
}

static void rtc_advance_second(rtc_t *rtc) {
    rtc->time += 0x00000001u;
    if ((rtc->time & 0x0000000Fu) != 0x0000000Au) {
        return;
    }
    rtc->time &= 0xFFFFFFF0u;
    rtc->time += 0x00000010u;
    if ((rtc->time & 0x000000FFu) != 0x00000060u) {
        return;
    }
    rtc->time &= 0xFFFFFF00u;
    rtc->time += 0x00000100u;
    if ((rtc->time & 0x00000F00u) != 0x00000A00u) {
        return;
    }
    rtc->time &= 0xFFFFF0FFu;
    rtc->time += 0x00001000u;
    if ((rtc->time & 0x0000FF00u) != 0x00006000u) {
        return;
    }
    rtc->time &= 0xFFFF00FFu;
    rtc->time += 0x00010000u;
    if ((rtc->time & 0x00FF0000u) == 0x00240000u) {
        rtc->time &= 0xFF00FFFFu;
        rtc->time += 0x01000000u;
        if ((rtc->time & 0x0F000000u) == 0x08000000u) {
            rtc->time &= 0xF0FFFFFFu;
            rtc->time |= 0x01000000u;
        }
        /* Midnight: the calendar moves too. Confirmed on real hardware. */
        rtc_advance_date(rtc);
    } else if ((rtc->time & 0x000F0000u) == 0x000A0000u) {
        rtc->time &= 0xFFF0FFFFu;
        rtc->time += 0x00100000u;
    }
}

void rtc_tick(rtc_t *rtc, struct intc *intc, uint32_t cycles) {
    rtc->tick_accumulator += cycles;
    for (;;) {
        int paused = (rtc->mode & 1u) != 0;
        uint32_t threshold = paused ? RTC_TICK_CYCLES_PAUSED : RTC_TICK_CYCLES_RUN;
        if (rtc->tick_accumulator < threshold) {
            break;
        }
        rtc->tick_accumulator -= threshold;
        rtc->int_line = !rtc->int_line;
        intc_set_line(intc, INT_RTC, rtc->int_line);
        /* One second for each full pulse, and not for each transition. The recorded rates are waveform
           rates, and the hardware makes two transitions for each pulse (see rtc.h). An advance at the
           falling edge uses exactly one of the two transitions. Thus the clock moves one time each
           second, while the line operates at 2Hz. */
        if (!paused && !rtc->int_line) {
            rtc_advance_second(rtc);
        }
    }
}
