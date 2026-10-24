/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_COM_H
#define PSEMU_COM_H

#include <stdint.h>

struct intc;

#define COM_REG_SPAN 0x20u

/* The PocketStation communication port. This block is the link to a PS1. The link goes through the
   memory card connector. A PocketStation is a memory card in that connector. This block holds the
   bytes of that connection.

   Register layout, at 0x0C000000:

   | Offset | Register  | Contents |
   |---|---|---|
   | +0x00 | COM_MODE  | bit0 Data Output Enable. bit1 /ACK Output Level (1 = drive LOW). bit2 unknown. |
   | +0x04 | COM_STAT1 | bit0 a byte arrived. bit1 the PS1 released /SEL. See selected below. |
   | +0x08 | COM_DATA  | bits 0 to 7. A read gets the byte from the PS1. A write sends a byte to the PS1. |
   | +0x10 | COM_CTRL1 | bit0 and bit1 unknown. The observed values are 0, 2, and 3. |
   | +0x14 | COM_STAT2 | bit0 Ready (0 = Busy, 1 = Ready). The hardware sets the bit after 8 bits. |
   | +0x18 | COM_CTRL2 | bit0 and bit1 unknown. The observed values are 1 and 3. |

   A published register map is the source of this layout. That map is community reverse engineering.
   It is not a manufacturer specification. The map marks the function of the CTRL1 bits and the CTRL2
   bits as unknown. It gives only the values that it observed. This file uses the same order of trust
   as each other peripheral of this emulator. See docs/hardware-notes.md.

   The two COM_STAT1 bits above do not come from that map. The map names bit 1 "Error flag", and it
   marks bit 0 as unknown. A trace of a real BIOS gives both meanings. See tools/com_probe.c.

   BIT 1 IS A LEVEL. It stays set for the full time that the PS1 holds the /SEL line released. A read
   of COM_STAT1 does not clear it, and com_set_selected clears it at the next hold. A bit that clears
   at a read stops the kernel in its end-of-command wait. The kernel then answers one command, and
   every second command after it. See test_a_second_command_answers in tests/bu_test.c, and "The /SEL
   line" in docs/hardware-notes.md.

   THE PROTOCOL IS NOT IN THIS FILE. The kernel of the real BIOS holds the protocol. A COM interrupt
   is FIQ source bit 6 (INT_COM, see intc.h). The FIQ handler of the kernel processes that source. It
   does this before it calls the FIQ callback of an app. That handler reads the incoming byte. It
   then selects a reply and writes the reply back. Thus this file models only the byte path and the
   handshake lines.

   The kernel gives each command. The memory card commands are 0x52 Read Sector, 0x53 Get ID, and
   0x57 Write Sector. The PocketStation commands are 0x50, and 0x58 to 0x5F.

   This division is necessary. The commands 0x5B and 0x5C execute a function number. The numbers 0x80
   to 0xFF resolve through a function table in the header of the app file. Only the app gives that
   code. Thus no protocol code outside the emulated machine can answer those commands.

   THE DOCKING SIGNAL IS SEPARATE FROM THIS BLOCK. INT_IOP (bit 11, see intc.h) is the docking sense.
   A value of 0 is undocked. A value of 1 is docked to a PS1. The IRQ handler of the kernel uses that
   one source for both directions of the transition. com_set_docked drives the signal. */

#define COM_MODE_OFFSET 0x00u
#define COM_STAT1_OFFSET 0x04u
#define COM_DATA_OFFSET 0x08u
#define COM_CTRL1_OFFSET 0x10u
#define COM_STAT2_OFFSET 0x14u
#define COM_CTRL2_OFFSET 0x18u

#define COM_MODE_OUT_ENABLE (1u << 0)
#define COM_MODE_ACK_LOW (1u << 1)
#define COM_MODE_EXPECT_CMD (1u << 2)

#define COM_STAT1_ERROR (1u << 1)
#define COM_STAT2_READY (1u << 0)

typedef struct com {
    uint32_t mode;
    uint32_t stat1;
    uint32_t ctrl1;
    uint32_t ctrl2;

    /* rx_data is the byte from the PS1. A read of COM_DATA gets this value.
       tx_data is the output holding register. A write to COM_DATA sets this value.

       THE OUTPUT IS ONE BYTE BEHIND THE INPUT. This block is a shift register. One exchange moves
       the byte of the PS1 in. The same exchange moves the held byte out. Thus the PS1 receives the
       byte of exchange N during exchange N+1. The kernel writes 0xFF into this register at
       initialization. That write gives the first exchange a byte to send.

       A trace of the kernel confirms this behavior. During the Get ID command, the kernel writes
       FLAG while it processes the 0x81 byte. It writes 0x5A while it processes the 0x53 byte. A
       published command table gives the reply to 0x81 as "N/A". It gives the reply to 0x53 as FLAG.
       Only a shift register agrees with both facts. See tools/com_probe.c. */
    uint32_t rx_data;
    uint32_t tx_data;
    uint32_t tx_shifted; /* the byte that goes out during the exchange in progress */

    /* The state of one byte exchange. com_begin_transfer sets rx_ready. It also copies tx_data into
       tx_shifted and clears ack_asserted. The FIQ handler of the kernel then answers. */
    int rx_ready;     /* COM_STAT2 bit 0. A byte arrived. The kernel did not read the byte yet. */
    int ack_asserted; /* The kernel drove /ACK LOW through COM_MODE bit 1. */

    /* The /SEL line of the connector. The PS1 holds this line for the full duration of one command.
       It releases the line between commands.

       selected is the level of that line. sel_drop_latch records a release since the last read of
       COM_STAT1. That latch is COM_STAT1 bit 1.

       THE KERNEL NEEDS THIS SIGNAL TO END A COMMAND. After the last byte, the kernel waits at
       0x040007B8 in the J110 revision. It polls COM_STAT1 there. Nothing else releases that wait. A
       test against a real dump confirms bit 1, and it rejects each of bits 2 to 7. See
       tools/com_probe.c, the selbit mode.

       The published register map names bit 1 "Error flag". It gives one candidate meaning for the
       flag: "/SEL disabled during transfer". That candidate is the correct one. The release of /SEL
       is not a fault. It is the usual end of each command. */
    int selected;
    int sel_drop_latch;

    int docked; /* The last value that com_set_docked received. */
} com_t;

/* Diagnostic flag. It uses the same pattern as psemu_ir_trace_enabled in ir.h.
   It is off by default, thus it has no cost in normal use.
   It records each COM register access with its real PC.
   Those PCs identify the COM routines of the kernel to disassemble. The function of the CTRL1 bits
   and the CTRL2 bits is unknown. Thus this trace is the method to recover them.
   See tools/com_probe.c. */
extern int psemu_com_trace_enabled;

void com_init(com_t *com);

/* These two functions take the interrupt controller. A register access changes the COM interrupt
   request. A read of COM_DATA takes the received byte, and that read clears the request. A write to
   COM_MODE can end the exchange, and that write also clears the request. See com_read and com_write
   for the evidence. */
uint32_t com_read(com_t *com, struct intc *intc, uint32_t offset);
void com_write(com_t *com, struct intc *intc, uint32_t offset, uint32_t value);

/* Sets the docking sense. `docked` is 0 for undocked. A nonzero value is the docked condition.
   This function drives INT_IOP. It does not change a register in this block.
   The kernel enables communication only while it senses the docked condition. Thus a caller must set
   this signal before it starts a transfer. */
void com_set_docked(com_t *com, struct intc *intc, int docked);

/* Sets the /SEL line. `selected` is 0 for released. A nonzero value holds the line.
   A PS1 holds this line for one command, and it releases the line between commands.
   A release sets COM_STAT1 bit 1, and that bit ends the wait of the kernel. See selected in the
   structure above. */
void com_set_selected(com_t *com, int selected);

/* Starts one byte exchange. `data_in` is the byte from the PS1.
   This function puts the byte at COM_DATA. It sets the Ready bit of COM_STAT2. It then asserts
   INT_COM. The CPU must execute after this call, because the kernel gives the answer.
   The caller reads com_transfer_acked for that answer. */
void com_begin_transfer(com_t *com, struct intc *intc, uint8_t data_in);

/* Returns a nonzero value after the kernel drives /ACK LOW for the exchange in progress.
   com_take_reply then gives the reply byte. */
int com_transfer_acked(const com_t *com);

/* Gets the byte that went out during the exchange in progress.
   This is the value that com_begin_transfer copied out of the output register. It is not the byte
   that the kernel wrote in answer to the current byte. See tx_data above. */
uint8_t com_take_reply(const com_t *com);

/* Ends the exchange. It clears INT_COM and the flags of the exchange.
   Call this function after com_transfer_acked returns a nonzero value. Call it also after a
   timeout. */
void com_end_transfer(com_t *com, struct intc *intc);

#endif
