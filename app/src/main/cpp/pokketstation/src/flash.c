/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "flash.h"

#include <string.h>

#define TITLE_SECTOR_HEADER_SIZE 0x80u
#define TITLE_SECTOR_MAGIC_OFFSET 0x52u

/* A real PS1 memory card directory has 16 frames of 128 bytes in block 0.
   Frame 0 is the card header. Frames 1-15 give the properties of blocks
   1-15.
   Frame layout:
   - byte 0: the in-use marker (0x51 for the first or only frame, 0x52 for
     a middle frame, 0x53 for the last frame, and 0xA0 for a free frame).
   - bytes 4-7: the total file size, little-endian. This value is valid
     only in the first frame of a file.
   - bytes 8-9: the link to the next block, little-endian. The value is
     0-based among the 15 data blocks. Add 1 to get the physical block
     number. The value 0xFFFF is the end of the chain.
   - byte 0x7F: the XOR checksum of the 127 bytes before it. */
#define DIRECTORY_FRAME_SIZE 128u
#define DIRECTORY_MAX_APP_BLOCKS 15u /* block 0 holds the directory */

/* A confirmed requirement. A byte-by-byte bisection of a real card dump
   isolated it. The menu-browsing code of the BIOS requires byte 6 of the
   file-name field (frame offset 0x10) to be the ASCII character 'P'.
   This code is separate from the app-selection and dispatch routine
   above, and it executes before that routine. It lets a user move to an
   entry and select it.

   Two real directory entries confirm the pattern. A usual PS1 save ID
   has a mandatory hyphen in its region code, for example "SLUS-00892".
   When the save also contains a PocketStation app, a 'P' replaces that
   hyphen: "BASLUSP00892..." and "BISLPMP86247...". The product-code
   prefix `SLPM` of the second example already contains a different 'P'.
   Because of this, the isolation of the real marker byte needed two
   rounds of bisection.

   Confirmed by test: a real card that operates correctly continues to
   dispatch when each other byte of the file name is incorrect or zero,
   if this one byte is 'P'. The card stops dispatch if this byte changes,
   even with a realistic name in the field. For an arbitrary loaded app
   there is no real product code to write here, thus this code leaves the
   remainder of the field empty. Only this one flag byte is necessary. */
#define DIRECTORY_POCKETSTATION_FLAG_OFFSET 0x10u

void flash_reset_registers(flash_t *flash) {
    flash->bank_mask = 0;
    flash->last_command = 0;
    memset(flash->bank_val, 0, sizeof(flash->bank_val));
    flash->unlock_step = 0;
}

void flash_init(flash_t *flash) {
    memset(flash->data, 0, sizeof(flash->data));
    flash_reset_registers(flash);
    flash->f_sn_lo = (uint16_t)(FLASH_DEFAULT_SERIAL & 0xFFFFu);
    flash->f_sn_hi = (uint16_t)((FLASH_DEFAULT_SERIAL >> 16) & 0xFFFFu);
    /* The recorded reset default is 001Ah. This value has no relation to
       the ID function. It is only an initial value. FlashWriteSerial
       always writes this register again without a change. It does not
       calculate the value. */
    flash->f_cal = 0x001Au;
}

uint32_t flash_get_serial(const flash_t *flash) {
    return ((uint32_t)flash->f_sn_hi << 16) | flash->f_sn_lo;
}

void flash_set_serial(flash_t *flash, uint32_t serial) {
    flash->f_sn_lo = (uint16_t)(serial & 0xFFFFu);
    flash->f_sn_hi = (uint16_t)((serial >> 16) & 0xFFFFu);
}

static uint8_t directory_frame_xor(const uint8_t *frame) {
    uint8_t xor_value = 0;
    uint32_t i;
    for (i = 0; i < 0x7Fu; i++) {
        xor_value ^= frame[i];
    }
    return xor_value;
}

int flash_app_body_is_valid(const uint8_t *data, size_t size) {
    if (!data || size < TITLE_SECTOR_HEADER_SIZE || size > DIRECTORY_MAX_APP_BLOCKS * FLASH_BLOCK_SIZE) {
        return 0;
    }
    return memcmp(&data[TITLE_SECTOR_MAGIC_OFFSET], "MCX0", 4) == 0 ||
           memcmp(&data[TITLE_SECTOR_MAGIC_OFFSET], "MCX1", 4) == 0;
}

psemu_status flash_save_app(const flash_t *flash, uint8_t *buf, size_t size) {
    if (!buf || size == 0u || size > DIRECTORY_MAX_APP_BLOCKS * FLASH_BLOCK_SIZE) {
        return PSEMU_ERR_BAD_SIZE;
    }
    memcpy(buf, &flash->data[FLASH_BLOCK_SIZE], size);
    return PSEMU_OK;
}

psemu_status flash_load_app(flash_t *flash, const uint8_t *data, size_t size) {
    if (size < TITLE_SECTOR_HEADER_SIZE || size > DIRECTORY_MAX_APP_BLOCKS * FLASH_BLOCK_SIZE) {
        return PSEMU_ERR_BAD_SIZE;
    }
    if (!flash_app_body_is_valid(data, size)) {
        return PSEMU_ERR_BAD_FORMAT;
    }

    memset(flash->data, 0, sizeof(flash->data));

    /* The app-selection routine of real hardware (docs/app-notes.md,
       "App-selection and dispatch") requires a real memory-card directory
       in FLASH2. The bytes of the app at offset 0 are not sufficient. The
       routine reads the directory frame of the selected slot, follows the
       block-chain link, and only then finds the entry point at the
       physical block that the chain gives.

       This function synthesizes a minimal card with one entry, thus the
       routine can find the loaded app. The card has a card header, one
       directory frame for each app block, and a chain that starts at slot
       1. Slot 1 agrees with the rule "Right from the clock screen moves
       to the first app in the list". The data of the app starts at
       physical block 1, immediately after the directory. */
    flash->data[0x00] = 'M';
    flash->data[0x01] = 'C';
    flash->data[0x7F] = directory_frame_xor(flash->data);

    uint32_t block_count = (uint32_t)((size + FLASH_BLOCK_SIZE - 1) / FLASH_BLOCK_SIZE);
    uint32_t block;
    for (block = 1; block <= block_count; block++) {
        uint8_t *frame = &flash->data[block * DIRECTORY_FRAME_SIZE];
        frame[0x00] = (block == 1) ? 0x51u : (block == block_count) ? 0x53u : 0x52u;
        if (block == 1) {
            frame[0x04] = (uint8_t)(size & 0xFFu);
            frame[0x05] = (uint8_t)((size >> 8) & 0xFFu);
            frame[0x06] = (uint8_t)((size >> 16) & 0xFFu);
            frame[0x07] = (uint8_t)((size >> 24) & 0xFFu);
            frame[DIRECTORY_POCKETSTATION_FLAG_OFFSET] = (uint8_t)'P';
        }
        uint32_t link = (block < block_count) ? block : 0xFFFFu;
        frame[0x08] = (uint8_t)(link & 0xFFu);
        frame[0x09] = (uint8_t)((link >> 8) & 0xFFu);
        frame[0x7F] = directory_frame_xor(frame);
    }
    for (block = block_count + 1; block < 16u; block++) {
        uint8_t *frame = &flash->data[block * DIRECTORY_FRAME_SIZE];
        frame[0x00] = 0xA0u;
        frame[0x08] = 0xFFu;
        frame[0x09] = 0xFFu;
        frame[0x7F] = directory_frame_xor(frame);
    }

    memcpy(&flash->data[FLASH_BLOCK_SIZE], data, size);
    return PSEMU_OK;
}

/* F_KEY1 (0x08002A54) and F_KEY2 (0x080055AA) are the flash
   unlock-sequence trigger addresses of real hardware. A subroutine
   at BIOS+0x1228 performs the three-step unlock: FFAAh to F_KEY2,
   FF55h to F_KEY1, then FFA0h to F_KEY2 again. This is the standard
   command-latch method that NOR flash chips use. The flash write
   function at BIOS+0x126C (SWI 16) calls that subroutine, then
   writes 128 bytes from a source pointer to FLASH2 at physical
   offset (page_number * 128). The IR command dispatcher at
   BIOS+0x1060 routes PS1 write command 57h to a handler that calls
   BIOS+0x126C.

   These addresses are NOT storage locations. Real flash hardware
   intercepts writes to these addresses as unlock commands. It does not
   send them to the data array. Thus the byte at that physical address
   does not change.

   A corrected fault, found through a real crash report (see
   docs/hardware-notes.md, "Flash memory"). This emulator did not
   intercept these addresses. Thus each real flash-write operation
   permanently corrupted a live data byte, at the physical offset with
   the same number as one of these two fixed addresses. That offset is
   inside live app code, in the compiled binary of one commercial app.
   Thus a usual in-game save caused code corruption with no error
   message. The corruption became visible only later, when execution got
   to the changed bytes. */
#define FLASH_KEY1_OFFSET 0x2A54u
#define FLASH_KEY2_OFFSET 0x55AAu

/* On real hardware, each key write is one 16-bit halfword
   ([8002A54h]=FF55h). This code must guard both bytes of each halfword,
   and not only the base offset. For example, a 32-bit store also writes
   the two bytes after the base address. */
static int flash_is_unlock_key_offset(uint32_t offset) {
    return offset == FLASH_KEY1_OFFSET || offset == FLASH_KEY1_OFFSET + 1u || offset == FLASH_KEY2_OFFSET ||
           offset == FLASH_KEY2_OFFSET + 1u;
}

/* The WRITE address for F_SN and F_CAL on real hardware. This address is
   different from the READ address: F_EXTRA, at FLASH_CTRL+0x300 (see
   F_SN_LO_OFFSET, F_SN_HI_OFFSET, and F_CAL_OFFSET above).

   Two sources confirm this address:
   - The available register description gives "[8000000h]=new F_SN_LO
     value [8000002h]=new F_SN_HI value".
   - A disassembly of the flash-write routine of a real homebrew ID
     editor shows that the routine does the real F_KEY1/F_KEY2 unlock
     sequence. It then writes the new serial number to physical offset 0
     and 2, and F_CAL to offset 8.

   A test on real retail hardware confirms that this address operates
   correctly. See docs/hardware-notes.md, "Hardware ID (F_SN)", for the
   full investigation. */
#define FLASH_HEADER_WRITE_SN_LO_OFFSET 0x0000u
#define FLASH_HEADER_WRITE_SN_HI_OFFSET 0x0002u
#define FLASH_HEADER_WRITE_CAL_OFFSET 0x0008u

static int flash_is_header_write_offset(uint32_t offset) {
    return offset == FLASH_HEADER_WRITE_SN_LO_OFFSET || offset == FLASH_HEADER_WRITE_SN_LO_OFFSET + 1u ||
           offset == FLASH_HEADER_WRITE_SN_HI_OFFSET || offset == FLASH_HEADER_WRITE_SN_HI_OFFSET + 1u ||
           offset == FLASH_HEADER_WRITE_CAL_OFFSET || offset == FLASH_HEADER_WRITE_CAL_OFFSET + 1u;
}

uint8_t flash_read8(flash_t *flash, uint32_t addr) {
    return flash->data[addr % PSEMU_FLASH_SIZE];
}

/* Physical offset 0, 2, and 8 have two functions. They are usual
   card-data storage (usually the directory header of block 0). They are
   also the real write target for F_SN and F_CAL. The applicable function
   depends on whether the real 3-step unlock sequence armed the addresses
   immediately before the write. It does not depend on the address alone.
   See the comment on flash_is_unlock_key_offset, and
   FLASH_HEADER_WRITE_SN_LO_OFFSET above.

   `unlock_step` (see flash.h) holds the position in that sequence. It
   uses only the next key address. This agrees with the method that this
   emulator always uses for these addresses: it treats them as commands
   and not as data, and it does not validate the values. The sequence
   arms when all 3 steps occur in the correct order.

   This redirection has a condition. It is not an unconditional address
   alias. The real save-write mechanism of one commercial app uses this
   same unlock-then-write-FLASH2 method for its own save data (see the
   F_KEY1/F_KEY2 corruption fault above). An unconditional alias can send
   a correct data write at offset 0, 2, or 8 to the wrong destination.

   The armed state continues across more than one write. A real header
   update is 3 separate halfword writes (F_SN_LO, F_SN_HI, and F_CAL)
   after one unlock sequence. The armed state stops at the first write to
   a different offset. That write shows that the write session moved to
   other data. */
void flash_write8(flash_t *flash, uint32_t addr, uint8_t value) {
    uint32_t offset = addr % PSEMU_FLASH_SIZE;

    if (flash_is_unlock_key_offset(offset)) {
        /* A real key write is one 16-bit halfword (psemu_bus_write16).
           It is always the low byte and then the high byte. See the
           definition of that function, and the STRH path of
           exec_halfword_transfer. This is the only real method for these
           writes. psemu_bus_write8 receives two separate calls.

           Only the low byte (the base offset, and not +1) advances
           `unlock_step`. The high byte is the second half of the same
           real write. Thus the high byte must do nothing here. It must
           not be a new event that resets the step counter. */
        int is_high_byte = offset == FLASH_KEY1_OFFSET + 1u || offset == FLASH_KEY2_OFFSET + 1u;
        int is_key2 = offset == FLASH_KEY2_OFFSET;
        int is_key1 = offset == FLASH_KEY1_OFFSET;
        if (is_high_byte) {
            return;
        }
        if (flash->unlock_step == 0 && is_key2) {
            flash->unlock_step = 1;
        } else if (flash->unlock_step == 1 && is_key1) {
            flash->unlock_step = 2;
        } else if (flash->unlock_step == 2 && is_key2) {
            flash->unlock_step = 3; /* armed */
        } else {
            flash->unlock_step = 0;
        }
        return;
    }

    if (flash->unlock_step == 3 && flash_is_header_write_offset(offset)) {
        if (offset == FLASH_HEADER_WRITE_SN_LO_OFFSET || offset == FLASH_HEADER_WRITE_SN_LO_OFFSET + 1u) {
            uint32_t shift = (offset - FLASH_HEADER_WRITE_SN_LO_OFFSET) * 8u;
            flash->f_sn_lo = (uint16_t)((flash->f_sn_lo & ~(0xFFu << shift)) | ((uint32_t)value << shift));
        } else if (offset == FLASH_HEADER_WRITE_SN_HI_OFFSET || offset == FLASH_HEADER_WRITE_SN_HI_OFFSET + 1u) {
            uint32_t shift = (offset - FLASH_HEADER_WRITE_SN_HI_OFFSET) * 8u;
            flash->f_sn_hi = (uint16_t)((flash->f_sn_hi & ~(0xFFu << shift)) | ((uint32_t)value << shift));
        } else {
            uint32_t shift = (offset - FLASH_HEADER_WRITE_CAL_OFFSET) * 8u;
            flash->f_cal = (uint16_t)((flash->f_cal & ~(0xFFu << shift)) | ((uint32_t)value << shift));
        }
        return;
    }

    flash->unlock_step = 0;
    flash->data[offset] = value;
}

/* Finds the physical 8KB block for a given FLASH1 virtual bank (0-15).
   The index of F_BANK_VAL is the PHYSICAL bank: table[p] = v. This is
   the opposite direction from a usual page table. Thus a conversion from
   virtual to physical needs a reverse linear search of the 16 entries. */
static uint32_t flash_resolve_physical_bank(const flash_t *flash, uint32_t virtual_bank) {
    uint32_t p;
    uint32_t lowest = 0;

    for (p = 0; p < FLASH_BANK_VAL_COUNT; p++) {
        if ((flash->bank_mask & (1u << p)) && (flash->bank_val[p] & 0xFu) == virtual_bank) {
            return p;
        }
    }
    /* No F_BANK_VAL entry selects this virtual slot. The reset value of
       the register is 0 for each physical bank, which agrees with the
       reset state of real hardware. Thus this code uses the earlier
       validated behavior: it treats the enabled physical blocks as one
       contiguous group, which starts at the enabled block with the
       lowest number.

       A disassembly confirms this: the app-selection routine (see
       "App-selection and dispatch" in docs/app-notes.md) writes only
       F_BANK_FLG. It never writes F_BANK_VAL. This fallback keeps usual
       multi-block app dispatch and execution correct, for each tested
       condition. */
    for (p = 0; p < FLASH_BANK_VAL_COUNT; p++) {
        if (flash->bank_mask & (1u << p)) {
            lowest = p;
            break;
        }
    }
    return lowest + virtual_bank;
}

uint8_t flash1_read8(flash_t *flash, uint32_t addr) {
    uint32_t virtual_bank = (addr / FLASH_BLOCK_SIZE) % FLASH_BANK_VAL_COUNT;
    uint32_t offset_in_bank = addr % FLASH_BLOCK_SIZE;
    uint32_t physical_bank = flash_resolve_physical_bank(flash, virtual_bank);
    return flash->data[(physical_bank * FLASH_BLOCK_SIZE + offset_in_bank) % PSEMU_FLASH_SIZE];
}

void flash1_write8(flash_t *flash, uint32_t addr, uint8_t value) {
    uint32_t virtual_bank = (addr / FLASH_BLOCK_SIZE) % FLASH_BANK_VAL_COUNT;
    uint32_t offset_in_bank = addr % FLASH_BLOCK_SIZE;
    uint32_t physical_bank = flash_resolve_physical_bank(flash, virtual_bank);
    uint32_t offset = (physical_bank * FLASH_BLOCK_SIZE + offset_in_bank) % PSEMU_FLASH_SIZE;
    /* The F_KEY1 and F_KEY2 unlock addresses are a chip decode in real
       hardware. They are not data storage. See the comment on
       flash_write8. This is true for each bus window that gets to the
       same physical offset: this virtual window, or FLASH2 directly. */
    if (flash_is_unlock_key_offset(offset)) {
        return;
    }
    flash->data[offset] = value;
}

uint8_t flash_ctrl_read8(flash_t *flash, uint32_t offset) {
    uint32_t word_index;
    uint32_t reg;

    if (offset >= FLASH_BANK_VAL_OFFSET && offset < FLASH_BANK_VAL_OFFSET + FLASH_BANK_VAL_COUNT * 4u) {
        uint32_t bank_index = (offset - FLASH_BANK_VAL_OFFSET) / 4u;
        return (uint8_t)(flash->bank_val[bank_index] >> ((offset % 4u) * 8u));
    }

    /* F_EXTRA (see flash.h). F_SN_LO, F_SN_HI, and F_CAL are real
       registers with storage in this region. Each other byte in the
       256-byte region is unidentified and unused, and it reads back as
       0. This agrees with the recorded defaults for the bytes with no
       identification. */
    if (offset == FLASH_SN_LO_OFFSET || offset == FLASH_SN_LO_OFFSET + 1u) {
        return (uint8_t)(flash->f_sn_lo >> ((offset - FLASH_SN_LO_OFFSET) * 8u));
    }
    if (offset == FLASH_SN_HI_OFFSET || offset == FLASH_SN_HI_OFFSET + 1u) {
        return (uint8_t)(flash->f_sn_hi >> ((offset - FLASH_SN_HI_OFFSET) * 8u));
    }
    if (offset == FLASH_CAL_OFFSET || offset == FLASH_CAL_OFFSET + 1u) {
        return (uint8_t)(flash->f_cal >> ((offset - FLASH_CAL_OFFSET) * 8u));
    }
    if (offset >= FLASH_EXTRA_OFFSET && offset < FLASH_EXTRA_OFFSET + FLASH_EXTRA_SPAN) {
        return 0u;
    }
    /* The range between the end of F_BANK_VAL (+0x140) and the start of
       F_EXTRA (+0x300). This range is unmapped and unidentified, the
       same as before this code made the span longer.
       This range must NOT go to the word_index selection below. That
       selection covers only +0x0, +0x4, +0x8, and +0x10. FLASH_CTRL_SPAN
       now continues past this range. Without this test, the selection
       incorrectly mirrors last_command across the full range. */
    if (offset >= 0x140u) {
        return 0u;
    }

    word_index = offset / 4u;
    if (word_index == 2u) {
        reg = flash->bank_mask;
    } else if (word_index == 0u) {
        /* On real hardware, +0 is a write-command and read-status
           register. It is not a simple mirror. A corrected fault: a real
           BIOS routine writes a command here. It then waits for bit 0 of
           this same address to read back as 1 ("ready"). The bank commit
           of this emulator always completes immediately, thus bit 0 is
           always ready after a write. Before the correction, this code
           returned the raw command value. Bit 0 of that value was 0 for
           the observed command (2), thus the loop continued for an
           unlimited time. This stopped each real app launch, and gave no
           error. */
        reg = flash->last_command | 1u;
    } else if (word_index == 4u) {
        /* +0x10 (F_WAIT2): a second confirmed busy-wait fault, now
           corrected. The flash-write routine of a real app reads bit 2
           here. It waits for the bit to read back as set after the write
           completes. This emulator did not model the register before the
           correction; the span stopped at +0xC. An unmapped read gave a
           default of 0, thus this loop also continued for an unlimited
           time, immediately after the correction of the +0 fault above.
           The writes of this emulator complete immediately, thus this
           code always reports "not busy". */
        reg = 0x04u;
    } else {
        reg = flash->last_command;
    }
    return (uint8_t)(reg >> ((offset % 4u) * 8u));
}

void flash_ctrl_write8(flash_t *flash, uint32_t offset, uint8_t value) {
    uint32_t reg_index;
    uint32_t shift;

    if (offset >= FLASH_BANK_VAL_OFFSET && offset < FLASH_BANK_VAL_OFFSET + FLASH_BANK_VAL_COUNT * 4u) {
        uint32_t bank_index = (offset - FLASH_BANK_VAL_OFFSET) / 4u;
        uint32_t bank_shift = (offset % 4u) * 8u;
        flash->bank_val[bank_index] =
            (flash->bank_val[bank_index] & ~(0xFFu << bank_shift)) | ((uint32_t)value << bank_shift);
        return;
    }

    /* F_EXTRA: see flash_ctrl_read8. On real hardware, an app uses
       FlashWriteSerial (SWI 0Fh) to change F_SN. Direct writes here give
       the same effect at the register level. This is important for a
       homebrew ID editor, which writes these bytes directly and does not
       use the SWI. */
    if (offset == FLASH_SN_LO_OFFSET || offset == FLASH_SN_LO_OFFSET + 1u) {
        uint32_t extra_shift = (offset - FLASH_SN_LO_OFFSET) * 8u;
        flash->f_sn_lo = (uint16_t)((flash->f_sn_lo & ~(0xFFu << extra_shift)) | ((uint32_t)value << extra_shift));
        return;
    }
    if (offset == FLASH_SN_HI_OFFSET || offset == FLASH_SN_HI_OFFSET + 1u) {
        uint32_t extra_shift = (offset - FLASH_SN_HI_OFFSET) * 8u;
        flash->f_sn_hi = (uint16_t)((flash->f_sn_hi & ~(0xFFu << extra_shift)) | ((uint32_t)value << extra_shift));
        return;
    }
    if (offset == FLASH_CAL_OFFSET || offset == FLASH_CAL_OFFSET + 1u) {
        uint32_t extra_shift = (offset - FLASH_CAL_OFFSET) * 8u;
        flash->f_cal = (uint16_t)((flash->f_cal & ~(0xFFu << extra_shift)) | ((uint32_t)value << extra_shift));
        return;
    }
    if (offset >= FLASH_EXTRA_OFFSET && offset < FLASH_EXTRA_OFFSET + FLASH_EXTRA_SPAN) {
        return; /* an unknown or reserved F_EXTRA byte */
    }
    if (offset >= 0x140u) {
        return; /* the unmapped range between F_BANK_VAL and F_EXTRA */
    }

    reg_index = offset / 4u;
    shift = (offset % 4u) * 8u;

    if (reg_index == 2u) { /* +8: the block bitmask (F_BANK_FLG) */
        flash->bank_mask = (flash->bank_mask & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        return;
    }
    if (reg_index == 0u) { /* +0: the activate trigger */
        flash->last_command = (flash->last_command & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        /* There is no cached offset to calculate again. flash1_read8 and
           flash1_write8 resolve F_BANK_FLG and F_BANK_VAL at each
           access. */
        return;
    }
}
