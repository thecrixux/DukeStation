/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "state.h"

#include <string.h>

#include "psemu_internal.h"

/* The save-state format of this emulator.

   ONE VISITOR SERVES THE THREE OPERATIONS. state_visit walks the machine one field at a time. The
   mode of the cursor selects measure, write, or read. Thus a write and a read cannot become
   different from each other.

   The format has four properties. Each one is a requirement:

   - It is portable across targets. Each integer uses an explicit width and little-endian order.
     Structure padding and pointer width are not the same on each target, and this emulator has
     targets with 32-bit pointers and targets with 64-bit pointers.
   - It holds no BIOS image. A BIOS dump is not the property of this project.
   - It carries its own version, in PSEMU_STATE_VERSION. psemu_load_state refuses a file with a
     different version. Thus a frontend does not track the layout of psemu_t.
   - It holds only the state that machine behavior needs. The diagnostic trace ring of the CPU is
     absent, and a load clears it.

   TO CHANGE THE FORMAT, CHANGE PSEMU_STATE_VERSION. Add a field at the end of its section. Never
   change the order of the existing fields.

   docs/hardware-notes.md, "Save states", records these properties. */

#define STATE_MAGIC_0 'P'
#define STATE_MAGIC_1 'K'
#define STATE_MAGIC_2 'S'
#define STATE_MAGIC_3 'T'

typedef enum { ST_MEASURE, ST_WRITE, ST_READ } st_mode_t;

typedef struct {
    st_mode_t mode;
    uint8_t *buf;
    size_t size;
    size_t pos;
    int error;
} st_t;

/* Each accessor below moves `pos` by the width of its field, in every mode. In ST_MEASURE mode the
   buffer is absent, thus only `pos` moves. A field that does not fit sets `error`, and each later
   call then does nothing. Thus one test of `error` at the end covers the full operation. */
static void st_raw(st_t *s, void *p, size_t n) {
    if (s->error) {
        return;
    }
    if (s->mode != ST_MEASURE) {
        if (s->pos + n > s->size) {
            s->error = 1;
            return;
        }
        if (s->mode == ST_WRITE) {
            memcpy(s->buf + s->pos, p, n);
        } else {
            memcpy(p, s->buf + s->pos, n);
        }
    }
    s->pos += n;
}

/* Each integer uses little-endian byte order, and it uses an explicit width. This makes the format
   the same on a big-endian target and on a little-endian target. */
static void st_u8(st_t *s, uint8_t *v) {
    st_raw(s, v, 1);
}

static void st_u16(st_t *s, uint16_t *v) {
    uint8_t b[2];
    if (s->mode == ST_WRITE) {
        b[0] = (uint8_t)(*v);
        b[1] = (uint8_t)(*v >> 8);
    }
    st_raw(s, b, sizeof(b));
    if (s->mode == ST_READ && !s->error) {
        *v = (uint16_t)((uint16_t)b[0] | ((uint16_t)b[1] << 8));
    }
}

static void st_u32(st_t *s, uint32_t *v) {
    uint8_t b[4];
    int i;
    if (s->mode == ST_WRITE) {
        for (i = 0; i < 4; i++) {
            b[i] = (uint8_t)(*v >> (i * 8));
        }
    }
    st_raw(s, b, sizeof(b));
    if (s->mode == ST_READ && !s->error) {
        uint32_t out = 0;
        for (i = 0; i < 4; i++) {
            out |= (uint32_t)b[i] << (i * 8);
        }
        *v = out;
    }
}

static void st_u64(st_t *s, uint64_t *v) {
    uint8_t b[8];
    int i;
    if (s->mode == ST_WRITE) {
        for (i = 0; i < 8; i++) {
            b[i] = (uint8_t)(*v >> (i * 8));
        }
    }
    st_raw(s, b, sizeof(b));
    if (s->mode == ST_READ && !s->error) {
        uint64_t out = 0;
        for (i = 0; i < 8; i++) {
            out |= (uint64_t)b[i] << (i * 8);
        }
        *v = out;
    }
}

/* An `int` field of the machine is a flag in every case here. The file uses one byte for it. */
static void st_flag(st_t *s, int *v) {
    uint8_t b = (uint8_t)(*v ? 1 : 0);
    st_u8(s, &b);
    if (s->mode == ST_READ && !s->error) {
        *v = b ? 1 : 0;
    }
}

static void st_i16(st_t *s, int16_t *v) {
    uint16_t u = (uint16_t)(*v);
    st_u16(s, &u);
    if (s->mode == ST_READ && !s->error) {
        *v = (int16_t)u;
    }
}

/* real_time_cycle_carry is the only floating-point field of the machine. psemu_run keeps it in the
   range 0.0 to 1.0, because it subtracts each whole cycle that it uses. Thus a fixed-point fraction
   of 32 bits holds it with more resolution than the reference clock can use.
   The file does not store the raw bits of the double. Those bits depend on the target. */
static void st_carry(st_t *s, double *v) {
    uint32_t fixed = 0;
    if (s->mode == ST_WRITE) {
        double c = *v;
        if (c < 0.0) {
            c = 0.0;
        }
        if (c > 0.999999999) {
            c = 0.999999999;
        }
        fixed = (uint32_t)(c * 4294967296.0);
    }
    st_u32(s, &fixed);
    if (s->mode == ST_READ && !s->error) {
        *v = (double)fixed / 4294967296.0;
    }
}

/* THE SIZE OF THIS FORMAT IS THE SAME FOR EACH STATE OF THE MACHINE. A ring buffer below writes its
   full capacity, and not only the entries that it holds now. The count travels with it, thus a read
   restores the correct number of entries.

   A fixed size is a requirement. The libretro interface calls retro_serialize_size one time and
   keeps the result. A caller also measures the state, then changes the machine, and then writes the
   state into the buffer of that measurement.

   An IR edge queue holds IR_EDGE_QUEUE_CAPACITY entries. A write starts at `head` and goes through
   the ring. A read fills the array from index 0 and sets head to 0. Thus the order of the entries
   stays correct, and the queue needs no head value in the file. */
static void st_ir_queue(st_t *s, ir_edge_queue_t *q) {
    uint32_t count = q->count;
    uint32_t i;

    st_u32(s, &count);
    if (s->error) {
        return;
    }
    if (s->mode == ST_READ) {
        if (count > IR_EDGE_QUEUE_CAPACITY) {
            s->error = 1;
            return;
        }
    }
    for (i = 0; i < IR_EDGE_QUEUE_CAPACITY; i++) {
        uint32_t slot = (s->mode == ST_READ) ? i : ((q->head + i) % IR_EDGE_QUEUE_CAPACITY);
        st_u64(s, &q->entries[slot].timestamp_cycles);
        st_flag(s, &q->entries[slot].level);
    }
    if (s->mode == ST_READ && !s->error) {
        q->head = 0;
        q->count = count;
    }
}

static void state_visit(psemu_t *ps, st_t *s) {
    int i;
    int j;

    /* CPU. The `bus` pointer is absent on purpose: psemu_load_state connects it again.
       The diagnostic trace ring is also absent. It holds 8192 entries of recent PCs for
       psemu_write_crash_report, and no machine behavior reads it. A load clears the ring, thus a
       crash report after a load covers only the steps after that load. */
    for (i = 0; i < 16; i++) {
        st_u32(s, &ps->cpu.r[i]);
    }
    st_u32(s, &ps->cpu.cpsr);
    for (i = 0; i < ARM_BANK_COUNT; i++) {
        st_u32(s, &ps->cpu.r13_bank[i]);
        st_u32(s, &ps->cpu.r14_bank[i]);
        st_u32(s, &ps->cpu.spsr_bank[i]);
    }
    for (i = 0; i < 2; i++) {
        for (j = 0; j < 5; j++) {
            st_u32(s, &ps->cpu.r8_12_bank[i][j]);
        }
    }
    st_flag(s, &ps->cpu.halted);
    st_flag(s, &ps->cpu.unimplemented);
    st_u64(s, &ps->cpu.total_steps);

    /* Bus. The BIOS image is absent on purpose. See the top comment of this file. A frontend loads
       the BIOS before it loads a state. The peripheral pointers are also absent. */
    st_raw(s, ps->bus.ram, sizeof(ps->bus.ram));
    st_u32(s, &ps->bus.pending_cycles);
    st_u32(s, &ps->bus.ram_lock_addr);
    st_u8(s, &ps->bus.ram_lock_value);

    /* LCD. */
    st_raw(s, ps->lcd.vram, sizeof(ps->lcd.vram));
    st_raw(s, ps->lcd.presented, sizeof(ps->lcd.presented));
    st_u32(s, &ps->lcd.mode);
    st_u32(s, &ps->lcd.cal);
    st_flag(s, &ps->lcd.dirty);

    /* Interrupt controller. */
    st_u32(s, &ps->intc.hold);
    st_u32(s, &ps->intc.status);
    st_u32(s, &ps->intc.enable);
    st_u32(s, &ps->intc.mask);
    st_u32(s, &ps->intc.enable_write_scratch);
    st_u32(s, &ps->intc.mask_write_scratch);
    st_u32(s, &ps->intc.ack_write_scratch);

    /* Flash. The card image is the save data of the machine, thus a state carries it. */
    st_raw(s, ps->flash.data, sizeof(ps->flash.data));
    st_u32(s, &ps->flash.bank_mask);
    st_u32(s, &ps->flash.last_command);
    for (i = 0; i < (int)FLASH_BANK_VAL_COUNT; i++) {
        st_u32(s, &ps->flash.bank_val[i]);
    }
    st_u16(s, &ps->flash.f_sn_lo);
    st_u16(s, &ps->flash.f_sn_hi);
    st_u16(s, &ps->flash.f_cal);
    st_u8(s, &ps->flash.unlock_step);

    /* Communication port. */
    st_u32(s, &ps->com.mode);
    st_u32(s, &ps->com.stat1);
    st_u32(s, &ps->com.ctrl1);
    st_u32(s, &ps->com.ctrl2);
    st_u32(s, &ps->com.rx_data);
    st_u32(s, &ps->com.tx_data);
    st_u32(s, &ps->com.tx_shifted);
    st_flag(s, &ps->com.rx_ready);
    st_flag(s, &ps->com.ack_asserted);
    st_flag(s, &ps->com.selected);
    st_flag(s, &ps->com.sel_drop_latch);
    st_flag(s, &ps->com.docked);

    /* IR. */
    st_u32(s, &ps->ir.mode);
    st_u32(s, &ps->ir.data);
    st_u64(s, &ps->ir.clock_cycles);
    st_flag(s, &ps->ir.tx_led_state);
    st_u64(s, &ps->ir.tx_last_edge_cycles);
    st_ir_queue(s, &ps->ir.tx_queue);
    st_ir_queue(s, &ps->ir.rx_queue);
    st_flag(s, &ps->ir.rx_level);
    st_flag(s, &ps->ir.rx_pending_valid);
    st_flag(s, &ps->ir.rx_pending_level);
    st_u64(s, &ps->ir.rx_pending_since_cycles);

    /* Timers. */
    for (i = 0; i < (int)TIMER_COUNT; i++) {
        st_u32(s, &ps->timer.timers[i].period);
        st_u32(s, &ps->timer.timers[i].count);
        st_u32(s, &ps->timer.timers[i].control);
        st_u32(s, &ps->timer.timers[i].cycle_accumulator);
    }

    /* RTC. */
    st_u32(s, &ps->rtc.mode);
    st_u32(s, &ps->rtc.control);
    st_u32(s, &ps->rtc.time);
    st_u32(s, &ps->rtc.date);
    st_u32(s, &ps->rtc.tick_accumulator);
    st_flag(s, &ps->rtc.int_line);

    /* DAC. The audio ring uses the same fixed-size treatment as an IR queue. See st_ir_queue. */
    st_u32(s, &ps->dac.ctrl);
    st_u32(s, &ps->dac.data);
    st_i16(s, &ps->dac.current_sample);
    st_u32(s, &ps->dac.cycle_accumulator);
    st_flag(s, &ps->dac.iop_muted);
    {
        uint32_t count = ps->dac.sample_count;
        uint32_t k;
        st_u32(s, &count);
        if (s->error) {
            return;
        }
        if (s->mode == ST_READ && count > DAC_SAMPLE_BUFFER_SIZE) {
            s->error = 1;
            return;
        }
        for (k = 0; k < DAC_SAMPLE_BUFFER_SIZE; k++) {
            uint32_t slot = (s->mode == ST_READ) ? k : ((ps->dac.sample_read_pos + k) % DAC_SAMPLE_BUFFER_SIZE);
            st_i16(s, &ps->dac.sample_buffer[slot]);
        }
        if (s->mode == ST_READ && !s->error) {
            ps->dac.sample_read_pos = 0;
            ps->dac.sample_write_pos = count % DAC_SAMPLE_BUFFER_SIZE;
            ps->dac.sample_count = count;
        }
    }

    /* Clock control. */
    st_u32(s, &ps->clk.mode);
    st_u32(s, &ps->clk.control);
    st_u32(s, &ps->clk.mode_write_scratch);
    st_u32(s, &ps->clk.control_write_scratch);

    /* IOP power control. */
    st_u32(s, &ps->iop.data);

    /* The remaining state of the machine. */
    st_carry(s, &ps->real_time_cycle_carry);
    st_u32(s, &ps->buttons);
    st_flag(s, &ps->has_bios);
    st_flag(s, &ps->app_running);
    st_u32(s, &ps->app_exec_idle_cycles);
}

/* The header is the magic number and the version. state_visit does not write it, because a read must
   test the header before it moves any field of the machine. */
#define STATE_HEADER_SIZE 8u

static void state_header(st_t *s, uint32_t *version) {
    uint8_t magic[4];
    if (s->mode == ST_WRITE) {
        magic[0] = STATE_MAGIC_0;
        magic[1] = STATE_MAGIC_1;
        magic[2] = STATE_MAGIC_2;
        magic[3] = STATE_MAGIC_3;
    }
    st_raw(s, magic, sizeof(magic));
    st_u32(s, version);
    if (s->mode == ST_READ && !s->error) {
        if (magic[0] != STATE_MAGIC_0 || magic[1] != STATE_MAGIC_1 || magic[2] != STATE_MAGIC_2 ||
            magic[3] != STATE_MAGIC_3) {
            s->error = 1;
        }
    }
}

/* The measure pass and the write pass do not change the machine. The visitor takes a writable
   pointer because one visitor serves all three modes. This cast is safe for those two modes. */
size_t psemu_state_size(const psemu_t *ps) {
    st_t s;
    uint32_t version = PSEMU_STATE_VERSION;
    memset(&s, 0, sizeof(s));
    s.mode = ST_MEASURE;
    state_header(&s, &version);
    state_visit((psemu_t *)ps, &s);
    return s.pos;
}

psemu_status psemu_save_state(const psemu_t *ps, void *buf, size_t size) {
    st_t s;
    uint32_t version = PSEMU_STATE_VERSION;
    memset(&s, 0, sizeof(s));
    s.mode = ST_WRITE;
    s.buf = (uint8_t *)buf;
    s.size = size;
    state_header(&s, &version);
    state_visit((psemu_t *)ps, &s);
    return s.error ? PSEMU_ERR_BAD_SIZE : PSEMU_OK;
}

psemu_status psemu_load_state(psemu_t *ps, const void *buf, size_t size) {
    st_t s;
    uint32_t version = 0;

    memset(&s, 0, sizeof(s));
    s.mode = ST_READ;
    s.buf = (uint8_t *)buf; /* ST_READ never writes through this pointer. See st_raw. */
    s.size = size;

    state_header(&s, &version);
    if (s.error) {
        return PSEMU_ERR_BAD_FORMAT;
    }
    if (version != PSEMU_STATE_VERSION) {
        /* A file from a different version of this format. Its fields do not agree with the fields
           here, thus a load of it corrupts the machine and gives no error. */
        return PSEMU_ERR_BAD_FORMAT;
    }

    state_visit(ps, &s);
    if (s.error) {
        return PSEMU_ERR_BAD_SIZE;
    }

    /* The trace ring is not in the file. Clear it, so a crash report after this load shows only real
       steps. See the CPU section of state_visit. */
    memset(ps->cpu.trace, 0, sizeof(ps->cpu.trace));
    ps->cpu.trace_pos = 0;

    return PSEMU_OK;
}
