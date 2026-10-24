/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_CLK_H
#define PSEMU_CLK_H

#include <stdint.h>

#define CLK_REG_SPAN 0x8u

/* CPU and timer clock speed control.
   Confirmed against real hardware behavior.

   A write to `mode` (bits 0-3, an index into CPU_FREQ below) programs the real oscillator rate of
   the ARM7 again.
   A read of `mode` also sets a "steady" bit (0x10). A real BIOS boot loop reads this bit before it
   continues.

   On real hardware, the timer rate, the RTC rate, and the DAC bit-bang rate all come from this same
   shared clock. All of them become faster and slower together.
   See clk_current_hz() and its use in psemu_run(). */
typedef struct clk {
    uint32_t mode;
    uint32_t control;
    uint32_t mode_write_scratch;
    uint32_t control_write_scratch;
} clk_t;

void clk_init(clk_t *clk);
uint8_t clk_read8(clk_t *clk, uint32_t offset);
void clk_write8(clk_t *clk, uint32_t offset, uint8_t value);

/* The effective CPU frequency in Hz, for the mode that is programmed at this time. */
uint32_t clk_current_hz(const clk_t *clk);

/* Bit 0 of `control` (+0x4) is a stop/standby request. It halts the CPU, and thus also all other
   parts that this oscillator clocks, until an external signal wakes the CPU. psemu_run does the
   stop. Its comment gives the sources that wake the CPU, and the peripherals that continue to
   operate.

   There are two separate claims here, at two different levels of evidence.

   THE BEHAVIOR IS CONFIRMED ON REAL HARDWARE. A real retail unit that operates the app below makes
   its screen blank approximately 37 seconds after the last button press. The unit then sleeps, and
   comes on again at the next button press. This emulator must stop the CPU at some point for this
   behavior to occur.

   THE APPLICABLE REGISTER IS INFERRED. It is not confirmed. This register has no documentation, and
   this project has no method to probe it directly. The app writes 1 here as the last step of a
   clear power-down sequence, in this order:

     IOP_STOP      = 0x62          sound and other IOP subsystems off
     INTC mask     = 0x200         RTC interrupt disabled
     LCD_MODE     &= ~0x48         DISON clear - display off
     CLK control   = 1             <- this register

   The app then executes a short delay loop and returns. No other step in that sequence can stop the
   CPU. If this emulator uses this write as the stop, the confirmed behavior occurs exactly.

   To make this register inert - which this emulator did before - is not a safe simplification. The
   app writes its idle countdown back only AFTER its sleep call returns. Thus a CPU that continues
   to run enters the same tick again, reads the same expired count, and calls sleep again. This
   recursion has no limit, at 28 bytes for each level, and the app has only 388 bytes of stack
   before its own globals. The recursion overruns the stack, corrupts a saved return address, and
   the CPU then executes data. See docs/hardware-notes.md, "CLK control (0x0B000004):
   stop/standby". */
int clk_stop_requested(const clk_t *clk);
void clk_clear_stop(clk_t *clk);

#endif
