/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_INTC_H
#define PSEMU_INTC_H

#include <stdint.h>

#define INTC_REG_SPAN 0x14u

/* The real PocketStation interrupt controller and its sources.
   A disassembly of a real BIOS corrected this data independently. See docs/hardware-notes.md.

   Registers at 0x0A000000:
   - hold (+0x0): read-only to software. Real hardware treats a write here as invalid.
   - status (+0x4): also read-only.
   - enable (+0x8): a write ORs bits into the register.
   - mask (+0xC): a write ANDs the applicable bits out of enable.
   - acknowledge (+0x10): write-only. It clears the applicable bits from hold and from status.

   Each asserted source latches into HOLD. ENABLE gates HOLD, and HOLD then operates the IRQ line and
   the FIQ line of the CPU.
   Button presses and the RTC tick (the bits in INT_STATUS_MASK) also latch into STATUS.
   STATUS lets code read a source directly, with no effect on the interrupt-delivery state. The RTC
   wait-for-pulse loop is one example.

   History: the interrupt-routing logic of this codebase first put STATUS_MASK bits only into
   `status`, and never into `hold`. A disassembly of a real BIOS showed that this was incorrect.
   The top-level IRQ handler of the BIOS tests `hold & enable & 0x200` (RTC).
   The periodic callback that the BIOS installs tests `hold & 1` (the Action button).
   Both tests reach real handlers, and a trace confirms this. Those handlers could never execute with
   the earlier status-only routing.
   Real hardware asserts these sources into both registers. This emulator now does the same. */
#define INT_BTN_ACTION 0x00000001u
#define INT_BTN_RIGHT 0x00000002u
#define INT_BTN_LEFT 0x00000004u
#define INT_BTN_DOWN 0x00000008u
#define INT_BTN_UP 0x00000010u
#define INT_UNKNOWN 0x00000020u
#define INT_COM 0x00000040u
#define INT_TIMER0 0x00000080u
#define INT_TIMER1 0x00000100u
#define INT_RTC 0x00000200u
#define INT_BATTERY 0x00000400u
#define INT_IOP 0x00000800u
#define INT_IRDA 0x00001000u
#define INT_TIMER2 0x00002000u
#define INT_IRQ_MASK 0x00001FBFu
#define INT_FIQ_MASK 0x00002040u
/* The sources whose live signal level STATUS shows.
   The name of that register is INT_INPUT, "Raw Interrupt Signal Levels", at 0x0A000004.
   See docs/hardware-notes.md, "Buttons".
   The buttons (bits 0-4) and the RTC (bit 9) were confirmed first.
   INT_IRDA (bit 12) came later, from a disassembly of the IR receive handler of a real app.
   That handler acknowledges the interrupt. It then reads STATUS, isolates bit 12, and compares that
   live level against the level that it expects. It stops immediately if the two levels differ.
   While bit 12 was absent from this mask, STATUS bit 12 always read back as 0.
   Thus that comparison could never succeed, and no IR transfer could decode.
   See apply_rx_level in core/src/ir.c, and docs/hardware-notes.md, "IR / IR Link".
   INT_IOP (bit 11) is the docking sense, and it came last. A published register map names this bit
   in INT_INPUT directly: "IRQ Docked (0=Undocked, 1=Docked to PSX)". That map also records a
   necessary read of this live level during a communication transfer. That read finds an undock event
   while the transfer is in progress. Only a level in STATUS can supply that reading.
   See com_set_docked in core/src/com.c, and docs/hardware-notes.md, "Communication port". */
#define INT_STATUS_MASK 0x00001A1Fu

/* The sources whose STATUS bit is a continuous signal level, and not a latched request.
   An acknowledge write does NOT clear these bits.
   A disassembly of the INT_IRDA handler of a real app confirms this behavior for IR.
   That handler acknowledges INT_IRDA. Only then does it read STATUS to sample the live line level.
   That order is correct only if an acknowledge does not change the level. If an acknowledge changes
   the level, the handler always reads the result of its own acknowledge, which is 0, in place of the
   real signal. This is what occurred before this mask was added.
   The buttons are in this mask for the same reason. docs/hardware-notes.md, "Buttons", gives that
   `status` follows the live button level for code that reads the register directly. An acknowledge
   cleared that level before. Thus a button that the user still held read back as released, until
   something pressed the button again.
   The confirmed real-hardware finding about buttons applies to HOLD, not to STATUS. HOLD is a
   momentary edge pulse for each press. intc_clear_hold_only and psemu_set_buttons do this, and
   test_button_hold_pulses_not_sustained tests it. That finding gives no data about STATUS, and this
   mask does not change it.
   A real app depends on the live level. pk_timing_bench holds Action to open its exit prompt, and
   counts 75000 sequential reads of STATUS to detect the hold. Only a level that continues after an
   acknowledge can accumulate that count.
   INT_IOP is in this mask for the same reason. Docking is a continuous physical condition. It is not
   an event. A device that stays in the connector stays docked. The kernel acknowledges the source at
   the transition. It then reads the level again to find the new direction. An acknowledge that
   clears the level makes each dock event read back as an undock event. */
#define INT_LEVEL_MASK                                                                                             \
    (INT_IRDA | INT_IOP | INT_BTN_ACTION | INT_BTN_RIGHT | INT_BTN_LEFT | INT_BTN_DOWN | INT_BTN_UP)

typedef struct intc {
    uint32_t hold;
    uint32_t status;
    uint32_t enable;
    uint32_t mask;
    /* Byte-write accumulators.
       Real code always does a full 32-bit store.
       This emulator applies the effect (an OR into enable, and the equivalent operations) when the
       highest byte of that store arrives. */
    uint32_t enable_write_scratch;
    uint32_t mask_write_scratch;
    uint32_t ack_write_scratch;
} intc_t;

void intc_init(intc_t *intc);
uint8_t intc_read8(intc_t *intc, uint32_t offset);
void intc_write8(intc_t *intc, uint32_t offset, uint8_t value);

/* TEMPORARY diagnostic flag. See intc.c. */
extern int psemu_intc_trace_enabled;

/* Sets or clears an interrupt source (see the INT_* values above).
   It routes the source to STATUS or to HOLD, as INT_STATUS_MASK gives. This is the same
   interrupt-routing logic that real hardware uses.
   A call with line = 0 does nothing.
   intc_irq_asserted and intc_fiq_asserted always calculate the asserted state when a caller asks
   for it. Real hardware has a separate "recalculate" step, but this emulator does not. */
void intc_set_line(intc_t *intc, uint32_t line, int state);
uint32_t intc_get_line(intc_t *intc, uint32_t line);

/* Clears `line` from HOLD only. It does not change STATUS.
   Use this function for sources whose HOLD pulse must show only the initial edge, and not a
   continuous level.

   See psemu_set_buttons in psemu.c for the reason that buttons need this function.
   A real BIOS callback uses `hold` to select the source to service.
   If the hold bit of a held button never clears, that button permanently starves each other source
   that the callback tests later in the same chain.

   A difference from real hardware confirms this: the button-action branch is before the RTC test in
   the callback. A hold bit that stays set blocks the RTC redraw operations while the user holds the
   button. Real hardware continues to redraw the screen, and acts only at button release. */
void intc_clear_hold_only(intc_t *intc, uint32_t line);

/* Follows the live signal level of `line` in STATUS.
   It also latches an interrupt request in HOLD at each call, for both level directions.
   IR receive needs this exact division of function, and intc_set_line cannot give it.
   The handler must execute at both edges of a pulse, because that is how the handler measures the
   pulse width.
   STATUS must continue to report the real line level, because the handler tests that level.
   A call to intc_set_line with state = 0 clears HOLD and does not cause the interrupt. */
void intc_set_level_and_pulse(intc_t *intc, uint32_t line, int level);

/* Clears `line` from STATUS only. It does not change HOLD.
   intc_clear_hold_only does the inverse.
   Use it for a source whose signal goes away before software sends an acknowledge for its latched
   request. The level must go, and the request must stay.
   See ir_tick in ir.c for the source that needs this function. */
void intc_clear_status_only(intc_t *intc, uint32_t line);

int intc_irq_asserted(intc_t *intc);
int intc_fiq_asserted(intc_t *intc);

#endif
