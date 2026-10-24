/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_MEMORY_H
#define PSEMU_MEMORY_H

#include <stdint.h>

#include "psemu/psemu.h"

#define PSEMU_RAM_BASE 0x00000000u
/* PSEMU_RAM_SIZE is public and is in psemu/psemu.h (included above). A frontend needs the value
   to expose RAM to its host. All other data in this map is internal. */
#define PSEMU_FLASH1_BASE 0x02000000u
#define PSEMU_BIOS_BASE 0x04000000u
#define PSEMU_FLASH_CTRL_BASE 0x06000000u
#define PSEMU_FLASH2_BASE 0x08000000u
/* Confirmed against real hardware: the interrupt controller (see
   intc.h). hold (+0x0), status (+0x4, buttons and RTC), enable (+0x8),
   mask (+0xC), acknowledge (+0x10). */
#define PSEMU_INTC_BASE 0x0A000000u
/* Confirmed against real hardware: 3 independent timers (see timer.h). */
#define PSEMU_TIMER_BASE 0x0A800000u
/* Confirmed against real hardware: CLK_MODE, the CPU and timer clock
   speed control (see clk.h). A real BIOS boot loop reads this register
   (LDR, TST #0x10, BEQ) before it writes to flash control. The loop
   waits for the clock to become stable after a speed change. */
#define PSEMU_CLK_BASE 0x0B000000u
/* Confirmed against real hardware: the real-time clock (see rtc.h). */
#define PSEMU_RTC_BASE 0x0B800000u
/* The communication port to a PS1, through the memory card connector
   (see com.h). COM_MODE (+0x0), COM_STAT1 (+0x4), COM_DATA (+0x8),
   COM_CTRL1 (+0x10), COM_STAT2 (+0x14), COM_CTRL2 (+0x18). A published
   register map is the source of this layout. That map marks the function
   of several bits as unknown. */
#define PSEMU_COM_BASE 0x0C000000u
#define PSEMU_IR_BASE 0x0C800000u
/* Confirmed against real hardware: LCD_MODE (+0x0, which holds DISON,
   ROT, and the draw mode) and LCD_CAL (+0x4). See lcd.h. */
#define PSEMU_LCD_MODE_BASE 0x0D000000u
#define PSEMU_LCD_VRAM_BASE 0x0D000100u
/* Confirmed against real hardware: IOP power control (see iop.h).
   IOP_CTRL (+0x0), IOP_STOP/IOP_STAT (+0x4), IOP_START (+0x8),
   IOP_DATA (+0xC). */
#define PSEMU_IOP_BASE 0x0D800000u
/* Confirmed against real hardware: the audio DAC (see dac.h). */
#define PSEMU_DAC_BASE 0x0D800010u

struct lcd;
struct intc;
struct flash;
struct com;
struct ir;
struct timer;
struct rtc;
struct dac;
struct clk;
struct iop;

typedef struct psemu_bus {
    uint8_t ram[PSEMU_RAM_SIZE];
    uint8_t bios[PSEMU_BIOS_SIZE];
    struct lcd *lcd;
    struct intc *intc;
    struct flash *flash;
    struct com *com;
    struct ir *ir;
    struct timer *timer;
    struct rtc *rtc;
    struct dac *dac;
    struct clk *clk;
    struct iop *iop;
    /* A running total of the real-hardware wait-state cycles that bus
       accesses charge during the current instruction step.
       arm7tdmi_step sets this value to 0 before the fetch operation and
       the execute operation. It then reads the value back as the true
       cost of that step. See "Memory access timing" in
       docs/hardware-notes.md. */
    uint32_t pending_cycles;
    /* One RAM byte that stays read-only to emulated code. The value
       PSEMU_RAM_SIZE means "no byte is locked". This value is a sentinel
       that no RAM address can equal. Thus the guard in bus_write8_raw is
       one compare that never matches while the function is off.

       This lock is necessary because a frontend cannot hold a BIOS-owned
       RAM setting if it writes the setting again between frames. The
       BIOS clears RAM early in its boot (pc 0x04000060, at approximately
       instruction 696), and the boot sound is complete at the end of
       that same frame. Thus a write for each frame gets no opportunity
       between the two events. See psemu_set_volume_override, and
       docs/hardware-notes.md, "System sound volume setting".

       This lock blocks only the writes that emulated code issues.
       psemu_reset and the settings writers of the core write to
       bus->ram directly. */
    uint32_t ram_lock_addr;
    uint8_t ram_lock_value;
} psemu_bus_t;

void psemu_bus_init(
    psemu_bus_t *bus, struct lcd *lcd, struct intc *intc, struct flash *flash, struct com *com, struct ir *ir,
    struct timer *timer, struct rtc *rtc, struct dac *dac, struct clk *clk, struct iop *iop);

uint8_t psemu_bus_read8(psemu_bus_t *bus, uint32_t addr);
uint16_t psemu_bus_read16(psemu_bus_t *bus, uint32_t addr);
uint32_t psemu_bus_read32(psemu_bus_t *bus, uint32_t addr);
void psemu_bus_write8(psemu_bus_t *bus, uint32_t addr, uint8_t value);
void psemu_bus_write16(psemu_bus_t *bus, uint32_t addr, uint16_t value);
void psemu_bus_write32(psemu_bus_t *bus, uint32_t addr, uint32_t value);

/* The opcode-fetch equivalents of read16 and read32. Only arm7tdmi_step
   uses these functions. They charge the real per-region "Memory Access
   Time for Opcode Fetch" table in place of the data-access table (see
   psemu_region_fetch_cycles). In all other respects they are the same as
   read16 and read32. */
uint16_t psemu_bus_fetch16(psemu_bus_t *bus, uint32_t addr);
uint32_t psemu_bus_fetch32(psemu_bus_t *bus, uint32_t addr);

/* The real per-region opcode-fetch wait-state cost. This is the
   documented "Memory Access Time for Opcode Fetch" table. This function
   is public so that arm_exec.c, thumb_exec.c, and cpu.c can charge
   pipeline-refill fetches (branches, PC writes, and exception entry) the
   same way that psemu_bus_fetch16 and psemu_bus_fetch32 do internally.
   See docs/hardware-notes.md, "Memory access timing", for the table and
   its confirmed BIOS opcode fetch. */
uint32_t psemu_region_fetch_cycles(uint32_t addr, int thumb);

/* TEMPORARY diagnostic flag. See memory.c. */
extern int psemu_clk_trace_enabled;

/* Diagnostic hook: this callback occurs at each bus read while it is not
   NULL. It is compiled in only for the psemu_trace library target,
   because it has a cost on the hot path. See memory.c. */
#ifdef PSEMU_TRACE_HOOKS
extern void (*psemu_bus_read_trace_cb)(uint32_t addr, uint8_t value, uint32_t pc);
/* The equivalent hook for writes. It reports all attempted writes. This
   includes writes that a region then discards, which a snapshot
   comparison cannot tell from no write at all. See memory.c. */
extern void (*psemu_bus_write_trace_cb)(uint32_t addr, uint8_t value, uint32_t pc);
#endif

#endif
