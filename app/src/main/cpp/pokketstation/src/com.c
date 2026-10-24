/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "com.h"

#include <stdio.h>

#include "cpu.h" /* psemu_debug_current_pc, for the trace flag below */
#include "intc.h"

/* See com.h. This flag is off by default. tools/com_probe.c sets it. */
int psemu_com_trace_enabled = 0;

/* The value that goes out while the kernel gives no reply byte.
   COM_MODE bit 0 (Data Output Enable) stays clear in that condition. Thus the PS1 sees an idle line.
   An idle data line on this bus is high. */
#define COM_REPLY_IDLE 0xFFu

static const char *com_reg_name(uint32_t word_index) {
    switch (word_index) {
    case 0:
        return "COM_MODE";
    case 1:
        return "COM_STAT1";
    case 2:
        return "COM_DATA";
    case 4:
        return "COM_CTRL1";
    case 5:
        return "COM_STAT2";
    case 6:
        return "COM_CTRL2";
    default:
        return "COM_NONE";
    }
}

void com_init(com_t *com) {
    com->mode = 0u;
    com->stat1 = 0u;
    com->ctrl1 = 0u;
    com->ctrl2 = 0u;
    com->rx_data = 0u;
    com->tx_data = COM_REPLY_IDLE;
    com->tx_shifted = COM_REPLY_IDLE;
    com->rx_ready = 0;
    com->ack_asserted = 0;
    com->selected = 0;
    com->sel_drop_latch = 0;
    com->docked = 0;
}

/* A SIDE EFFECT OF A READ MUST OCCUR ON ONE BYTE LANE ONLY, AND THAT LANE MUST BE THE LANE THAT
   HOLDS THE BITS. One register here clears state at a read: COM_DATA takes the arrived byte. That
   value is in the low byte, thus the clear occurs only for shift == 0.

   This rule is necessary. psemu_bus_read32 (core/src/memory.c) makes four calls to this function,
   one for each byte lane, and it combines the four results with the | operator. C does not sequence
   the operands of that operator, thus a compiler can call the lanes in any order. A clear on every
   lane makes the result depend on that order, and the assembled word then loses the bit. */
uint32_t com_read(com_t *com, struct intc *intc, uint32_t offset) {
    uint32_t word_index = (offset / 4u) % 8u;
    uint32_t shift = (offset % 4u) * 8u;
    uint32_t value;

    switch (word_index) {
    case 0:
        value = com->mode;
        break;
    case 1:
        /* COM_STAT1 holds two flags that the kernel needs.

           BIT 1 REPORTS A RELEASE OF THE /SEL LINE. That release is the end of one command. The
           kernel waits for this bit after the last byte, at 0x040007B8 in the J110 revision.

           BIT 0 REPORTS AN ARRIVED BYTE. It gives the same condition as the Ready bit of COM_STAT2.
           The write path of the kernel polls this register in place of COM_STAT2, at 0x040015D6.

           A READ OF THIS REGISTER DOES NOT CLEAR BIT 1. The bit stays set for the full time that the
           PS1 holds the line released. com_set_selected clears it at the next hold. See
           test_a_second_command_answers in tests/bu_test.c for the evidence. */
        value = com->stat1 | (com->rx_ready ? 1u : 0u) | (com->sel_drop_latch ? COM_STAT1_ERROR : 0u);
        break;
    case 2:
        /* A read of COM_DATA takes the byte that the PS1 sent.

           THIS READ CLEARS THE READY BIT OF COM_STAT2. IT ALSO CLEARS THE COM INTERRUPT REQUEST.
           The kernel answers one byte for each interrupt. The interrupt lines of this emulator are
           level-triggered (see intc.h), thus INT_COM must clear here. Without this clear the FIQ
           handler starts again immediately, and it processes the remainder of the command inside one
           exchange.

           ONLY THE LOW BYTE LANE TAKES THE BYTE. See the note on side effects above. */
        value = com->rx_data;
        if (shift == 0u) {
            com->rx_ready = 0;
            intc_set_line(intc, INT_COM, 0);
        }
        break;
    case 4:
        value = com->ctrl1;
        break;
    case 5:
        value = com->rx_ready ? COM_STAT2_READY : 0u;
        break;
    case 6:
        value = com->ctrl2;
        break;
    default:
        /* The range at +0x0C and the range at +0x1C have no known register. The published register
           map records both ranges as zero. Thus this emulator has no basis to make register state
           for them. Both ranges read back as 0. A write to them has no effect. ir_read gives
           IRDA_MISC the same treatment. */
        value = 0u;
        break;
    }

    if (psemu_com_trace_enabled) {
        printf("[com trace] pc=0x%08X READ %s (+0x%02X) = 0x%02X (full=0x%08X)\n", psemu_debug_current_pc,
            com_reg_name(word_index), (unsigned)offset, (unsigned)((value >> shift) & 0xFFu), value);
    }
    return (value >> shift) & 0xFFu;
}

void com_write(com_t *com, struct intc *intc, uint32_t offset, uint32_t value) {
    uint32_t word_index = (offset / 4u) % 8u;
    uint32_t shift = (offset % 4u) * 8u;
    uint32_t byte = value & 0xFFu;
    uint32_t *reg;

    switch (word_index) {
    case 0:
        reg = &com->mode;
        break;
    case 2:
        reg = &com->tx_data;
        break;
    case 4:
        reg = &com->ctrl1;
        break;
    case 6:
        reg = &com->ctrl2;
        break;
    case 1:
    case 5:
        /* COM_STAT1 and COM_STAT2 are status registers. A write has no effect. */
        if (psemu_com_trace_enabled) {
            printf("[com trace] pc=0x%08X WRITE %s (+0x%02X) = 0x%02X (ignored, status)\n",
                psemu_debug_current_pc, com_reg_name(word_index), (unsigned)offset, (unsigned)byte);
        }
        return;
    default:
        /* See the same range in com_read. */
        if (psemu_com_trace_enabled) {
            printf("[com trace] pc=0x%08X WRITE %s (+0x%02X) = 0x%02X (ignored, reserved)\n",
                psemu_debug_current_pc, com_reg_name(word_index), (unsigned)offset, (unsigned)byte);
        }
        return;
    }

    *reg = (*reg & ~(0xFFu << shift)) | (byte << shift);

    if (psemu_com_trace_enabled) {
        printf("[com trace] pc=0x%08X WRITE %s (+0x%02X) = 0x%02X (full=0x%08X)\n", psemu_debug_current_pc,
            com_reg_name(word_index), (unsigned)offset, (unsigned)byte, *reg);
    }

    if (word_index == 0u && (com->mode & COM_MODE_ACK_LOW) != 0u) {
        /* The kernel drove /ACK LOW. That signal tells the PS1 two facts. This device accepted the
           byte, and this device gave its reply. The signal is the end of one byte exchange. This
           code records the event, and it does not follow the level.

           THE ACKNOWLEDGE ALSO CLEARS THE READY BIT OF COM_STAT2. The exchange is complete at this
           point, and the shift register then waits for 8 new bits. Ready reports the arrival of
           those bits, thus Ready must read 0 until they arrive.

           A read of COM_DATA is not sufficient on its own. The kernel handles a full command inside
           one FIQ, and it polls COM_STAT2 for each byte after the first byte. That poll is at
           0x04001592 in the J110 revision. The data phase of a command sends bytes and receives only
           dummy bytes, thus the kernel never reads COM_DATA during that phase. */
        com->ack_asserted = 1;
        com->rx_ready = 0;
        intc_set_line(intc, INT_COM, 0);
    }
}

void com_set_docked(com_t *com, struct intc *intc, int docked) {
    com->docked = docked ? 1 : 0;
    /* INT_IOP fires an interrupt on both the dock transition and the undock transition.
       intc_set_level_and_pulse latches HOLD on each edge so the IRQ handler runs in both
       directions. STATUS follows the live level. The IRQ handler acknowledges HOLD and then
       reads STATUS to determine direction: STATUS bit 11 = 1 means docked, 0 means undocked.
       The kernel also reads this live level during a transfer to detect an undock event while
       the transfer is in progress. See intc.h and INT_LEVEL_MASK. */
    intc_set_level_and_pulse(intc, INT_IOP, com->docked);
}

void com_set_selected(com_t *com, int selected) {
    int now = selected ? 1 : 0;
    if (com->selected && !now) {
        /* The PS1 released the line. This is the end of one command. */
        com->sel_drop_latch = 1;
    } else if (!com->selected && now) {
        /* The PS1 holds the line again. This is the start of the next command. */
        com->sel_drop_latch = 0;
    }
    com->selected = now;
}

void com_begin_transfer(com_t *com, struct intc *intc, uint8_t data_in) {
    /* The two directions of the exchange occur together. The byte of the PS1 moves in. The byte
       in the output register moves out. Copy that held byte now. The kernel writes the byte for the
       next exchange over it. See tx_data in com.h. */
    com->tx_shifted = com->tx_data;
    com->rx_data = data_in;
    com->rx_ready = 1;
    com->ack_asserted = 0;

    if (psemu_com_trace_enabled) {
        printf("[com trace] --- transfer begin, data_in=0x%02X, shifting out 0x%02X ---\n", (unsigned)data_in,
            (unsigned)(com->tx_shifted & 0xFFu));
    }

    /* INT_COM is FIQ source bit 6. The FIQ handler of the kernel processes this source. It does this
       before it calls the FIQ callback of an app. See intc.h and the top comment of com.h. */
    intc_set_line(intc, INT_COM, 1);
}

int com_transfer_acked(const com_t *com) {
    return com->ack_asserted;
}

uint8_t com_take_reply(const com_t *com) {
    return (uint8_t)(com->tx_shifted & 0xFFu);
}

void com_end_transfer(com_t *com, struct intc *intc) {
    if (psemu_com_trace_enabled) {
        printf("[com trace] --- transfer end, acked=%d reply=0x%02X ---\n", com->ack_asserted,
            (unsigned)com_take_reply(com));
    }
    intc_set_line(intc, INT_COM, 0);
    com->rx_ready = 0;
    com->ack_asserted = 0;
}
