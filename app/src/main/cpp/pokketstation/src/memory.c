/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "memory.h"

#include <stdio.h>
#include <string.h>

#include "clk.h"
#include "com.h"
#include "cpu.h"
#include "dac.h"
#include "flash.h"
#include "intc.h"
#include "iop.h"
#include "ir.h"
#include "lcd.h"
#include "rtc.h"
#include "timer.h"

/* Diagnostic flag. See psemu_intc_trace_enabled in intc.c for the same
   pattern. The `clktrace` flag in tools/inspect.c sets this flag, to
   record each real CLK_MODE write and DAC_CTRL write with its real PC
   (see docs/hardware-notes.md, "CLK_MODE"). This is permanent diagnostic
   equipment. */
int psemu_clk_trace_enabled = 0;

#ifdef PSEMU_TRACE_HOOKS
/* Diagnostic hook. While this hook is set, each bus read reports its
   address, its value, and the real PC that issued it. A snapshot
   comparison probe cannot see reads. tools/volume_probe.c needed this
   hook to find the RAM that the audio code of the BIOS reads.
   tools/datetime_probe.c needs it to see the registers that the BIOS
   tests before it resets the clock. Each callback filters by address.

   psemu_clk_trace_enabled above is only switched off during execution.
   This hook is different: the compiler removes it by default. It is on
   the busiest path in the emulator, one time for each byte of each bus
   read, and this includes opcode fetches. A test that is always false
   still costs approximately 20% on a fixed workload of 6.5M
   instructions. Only the psemu_trace library target defines
   PSEMU_TRACE_HOOKS (see core/CMakeLists.txt). Frontends link the usual
   psemu library, and they have no cost. */
void (*psemu_bus_read_trace_cb)(uint32_t addr, uint8_t value, uint32_t pc) = NULL;

/* The equivalent hook for writes. It answers a question that a snapshot
   comparison cannot answer: did a write occur? A comparison sees only the
   net change. Thus it reports nothing in three different conditions: an
   app never writes, an app writes the same value that the location
   already holds, or a layer between the app and the storage discards the
   write and gives no error. Those three conditions have very different
   causes, and without this hook they give the same evidence.

   This difference is not theoretical. The IR transmit path had this exact
   shape: an app wrote IRDA_DATA thousands of times, while a mode-bit test
   before the write discarded each one. Thus the app looked idle, but it
   operated correctly (see tx_emit_active in ir.c).
   tools/ir_probe.c uses this hook to identify the same three conditions
   for flash writes. That tool examines whether an app writes data to the
   PS1 save on the same memory card.

   This hook reports the value of the write, before region-specific code
   changes or discards the value. The compile-out rules are the same as
   the rules for the read hook: only the psemu_trace target defines
   PSEMU_TRACE_HOOKS. This hook is on the write path and not on the
   opcode-fetch path, thus it is much less expensive than the read hook.
   The gate is the same, for consistency. */
void (*psemu_bus_write_trace_cb)(uint32_t addr, uint8_t value, uint32_t pc) = NULL;
#endif

void psemu_bus_init(
    psemu_bus_t *bus, struct lcd *lcd, struct intc *intc, struct flash *flash, struct com *com, struct ir *ir,
    struct timer *timer, struct rtc *rtc, struct dac *dac, struct clk *clk, struct iop *iop) {
    memset(bus->ram, 0, sizeof(bus->ram));
    memset(bus->bios, 0, sizeof(bus->bios));
    bus->lcd = lcd;
    bus->intc = intc;
    bus->flash = flash;
    bus->com = com;
    bus->ir = ir;
    bus->timer = timer;
    bus->rtc = rtc;
    bus->dac = dac;
    bus->clk = clk;
    bus->iop = iop;
    bus->pending_cycles = 0;
    bus->ram_lock_addr = PSEMU_RAM_SIZE; /* no byte is locked */
    bus->ram_lock_value = 0;
}

/* The real per-region data-access wait-state cost. This is the "Memory
   Access Time for Data Read/Write" table. See docs/hardware-notes.md,
   "Memory access timing". A test confirms that this cost does not change
   with the access width (8, 16, or 32 bits). It also does not change
   between a sequential access and a non-sequential access. Each logical
   psemu_bus_read or psemu_bus_write call below calls this function one
   time. No call occurs for each byte. */
static uint32_t psemu_region_data_cycles(uint32_t addr) {
    if (addr < PSEMU_RAM_SIZE) {
        return 1u; /* WRAM */
    }
    /* FLASH_CTRL (the F_xxx bank-select, F_WAIT, and F_SN registers) gets
       the fast 1-cycle rate of WRAM. A test on real hardware confirms
       this, with the timing-benchmark app of this project. A loop that
       read FLASH_CTRL+0x100 (F_BANK_VAL[0]) 30000 times gave exactly the
       same elapsed-tick count as an identical loop that read WRAM.

       The available table gives only "WRAM (and SOME F_xxx ports)" for
       the fast rate. It does not name the applicable ports. An earlier
       assumption used the slow rate, the same as FLASH and BIOS. That
       assumption came from a disassembly of the source of an independent
       emulator. Real hardware now replaces that assumption. This agrees
       with the order of trust of this project: real hardware has more
       authority than a disassembly of an independent emulator. See the
       "Memory access timing" section of docs/hardware-notes.md, and
       docs/app-notes.md, for the full real-hardware result. */
    if (addr >= PSEMU_FLASH_CTRL_BASE && addr < PSEMU_FLASH_CTRL_BASE + FLASH_CTRL_SPAN) {
        return 1u;
    }
    /* VIRT_FLASH, PHYS_FLASH, XTRA_FLASH, BIOS, VRAM, and I/O get 2 cycles. */
    return 2u;
}

uint32_t psemu_region_fetch_cycles(uint32_t addr, int thumb) {
    if (addr < PSEMU_RAM_SIZE) {
        return 1u; /* WRAM: 1 cycle, for ARM and for Thumb */
    }
    if ((addr >= PSEMU_FLASH1_BASE && addr < PSEMU_FLASH1_BASE + PSEMU_FLASH_SIZE) ||
        (addr >= PSEMU_FLASH2_BASE && addr < PSEMU_FLASH2_BASE + PSEMU_FLASH_SIZE)) {
        return thumb ? 1u : 2u; /* FLASH: 2 cycles for ARM, 1 cycle for Thumb */
    }
    if (addr >= PSEMU_BIOS_BASE && addr < PSEMU_BIOS_BASE + PSEMU_BIOS_SIZE) {
        /* BIOS gets the same rate as FLASH. This includes the ARM and
           Thumb difference: 2 cycles for ARM, and 1 cycle for Thumb. It
           is not 2 cycles for both. A test on real hardware confirms
           this rate. The available opcode-fetch table gives only a "?"
           for BIOS, thus the table alone cannot give this value. The
           real kernel executes from BIOS ROM continuously, thus this
           cost must have a value.

           Real hardware: the pk_timing_bench homebrew app of this
           project timed a real BIOS ARM helper against a copy of that
           helper in WRAM. The BIOS version was approximately 1.2 times
           slower. This agrees with a 2-cycle ARM rate for BIOS against a
           1-cycle rate for WRAM. The app timed a real BIOS Thumb helper
           the same way, and the result was approximately 1.02 times.
           This agrees with a Thumb rate for BIOS that is equal to the
           1-cycle rate of WRAM, and equal to the Thumb rate of FLASH.
           The loop cost and the timer cost reduce each ratio from a pure
           2:1 signal. The same app measures the documented 2:1 rate of
           FLASH as approximately 1.7:1, thus that reduction is a known
           property of the method.

           The available data agrees with the same rate. The data-access
           table puts BIOS with VIRT_FLASH, PHYS_FLASH, and XTRA_FLASH in
           one slow-rate entry. The bus-width section gives "FLASH and
           BIOS ROM seem to be allowed to be read only in 16bit and 32bit
           units... RAM can be freely read/written in 8bit, 16bit, and
           32bit units". Both statements put BIOS with the bus behavior
           of FLASH, and never with the behavior of WRAM.

           See "Memory access timing" in docs/hardware-notes.md, and
           screens 1, 3, and 4 in pk_timing_bench/VERIFICATION.md. */
        return thumb ? 1u : 2u;
    }
    /* All other regions (VRAM and I/O) get 2 cycles. */
    return 2u;
}

/* Raw 8-bit access with no cost. This is the region-routing logic. Only
   this file calls this function, from the psemu_bus_* functions below.
   Those functions add the wait-state cost of the region one time for
   each logical call. Never call this function directly. If you do, the
   accesses have no cost. */
static uint8_t bus_read8_untraced(psemu_bus_t *bus, uint32_t addr) {
    if (addr < PSEMU_RAM_SIZE) {
        return bus->ram[addr];
    }
    if (addr >= PSEMU_BIOS_BASE && addr < PSEMU_BIOS_BASE + PSEMU_BIOS_SIZE) {
        return bus->bios[addr - PSEMU_BIOS_BASE];
    }
    /* FLASH1 is a banked window onto FLASH2. FLASH_CTRL selects the block
       that gives the offset (see docs/hardware-notes.md). */
    if (addr >= PSEMU_FLASH1_BASE && addr < PSEMU_FLASH1_BASE + PSEMU_FLASH_SIZE) {
        return flash1_read8(bus->flash, addr - PSEMU_FLASH1_BASE);
    }
    if (addr >= PSEMU_FLASH2_BASE && addr < PSEMU_FLASH2_BASE + PSEMU_FLASH_SIZE) {
        return flash_read8(bus->flash, addr - PSEMU_FLASH2_BASE);
    }
    if (addr >= PSEMU_FLASH_CTRL_BASE && addr < PSEMU_FLASH_CTRL_BASE + FLASH_CTRL_SPAN) {
        return flash_ctrl_read8(bus->flash, addr - PSEMU_FLASH_CTRL_BASE);
    }
    if (addr >= PSEMU_LCD_VRAM_BASE && addr < PSEMU_LCD_VRAM_BASE + LCD_VRAM_SIZE) {
        return lcd_read8(bus->lcd, addr - PSEMU_LCD_VRAM_BASE);
    }
    if (addr >= PSEMU_LCD_MODE_BASE && addr < PSEMU_LCD_MODE_BASE + LCD_MODE_REG_SPAN) {
        return lcd_mode_read8(bus->lcd, addr - PSEMU_LCD_MODE_BASE);
    }
    if (addr >= PSEMU_CLK_BASE && addr < PSEMU_CLK_BASE + CLK_REG_SPAN) {
        return clk_read8(bus->clk, addr - PSEMU_CLK_BASE);
    }
    if (addr >= PSEMU_RTC_BASE && addr < PSEMU_RTC_BASE + RTC_REG_SPAN) {
        return rtc_read8(bus->rtc, addr - PSEMU_RTC_BASE);
    }
    if (addr >= PSEMU_INTC_BASE && addr < PSEMU_INTC_BASE + INTC_REG_SPAN) {
        return intc_read8(bus->intc, addr - PSEMU_INTC_BASE);
    }
    if (addr >= PSEMU_COM_BASE && addr < PSEMU_COM_BASE + COM_REG_SPAN) {
        return (uint8_t)com_read(bus->com, bus->intc, addr - PSEMU_COM_BASE);
    }
    if (addr >= PSEMU_IR_BASE && addr < PSEMU_IR_BASE + IR_REG_SPAN) {
        return (uint8_t)ir_read(bus->ir, addr - PSEMU_IR_BASE);
    }
    if (addr >= PSEMU_TIMER_BASE && addr < PSEMU_TIMER_BASE + TIMER_REG_SPAN) {
        return timer_read8(bus->timer, addr - PSEMU_TIMER_BASE);
    }
    if (addr >= PSEMU_DAC_BASE && addr < PSEMU_DAC_BASE + DAC_REG_SPAN) {
        return dac_read8(bus->dac, addr - PSEMU_DAC_BASE);
    }
    if (addr >= PSEMU_IOP_BASE && addr < PSEMU_IOP_BASE + IOP_REG_SPAN) {
        return iop_read8(bus->iop, addr - PSEMU_IOP_BASE);
    }
    return 0;
}

#ifdef PSEMU_TRACE_HOOKS
/* Reads one time, then sends that same value to the trace hook. Several
   regions have side effects at a read. Thus this function must not read
   the value a second time for the trace. */
static uint8_t bus_read8_raw(psemu_bus_t *bus, uint32_t addr) {
    uint8_t value = bus_read8_untraced(bus, addr);
    if (psemu_bus_read_trace_cb) {
        psemu_bus_read_trace_cb(addr, value, psemu_debug_current_pc);
    }
    return value;
}
#else
#define bus_read8_raw bus_read8_untraced
#endif

/* Raw 8-bit write with no cost. See bus_read8_raw. */
static void bus_write8_raw(psemu_bus_t *bus, uint32_t addr, uint8_t value) {
#ifdef PSEMU_TRACE_HOOKS
    /* This callback occurs before the region dispatch below. Thus this
       code still reports a write that a region then discards. This is
       the function of the hook. See psemu_bus_write_trace_cb. */
    if (psemu_bus_write_trace_cb) {
        psemu_bus_write_trace_cb(addr, value, psemu_debug_current_pc);
    }
#endif
    if (addr < PSEMU_RAM_SIZE) {
        /* One compare, on the RAM-write path only. The read hook above is
           on the opcode-fetch path, but this compare is not. See
           ram_lock_addr in memory.h. */
        if (addr != bus->ram_lock_addr) {
            bus->ram[addr] = value;
        }
        return;
    }
    if (addr >= PSEMU_FLASH1_BASE && addr < PSEMU_FLASH1_BASE + PSEMU_FLASH_SIZE) {
        flash1_write8(bus->flash, addr - PSEMU_FLASH1_BASE, value);
        return;
    }
    if (addr >= PSEMU_FLASH2_BASE && addr < PSEMU_FLASH2_BASE + PSEMU_FLASH_SIZE) {
        flash_write8(bus->flash, addr - PSEMU_FLASH2_BASE, value);
        return;
    }
    if (addr >= PSEMU_FLASH_CTRL_BASE && addr < PSEMU_FLASH_CTRL_BASE + FLASH_CTRL_SPAN) {
        flash_ctrl_write8(bus->flash, addr - PSEMU_FLASH_CTRL_BASE, value);
        return;
    }
    if (addr >= PSEMU_LCD_VRAM_BASE && addr < PSEMU_LCD_VRAM_BASE + LCD_VRAM_SIZE) {
        lcd_write8(bus->lcd, addr - PSEMU_LCD_VRAM_BASE, value);
        return;
    }
    if (addr >= PSEMU_LCD_MODE_BASE && addr < PSEMU_LCD_MODE_BASE + LCD_MODE_REG_SPAN) {
        lcd_mode_write8(bus->lcd, addr - PSEMU_LCD_MODE_BASE, value);
        return;
    }
    if (addr >= PSEMU_CLK_BASE && addr < PSEMU_CLK_BASE + CLK_REG_SPAN) {
        if (psemu_clk_trace_enabled) {
            printf(
                "[clk trace] pc=0x%08X WRITE CLK_MODE (+0x%X) = 0x%02X\n", psemu_debug_current_pc,
                (unsigned)(addr - PSEMU_CLK_BASE), (unsigned)value);
        }
        clk_write8(bus->clk, addr - PSEMU_CLK_BASE, value);
        return;
    }
    if (addr >= PSEMU_RTC_BASE && addr < PSEMU_RTC_BASE + RTC_REG_SPAN) {
        rtc_write8(bus->rtc, addr - PSEMU_RTC_BASE, value);
        return;
    }
    if (addr >= PSEMU_INTC_BASE && addr < PSEMU_INTC_BASE + INTC_REG_SPAN) {
        intc_write8(bus->intc, addr - PSEMU_INTC_BASE, value);
        return;
    }
    if (addr >= PSEMU_COM_BASE && addr < PSEMU_COM_BASE + COM_REG_SPAN) {
        com_write(bus->com, bus->intc, addr - PSEMU_COM_BASE, value);
        return;
    }
    if (addr >= PSEMU_IR_BASE && addr < PSEMU_IR_BASE + IR_REG_SPAN) {
        ir_write(bus->ir, addr - PSEMU_IR_BASE, value);
        return;
    }
    if (addr >= PSEMU_TIMER_BASE && addr < PSEMU_TIMER_BASE + TIMER_REG_SPAN) {
        timer_write8(bus->timer, addr - PSEMU_TIMER_BASE, value);
        return;
    }
    if (addr >= PSEMU_DAC_BASE && addr < PSEMU_DAC_BASE + DAC_REG_SPAN) {
        if (psemu_clk_trace_enabled && addr == PSEMU_DAC_BASE) {
            printf(
                "[clk trace] pc=0x%08X WRITE DAC_CTRL = 0x%02X (enable=%d)\n", psemu_debug_current_pc,
                (unsigned)value, value & 1);
        }
        dac_write8(bus->dac, addr - PSEMU_DAC_BASE, value);
        return;
    }
    if (addr >= PSEMU_IOP_BASE && addr < PSEMU_IOP_BASE + IOP_REG_SPAN) {
        iop_write8(bus->iop, addr - PSEMU_IOP_BASE, value);
        /* Copy the sound-enable gate into the DAC directly. This gate and
           the enable bit of DAC_CTRL must both be set before audio
           plays. Confirmed against real hardware. See iop.h and dac.h. */
        dac_set_iop_muted(bus->dac, !iop_sound_enabled(bus->iop));
        return;
    }
}

/* The public accessors below apply the cost. Each one adds the
   wait-state cost of its region one time (see psemu_region_data_cycles
   and psemu_region_fetch_cycles), for each access width. It then calls
   the raw routing logic above. Only the opcode fetch in arm7tdmi_step
   uses the fetch functions. Each data access in an instruction handler
   (arm_exec.c and thumb_exec.c) uses the data-access functions. */

uint8_t psemu_bus_read8(psemu_bus_t *bus, uint32_t addr) {
    bus->pending_cycles += psemu_region_data_cycles(addr);
    return bus_read8_raw(bus, addr);
}

void psemu_bus_write8(psemu_bus_t *bus, uint32_t addr, uint8_t value) {
    bus->pending_cycles += psemu_region_data_cycles(addr);
    bus_write8_raw(bus, addr, value);
}

uint16_t psemu_bus_read16(psemu_bus_t *bus, uint32_t addr) {
    bus->pending_cycles += psemu_region_data_cycles(addr);
    return (uint16_t)(bus_read8_raw(bus, addr) | ((uint16_t)bus_read8_raw(bus, addr + 1) << 8));
}

uint32_t psemu_bus_read32(psemu_bus_t *bus, uint32_t addr) {
    bus->pending_cycles += psemu_region_data_cycles(addr);
    return (uint32_t)bus_read8_raw(bus, addr) | ((uint32_t)bus_read8_raw(bus, addr + 1) << 8) |
           ((uint32_t)bus_read8_raw(bus, addr + 2) << 16) | ((uint32_t)bus_read8_raw(bus, addr + 3) << 24);
}

void psemu_bus_write16(psemu_bus_t *bus, uint32_t addr, uint16_t value) {
    bus->pending_cycles += psemu_region_data_cycles(addr);
    bus_write8_raw(bus, addr, (uint8_t)value);
    bus_write8_raw(bus, addr + 1, (uint8_t)(value >> 8));
}

void psemu_bus_write32(psemu_bus_t *bus, uint32_t addr, uint32_t value) {
    bus->pending_cycles += psemu_region_data_cycles(addr);
    bus_write8_raw(bus, addr, (uint8_t)value);
    bus_write8_raw(bus, addr + 1, (uint8_t)(value >> 8));
    bus_write8_raw(bus, addr + 2, (uint8_t)(value >> 16));
    bus_write8_raw(bus, addr + 3, (uint8_t)(value >> 24));
}

uint16_t psemu_bus_fetch16(psemu_bus_t *bus, uint32_t addr) {
    bus->pending_cycles += psemu_region_fetch_cycles(addr, 1);
    return (uint16_t)(bus_read8_raw(bus, addr) | ((uint16_t)bus_read8_raw(bus, addr + 1) << 8));
}

uint32_t psemu_bus_fetch32(psemu_bus_t *bus, uint32_t addr) {
    bus->pending_cycles += psemu_region_fetch_cycles(addr, 0);
    return (uint32_t)bus_read8_raw(bus, addr) | ((uint32_t)bus_read8_raw(bus, addr + 1) << 8) |
           ((uint32_t)bus_read8_raw(bus, addr + 2) << 16) | ((uint32_t)bus_read8_raw(bus, addr + 3) << 24);
}
