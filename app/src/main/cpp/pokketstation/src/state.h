/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_STATE_H
#define PSEMU_STATE_H

#include "psemu/psemu.h"

/* The version of the save-state format. core/src/state.c holds the format.

   INCREASE THIS VALUE FOR EACH CHANGE TO THE FORMAT. psemu_load_state refuses a file that carries a
   different value. A field that moves, a field that changes width, and a field that goes away are
   all changes to the format. A new field at the end of a section is also a change.

   A frontend does not have to track this value. It also must not track the layout of psemu_t. The
   core refuses a file that it cannot read, and psemu_load_state returns PSEMU_ERR_BAD_FORMAT for
   that condition.

   1: the first version of the field-by-field format. It replaces a raw copy of psemu_t. */
#define PSEMU_STATE_VERSION 1u

#endif
