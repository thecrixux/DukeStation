/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_INTERNAL_H
#define PSEMU_INTERNAL_H

#include "clk.h"
#include "com.h"
#include "cpu.h"
#include "dac.h"
#include "flash.h"
#include "intc.h"
#include "iop.h"
#include "ir.h"
#include "lcd.h"
#include "memory.h"
#include "psemu/psemu.h"
#include "rtc.h"
#include "timer.h"

struct psemu {
    arm7tdmi_t cpu;
    psemu_bus_t bus;
    lcd_t lcd;
    intc_t intc;
    flash_t flash;
    com_t com;
    ir_t ir;
    psemu_timer_t timer;
    rtc_t rtc;
    dac_t dac;
    clk_t clk;
    iop_t iop;
    /* The fractional carry between psemu_run() calls.
       It converts real elapsed time back into the fixed PSEMU_ASSUMED_CPU_HZ reference
       rate that Timer, RTC, and DAC use. See the comment on psemu_run for more data. */
    double real_time_cycle_carry;
    uint32_t buttons; /* the last PSEMU_BUTTON_* bitmask, for edge detection into the INTC */
    int has_bios;
    /* app_running shows whether a dispatched app owns WRAM. See psemu_app_running.
       app_exec_idle_cycles counts the cycles that executed after the last instruction
       fetch from the FLASH1 window. It increases only while app_running is set. */
    int app_running;
    uint32_t app_exec_idle_cycles;
};

#endif
