/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_H
#define PSEMU_H

#include <stddef.h>
#include <stdint.h>
#include <stdio.h>

#ifdef __cplusplus
extern "C" {
#endif

#define PSEMU_LCD_WIDTH 32
#define PSEMU_LCD_HEIGHT 32
#define PSEMU_LCD_STRIDE (PSEMU_LCD_WIDTH / 8)

#define PSEMU_BIOS_SIZE (16 * 1024)
#define PSEMU_FLASH_SIZE (128 * 1024)
/* Work RAM at 0x00000000. The range 0x000-0x1FF is kernel memory. The range
   0x200-0x7FF is user memory. See docs/hardware-notes.md, "Memory map".
   This constant is public because a frontend must know the size to expose RAM
   for host-side introspection. See psemu_ram_data. PSEMU_RAM_BASE and the
   remainder of the address map stay internal to core/src/memory.h. */
#define PSEMU_RAM_SIZE 0x800u

typedef enum {
    PSEMU_BUTTON_UP = 1 << 0,
    PSEMU_BUTTON_RIGHT = 1 << 1,
    PSEMU_BUTTON_DOWN = 1 << 2,
    PSEMU_BUTTON_LEFT = 1 << 3,
    PSEMU_BUTTON_FIRE = 1 << 4
} psemu_button;

typedef enum {
    PSEMU_OK = 0,
    PSEMU_ERR_BAD_SIZE = -1,
    PSEMU_ERR_BAD_FORMAT = -2,
    PSEMU_ERR_NO_BIOS = -3
} psemu_status;

typedef struct psemu psemu_t;

psemu_t *psemu_create(void);
void psemu_destroy(psemu_t *ps);
void psemu_reset(psemu_t *ps);

/* `data` must be exactly PSEMU_BIOS_SIZE bytes. */
psemu_status psemu_load_bios(psemu_t *ps, const uint8_t *data, size_t size);

/* Loads a PSX Title Sector app image into the emulator.
   This function makes a synthesized memory-card directory with one entry at slot 1.
   The real BIOS menu finds the app in this directory and dispatches it.
   Dispatch operates the same way as dispatch from a full memory card.
   To go past the date/time screen, press Down, then Action.
   To start the app, press Right, then Action.
   See docs/app-notes.md, "App-selection and dispatch", for more data.
   The maximum value of `size` is 15 blocks. See PSEMU_FLASH_SIZE.
   This limit is one block less than a full card, because block 0 holds the
   synthesized directory. */
psemu_status psemu_load_app(psemu_t *ps, const uint8_t *data, size_t size);

/* Extracts the PSX Title Sector from a single-save .mcs file and loads it.
   A .mcs file starts with a PS1 memory-card directory frame of 0x80 bytes.
   The raw data blocks of the save come after that frame.
   Most PS1 save tools use this layout for a single-save export.
   This function loads the Title Sector the same way as psemu_load_app.
   `data` must start with the directory frame, not with the Title Sector body.
   To load a Title Sector body directly, use psemu_load_app. */
psemu_status psemu_load_mcs(psemu_t *ps, const uint8_t *data, size_t size);

/* Loads a raw FLASH2 image into flash directly. A full memory-card dump is one example.
   This function does not do the single-Title-Sector validation that psemu_load_app does.
   Use this function for a real card image that contains its own directory.
   The BIOS app-selection menu then finds and starts the apps on that card.
   This operation is the same as the operation on real hardware.
   See docs/app-notes.md.
   The only other necessary call is psemu_set_buttons.
   `size` can be less than PSEMU_FLASH_SIZE.
   The remainder of flash stays at zero. */
psemu_status psemu_load_flash_image(psemu_t *ps, const uint8_t *data, size_t size);

/* Copies the full FLASH2 image out, in the condition an app left it.
   This function is the inverse of psemu_load_flash_image.
   The layout is the raw layout that a .mcd file uses.
   Thus you can write the result back over the card image that supplied it.

   This function is necessary because the real output of an app is frequently not the
   state of the app. The output is often a change to the save of the PS1 game in
   a different block of the same card. One trading-card app sends cards into the save of
   its PS1 game in this manner. See docs/app-notes.md, "How an app reaches the PS1
   save on the same card". Without a method to get flash out again, that change stays in
   one emulator session and is then lost.

   `size` must be equal to or more than PSEMU_FLASH_SIZE. If `size` is less, this
   function returns PSEMU_ERR_BAD_SIZE. Be careful if you loaded a .mcs or .pss file and
   then write the result to a file. Flash then holds a synthesized directory around a
   moved save (see psemu_load_mcs). Flash does not hold the file that you loaded. */
psemu_status psemu_save_flash_image(const psemu_t *ps, uint8_t *buf, size_t size);

/* A stable, writable pointer to the full FLASH2 image. These are the same bytes that
   psemu_save_flash_image copies out, but without the copy. The pointer stays valid until
   psemu_destroy. psemu_load_state does NOT make the pointer invalid: that function copies
   into this instance and connects the internal pointers of the instance again. It does
   not replace the instance. psemu_reset does not change the contents, in the same way
   that it does not change other loaded content.

   This pointer is for a frontend whose host keeps a memory region of fixed size for the
   frontend. Such a host does not call a save function. The libretro frontend in this
   repository uses this pointer for its save-memory interface. The host reads and writes
   the region directly, on the schedule of the host.

   THE SHAPE OF THIS REGION IS A COMPATIBILITY CONTRACT. IT IS NOT AN IMPLEMENTATION
   DETAIL. The region is exactly PSEMU_FLASH_SIZE bytes of raw card image, in the layout
   that a .mcd file uses. Because of this, a dump of this region that a host writes is a
   .mcd file, byte for byte. psemu_identify_content reads such a dump back as
   PSEMU_CONTENT_CARD, and PS1 memory-card tools open it directly. This is how a user
   gets a card out of this emulator. Do not make this region smaller for .mcs or .pss
   content, and do not add a header. Either change makes every file from an older build
   invalid, and gives no error. */
uint8_t *psemu_flash_data(psemu_t *ps);

/* A stable, writable pointer to PSEMU_RAM_SIZE bytes of work RAM. The lifetime and
   stability rules are the same as the rules for psemu_flash_data.

   This is live working memory. It is NOT save data. psemu_reset sets it to zero, and a
   frontend must not keep it: flash holds all durable data. This pointer is for host-side
   introspection, when the host must monitor the RAM of the machine during operation. The
   libretro frontend in this repository exposes it as system RAM, which supplies the data
   for host cheat-search and memory-viewer functions. */
uint8_t *psemu_ram_data(psemu_t *ps);

/* Finds the content type of `data` from its size and its content. This function does not
   use a file extension. It then loads `data` with the applicable loader.
   All frontends must call this function. A frontend must not repeat this dispatch logic.
   The frontends contained two copies of this logic before, and the two copies became
   different.

   Dispatch rules:
   - If `data` is exactly PSEMU_FLASH_SIZE bytes, this function treats it as a full
     memory-card image. It then calls psemu_load_flash_image.
   - If `data` starts with the DexDrive magic, and the bytes after its 3904-byte header
     are a whole number of card blocks and are not more than PSEMU_FLASH_SIZE, this
     function treats it as a .gme dump. It gives those bytes to psemu_load_flash_image,
     which makes each block that the file does not hold zero. A dump that ends early is
     thus still usable: the directory in block 0 gives the state of each block.
   - If not, this function first tries `data` as a single-save .mcs file, with
     psemu_load_mcs. Single-save exports are much more frequent than Title Sector dumps.
   - If that attempt fails, this function tries `data` as a Title Sector .pss file, with
     psemu_load_app.
   - If no loader accepts the data, this function returns the status of the last loader
     that it tried. */
psemu_status psemu_load_content(psemu_t *ps, const uint8_t *data, size_t size);

/* Gives the result that psemu_load_content would give for `data`. This function does not
   load the data and has no side effects. It IS the dispatch logic of psemu_load_content:
   that function calls this one. Thus a caller cannot become different from it, which can
   occur with a separate size check or extension check.

   A frontend needs this function to write content out again. The three content kinds
   round-trip differently, and only the caller knows the source file of the bytes. See
   psemu_save_app_image. */
typedef enum {
    PSEMU_CONTENT_UNKNOWN = 0, /* no loader accepts the data */
    PSEMU_CONTENT_CARD,        /* full memory-card image, exactly PSEMU_FLASH_SIZE bytes */
    PSEMU_CONTENT_MCS,         /* single-save export: a directory frame of 0x80 bytes, then the app body */
    PSEMU_CONTENT_APP,         /* Title Sector body, with no directory frame */
    PSEMU_CONTENT_GME          /* DexDrive card dump: 3904-byte header, then a whole number of card blocks */
} psemu_content_kind;

psemu_content_kind psemu_identify_content(const uint8_t *data, size_t size);

/* A stable identity for loaded content. The hash identifies which app or card this is.
   It does not identify the data that the content holds at this time. A hash of the full
   file cannot give this identity, because an app save changes the full file.

   This difference became important when a frontend got the ability to write content to
   disk. A save state contains its own copy of flash. Thus, if you load a save state onto
   the wrong card, it replaces that full card, and all subsequent app writes go into the
   wrong file. The guard must continue to operate after an app saves. If it does not, it
   obstructs the user frequently, and a user then removes it. The guard is then absent for
   the condition that causes damage.

   The hash uses these bytes, for each content kind:
   - CARD: the allocation state of each directory frame, and the file name of each frame
     in use. This data shows which saves are on this card. An app that writes save data
     does not change this data.
   - MCS: the directory frame of the file (state and name), then the title-sector metadata
     of its body.
   - APP: only the title-sector metadata of the body.
   - UNKNOWN: the full buffer, because no better data is available.

   "Title-sector metadata" is all data up to the end of the standard PS1 icon. This
   includes the header, the Shift-JIS title, the CLUT, and the icon frames. All of this
   data must stay valid, or the BIOS cannot dispatch the app. Thus an app cannot use this
   data for save data. This data is also what a person means by the identity of an app. A
   test with a real app and a real save confirms this. In that test, an IR card trade
   changed bytes at save-data offsets 0x59 and 0x340, which are after this region.

   TWO CARDS THAT HOLD THE SAME FILES GIVE THE SAME HASH. No change can correct this: a
   real PS1 memory card has no serial number, thus there is no method to identify two
   identical cards. A frontend that must keep them separate must also use the file path.
   The desktop frontend already gives each save-state file the name of its card. */
uint32_t psemu_content_identity_hash(const uint8_t *data, size_t size);

/* Copies `size` bytes of the body of the loaded app out. This function is the inverse of
   psemu_load_app. It is also the inverse of the part of psemu_load_mcs that comes after
   the directory frame. `size` is the payload size of the caller. For a .pss file, this is
   the full file. For a .mcs file, this is the file minus its first frame of 0x80 bytes.

   To build a .mcs file again, write that same frame of 0x80 bytes in front of this data.
   The frame gives the properties of the file (size, name, and link). It does not give the
   contents of the file, and an app cannot access it. Thus the copy of the frame in the
   loaded file stays correct.

   ONLY THE BLOCKS OF THE APP ROUND-TRIP. A loaded app operates in a memory card that this
   emulator synthesizes around the app (see psemu_load_app). No other part of that card is
   in the file: not the directory, and not any other block. An app can write outside its
   own blocks, because FLASH2 shows it the full synthesized card. A .mcs or .pss file has
   no space for those bytes, thus this function discards them. If an app needs more space
   than its own blocks, load it as part of a real .mcd card.

   Returns PSEMU_ERR_BAD_SIZE if `size` is 0, or if `size` is more than the 15 blocks that
   an app can use. */
psemu_status psemu_save_app_image(const psemu_t *ps, uint8_t *buf, size_t size);

void psemu_set_buttons(psemu_t *ps, uint32_t buttons);

/* F_SN is the hardware serial number of the PocketStation.
   Apps read F_SN with SWI 0Ah (FlashReadSerial).
   The companion app of one PS1 game reads F_SN when the user makes a new save.
   That app removes the high byte of F_SN.
   It then uses the last 3 decimal digits of the remaining value as an "ID" statistic.
   This "ID" statistic alone sets the rank: the maximum HP, the weapon value, and the
   item-drop probability.
   A disassembly of a real copy of that game confirms this behavior.
   See docs/hardware-notes.md.

   PSEMU_DEFAULT_HARDWARE_ID is equivalent to the string "410000D3". See
   psemu_parse_hardware_id.
   The low 24 bits of this default value are 211, and public research gives 211 as the
   best rank. Thus a new save from that app gets the best rank with no user action.
   A frontend can call psemu_set_hardware_id before it loads content. Use this call to
   restore a value that the frontend kept from an earlier session. For example, restore a
   value after a user changes it with a homebrew ID-editor app. */
#define PSEMU_DEFAULT_HARDWARE_ID (((uint32_t)'A' << 24) | 211u)
uint32_t psemu_get_hardware_id(const psemu_t *ps);
void psemu_set_hardware_id(psemu_t *ps, uint32_t id);

/* The human-readable form is exactly 8 hex digits (0-9, A-F, or a-f).
   A homebrew "ID rewriter" app shows and changes this same form on the screen of a real
   PocketStation.
   Each digit on the screen is one hex nibble of the raw F_SN register.
   Tests on real hardware show that the first digit does not have to be a letter.
   A real unit accepts and keeps a value such as "EEEEEEEE".
   This function accepts only this 8-hex-digit form.
   A hardware-ID string that a frontend keeps holds the raw value exactly. The string
   hides nothing and translates nothing.

   Real units also show a "sticker" form below the front cover. The sticker form is one
   ASCII letter and then 8 decimal digits, for example "A02374684". The sticker form is a
   different and less general encoding. It cannot show every value that the hardware
   permits. A frontend must do all conversion of the sticker form. This function does not
   do that conversion.

   If the operation is successful, this function returns a nonzero value and writes
   *out_id. If the operation fails, this function returns 0 and does not change *out_id. */
#define PSEMU_HARDWARE_ID_STRING_SIZE 9 /* 8 hex digits and '\0' */
int psemu_parse_hardware_id(const char *str, uint32_t *out_id);
/* The inverse of psemu_parse_hardware_id. This function writes only the canonical
   8-hex-digit form. `buf` must be PSEMU_HARDWARE_ID_STRING_SIZE bytes or more. */
void psemu_format_hardware_id(uint32_t id, char *buf, size_t buf_size);

/* This value is the reference clock rate for the cycle budget of psemu_run. The unit is Hz.

   The CPU of real hardware runs at a variable rate, and CLK_MODE selects that rate. The `cycles`
   argument of psemu_run is NOT in those variable cycles. It is in the fixed reference rate here.
   psemu_run converts between the two. The RTC and the DAC also use this rate.

   A frontend needs this value to pace the machine. The budget for one frame is this rate divided by
   the frame rate of the frontend. The screen of the device refreshes at approximately 32Hz, thus
   PSEMU_REFERENCE_CLOCK_HZ / 32 is one frame of the device. A host with a different rate divides by
   its own rate. One example of such a host is a PS1 emulator at 60Hz. */
#define PSEMU_REFERENCE_CLOCK_HZ 1056000u

/* Runs for approximately `cycles` CPU cycles. Returns the number of cycles that it
   executed. */
uint32_t psemu_run(psemu_t *ps, uint32_t cycles);

/* Settings that the BIOS owns. These settings are in RAM, not in a hardware register.
   docs/hardware-notes.md gives data on both settings, in "System sound volume setting"
   and "Where the date/time settings actually live". A trace of a real BIOS found both
   addresses. No published register map gives them.

   A frontend uses these functions to hold a setting at a selected value. The addresses
   are usual RAM, and the BIOS menus also write to them. Thus, to "force" a value, the
   frontend must write the value again, usually one time for each rendered frame. This
   cost is a few byte stores. A frame already executes tens of thousands of instructions.

   These functions never read the host clock or other ambient state. Thus the emulator
   stays deterministic. A caller that wants the current time must supply it. */

/* The three values that the BIOS sound menu cycles through. A larger value gives more
   attenuation. The BIOS gives 0x04 a special function: full silence. */
#define PSEMU_VOLUME_LOUD 0x00u
#define PSEMU_VOLUME_SOFT 0x02u
#define PSEMU_VOLUME_MUTE 0x04u

/* Writes the volume byte one time, or reads the byte back. The BIOS sound menu or an app
   can write over the byte after that. */
void psemu_set_volume(psemu_t *ps, uint8_t level);
uint8_t psemu_get_volume(const psemu_t *ps);

/* Holds the volume at `level` until psemu_clear_volume_override. This function writes the
   value now, writes the value again at each psemu_reset, and makes the byte read-only to
   emulated code. Thus the BIOS sound menu has no more effect, which is the function of an
   override.

   For this setting, a write for each frame is NOT sufficient. This is why the setting
   needs its own function. The value must be in position before the BIOS reads it during
   sound initialization. The BIOS also clears RAM early in its boot sequence. Both of
   these events occur in the first emulated frame of the frontend, and the boot sound is
   complete at the end of that frame. A write for each frame gets no opportunity between
   the two events. Thus the boot sound plays at full volume, whatever number of writes the
   frontend does. See docs/hardware-notes.md, "System sound volume setting".

   On real hardware, battery-backed SRAM holds the byte, and the BIOS uses the byte as
   existing state. This emulator always does a cold boot. Thus the lock does the function
   of the battery.

   The cost is one compare on the RAM write path, and nothing on the read path or the
   opcode-fetch path. Use psemu_settings_offsets_known as a guard for these calls, as for
   psemu_set_volume. */
void psemu_set_volume_override(psemu_t *ps, uint8_t level);
void psemu_clear_volume_override(psemu_t *ps);

/* Sets the clock that the BIOS and the apps report.
   `year` is a full year, for example 2026. `dow` is the day of the week: 1 is Sunday and
   7 is Saturday.

   This function writes the time registers and the date registers of the RTC. It also
   writes BOTH century bytes: the byte that the BIOS clock screen shows, and the different
   byte that GetBcdDate (SWI 0Dh) returns to apps. If you write only one byte, the year on
   the screen and the year that an app sees do not agree.

   This function returns 0 and changes nothing in two conditions: if an argument is out of
   range, or if the RTC is in program mode (RTC_MODE bit 0, PRGSEL). The BIOS programs the
   clock with a loop of RTC_ADJUST increments, until each field gets to a target value. If
   you write over the registers during that loop, the loop can continue for an unlimited
   time. A caller must try again at the next frame. */
int psemu_set_datetime(psemu_t *ps, int year, int month, int day, int hour, int minute, int second, int dow);

/* Returns a nonzero value if this emulator has verified the RAM layout of the loaded BIOS
   revision.

   The addresses that psemu_set_volume and psemu_set_datetime write were traced against
   specific BIOS revisions. On a different revision, these addresses are arbitrary kernel
   RAM. A write to them then corrupts other state and gives no error. A frontend must
   disable all settings-override controls when this function returns 0. Returns 0 if no
   BIOS is loaded. */
int psemu_settings_offsets_known(const psemu_t *ps);

/* Returns a nonzero value while a dispatched app owns the machine, and not the BIOS
   shell. psemu_run finds this condition from the location of the instruction fetches: an
   app executes from the FLASH1 window, and the BIOS never does this.

   This function limits the scope of the settings overrides above. The addresses of those
   overrides are BIOS-owned RAM only while the BIOS shell operates. When the BIOS
   dispatches an app from the card, that RAM becomes the RAM of the app. A frontend that
   continues to apply an override for each frame then writes into the memory of an app in
   operation, 32 times each second. This corrupts app state. With a real card, this is
   sufficient to make an app reject its own save data as invalid.

   Thus you must use this function and psemu_settings_offsets_known together, as guards
   for an override that occurs each frame. Overrides then stay in effect during the BIOS
   shell, stop while an app operates, and start again when control returns.

   This function returns to 0 a short time after the app stops execution. It does not
   return to 0 immediately. An app is inside the BIOS for the duration of each SWI that it
   issues, and these periods must not read as "the app exited". A psemu_reset clears the
   condition immediately. */
int psemu_app_running(const psemu_t *ps);

/* 1bpp, row-major, PSEMU_LCD_STRIDE bytes for each row. Bit 0 is the leftmost pixel. */
const uint8_t *psemu_get_framebuffer(const psemu_t *ps);

/* Returns a nonzero value one time for each framebuffer change, then clears the flag. */
int psemu_framebuffer_dirty(psemu_t *ps);

/* Clears the ROT bit of LCD_MODE and recomputes the presented framebuffer.
   The standalone orientation has ROT clear. The docked orientation has ROT set.
   The dock interrupt handler sets ROT. The undock transition does not clear it: the interrupt
   controller does not fire on the falling edge of INT_IOP, so the kernel never runs an undock
   handler. Call this function before a state save to store the standalone orientation. */
void psemu_lcd_clear_rot(psemu_t *ps);

/* This is the fixed output rate of psemu_get_audio_samples.
   Real hardware has no fixed sample rate. Software controls the DAC directly, bit by bit
   (see dac.h).
   This emulator selects this resample rate. */
#define PSEMU_AUDIO_SAMPLE_RATE_HZ 8000

/* Moves a maximum of max_samples samples into buf. The samples are mono, signed 16-bit
   PCM, at PSEMU_AUDIO_SAMPLE_RATE_HZ.
   Returns the number of samples that it wrote.
   Call this function at regular intervals, for example one time for each rendered frame.
   Send the result to an audio output interface. */
uint32_t psemu_get_audio_samples(psemu_t *ps, int16_t *buf, uint32_t max_samples);

/* IR link. These functions show the IR peripheral of the core as a queue of edges.
   psemu_get_audio_samples uses the same pull/push shape. This shape lets a frontend
   operate real I/O, and the core knows nothing about the transport of that I/O.
   The core models IR as an asynchronous edge relay between two instances that have
   independent clocks. Real IR hardware is two separate devices, and they share no clock.
   Thus there is no lockstep timing assumption here. Each instance uses only its own local
   clock.

   An "edge" is a transition of the demodulated IR signal. Level 1 means that the carrier
   or the LED came on. Level 0 means that it went off. The core relays only that on/off
   envelope. It does not model the 40kHz sub-carrier inside an on interval, thus BGEN does
   not gate an edge. See core/src/ir.h for the full model, which covers the transmit-emit
   condition (IFMODE and STDBY), the BFLT debounce, and INT_IRDA delivery.

   Usual use, one time for each rendered frame, after psemu_run:
   - Call psemu_ir_pop_tx_edge in a loop to drain the queue. Relay each edge. One example
     of a destination is a local transport to a different instance.
   - For each edge that comes from that other instance, convert the timestamp of the edge
     into the timeline of this instance. Then call psemu_ir_push_rx_edge. */
typedef struct {
    uint64_t timestamp_us; /* on the IR clock of this instance. See psemu_ir_get_clock_us. */
    int level;
} psemu_ir_edge_t;

/* Gets the next TX edge that this instance made.
   Returns 1 and fills *out_edge. Returns 0 if no edges are pending. */
int psemu_ir_pop_tx_edge(psemu_t *ps, psemu_ir_edge_t *out_edge);

/* Puts an RX edge from an external source into the queue.
   This function delivers the edge at `local_timestamp_us`, on the IR clock of this
   instance.
   A caller that relays edges from a different instance must first convert the timestamp
   of the edge into the timeline of this instance.
   The two IR clocks are not synchronized with each other. One conversion method is a
   shared wall-clock reference that both instances can read. */
void psemu_ir_push_rx_edge(psemu_t *ps, uint64_t local_timestamp_us, int level);

/* The local IR clock of this instance, in microseconds.
   Nothing synchronizes this clock to a different clock.
   It is useful only as one half of a timestamp conversion. See psemu_ir_push_rx_edge. */
uint64_t psemu_ir_get_clock_us(const psemu_t *ps);

/* Communication port. These functions connect this emulator to an emulated PS1. The connection goes
   through the memory card connector. A PocketStation is a memory card in that connector.

   THE PROTOCOL IS NOT HERE. The BIOS of the emulated machine holds the protocol. A byte from the
   PS1 raises the COM interrupt. The FIQ handler of the kernel then answers that byte. That
   handler holds the three memory card commands: 0x52 Read Sector, 0x53 Get ID, and 0x57 Write
   Sector. It also holds the PocketStation commands 0x50, and 0x58 to 0x5F. Thus a caller supplies
   bytes and receives bytes. A caller must not implement a command.

   This division is necessary. The commands 0x5B and 0x5C execute a function number. The numbers 0x80
   to 0xFF resolve through a function table in the header of the app file. Only the app holds that
   code. Thus no protocol code outside this emulator can answer those commands.

   A caller needs a loaded BIOS. Without a BIOS, no code answers a transfer. Each transfer then
   reports no acknowledge.

   Usual use, from the memory card transfer path of a PS1 emulator:
   - Call psemu_com_set_docked(ps, 1) one time. Do this when the user puts the device in the slot.
     Then run the machine for several frames. The kernel enables communication in response to the
     docking signal. A stopped machine cannot do this.
   - Call psemu_com_transfer for each byte of a transfer. Supply the byte from the PS1. Send the
     reply back to the PS1.
   - Call psemu_com_set_docked(ps, 0) when the user removes the device. */

/* Sets the docking sense. `docked` is 0 for undocked. A nonzero value is the docked condition.
   Real hardware senses the supply voltage on the connector. The kernel enables communication only
   while it senses the docked condition. It disables communication again at removal. The kernel also
   uses this signal for three more operations: it powers down the audio DAC, it powers down the
   infrared transceiver, and it rotates the screen. ComFlags bits 16 to 18 select those operations. */
void psemu_com_set_docked(psemu_t *ps, int docked);

/* Returns the last value that psemu_com_set_docked received. */
int psemu_com_get_docked(const psemu_t *ps);

/* Returns a nonzero value while the kernel accepts a command.

   A HOST MUST WAIT FOR THIS CONDITION AFTER IT DOCKS THE DEVICE. psemu_com_set_docked only asserts
   the docking level. The kernel senses that level in an interrupt handler, and it then enables
   communication. That handler waits before it reads the level again, because a real connector has a
   switch-bounce period. Thus the condition arrives one frame or more after the call, and a transfer
   before it gets no answer.

   The usual sequence is a call to psemu_com_set_docked(ps, 1), and then psemu_run for one frame at a
   time until this function returns a nonzero value. A host that gives up must report the device as
   absent, because no command can succeed. */
int psemu_com_is_enabled(const psemu_t *ps);

/* Sets the /SEL line of the connector. `selected` is 0 for released. A nonzero value holds the line.
   A PS1 holds this line for the full duration of one command. It releases the line between
   commands. On a PS1 emulator this line is the select bit of the controller port.

   A CALLER MUST RELEASE THIS LINE AFTER EACH COMMAND. The kernel waits for the release, and that
   wait is how it learns that a command ended. Without the release, the kernel answers the first
   command and then answers nothing. psemu_com_transfer holds the line for each byte that it sends,
   thus a caller only has to release it.

   The usual sequence for one command is a call to psemu_com_transfer for each byte, and then a call
   to psemu_com_set_selected(ps, 0). Run the machine for a short time after the release, so the
   kernel can leave its wait and make its command state ready again. */
void psemu_com_set_selected(psemu_t *ps, int selected);

/* Exchanges one byte with the PS1.
   `data_in` is the byte from the PS1. This function writes the reply of the device to
   *data_out.

   This function RUNS THE CPU. An interrupt handler on real hardware answers a byte. Thus the
   emulated machine must execute before the reply exists. This function executes a maximum of
   `timeout_cycles` cycles. It stops when the kernel drives the acknowledge line.

   It returns a nonzero value after the device acknowledges the byte. It returns 0 if the device does
   not answer inside the budget. A PS1 gives a missing acknowledge one of two meanings: the
   device is absent, or the command is complete. A caller gives a 0 return the same two meanings. The
   acknowledge of a memory card carries the same meaning.

   *data_out gets 0xFF if the device supplies no reply byte. An idle data line on this bus is high.

   PSEMU_COM_DEFAULT_TIMEOUT_CYCLES is a sufficient budget for `timeout_cycles` in the docked
   condition. A caller can supply a larger value.

   The `wait` mode of tools/com_probe.c measured the cost of one answer against a real J110 dump. The
   first byte of a command costs 177 reference cycles, because the kernel must take the FIQ. Each
   byte after the first byte costs 52 to 112 cycles, because the kernel already polls COM_STAT2
   inside that same FIQ. Thus 177 cycles is the worst measured answer.

   THIS COST SCALES WITH CLK_MODE. That measurement used CLK_MODE 7 (3,997,696Hz), and the kernel
   selects that mode when it enables communication in the docked condition. The budget here is in
   PSEMU_ASSUMED_CPU_HZ reference cycles (1,056,000Hz). Thus a slower clock makes the same work cost
   more reference cycles. The measured 177 cycles is approximately 670 CPU cycles. That work costs
   approximately 5600 reference cycles at CLK_MODE 2, and approximately 21600 at CLK_MODE 0.

   This value covers CLK_MODE 2 and each faster mode. It is approximately 46 times the worst measured
   answer at the docked clock. An app that slows the clock below CLK_MODE 2 while it is docked needs
   a larger budget from the caller.

   Do not make this value very large. A command gives no acknowledge for its last byte, because the
   command is complete at that point. Thus every command reaches this budget one time, and the
   machine executes for the full budget before this function returns. */
#define PSEMU_COM_DEFAULT_TIMEOUT_CYCLES 8192u
int psemu_com_transfer(psemu_t *ps, uint8_t data_in, uint8_t *data_out, uint32_t timeout_cycles);

/* Like psemu_com_transfer, but releases /SEL immediately after the byte enters the COM buffer,
   before running any ARM cycles.
   Use this for the last byte of a dispatch command (0x5B/0x5C). The FIQ stays in FIQ mode
   for the full command (it polls COM_STAT2 internally for each byte). After the last byte, the
   FIQ reads the byte from COM_STAT2, calls the app callback, and then checks sel_drop_latch
   via COM_STAT1. That check runs inside the FIQ before any ARM cycles from the caller execute.
   Releasing /SEL before psemu_run guarantees that sel_drop_latch is 1 at the check. The FIQ
   then runs its end-of-command path: it clears the command-active flag and the outer-loop flag
   in RAM, and the app function exits its wait and commits a flash write.
   A normal psemu_com_transfer call holds /SEL for the full timeout. The FIQ checks
   sel_drop_latch, finds 0, and exits without cleanup. The app function stays in its wait.
   SELECT stays low on return. A subsequent psemu_com_set_selected(ps, 0) is a no-op. */
int psemu_com_transfer_and_select_drop(psemu_t *ps, uint8_t data_in, uint8_t *data_out,
                                       uint32_t timeout_cycles);

/* Returns a nonzero value if the CPU executed an opcode that this emulator does not
   recognize.
   This flag is sticky: it stays set after it is set one time.
   Real hardware never causes this fault.
   The fault has one of two causes:
   - The ARM/Thumb decoder of this emulator has a gap.
   - Code before this point calculated a bad jump target, and the CPU now executes data
     that is not code.
   If the fault occurs late in execution, a bad jump target is the more probable cause.
   After this flag is set, the register state and the memory state have no more meaning.
   A frontend must stop execution and report the fault. If a frontend continues, it
   corrupts state and gives no error. */
int psemu_cpu_faulted(const psemu_t *ps);

/* Writes a human-readable diagnostic report to the open file `f`.
   The report contains:
   - The full register state.
   - The fault opcode and its true fetch address, if psemu_cpu_faulted() returns a nonzero
     value.
   - The most recent PCs. See PSEMU_TRACE_SIZE in cpu.h.
   An earlier crash investigation had to add tracing by hand to find this same data. See
   docs/hardware-notes.md.

   A frontend must call this function when a condition looks incorrect. Do not call it
   only for a confirmed CPU fault. A hotkey that the user presses to write a report is
   also useful, together with automatic fault detection.

   This function does not open, close, or flush `f`. The caller owns the file. The file
   can also be a different stream, for example stderr. The caller can write its own data,
   such as recent input history, a frame count, or a timestamp, before this call or after
   it. */
void psemu_write_crash_report(const psemu_t *ps, FILE *f);

size_t psemu_state_size(const psemu_t *ps);
psemu_status psemu_save_state(const psemu_t *ps, void *buf, size_t size);
psemu_status psemu_load_state(psemu_t *ps, const void *buf, size_t size);

#ifdef __cplusplus
}
#endif

#endif
