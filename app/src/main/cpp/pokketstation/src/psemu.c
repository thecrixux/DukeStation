/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "psemu_internal.h"

#include <stdio.h>
#include <stdlib.h>
#include <string.h>

#define BIOS_RESET_VECTOR PSEMU_BIOS_BASE

psemu_t *psemu_create(void) {
    psemu_t *ps = (psemu_t *)malloc(sizeof(psemu_t));
    if (!ps) {
        return NULL;
    }
    lcd_init(&ps->lcd);
    intc_init(&ps->intc);
    flash_init(&ps->flash);
    com_init(&ps->com);
    ir_init(&ps->ir);
    timer_init(&ps->timer);
    rtc_init(&ps->rtc);
    dac_init(&ps->dac);
    clk_init(&ps->clk);
    iop_init(&ps->iop);
    psemu_bus_init(
        &ps->bus, &ps->lcd, &ps->intc, &ps->flash, &ps->com, &ps->ir, &ps->timer, &ps->rtc, &ps->dac, &ps->clk,
        &ps->iop);
    arm7tdmi_init(&ps->cpu, &ps->bus);
    ps->buttons = 0;
    ps->has_bios = 0;
    ps->real_time_cycle_carry = 0.0;
    ps->app_running = 0;
    ps->app_exec_idle_cycles = 0;
    return ps;
}

void psemu_destroy(psemu_t *ps) {
    free(ps);
}

void psemu_reset(psemu_t *ps) {
    /* A true hardware-level reset. Each peripheral goes back to its
       power-on default, the same way that psemu_create sets it up.
       Loaded content continues, because a reset does not erase ROM
       content or flash content. This function does not change
       ps->bus.bios[], ps->flash.data[], ps->flash.f_sn_lo, f_sn_hi,
       f_cal (the hardware ID and the LCD calibration), or ps->has_bios.

       Without this function, a mid-session load of a different BIOS,
       app, or card left the peripheral register state of the earlier
       session below the new content. That state includes the FLASH1
       bank mapping, the INTC enable and mask registers, CLK_MODE, the
       DAC buffer state, and old RAM. This is what caused visible
       errors on the screen after a load. */
    memset(ps->bus.ram, 0, sizeof(ps->bus.ram));
    /* A setting that a frontend holds is not part of the power-on state
       that this function restores. Such a setting does the function of
       battery-backed SRAM, and a reset does NOT clear that memory. Write
       the value again after the clear operation. Thus the value is in
       position for the first read by the BIOS, and not only from the
       next frame of the frontend. See psemu_set_volume_override. */
    if (ps->bus.ram_lock_addr < PSEMU_RAM_SIZE) {
        ps->bus.ram[ps->bus.ram_lock_addr] = ps->bus.ram_lock_value;
    }
    lcd_init(&ps->lcd);
    /* lcd_init clears dirty. That is correct for a new psemu_create,
       where the screen is empty. A mid-session reset instead needs the
       frontend to redraw immediately, to replace the frame that the
       screen already shows. */
    ps->lcd.dirty = 1;
    intc_init(&ps->intc);
    com_init(&ps->com);
    ir_init(&ps->ir);
    timer_init(&ps->timer);
    rtc_init(&ps->rtc);
    dac_init(&ps->dac);
    clk_init(&ps->clk);
    iop_init(&ps->iop);
    flash_reset_registers(&ps->flash);
    ps->buttons = 0;
    ps->real_time_cycle_carry = 0.0;
    /* A reset returns to the BIOS. Thus the dispatched app stops. */
    ps->app_running = 0;
    ps->app_exec_idle_cycles = 0;
    arm7tdmi_reset(&ps->cpu, BIOS_RESET_VECTOR);
}

psemu_status psemu_load_bios(psemu_t *ps, const uint8_t *data, size_t size) {
    if (size != PSEMU_BIOS_SIZE) {
        return PSEMU_ERR_BAD_SIZE;
    }
    memcpy(ps->bus.bios, data, size);
    ps->has_bios = 1;
    return PSEMU_OK;
}

psemu_status psemu_load_app(psemu_t *ps, const uint8_t *data, size_t size) {
    return flash_load_app(&ps->flash, data, size);
}

/* The layout of a PS1 directory frame:
   - Byte 0 is the in-use marker.
   - Bytes 4-7 hold the total data size of the file. The value is little-endian, and it is a multiple
     of FLASH_BLOCK_SIZE.
   - The other bytes hold link data and file-name data. This emulator does not need that data. */
#define MCS_HEADER_SIZE 0x80u
#define MCS_DATASIZE_OFFSET 0x04u

/* DexDrive card dump: 11-byte magic, then a header of GME_HEADER_SIZE bytes, then the card data.
   A full dump gives PSEMU_FLASH_SIZE bytes of card data, thus a total of 134976 bytes. */
#define GME_MAGIC "123-456-STD"
#define GME_MAGIC_SIZE 11u
#define GME_HEADER_SIZE 3904u

/* The card part of a .gme file. This is each test that is possible on the file alone.
   psemu_identify_content and psemu_load_content both use this function. Thus the two functions
   always agree on the definition of a .gme file.

   A dump that ends early gives fewer blocks than a full card, and it is still a .gme file. The card
   part is a whole number of blocks, and it always holds block 0, because the directory is there.
   The directory gives the state of each block of the full card, thus the blocks that the file does
   not hold are known. psemu_load_flash_image makes those blocks zero.

   This function returns a nonzero value and writes *out_card_size when `data` holds a .gme file. */
static int gme_card_size(const uint8_t *data, size_t size, size_t *out_card_size) {
    size_t card_size;
    if (!data || size <= GME_HEADER_SIZE || memcmp(data, GME_MAGIC, GME_MAGIC_SIZE) != 0) {
        return 0;
    }
    card_size = size - GME_HEADER_SIZE;
    if (card_size % FLASH_BLOCK_SIZE != 0 || card_size > PSEMU_FLASH_SIZE) {
        return 0;
    }
    *out_card_size = card_size;
    return 1;
}

/* The directory-frame part of the validation of a .mcs file. This is each test that is possible
   before flash_load_app receives the body. This function returns a nonzero value and writes
   *out_payload_size when `data` holds a frame whose recorded size agrees with the bytes after it.
   psemu_load_mcs and psemu_identify_content both use this function. Thus the two functions always
   agree on the definition of a .mcs file. */
static int mcs_payload_size(const uint8_t *data, size_t size, size_t *out_payload_size) {
    size_t payload_size;
    uint32_t datasize;
    if (!data || size <= MCS_HEADER_SIZE) {
        return 0;
    }
    payload_size = size - MCS_HEADER_SIZE;
    if (payload_size % FLASH_BLOCK_SIZE != 0) {
        return 0;
    }
    datasize = (uint32_t)data[MCS_DATASIZE_OFFSET] | ((uint32_t)data[MCS_DATASIZE_OFFSET + 1] << 8) |
               ((uint32_t)data[MCS_DATASIZE_OFFSET + 2] << 16) | ((uint32_t)data[MCS_DATASIZE_OFFSET + 3] << 24);
    if (datasize != payload_size) {
        return 0;
    }
    *out_payload_size = payload_size;
    return 1;
}

psemu_status psemu_load_mcs(psemu_t *ps, const uint8_t *data, size_t size) {
    size_t payload_size;
    if (!mcs_payload_size(data, size, &payload_size)) {
        /* This division of the error codes comes from the earlier inline version: a file that is too
           short or has incorrect alignment is a size fault. A frame that does not agree with the
           bytes after it is a format fault. */
        if (!data || size <= MCS_HEADER_SIZE || (size - MCS_HEADER_SIZE) % FLASH_BLOCK_SIZE != 0) {
            return PSEMU_ERR_BAD_SIZE;
        }
        return PSEMU_ERR_BAD_FORMAT;
    }
    return flash_load_app(&ps->flash, data + MCS_HEADER_SIZE, payload_size);
}

psemu_status psemu_load_flash_image(psemu_t *ps, const uint8_t *data, size_t size) {
    if (size > sizeof(ps->flash.data)) {
        return PSEMU_ERR_BAD_SIZE;
    }
    memset(ps->flash.data, 0, sizeof(ps->flash.data));
    memcpy(ps->flash.data, data, size);
    return PSEMU_OK;
}

psemu_status psemu_save_flash_image(const psemu_t *ps, uint8_t *buf, size_t size) {
    if (size < sizeof(ps->flash.data)) {
        return PSEMU_ERR_BAD_SIZE;
    }
    memcpy(buf, ps->flash.data, sizeof(ps->flash.data));
    return PSEMU_OK;
}

/* See the header for the reason that the layout of these two regions is a contract, and not an
   implementation detail. Both regions are simple arrays in psemu_t. Thus their addresses stay
   correct for the full lifetime of the instance. */
uint8_t *psemu_flash_data(psemu_t *ps) {
    return ps->flash.data;
}

uint8_t *psemu_ram_data(psemu_t *ps) {
    return ps->bus.ram;
}

psemu_status psemu_save_app_image(const psemu_t *ps, uint8_t *buf, size_t size) {
    return flash_save_app(&ps->flash, buf, size);
}

psemu_content_kind psemu_identify_content(const uint8_t *data, size_t size) {
    size_t payload_size;
    size_t card_size;
    if (!data) {
        return PSEMU_CONTENT_UNKNOWN;
    }
    if (size == PSEMU_FLASH_SIZE) {
        return PSEMU_CONTENT_CARD;
    }
    if (gme_card_size(data, size, &card_size)) {
        return PSEMU_CONTENT_GME;
    }
    /* This function tests for a .mcs file before it tests for a Title Sector body. Single-save
       exports are much more frequent than Title Sector dumps. The two kinds are also easy to tell
       apart: only a .mcs file has a directory frame whose recorded size agrees with the bytes after
       it. */
    if (mcs_payload_size(data, size, &payload_size) && flash_app_body_is_valid(data + MCS_HEADER_SIZE, payload_size)) {
        return PSEMU_CONTENT_MCS;
    }
    if (flash_app_body_is_valid(data, size)) {
        return PSEMU_CONTENT_APP;
    }
    return PSEMU_CONTENT_UNKNOWN;
}

/* FNV-1a. This is the same algorithm that the desktop frontend used when it hashed full files. The
   algorithm stays the same, thus only the hashed bytes change, and not the method. */
#define FNV1A_OFFSET_BASIS 2166136261u
#define FNV1A_PRIME 16777619u

static void identity_hash_update(uint32_t *hash, const uint8_t *data, size_t size) {
    size_t i;
    for (i = 0; i < size; i++) {
        *hash ^= data[i];
        *hash *= FNV1A_PRIME;
    }
}

/* The identity of a PS1 directory frame: the in-use state, and the name of the file. This identity
   does not include the size or the block link. Those two values give the position of the file. A save
   manager can move a file on a card, and the file is still the same file. */
#define DIRECTORY_FRAME_SIZE 128u
#define DIRECTORY_NAME_OFFSET 0x0Au
#define DIRECTORY_NAME_SIZE 21u

static void identity_hash_directory_frame(uint32_t *hash, const uint8_t *frame) {
    identity_hash_update(hash, frame, 1); /* the allocation state */
    if (frame[0] != 0xA0u) {
        identity_hash_update(hash, frame + DIRECTORY_NAME_OFFSET, DIRECTORY_NAME_SIZE);
    }
}

/* All data up to the end of the standard PS1 icon: the header, the title, the CLUT, and the icon
   frames. See the header comment on psemu_content_identity_hash for the reason that the hash uses
   this region and no more. */
#define TITLE_SECTOR_ICON_FLAG_OFFSET 0x02u
#define TITLE_SECTOR_ICON_DATA_OFFSET 0x80u
#define TITLE_SECTOR_ICON_FRAME_SIZE 128u

static void identity_hash_title_sector(uint32_t *hash, const uint8_t *body, size_t size) {
    /* The low bits of the standard PS1 icon-flag byte give the frame count, from 1 to 3. This code
       limits the value in place of a direct use: this function operates on a file with no
       validation except its magic number. */
    size_t frames = (size_t)(body[TITLE_SECTOR_ICON_FLAG_OFFSET] & 0x03u);
    size_t end;
    if (frames < 1u) {
        frames = 1u;
    }
    end = TITLE_SECTOR_ICON_DATA_OFFSET + frames * TITLE_SECTOR_ICON_FRAME_SIZE;
    if (end > size) {
        end = size;
    }
    identity_hash_update(hash, body, end);
}

uint32_t psemu_content_identity_hash(const uint8_t *data, size_t size) {
    uint32_t hash = FNV1A_OFFSET_BASIS;
    /* This variable has an initial value because no code here enforces the condition that makes the
       PSEMU_CONTENT_MCS case below safe. psemu_identify_content returns MCS only when
       mcs_payload_size was successful with these same arguments, and that function is deterministic.
       Thus the second call cannot fail. But that logic goes across two functions, which is why
       -Wmaybe-uninitialized reports it on GCC. If the condition stops being true, then
       identity_hash_title_sector reads past the end of the buffer with an incorrect length. An
       initial value of zero changes that result to a hash of no extra bytes. No reachable behavior
       changes, thus the hashes that are already on disk stay valid. */
    size_t payload_size = 0;
    uint32_t frame;
    if (!data) {
        return hash;
    }
    switch (psemu_identify_content(data, size)) {
    case PSEMU_CONTENT_CARD:
        for (frame = 1; frame < 16u; frame++) {
            identity_hash_directory_frame(&hash, data + frame * DIRECTORY_FRAME_SIZE);
        }
        return hash;
    case PSEMU_CONTENT_GME:
        for (frame = 1; frame < 16u; frame++) {
            identity_hash_directory_frame(&hash, data + GME_HEADER_SIZE + frame * DIRECTORY_FRAME_SIZE);
        }
        return hash;
    case PSEMU_CONTENT_MCS:
        (void)mcs_payload_size(data, size, &payload_size);
        identity_hash_directory_frame(&hash, data);
        identity_hash_title_sector(&hash, data + MCS_HEADER_SIZE, payload_size);
        return hash;
    case PSEMU_CONTENT_APP:
        identity_hash_title_sector(&hash, data, size);
        return hash;
    default:
        identity_hash_update(&hash, data, size);
        return hash;
    }
}

psemu_status psemu_load_content(psemu_t *ps, const uint8_t *data, size_t size) {
    /* This variable has an initial value for the same reason as the one in
       psemu_content_identity_hash: psemu_identify_content gives GME only when gme_card_size was
       successful with these same arguments, and that function is deterministic. Thus the second
       call cannot fail. A value of zero makes a load of no bytes, which leaves flash clear. */
    size_t card_size = 0;
    switch (psemu_identify_content(data, size)) {
    case PSEMU_CONTENT_CARD:
        return psemu_load_flash_image(ps, data, size);
    case PSEMU_CONTENT_GME:
        (void)gme_card_size(data, size, &card_size);
        return psemu_load_flash_image(ps, data + GME_HEADER_SIZE, card_size);
    case PSEMU_CONTENT_MCS:
        return psemu_load_mcs(ps, data, size);
    case PSEMU_CONTENT_APP:
        return psemu_load_app(ps, data, size);
    default:
        /* This behavior is the same as the behavior of the earlier fallback chain. No loader
           accepted the data, thus this code reports the status of the last loader that it tried. It
           does not make a new status. */
        return psemu_load_app(ps, data, size);
    }
}

uint32_t psemu_get_hardware_id(const psemu_t *ps) {
    return flash_get_serial(&ps->flash);
}

void psemu_set_hardware_id(psemu_t *ps, uint32_t id) {
    flash_set_serial(&ps->flash, id);
}

static int hex_digit_value(char c) {
    if (c >= '0' && c <= '9') {
        return c - '0';
    }
    if (c >= 'A' && c <= 'F') {
        return c - 'A' + 10;
    }
    if (c >= 'a' && c <= 'f') {
        return c - 'a' + 10;
    }
    return -1;
}

int psemu_parse_hardware_id(const char *str, uint32_t *out_id) {
    /* This function accepts only one form: exactly 8 hex digits (0-9, A-F, or a-f).
       Real hardware has no rule that the first digit must be a letter.
       A test on real hardware confirms this: a real homebrew ID editor writes and
       keeps a value such as "EEEEEEEE".
       This form is also what that homebrew app shows and changes: 8 raw hex nibbles,
       with no more structure.
       This function does not accept the "sticker" form of one letter and 8 decimal
       digits that real units print below the front cover.
       Thus a hardware-ID string that a frontend keeps shows the value exactly. It does
       not conceal a second, less general encoding.
       A frontend that must accept the sticker form can convert the value before it
       calls this function.
       That conversion is a frontend function. It is not part of the canonical format. */
    uint32_t value;
    int i;

    if (!str) {
        return 0;
    }
    value = 0;
    for (i = 0; i < 8; i++) {
        int d = hex_digit_value(str[i]);
        if (d < 0) {
            return 0;
        }
        value = (value << 4) | (uint32_t)d;
    }
    if (str[8] != '\0') {
        return 0;
    }
    *out_id = value;
    return 1;
}

void psemu_format_hardware_id(uint32_t id, char *buf, size_t buf_size) {
    /* The canonical form (see psemu_parse_hardware_id) is 8 hex digits.
       This form agrees exactly with the on-screen form of a real homebrew ID editor.
       This form can round-trip each value that the real hardware permits.
       The "sticker" form of one letter and 8 decimal digits cannot do this. It cannot
       show a high byte outside the ranges A-Z and a-z. */
    snprintf(buf, buf_size, "%08X", (unsigned)id);
}

void psemu_set_buttons(psemu_t *ps, uint32_t buttons) {
    /* Real hardware asserts the interrupt line of a button at each press edge and each
       release edge (see docs/hardware-notes.md). Real hardware does not use a polled
       level for this function.
       This code converts the PSEMU_BUTTON_* bits of this emulator to the real
       INT_BTN_* bits.
       PSEMU_BUTTON_* is a convention of this emulator. It is not the bit layout of real
       hardware. */
    static const struct {
        uint32_t psemu_bit;
        uint32_t int_bit;
    } button_map[] = {
        {PSEMU_BUTTON_UP, INT_BTN_UP},
        {PSEMU_BUTTON_RIGHT, INT_BTN_RIGHT},
        {PSEMU_BUTTON_DOWN, INT_BTN_DOWN},
        {PSEMU_BUTTON_LEFT, INT_BTN_LEFT},
        {PSEMU_BUTTON_FIRE, INT_BTN_ACTION},
    };
    uint32_t changed = buttons ^ ps->buttons;
    size_t i;

    for (i = 0; i < sizeof(button_map) / sizeof(button_map[0]); i++) {
        if (changed & button_map[i].psemu_bit) {
            intc_set_line(&ps->intc, button_map[i].int_bit, (buttons & button_map[i].psemu_bit) != 0);
        } else if (buttons & button_map[i].psemu_bit) {
            /* The user still holds the button, thus this call has no new edge.
               HOLD must pulse only at the press edge.
               HOLD must not stay latched as a continuous level for the full time that
               the user holds the button.

               A difference from real hardware confirms this. The general system-tick
               callback tests `hold & INT_BTN_ACTION` before its RTC test. A hold bit
               that stays set permanently prevents the RTC redraw path, for the full
               time that the user holds the button. Real hardware continues to redraw
               and blink while the user holds the button. It acts only at release.

               This code does not change STATUS. STATUS continues to follow the live
               level, for code that reads the register directly. */
            intc_clear_hold_only(&ps->intc, button_map[i].int_bit);
        }
    }
    ps->buttons = buttons;
}

/* The time that the CPU must execute no FLASH1 instruction before this code
   treats a dispatched app as stopped. See psemu_app_running in psemu/psemu.h.

   An app in operation spends long periods inside the BIOS: each SWI that the
   app issues executes from the BIOS window until the SWI returns. Thus one
   sample of "the PC is not in FLASH1" has no meaning, and this test must be a
   timeout and not an immediate test.

   The unit is real elapsed time, at the PSEMU_ASSUMED_CPU_HZ reference rate.
   This is the same accumulator that psemu_run supplies to the RTC and the DAC.
   It is also the same unit as the per-frame cycle budget of a frontend. A raw
   step_cycles count is not sufficient: those counts scale with CLK_MODE, and
   the CLK_MODE table covers more than two orders of magnitude. Thus one fixed
   value gives seconds at the idle clock rate, and milliseconds at the fastest
   clock rate.

   One second is much longer than the necessary time. An error in the
   "the app already exited" direction has no visible cost to a frontend: an
   override starts again a few frames later than the earliest possible frame.
   An error in the other direction causes the exact fault that
   psemu_app_running prevents. Thus this value is long enough for each possible
   BIOS call. It is not tuned for a fast response. */
#define APP_EXEC_GRACE_CYCLES ((uint32_t)PSEMU_ASSUMED_CPU_HZ)

/* FLASH1 is the window onto card flash. A dispatched app executes from this
   window (see flash.h). The BIOS never executes from it. */
static void psemu_track_app_execution(psemu_t *ps, uint32_t pc, uint32_t real_time_cycles) {
    if (pc >= PSEMU_FLASH1_BASE && pc < PSEMU_FLASH1_BASE + PSEMU_FLASH_SIZE) {
        ps->app_running = 1;
        ps->app_exec_idle_cycles = 0;
        return;
    }
    if (!ps->app_running) {
        return;
    }
    ps->app_exec_idle_cycles += real_time_cycles;
    if (ps->app_exec_idle_cycles >= APP_EXEC_GRACE_CYCLES) {
        ps->app_running = 0;
        ps->app_exec_idle_cycles = 0;
    }
}

int psemu_app_running(const psemu_t *ps) {
    return ps->app_running;
}

uint32_t psemu_run(psemu_t *ps, uint32_t cycles) {
    if (!ps->has_bios) {
        return 0;
    }
    /* `cycles` is a time budget at the reference clock rate PSEMU_ASSUMED_CPU_HZ (see
       dac.h). See docs/hardware-notes.md, "CLK_MODE", for a summary. This comment gives
       the full reasoning.

       Timer uses the raw step_cycles value, which CLK_MODE scales.
       The System Clock clocks the real timers. Thus they connect directly to the
       variable clock of the CPU. They do not use an independent oscillator.
       A direct measurement confirms this. A Timer at a fixed reference rate made two
       errors:
       - The HELLO animation was approximately 4 times too slow during CLK_MODE 7. The
         same Timer1 heartbeat drives the HELLO animation and the audio. Both are
         confirmed uses of the same IRQ by GUI code.
       - The blink on the date-setting screen was approximately 2 times too fast during
         CLK_MODE 4.
       Both errors agree almost exactly with the ratio between the real Hz value of
       CLK_MODE 7 or 4 and the fixed reference rate: 3.97 times and 2.01 times.
       This confirms that the Timer rate must follow CLK_MODE. The two must not be
       independent.

       The RTC and the DAC stay at real elapsed time for all values of CLK_MODE, for
       different reasons.
       The RTC is a separate oscillator, independent of the CPU clock. Real hardware
       confirms this: its RTC ticks at a constant real 1Hz, with no relation to the
       CPU-frequency setting.
       The DAC resample function of this emulator needs a fixed real-time output rate,
       to supply a usual audio interface. This is true for all rates of DACV writes by
       the app.
       The DACV content still follows CLK_MODE through the Timer: the audio content and
       the pitch change with CLK_MODE, the same as on real hardware.

       A fractional carry (ps->real_time_cycle_carry) converts the real elapsed time of
       each step back into the fixed PSEMU_ASSUMED_CPU_HZ reference rate that the RTC
       and the DAC use. The conversion uses the active clk_current_hz() value. This
       method keeps real time exact across the steps, and prevents an error from integer
       truncation. dac_tick uses this same accumulator pattern internally. */
    double budget_seconds = (double)cycles / (double)PSEMU_ASSUMED_CPU_HZ;
    double elapsed_seconds = 0.0;
    uint32_t ran = 0;
    while (elapsed_seconds < budget_seconds) {
        uint32_t pc = ps->cpu.r[15];
        uint32_t step_cycles;
        /* Software requested a clock stop (see clk.h). Execute nothing, and stop the peripherals
           that use the same oscillator. The System Clock clocks the Timer, thus the Timer also stops
           here. This behavior is important. A wake on each asserted interrupt is not a stop at all,
           because a Timer in operation asserts again in microseconds, and the CPU never pauses.

           The RTC and the DAC keep their own real-time rate. The RTC is a separate oscillator (see
           rtc.h). The DAC resample function uses the fixed output rate of this emulator, which no
           hardware clocks.

           A button is an external signal, and this clock does not drive it. Thus a button can wake
           the CPU. The RTC uses a separate oscillator (see rtc.h), and it ticks during a clock stop.
           Thus an RTC tick can also wake the CPU. An app that steps a walking animation enters clock
           stop between frames and waits for INT_RTC. The wake operation clears the CLK_STOP bit.
           One fact is unconfirmed: whether real hardware clears the CLK_STOP bit automatically, or
           whether the kernel clears it. */
        if (clk_stop_requested(&ps->clk)) {
            static const uint32_t WAKE_SOURCES =
                INT_BTN_ACTION | INT_BTN_RIGHT | INT_BTN_LEFT | INT_BTN_DOWN | INT_BTN_UP | INT_RTC;
            if (ps->intc.hold & ps->intc.enable & WAKE_SOURCES) {
                clk_clear_stop(&ps->clk);
            } else {
                /* This loop uses one reference-rate cycle for each iteration. It does not use one CPU
                   cycle at the current CLK_MODE. No code executes here, thus this interval controls
                   only the resolution of the RTC ticks and the DAC ticks. The reference rate also
                   keeps `ran` correct against the real elapsed time. This function returns `ran` in
                   reference-rate cycles. A count of CPU cycles made `ran` too large, by the ratio
                   between the two rates. That error was as much as approximately 4 times at
                   CLK_MODE 7. Thus a caller that used the return value for its pacing saw the clock
                   of a sleeping device operate too fast. */
                double stopped_dt = 1.0 / (double)PSEMU_ASSUMED_CPU_HZ;
                uint32_t real_time_cycles;
                elapsed_seconds += stopped_dt;
                ps->real_time_cycle_carry += stopped_dt * (double)PSEMU_ASSUMED_CPU_HZ;
                real_time_cycles = (uint32_t)ps->real_time_cycle_carry;
                ps->real_time_cycle_carry -= (double)real_time_cycles;
                rtc_tick(&ps->rtc, &ps->intc, real_time_cycles);
                dac_tick(&ps->dac, real_time_cycles);
                ran += 1u;
                continue;
            }
        }
        step_cycles = arm7tdmi_step(&ps->cpu);
        double dt = (double)step_cycles / (double)clk_current_hz(&ps->clk);
        elapsed_seconds += dt;

        ps->real_time_cycle_carry += dt * (double)PSEMU_ASSUMED_CPU_HZ;
        uint32_t real_time_cycles = (uint32_t)ps->real_time_cycle_carry;
        ps->real_time_cycle_carry -= (double)real_time_cycles;

        psemu_track_app_execution(ps, pc, real_time_cycles);

        timer_tick(&ps->timer, &ps->intc, step_cycles);
        rtc_tick(&ps->rtc, &ps->intc, real_time_cycles);
        dac_tick(&ps->dac, real_time_cycles);
        ir_tick(&ps->ir, &ps->intc, real_time_cycles);
        ran += step_cycles;
    }
    return ran;
}

const uint8_t *psemu_get_framebuffer(const psemu_t *ps) {
    return ps->lcd.presented;
}

/* Converts between the internal reference-rate cycle clock of ir_t and real microseconds.
   It uses the same PSEMU_ASSUMED_CPU_HZ reference rate that psemu_run uses for the RTC and the DAC.
   This conversion occurs only at this public interface.
   The ir_t structure of the core stays in cycle units, the same as each other peripheral. */
int psemu_ir_pop_tx_edge(psemu_t *ps, psemu_ir_edge_t *out_edge) {
    ir_edge_t edge;
    if (!ir_pop_tx_edge(&ps->ir, &edge)) {
        return 0;
    }
    out_edge->timestamp_us = (edge.timestamp_cycles * 1000000ull) / PSEMU_ASSUMED_CPU_HZ;
    out_edge->level = edge.level;
    return 1;
}

void psemu_ir_push_rx_edge(psemu_t *ps, uint64_t local_timestamp_us, int level) {
    uint64_t cycles = (local_timestamp_us * (uint64_t)PSEMU_ASSUMED_CPU_HZ) / 1000000ull;
    ir_push_rx_edge(&ps->ir, cycles, level);
}

uint64_t psemu_ir_get_clock_us(const psemu_t *ps) {
    return (ir_get_clock_cycles(&ps->ir) * 1000000ull) / PSEMU_ASSUMED_CPU_HZ;
}

void psemu_com_set_docked(psemu_t *ps, int docked) {
    com_set_docked(&ps->com, &ps->intc, docked);
}

int psemu_com_get_docked(const psemu_t *ps) {
    return ps->com.docked;
}

/* The ComFlags word in kernel RAM. A published register map gives this address, and it gives SWI 06h
   (GetPtrToComFlags) as the supported method to find it. This function reads the address directly,
   because a host must observe the word without a call into the machine.
   Bit 9 is "Communication Enabled And Docked". No code answers a command while that bit is clear.
   tools/com_probe.c reads the same address. */
#define COMFLAGS_ADDR 0x0C0u
#define COMFLAGS_ENABLED (1u << 9)

int psemu_com_is_enabled(const psemu_t *ps) {
    uint32_t flags = (uint32_t)ps->bus.ram[COMFLAGS_ADDR] | ((uint32_t)ps->bus.ram[COMFLAGS_ADDR + 1] << 8) |
        ((uint32_t)ps->bus.ram[COMFLAGS_ADDR + 2] << 16) | ((uint32_t)ps->bus.ram[COMFLAGS_ADDR + 3] << 24);
    return (flags & COMFLAGS_ENABLED) ? 1 : 0;
}

/* The execution step size of psemu_com_transfer. That function runs the machine in steps of this
   size. It tests the acknowledge line between steps.
   A small value stops the machine near the answer of the kernel. It costs one more psemu_run call
   for each step. A large value runs past the answer. The machine then executes instructions that the
   PS1 did not wait for. That condition is not a fault, because real hardware also executes
   between bytes. It costs only host time.
   The kernel answers from a FIQ handler. Entry and dispatch of that handler cost approximately 98
   raw cycles on real hardware (see docs/hardware-notes.md, "Timing measurements on real hardware").
   Thus this value is near the cost of one answer. */
#define COM_POLL_CHUNK_CYCLES 64u

void psemu_com_set_selected(psemu_t *ps, int selected) {
    com_set_selected(&ps->com, selected);
}

int psemu_com_transfer(psemu_t *ps, uint8_t data_in, uint8_t *data_out, uint32_t timeout_cycles) {
    uint32_t ran = 0;
    int acked;

    /* A byte can only arrive while the PS1 holds the /SEL line. A caller that never sets the
       line still gets a working transfer, because this call holds it. The caller keeps the duty to
       release the line at the end of a command. See psemu_com_set_selected. */
    com_set_selected(&ps->com, 1);
    com_begin_transfer(&ps->com, &ps->intc, data_in);

    while (ran < timeout_cycles && !com_transfer_acked(&ps->com)) {
        uint32_t remaining = timeout_cycles - ran;
        uint32_t chunk = (remaining < COM_POLL_CHUNK_CYCLES) ? remaining : COM_POLL_CHUNK_CYCLES;
        uint32_t did;
        if (psemu_cpu_faulted(ps)) {
            /* The register state and the memory state have no more meaning after this fault. Execute
               no more instructions. Report no acknowledge. See psemu_cpu_faulted. */
            break;
        }
        did = psemu_run(ps, chunk);
        if (did == 0u) {
            /* psemu_run advanced no cycles. This condition must not occur. A loop with no exit is
               worse than a transfer that reports no acknowledge. */
            break;
        }
        ran += did;
    }

    acked = com_transfer_acked(&ps->com);
    if (data_out) {
        *data_out = com_take_reply(&ps->com);
    }
    com_end_transfer(&ps->com, &ps->intc);
    return acked;
}

int psemu_com_transfer_and_select_drop(psemu_t *ps, uint8_t data_in, uint8_t *data_out,
                                       uint32_t timeout_cycles) {
    uint32_t ran = 0;
    int acked;

    /* Assert SELECT before the transfer so the BIOS sees the line in the held state at the
       start of this byte. com_set_selected is a no-op when SELECT is already held (1 -> 1). */
    com_set_selected(&ps->com, 1);
    com_begin_transfer(&ps->com, &ps->intc, data_in);

    /* Drop /SEL before running any cycles. The byte is in the COM buffer (com_begin_transfer
       set COM_STAT2 ready). The FIQ's internal poll loop will read the byte, call the app
       callback, and then check sel_drop_latch. sel_drop_latch is 1 at that check because
       we set it here, before the FIQ executes any of those instructions. */
    com_set_selected(&ps->com, 0);

    while (ran < timeout_cycles && !com_transfer_acked(&ps->com)) {
        uint32_t remaining = timeout_cycles - ran;
        uint32_t chunk = (remaining < COM_POLL_CHUNK_CYCLES) ? remaining : COM_POLL_CHUNK_CYCLES;
        uint32_t did;
        if (psemu_cpu_faulted(ps)) {
            break;
        }
        did = psemu_run(ps, chunk);
        if (did == 0u) {
            break;
        }
        ran += did;
    }

    acked = com_transfer_acked(&ps->com);
    if (data_out) {
        *data_out = com_take_reply(&ps->com);
    }
    com_end_transfer(&ps->com, &ps->intc);
    return acked;
}

int psemu_cpu_faulted(const psemu_t *ps) {
    return ps->cpu.unimplemented;
}

void psemu_write_crash_report(const psemu_t *ps, FILE *f) {
    const arm7tdmi_t *cpu = &ps->cpu;
    int thumb = (cpu->cpsr & CPSR_T) != 0;
    uint32_t i;

    fprintf(f, "psemu diagnostic report\n");
    fprintf(f, "total instructions executed: %llu\n", (unsigned long long)cpu->total_steps);
    fprintf(f, "buttons held: 0x%08X\n", ps->buttons);
    fprintf(f, "clk mode: 0x%08X\n", ps->clk.mode);
    fprintf(f, "cpu faulted (unrecognized opcode): %s\n", cpu->unimplemented ? "YES" : "no");

    /* FLASH1 (0x02000000 and above) is a live virtual window. This code resolves it against
       F_BANK_FLG and F_BANK_VAL.
       FLASH1 is not a fixed offset into FLASH2 (see docs/hardware-notes.md).
       Without this bank data, you cannot convert a FLASH1 address in the trace or the
       registers below back to a physical byte offset for a later static analysis. */
    fprintf(f, "flash F_BANK_FLG (bank_mask): 0x%08X\n", ps->flash.bank_mask);
    fprintf(f, "flash F_BANK_VAL (bank_val[physical] = virtual):\n");
    for (i = 0; i < FLASH_BANK_VAL_COUNT; i++) {
        fprintf(f, "  [%u] = 0x%08X\n", i, ps->flash.bank_val[i]);
    }

    fprintf(f, "\nregisters:\n");
    for (i = 0; i < 15; i++) {
        fprintf(f, "  r%-2u = 0x%08X\n", i, cpu->r[i]);
    }
    fprintf(f, "  pc  = 0x%08X\n", cpu->r[15]);
    fprintf(f, "  cpsr = 0x%08X (mode=0x%02X, %s)\n", cpu->cpsr, cpu->cpsr & CPSR_MODE_MASK,
        thumb ? "thumb" : "arm");

    if (cpu->unimplemented) {
        /* r[15] is already past the instruction that caused the fault. arm7tdmi_step
           advances it before dispatch. This code reverses that advance, to find the
           true fetch address of the instruction. This code then masks the result to the
           natural alignment for this mode. This operation agrees with the crash reporter
           in tools/inspect.c. That reporter did this operation incorrectly at first (see
           docs/hardware-notes.md). */
        uint32_t fault_pc = thumb ? cpu->r[15] - 2u : cpu->r[15] - 4u;
        uint32_t fetch_pc = thumb ? (fault_pc & ~1u) : (fault_pc & ~3u);
        uint32_t raw = thumb ? psemu_bus_read16((psemu_bus_t *)&ps->bus, fetch_pc)
                              : psemu_bus_read32((psemu_bus_t *)&ps->bus, fetch_pc);
        fprintf(
            f, "\nfault: unrecognized %s opcode 0x%0*X, fetched from 0x%08X (pc was 0x%08X before advancing)\n",
            thumb ? "thumb" : "arm", thumb ? 4 : 8, raw, fetch_pc, fault_pc);
    }

    {
        uint32_t count = cpu->total_steps < PSEMU_TRACE_SIZE ? (uint32_t)cpu->total_steps : PSEMU_TRACE_SIZE;
        uint32_t start = (cpu->trace_pos - count + PSEMU_TRACE_SIZE) % PSEMU_TRACE_SIZE;
        fprintf(f, "\nlast %u executed PCs (oldest first):\n", count);
        for (i = 0; i < count; i++) {
            uint32_t idx = (start + i) % PSEMU_TRACE_SIZE;
            fprintf(
                f, "  pc=0x%08X %s\n", cpu->trace[idx].pc, (cpu->trace[idx].cpsr & CPSR_T) ? "(thumb)" : "(arm)");
        }
    }
}

int psemu_framebuffer_dirty(psemu_t *ps) {
    int was_dirty = ps->lcd.dirty;
    ps->lcd.dirty = 0;
    return was_dirty;
}

void psemu_lcd_clear_rot(psemu_t *ps) {
    uint8_t byte0 = (uint8_t)(ps->lcd.mode & 0xFFu);
    lcd_mode_write8(&ps->lcd, 0u, byte0 & ~(uint8_t)LCD_MODE_ROT);
}

/* Settings that the BIOS owns. These settings are in RAM. See
   docs/hardware-notes.md for the method that found each address. No published
   register map gives them.

   PSEMU_RAM_VOLUME: the sound setting. The BIOS reads this byte, but it never
   writes the byte at boot. Thus on real hardware the byte continues only
   because the battery keeps the SRAM powered.
   PSEMU_RAM_CENTURY_SCREEN: the century that the BIOS clock screen shows.
   PSEMU_RAM_CENTURY_SWI: the century that GetBcdDate (SWI 0Dh) returns to apps.
   These two bytes are separate, and a test confirms that they can disagree:
   the SWI reported 2026 while the screen showed 1926. */
#define PSEMU_RAM_VOLUME 0x290u
#define PSEMU_RAM_CENTURY_SCREEN 0x426u
#define PSEMU_RAM_CENTURY_SWI 0x0CFu

void psemu_set_volume(psemu_t *ps, uint8_t level) {
    ps->bus.ram[PSEMU_RAM_VOLUME] = level;
}

uint8_t psemu_get_volume(const psemu_t *ps) {
    return ps->bus.ram[PSEMU_RAM_VOLUME];
}

void psemu_set_volume_override(psemu_t *ps, uint8_t level) {
    ps->bus.ram[PSEMU_RAM_VOLUME] = level;
    ps->bus.ram_lock_addr = PSEMU_RAM_VOLUME;
    ps->bus.ram_lock_value = level;
}

void psemu_clear_volume_override(psemu_t *ps) {
    ps->bus.ram_lock_addr = PSEMU_RAM_SIZE;
    ps->bus.ram_lock_value = 0;
}

static uint8_t to_bcd(int value) {
    return (uint8_t)(((value / 10) << 4) | (value % 10));
}

int psemu_set_datetime(psemu_t *ps, int year, int month, int day, int hour, int minute, int second, int dow) {
    if (year < 0 || year > 9999 || month < 1 || month > 12 || day < 1 || day > 31 || hour < 0 || hour > 23 ||
        minute < 0 || minute > 59 || second < 0 || second > 59 || dow < 1 || dow > 7) {
        return 0;
    }
    /* PRGSEL: the BIOS is in the middle of a clock adjustment with
       RTC_ADJUST increments. A write to the registers now can prevent the
       loop from reaching its target. See the comment on this function in
       psemu/psemu.h. */
    if (ps->rtc.mode & 1u) {
        return 0;
    }

    /* RTC_DATE holds the day, the month, and the year, from the LSB up.
       RTC_TIME holds the seconds, the minutes, the hours, and the day of
       the week. See core/src/rtc.h. */
    ps->rtc.date = ((uint32_t)to_bcd(year % 100) << 16) | ((uint32_t)to_bcd(month) << 8) | to_bcd(day);
    ps->rtc.time = ((uint32_t)to_bcd(dow) << 24) | ((uint32_t)to_bcd(hour) << 16) | ((uint32_t)to_bcd(minute) << 8) |
                   to_bcd(second);

    uint8_t century = to_bcd(year / 100);
    ps->bus.ram[PSEMU_RAM_CENTURY_SCREEN] = century;
    ps->bus.ram[PSEMU_RAM_CENTURY_SWI] = century;
    return 1;
}

/* FNV-1a over the full 16KB BIOS image. This table holds each revision whose
   RAM layout a trace has confirmed. This code uses a hash and not a signature
   scan, because a hash fails safe: an unknown or changed image turns the
   function off. It does not write to addresses that have a different function
   in that image. Add an entry only after you verify the addresses above
   against that revision. */
static const uint32_t known_bios_hashes[] = {
    0xB2E46838u, /* J110 (retail, docs/hardware-notes.md "BIOS/kernel revisions") */
};

int psemu_settings_offsets_known(const psemu_t *ps) {
    if (!ps->has_bios) {
        return 0;
    }
    uint32_t hash = 2166136261u;
    for (size_t i = 0; i < PSEMU_BIOS_SIZE; i++) {
        hash ^= ps->bus.bios[i];
        hash *= 16777619u;
    }
    for (size_t i = 0; i < sizeof(known_bios_hashes) / sizeof(known_bios_hashes[0]); i++) {
        if (hash == known_bios_hashes[i]) {
            return 1;
        }
    }
    return 0;
}

uint32_t psemu_get_audio_samples(psemu_t *ps, int16_t *buf, uint32_t max_samples) {
    return dac_read_samples(&ps->dac, buf, max_samples);
}
