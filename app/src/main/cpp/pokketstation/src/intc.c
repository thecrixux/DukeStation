/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "intc.h"

#include <stdio.h>

#include "cpu.h"

/* Diagnostic flag. See psemu_debug_current_pc in cpu.h.
   This flag is off by default, thus it has no cost in normal use.
   The `intctrace` flag in tools/inspect.c sets it, to record each real INTC access with its real PC.
   A static disassembly cannot tell ARM code from Thumb code, because it does not track the mode during
   execution.
   This flag is permanent diagnostic equipment. It is not part of one investigation. */
int psemu_intc_trace_enabled = 0;

static const char *offset_name(uint32_t word_index) {
    switch (word_index) {
    case 0:
        return "hold";
    case 1:
        return "status";
    case 2:
        return "enable";
    case 3:
        return "mask";
    default:
        return "ack";
    }
}

void intc_init(intc_t *intc) {
    intc->hold = 0;
    intc->status = 0;
    intc->enable = 0;
    intc->mask = 0;
    intc->enable_write_scratch = 0;
    intc->mask_write_scratch = 0;
    intc->ack_write_scratch = 0;
}

uint8_t intc_read8(intc_t *intc, uint32_t offset) {
    uint32_t word_index = offset / 4u;
    uint32_t shift = (offset % 4u) * 8u;
    uint32_t value;

    switch (word_index) {
    case 0:
        value = intc->hold;
        break;
    case 1:
        value = intc->status;
        break;
    case 2:
        value = intc->enable;
        break;
    default: /* on real hardware, mask (+0xC) and acknowledge (+0x10) read back as 0 */
        value = 0;
        break;
    }
    if (psemu_intc_trace_enabled) {
        printf(
            "[intc trace] pc=0x%08X READ %s (+0x%X) byte@shift%u = 0x%02X (full=0x%08X)\n",
            psemu_debug_current_pc, offset_name(word_index), (unsigned)offset, (unsigned)shift,
            (unsigned)((value >> shift) & 0xFFu), value);
    }
    return (uint8_t)(value >> shift);
}

static void accumulate_byte(uint32_t *scratch, uint32_t shift, uint8_t value) {
    *scratch = (*scratch & ~(0xFFu << shift)) | ((uint32_t)value << shift);
}

void intc_write8(intc_t *intc, uint32_t offset, uint8_t value) {
    uint32_t word_index = offset / 4u;
    uint32_t shift = (offset % 4u) * 8u;

    if (psemu_intc_trace_enabled) {
        printf(
            "[intc trace] pc=0x%08X WRITE %s (+0x%X) byte@shift%u = 0x%02X\n", psemu_debug_current_pc,
            offset_name(word_index), (unsigned)offset, (unsigned)shift, (unsigned)value);
    }

    switch (word_index) {
    case 0: /* hold: an invalid write on real hardware. It has no effect. */
    case 1: /* status: an invalid write on real hardware. It has no effect. */
        break;
    case 2: /* enable: OR the bits in after a full 32-bit store completes */
        accumulate_byte(&intc->enable_write_scratch, shift, value);
        if (shift == 24u) {
            intc->enable |= intc->enable_write_scratch;
        }
        break;
    case 3: /* mask: clear the applicable enable bits, and latch the mask value */
        accumulate_byte(&intc->mask_write_scratch, shift, value);
        if (shift == 24u) {
            intc->enable &= ~intc->mask_write_scratch;
            intc->mask = intc->mask_write_scratch;
        }
        break;
    default: /* acknowledge (+0x10): clear the applicable bits from hold, and from status except the live-level bits */
        accumulate_byte(&intc->ack_write_scratch, shift, value);
        if (shift == 24u) {
            intc->hold &= ~intc->ack_write_scratch;
            /* The INT_LEVEL_MASK bits are a continuous signal level. They are not a latched
               request. An acknowledge must not clear them. See the comment on INT_LEVEL_MASK in
               intc.h. */
            intc->status &= ~(intc->ack_write_scratch & ~INT_LEVEL_MASK);
        }
        break;
    }
}

void intc_set_line(intc_t *intc, uint32_t line, int state) {
    if (line == 0) {
        return;
    }
    if (state) {
        /* Each asserted source latches into `hold`.
           This causes real IRQ delivery, through hold & enable & INT_IRQ_MASK.
           The STATUS_MASK bits (the buttons and the RTC) also latch into `status`, for a direct
           read.

           History: this code first put the STATUS_MASK bits only into `status`, and never into
           `hold`. A disassembly of the real BIOS confirmed that this was incorrect.
           The top-level IRQ handler of the BIOS tests `hold & enable & 0x200` (RTC).
           The periodic callback that the BIOS installs tests `hold & 1` (the Action button).
           Both tests reach real handlers: the RTC acknowledge, and the day-rollover data (see
           docs/hardware-notes.md). Those handlers never executed with the earlier status-only
           routing.
           With that routing, the buttons and the RTC could never deliver an interrupt. Code could
           see them only through a direct read of `status`. */
        intc->hold |= line;
        intc->status |= line & INT_STATUS_MASK;
    } else {
        /* History: an earlier version made only STATUS follow the de-assertion here.
           The reasoning was that INT_INPUT ("Raw Interrupt Signal Levels") is different from
           INT_LATCH ("Interrupt Request Flags"), and that the real RTC handler acknowledges its own
           bit.

           A trace of real hardware showed that this reasoning was incorrect. The real button-action
           callback, at 0x04003784, never acknowledges bit 0. A permanent latch of `hold` after a
           button release caused the CPU to enter the IRQ handler again at almost each instruction
           after one press. One test measured 559034 re-entries in 20 million instructions. A real
           device that a person can use does not operate this way.

           The buttons clear `hold` at release, the same as `status`.
           Only the real RTC handler also sends an acknowledge.
           This has no effect either way, because an acknowledge already clears `hold` and
           `status`. */
        intc->status &= ~line;
        intc->hold &= ~line;
    }
}

uint32_t intc_get_line(intc_t *intc, uint32_t line) {
    return intc->status & line;
}

void intc_clear_hold_only(intc_t *intc, uint32_t line) {
    intc->hold &= ~line;
}

void intc_clear_status_only(intc_t *intc, uint32_t line) {
    intc->status &= ~line;
}

void intc_set_level_and_pulse(intc_t *intc, uint32_t line, int level) {
    if (line == 0) {
        return;
    }
    /* Each edge latches an interrupt request, for both directions.
       See the comment on this function in intc.h. */
    intc->hold |= line;
    if (level) {
        intc->status |= line & INT_STATUS_MASK;
    } else {
        intc->status &= ~line;
    }
}

int intc_irq_asserted(intc_t *intc) {
    return (intc->hold & intc->enable & INT_IRQ_MASK) != 0;
}

int intc_fiq_asserted(intc_t *intc) {
    return (intc->hold & intc->enable & INT_FIQ_MASK) != 0;
}
