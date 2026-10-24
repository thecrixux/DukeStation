/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_FLASH_H
#define PSEMU_FLASH_H

#include <stddef.h>
#include <stdint.h>

#include "psemu/psemu.h"

#define FLASH_BLOCK_SIZE 8192u
/* FLASH_CTRL register map:
 * +0x0  command and status.
 * +0x4  unused. It mirrors the command register.
 * +0x8  F_BANK_FLG, a bitmask of the enabled physical 8KB blocks.
 * +0xC  F_WAIT1 (waitstates).
 * +0x10 F_WAIT2 (waitstates, and flash-write control and status).
 *
 * A corrected fault: a real BIOS flash-write routine reads +0x10 and
 * waits for bit 2 to read back as set. This emulator did not model that
 * register before the correction; the span stopped at +0xC. An unmapped
 * read returned 0, thus the poll loop continued for an unlimited time.
 * This was the second of two busy-wait faults that stopped each real app
 * launch. The first fault was in the +0 command readback of
 * flash_ctrl_read8. See the comment on that function.
 * This emulator does not model real flash write timing, thus writes
 * complete immediately. Because of this, +0x10 always reads back as
 * "not busy".
 *
 * The span continues to 0x140, to also cover F_BANK_VAL (+0x100 to
 * +0x13C, 16 words). F_BANK_FLG shows which physical 8KB blocks are
 * enabled. F_BANK_VAL maps each physical block to a virtual bank slot
 * (0-15): table[physical] = virtual. This is the opposite direction from
 * a usual page table. An earlier version of this file used a linear
 * offset in place of the table. That assumption was incorrect. The range
 * between +0x14 and +0xFF is unmapped. Reads in this range give the
 * last_command mirror default.
 */
/* F_EXTRA (0x300-0x3FF, 256 bytes) is a separate "header" region. It is
 * outside the usual FLASH_CTRL registers. It holds F_SN_LO and F_SN_HI
 * (the 32-bit hardware serial number, which SWI 0Ah FlashReadSerial and
 * SWI 0Fh FlashWriteSerial read and write). It also holds F_CAL (the LCD
 * calibration). Real hardware writes F_CAL again together with F_SN at
 * each FlashWriteSerial call. The span continues from 0x140 to 0x400 to
 * cover F_EXTRA. See flash_ctrl_read8 and flash_ctrl_write8 for the
 * reason that the range between the two regions (0x140-0x2FF, unmapped
 * and unidentified) reads back as 0, and does not use the F_BANK_VAL
 * logic or the command-mirror logic.
 */
#define FLASH_CTRL_SPAN 0x400u
#define FLASH_BANK_VAL_OFFSET 0x100u
#define FLASH_BANK_VAL_COUNT 16u
#define FLASH_EXTRA_OFFSET 0x300u
#define FLASH_EXTRA_SPAN 0x100u
#define FLASH_SN_LO_OFFSET 0x300u
#define FLASH_SN_HI_OFFSET 0x302u
#define FLASH_CAL_OFFSET 0x308u

/* The default hardware ID (F_SN).
 *
 * The companion app of one PS1 game reads the serial number of the
 * PocketStation with SWI 0Ah, when the player makes a new save. A
 * disassembly of a real copy of that app confirms this (see
 * docs/hardware-notes.md, "Hardware ID (F_SN)"). The app masks F_SN down
 * to its low 24 bits. It converts that value to a string of decimal
 * digits, and uses the last 3 digits as the initial "ID" statistic of
 * the save. This ID statistic alone sets the rank. Public research gives
 * ID 211 as the best rank: the maximum HP and weapon value, and the best
 * item-drop probability. 211 is also the day and the month of the
 * Japanese release of that game (2/11).
 *
 * The mask removes the high byte of F_SN before this calculation, thus
 * the high byte has no effect on the rank. On some real units, the high
 * byte holds an ASCII letter. That letter is part of a serial sticker
 * that has one letter and 8 decimal digits, for example "A02374684". The
 * raw register has no such structural requirement (see
 * psemu_parse_hardware_id and psemu_format_hardware_id in
 * core/src/psemu.c).
 *
 * This emulator sets a default F_SN of 0x410000D3. The hex string form
 * of this emulator gives this value as "410000D3". The low 24 bits end
 * in "211", thus each new save from that app gets the best rank by
 * default. The high byte is an arbitrary letter, 'A'. It makes the value
 * look like a real serial number.
 */
#define FLASH_DEFAULT_SERIAL (((uint32_t)'A' << 24) | 211u)

typedef struct flash {
    uint8_t data[PSEMU_FLASH_SIZE];
    uint32_t bank_mask;    /* the last value written to FLASH_CTRL+8 (F_BANK_FLG) */
    uint32_t last_command; /* the last value written to FLASH_CTRL+0 */
    uint32_t bank_val[FLASH_BANK_VAL_COUNT]; /* F_BANK_VAL, with the physical bank as the index */
    uint16_t f_sn_lo;                        /* F_SN_LO: the LSBs of the hardware serial number */
    uint16_t f_sn_hi;                        /* F_SN_HI: the MSBs of the hardware serial number */
    uint16_t f_cal;                          /* F_CAL: the LCD calibration. FlashWriteSerial writes it again unchanged. */
    /* The position in the real F_KEY2/F_KEY1/F_KEY2 flash-unlock sequence.
       0 means not started, and 3 means fully armed. See the comment on
       flash_write8 for more data. While the sequence is armed, a write to
       physical offset 0, 2, or 8 changes F_SN_LO, F_SN_HI, or F_CAL. It
       does not change usual card data. */
    uint8_t unlock_step;
} flash_t;

/* Combines F_SN_LO and F_SN_HI the same way that SWI 0Ah
   (FlashReadSerial) does. */
uint32_t flash_get_serial(const flash_t *flash);
/* Divides serial into F_SN_LO and F_SN_HI the same way that SWI 0Fh
   (FlashWriteSerial) does. It does not change F_CAL. Real hardware
   writes the old value of F_CAL again here, because there is no other
   value to write. */
void flash_set_serial(flash_t *flash, uint32_t serial);

void flash_init(flash_t *flash);

/* Sets only the volatile FLASH_CTRL register state to the power-on defaults.
   That state is bank_mask, last_command, bank_val[], and unlock_step.
   It does not change data[], which holds the loaded card content or app content.
   It also does not change f_sn_lo, f_sn_hi, or f_cal. These fields hold the hardware ID and the
   LCD calibration.
   Those fields are flash-backed content. They are not volatile registers.
   They must continue after a reset, the same way that real flash content and a factory-programmed
   serial number continue.
   psemu_reset uses this function. */
void flash_reset_registers(flash_t *flash);

/* Validates a PSX Title Sector app image. It then loads the image into a
   synthesized memory-card directory that has one entry, at slot 1. Thus
   the app-selection and dispatch routine of the real BIOS can get to the
   app (see docs/app-notes.md, "App-selection and dispatch"). The maximum
   value of `size` is 15 blocks (DIRECTORY_MAX_APP_BLOCKS), and not the
   full 16 blocks, because block 0 holds the synthesized directory. */
psemu_status flash_load_app(flash_t *flash, const uint8_t *data, size_t size);

/* The size and magic-number part of the validation that flash_load_app does. This function loads
   nothing. It returns a nonzero value if `data` is a Title Sector body that flash_load_app accepts.
   flash_load_app calls this function. Thus "does this data load?" and "did this data load?" always
   give the same answer.
   psemu_identify_content needs this answer with no side effects, because it must give a content type
   before it loads anything. */
int flash_app_body_is_valid(const uint8_t *data, size_t size);

/* Copies `size` bytes of the body of the loaded app out. This function is the exact inverse of the
   final copy that flash_load_app does. The body is contiguous at physical block 1, immediately after
   the synthesized directory. Thus this function is a simple copy from that location, and `size` is
   the payload size of the caller. */
psemu_status flash_save_app(const flash_t *flash, uint8_t *buf, size_t size);

/* FLASH2: the physical flash, with no window. */
uint8_t flash_read8(flash_t *flash, uint32_t addr);
void flash_write8(flash_t *flash, uint32_t addr, uint8_t value);

/* FLASH1: a virtual window onto FLASH2. This code resolves each 8KB
   virtual bank (0-15) against F_BANK_FLG and F_BANK_VAL as the emulator
   runs (see docs/app-notes.md, "App-selection and dispatch"). If
   F_BANK_VAL has no configuration for a bank, the code uses a contiguous
   linear offset from the lowest enabled physical block. */
uint8_t flash1_read8(flash_t *flash, uint32_t addr);
void flash1_write8(flash_t *flash, uint32_t addr, uint8_t value);

/* FLASH_CTRL: the bank-select registers. This project found them by
   reverse engineering of a real BIOS.
   +8 (F_BANK_FLG) is a bitmask of the physical blocks of the app. +0 is
   an activate trigger. +0x100 and above (F_BANK_VAL) gives a virtual
   slot to each physical block. */
uint8_t flash_ctrl_read8(flash_t *flash, uint32_t offset);
void flash_ctrl_write8(flash_t *flash, uint32_t offset, uint8_t value);

#endif
