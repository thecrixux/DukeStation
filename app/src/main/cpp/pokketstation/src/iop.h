/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_IOP_H
#define PSEMU_IOP_H

#include <stdint.h>

#define IOP_REG_SPAN 0x10u

/* The PocketStation IOP power-control register.
   One bitmask, IOP_DATA, receives writes through two complementary write-only ports.
   IOP_STOP (+0x4) ORs bits into the mask. IOP_START (+0x8) ANDs bits out of the mask.
   A set bit means that the subsystem is stopped.

   The description of bit 5 is "Sound Enable (START=On, STOP=Off)".
   Audio needs this bit set and DAC_CTRL bit 0 set (see dac.h).
   This emulator does not model the other bits (LED and IR), because they have no effect on emulated
   behavior.
   Software reads the bitmask back through IOP_STAT, which has the same address as IOP_STOP (+0x4).

   Each byte writes directly into `data` at its own bit position: an OR for STOP, and an AND-NOT for
   START. This emulator does not collect writes in a scratch register and apply them only after a
   full 32-bit store.

   History: an earlier version of this file did use that collect-and-apply method. This was a
   confirmed fault. A direct trace of the BIOS and the apps (see docs/hardware-notes.md) showed that
   real code writes these registers with single-byte stores. Single-byte stores never got to the
   word-complete gate, thus this emulator discarded them and gave no error.
   To apply each byte immediately gives the same result as collect-and-apply for a full 32-bit store.
   OR and AND-NOT are per-bit operations, thus the order of the bytes has no effect. The
   delayed-apply step was not necessary. */
typedef struct iop {
    uint32_t data;
} iop_t;

#define IOP_BIT_SOUND_STOPPED 0x20u

void iop_init(iop_t *iop);
uint8_t iop_read8(iop_t *iop, uint32_t offset);
void iop_write8(iop_t *iop, uint32_t offset, uint8_t value);

/* Returns a nonzero value if bit 5 (Sound Enable) is in the STARTED (enabled) state.
   This state means that the subsystem is not stopped. */
int iop_sound_enabled(const iop_t *iop);

#endif
