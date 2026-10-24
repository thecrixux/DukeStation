/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "ir.h"

#include <stdio.h>

#include "cpu.h" /* psemu_debug_current_pc, for the trace flag below */
#include "dac.h" /* PSEMU_ASSUMED_CPU_HZ: the reference-rate cycle unit of the `cycles` argument of ir_tick */
#include "intc.h"

/* See ir.h. This flag is off by default. tools/ir_probe.c sets it. */
int psemu_ir_trace_enabled = 0;

/* Approximately 2 carrier periods, in PSEMU_ASSUMED_CPU_HZ reference-rate cycles.
   This is an inferred debounce window. It is not a confirmed hardware measurement. See the top comment
   of ir.h. */
#define IR_BFLT_DEBOUNCE_CYCLES ((2ull * PSEMU_ASSUMED_CPU_HZ) / IR_CARRIER_HZ)

/* This constant is a deliberate concession. It is not a model of a physical effect. This comment records
   it and does not conceal it.
   The receive-side sync-pulse acceptance window of a real IR app rejects the transmitted sync pulse of
   this emulator by a small margin. The measurement gives 26 to 38 Timer2 ticks less than the floor of
   the window. See "The transmitted falling edge is stretched" in docs/hardware-notes.md.
   Measurements on real hardware, at three separate times, removed each CPU explanation and each
   interrupt-dispatch explanation for a shortfall of this shape. Those measurements covered the timer
   reload behavior, the interrupt entry cost, and the re-arm latency, on both IRQ and FIQ. They included
   a full realistic dispatch chain with the same shape as the real transmit handler. All three agreed
   with real hardware exactly. Thus the digital timing of this emulator is not the cause of the gap.
   The best remaining explanation is the physics of a real transceiver, outside the CPU: an LED does not
   switch off immediately, and the response of a receiving photodiode and its AGC settling add real time
   before a pulse reads as complete. This emulator does not model IR as an analog signal, and this
   constant does not try to model it. The constant only delays the apparent end of a transmitted pulse,
   by a fixed quantity. Thus this fully digital link can satisfy the receive-side timing that a real app
   expects. That app was tuned against real analog hardware, which this project cannot reproduce.
   Only the falling edge is delayed: the point where software commands the LED off. The rising edge, where
   the LED comes on, stays at its true digital time. The OFF gap between pulses is not stretched. There
   is no real-hardware evidence for the receiver requirements in that gap. There is evidence only for the
   ON-pulse acceptance window.
   The tuning of this constant uses the measured shortfall of this emulator: 38 Timer2 ticks, at the
   IR-screen clock rate of a real app and the /2 divisor of Timer2. This code converts that value to the
   fixed PSEMU_ASSUMED_CPU_HZ reference rate. The tuning does not use the unrelated "184" constant of the
   real app. An earlier attempt used 184 directly and changed the order of the edges: it delayed a falling
   edge past the rising edge of the next pulse. The real gap between transmitted pulses is much less than
   184 Timer2 ticks. See tools/ir_probe.c for the two-instance test that calibrated this constant, and for
   the trace that found that incorrect order before release.
   A second, related failure has the same cause, at a smaller scale. A stretch of the falling edge must
   compress the OFF gap after it. There is no method to make an ON pulse longer without a reduction of an
   adjacent interval. At a value of 316, that compression left a margin of only approximately 1.2 to 1.6
   times IR_BFLT_DEBOUNCE_CYCLES on the shortest real gaps. That margin is too small: the glitch filter of
   this emulator sometimes rejected a real gap as noise. It then combined two pulses, and the bits that
   they encoded, into one pulse. The byte-for-byte buffer comparison in tools/ir_probe.c found this
   directly: specific bytes decoded incorrectly, and the raw Timer2-tick differences at those positions
   did not agree with any single-pulse duration. They agreed only with sums of two or three sequential
   pulses. A value of 200 keeps the sync pulse in its acceptance window with a good margin (4268 against a
   window of 4200 to 5400). It also keeps more than 3 times the debounce margin on the shortest gap.

   This code also limits the stretch to the ON duration of the pulse (see enqueue_tx_edge). The text above
   describes the tuning against one app whose pulses are wide envelopes. For that app, 200 cycles is a
   small correction. It is not a small correction for each app. One trading-card app transmits pulses of
   approximately 7 cycles (6.6us), with a space of 205 or 406 cycles between them. It encodes each bit in
   the length of the gap, and not in the pulse. A flat value of 200 inverted the waveform for that app. A
   measurement over a real transfer showed that a 7-cycle ON and 205-cycle OFF pattern arrived as a
   207-cycle ON and 5-cycle OFF pattern. Also, 272 gaps became exactly 0 cycles: the falling edge arrived
   at the next rising edge, and combined two pulses into one continuous ON period. Only the order guard
   below prevented a reversal of the edges, and a gap of zero length is already unrecoverable.
   The limit at the ON duration keeps this correction a turn-off tail, and not fabricated signal. A tail
   that is longer than its pulse is not a tail. The AGC of a receiver also settles faster after less
   delivered energy, thus a short pulse gets a proportionally short stretch. The limit has no effect for
   the app that supplied the tuning. The sync pulse of that app is approximately 4068 cycles wide, thus
   min(200, 4068) is still 200. Its measured sync figure of 4268 against the 4200 to 5400 window does not
   change. */
#define IR_TX_FALL_STRETCH_CYCLES 200ull

void ir_init(ir_t *ir) {
    ir->mode = 0;
    ir->data = 0;
    ir->clock_cycles = 0;
    ir->tx_led_state = 0;
    ir->tx_last_edge_cycles = 0;
    ir->tx_queue.head = 0;
    ir->tx_queue.count = 0;
    ir->rx_queue.head = 0;
    ir->rx_queue.count = 0;
    ir->rx_level = 0;
    ir->rx_pending_valid = 0;
    ir->rx_pending_level = 0;
    ir->rx_pending_since_cycles = 0;
}

/* Gives a name to offsets 0, 4, 8, and 0xC for the trace output. Offset 8 has no known register name. It
   is in the range between IRDA_DATA and IRDA_MISC. This function reports offset 8 as part of the reserved
   span of IRDA_MISC. It does not make a new name. No available data separates the two offsets. */
static const char *ir_reg_name(uint32_t word_index) {
    switch (word_index) {
    case 0:
        return "IRDA_MODE";
    case 1:
        return "IRDA_DATA";
    default:
        return "IRDA_MISC";
    }
}

uint32_t ir_read(ir_t *ir, uint32_t offset) {
    uint32_t word_index = (offset / 4u) % 4u;
    uint32_t shift = (offset % 4u) * 8u;
    uint32_t value;

    if (word_index == 1u && (ir->mode & IR_MODE_IFMODE) == 0u) {
        /* Receive mode: DATA bit 0 shows the live demodulated line, which is active low. A carrier that
           is present reads 0, and an idle line reads 1. ir->rx_level stays in physical terms (1 means
           that the carrier is present). Thus the inversion is here, and in the INT_IRDA level of
           apply_rx_level. These are the two locations that software can read. See the top comment of
           ir.h for the disassembly that supports this. */
        value = ir->rx_level ? 0u : IR_DATA_LED;
    } else if (word_index >= 2u) {
        /* IRDA_MISC (+0xC) and the range before it (+0x8) get the same treatment. IRDA_MISC is unknown or
           reserved, with no known reset value and no known behavior. Thus this emulator has no basis to
           make register state for it. The register reads back as 0, and writes have no effect. This
           project gives BATT_CTRL the same treatment (see docs/hardware-notes.md, "Known open
           questions"). */
        value = 0u;
    } else {
        value = (word_index == 0u) ? ir->mode : ir->data;
    }
    if (psemu_ir_trace_enabled) {
        printf("[ir trace] t=%llu pc=0x%08X READ %s (+0x%X) = 0x%02X (full=0x%08X)\n",
            (unsigned long long)ir->clock_cycles, psemu_debug_current_pc, ir_reg_name(word_index), (unsigned)offset,
            (unsigned)((value >> shift) & 0xFFu), value);
    }
    return (value >> shift) & 0xFFu;
}

static int queue_push(ir_edge_queue_t *q, uint64_t timestamp_cycles, int level) {
    uint32_t tail;
    if (q->count >= IR_EDGE_QUEUE_CAPACITY) {
        if (psemu_ir_trace_enabled) {
            printf("[ir trace] QUEUE FULL: dropped edge level=%d t=%llu\n", level,
                (unsigned long long)timestamp_cycles);
        }
        return 0; /* the queue is full. Discard the newest edge, and do not corrupt the order. */
    }
    tail = (q->head + q->count) % IR_EDGE_QUEUE_CAPACITY;
    q->entries[tail].timestamp_cycles = timestamp_cycles;
    q->entries[tail].level = level;
    q->count++;
    return 1;
}

static int queue_pop(ir_edge_queue_t *q, ir_edge_t *out) {
    if (q->count == 0u) {
        return 0;
    }
    *out = q->entries[q->head];
    q->head = (q->head + 1u) % IR_EDGE_QUEUE_CAPACITY;
    q->count--;
    return 1;
}

static const ir_edge_t *queue_peek(const ir_edge_queue_t *q) {
    return q->count ? &q->entries[q->head] : (void *)0;
}

/* A transmission is observable in two conditions: IFMODE selects transmit, and STDBY is clear.
   A write to IRDA_DATA outside those conditions still changes the register value, the same as on real
   hardware. It makes no edge, because the transmitter does not drive the LED.

   BGEN is not part of this test. This is a correction of an earlier model. BGEN selects whether the
   hardware divides the ON envelope of the LED into a 40kHz burst. It does not control whether the LED
   comes on. This emulator relays only that ON/OFF envelope, and it does not model the sub-carrier in the
   envelope (see the top comment of ir.h). Thus BGEN has nothing to gate here.

   Two real apps that operate correctly give the answer. They use different BGEN values, and they agree in
   each other respect:
     - One app transmits with IRDA_MODE = 0x01: BGEN = 0 (the hardware carrier is on) and BFLT = 0 (the
       glitch filter is on). It sends wide envelope pulses, and the hardware fills them with the carrier.
     - A second app transmits with IRDA_MODE = 0x0D: BGEN = 1 (the hardware carrier is off) and BFLT = 1
       (the glitch filter is off). It drives the LED directly, with pulses of approximately 7 cycles
       (6.6us), and a space of 205 or 406 cycles between them. This is pulse-distance modulation, where
       the gap holds the bit. Those pulses are much shorter than IR_BFLT_DEBOUNCE_CYCLES. This is why the
       app turns the glitch filter off in the same write.
   Both apps transfer data on real hardware. A gate on BGEN made the second app send nothing at all: this
   emulator discarded each IRDA_DATA write, and tools/ir_probe.c reported "edges relayed: A->B 0" against a
   save state on the transfer screen of that app. */
static int tx_emit_active(const ir_t *ir) {
    return (ir->mode & IR_MODE_IFMODE) != 0u && (ir->mode & IR_MODE_STDBY) == 0u;
}

static void enqueue_tx_edge(ir_t *ir, int level) {
    /* See IR_TX_FALL_STRETCH_CYCLES above. This code delays only a falling edge (level 0, where software
       commands the LED off). The delay is never more than the ON duration of the pulse. */
    uint64_t timestamp = ir->clock_cycles;
    if (level == 0) {
        /* A falling edge always comes after a rising edge. handle_data_write adds an edge only at a real
           change, and handle_mode_write sets 0 only while tx_led_state is 1. A rising edge is never
           stretched. Thus tx_last_edge_cycles still holds the true rise time of this pulse, and the ON
           duration is available here without a second timestamp. This design is deliberate: ir_t is part
           of the raw psemu_t structure that a save state contains, and one more field there makes each
           existing save invalid. See QUICKSAVE_VERSION in frontends/desktop/main.c. */
        uint64_t on_duration = ir->clock_cycles - ir->tx_last_edge_cycles;
        uint64_t stretch = on_duration < IR_TX_FALL_STRETCH_CYCLES ? on_duration : IR_TX_FALL_STRETCH_CYCLES;
        timestamp += stretch;
    }
    /* This test prevents a stretched falling edge from arriving after the pulse that follows it. With the
       tuned constant above, a real gap of this length must not occur. But if such a gap occurs, this test
       gives a compressed edge in place of a queue in the wrong order, which would give no error. This
       test found that exact condition one time during the tuning of the constant: an earlier, larger
       value delayed a falling edge past the next rising edge. */
    if (timestamp < ir->tx_last_edge_cycles) {
        timestamp = ir->tx_last_edge_cycles;
    }
    ir->tx_last_edge_cycles = timestamp;
    queue_push(&ir->tx_queue, timestamp, level);
}

static void handle_mode_write(ir_t *ir) {
    /* The LED goes off when the transmit-emit condition ends. Standby, receive mode, and a disabled
       carrier all end that condition.
       A real IR LED operates the same way when it loses power. Software cannot leave the LED on. */
    if (!tx_emit_active(ir) && ir->tx_led_state != 0) {
        enqueue_tx_edge(ir, 0);
        ir->tx_led_state = 0;
    }
}

static void handle_data_write(ir_t *ir) {
    int new_level;
    if (!tx_emit_active(ir)) {
        return;
    }
    new_level = (ir->data & IR_DATA_LED) ? 1 : 0;
    if (new_level != ir->tx_led_state) {
        enqueue_tx_edge(ir, new_level);
        ir->tx_led_state = new_level;
    }
}

void ir_write(ir_t *ir, uint32_t offset, uint32_t value) {
    uint32_t word_index = (offset / 4u) % 4u;
    uint32_t shift = (offset % 4u) * 8u;
    uint32_t byte = value & 0xFFu;
    uint32_t *reg;

    if (word_index >= 2u) {
        /* A write to IRDA_MISC or to the range before it has no effect. See the comment on the same
           range in ir_read. */
        if (psemu_ir_trace_enabled) {
            printf("[ir trace] t=%llu pc=0x%08X WRITE %s (+0x%X) = 0x%02X (ignored, reserved)\n",
                (unsigned long long)ir->clock_cycles, psemu_debug_current_pc, ir_reg_name(word_index),
                (unsigned)offset, (unsigned)byte);
        }
        return;
    }

    reg = (word_index == 0u) ? &ir->mode : &ir->data;
    *reg = (*reg & ~(0xFFu << shift)) | (byte << shift);

    if (psemu_ir_trace_enabled) {
        printf("[ir trace] t=%llu pc=0x%08X WRITE %s (+0x%X) = 0x%02X (full=0x%08X)\n",
            (unsigned long long)ir->clock_cycles, psemu_debug_current_pc, ir_reg_name(word_index), (unsigned)offset,
            (unsigned)byte, *reg);
    }

    if (word_index == 0u) {
        handle_mode_write(ir);
    } else if (shift == 0u) {
        handle_data_write(ir);
    }
}

/* Receive mode is active while IFMODE selects receive and STDBY is clear.
   The receive path gives a signal only in that condition. */
static int rx_listen_active(const ir_t *ir) {
    return (ir->mode & IR_MODE_IFMODE) == 0u && (ir->mode & IR_MODE_STDBY) == 0u;
}

/* Writes a level that passed the debounce filter to rx_level.
   It asserts INT_IRDA if receive mode is active (IFMODE = 0, STDBY = 0).
   It discards an edge that arrives during a transmission or in standby. A real half-duplex transceiver
   operates the same way. */
static void apply_rx_level(ir_t *ir, struct intc *intc, int level) {
    int listening;
    if (level == ir->rx_level) {
        return;
    }
    ir->rx_level = level;
    listening = rx_listen_active(ir);
    if (psemu_ir_trace_enabled) {
        printf("[ir trace] t=%llu RX edge level=%d %s (mode=0x%08X)\n", (unsigned long long)ir->clock_cycles, level,
            listening ? "-> INT_IRDA asserted" : "DROPPED (not listening)", ir->mode);
    }
    if (listening) {
        /* This call is not a simple intc_set_line.
           The receive handler reads the live line level from STATUS bit 12.
           It then compares that level against the level that it expects.
           Thus STATUS must follow the real level, and HOLD must still latch an interrupt at both edges.
           A disassembly of the INT_IRDA handler of a real app confirms this.
           See the comment on INT_STATUS_MASK in intc.h. */
        intc_set_level_and_pulse(intc, INT_IRDA, !level);
    }
}

/* BFLT is bit 3, and it uses inverted logic. Thus a value of 0 enables the filter.
   The filter rejects a transition that does not continue for IR_BFLT_DEBOUNCE_CYCLES before an opposite
   edge arrives.
   A raw edge starts a pending candidate, or starts one again.
   resolve_pending in ir_tick accepts that candidate after sufficient local time with no opposite edge. */
static void debounce_edge(ir_t *ir, struct intc *intc, int level, uint64_t at_cycles) {
    int filter_enabled = (ir->mode & IR_MODE_BFLT) == 0u;
    if (!filter_enabled) {
        apply_rx_level(ir, intc, level);
        ir->rx_pending_valid = 0;
        return;
    }
    if (level == ir->rx_level) {
        ir->rx_pending_valid = 0; /* this level agrees with the accepted level. Cancel an old opposite candidate. */
        return;
    }
    ir->rx_pending_valid = 1;
    ir->rx_pending_level = level;
    ir->rx_pending_since_cycles = at_cycles;
}

static void resolve_pending(ir_t *ir, struct intc *intc) {
    if (!ir->rx_pending_valid) {
        return;
    }
    if (ir->clock_cycles - ir->rx_pending_since_cycles >= IR_BFLT_DEBOUNCE_CYCLES) {
        apply_rx_level(ir, intc, ir->rx_pending_level);
        ir->rx_pending_valid = 0;
    }
}

void ir_tick(ir_t *ir, struct intc *intc, uint32_t cycles) {
    ir->clock_cycles += cycles;

    /* The receive path gives a signal only while receive mode is active. Thus the INT_IRDA level in
       STATUS is 0 during a transmission and in standby.
       This code clears only that level. A latched request in HOLD stays, and only an acknowledge from
       software clears it. See intc_clear_status_only.

       apply_rx_level writes that level only on an edge, and only while receive mode is active. Thus the
       level from the last edge of a receive period stays after that period ends. The receive routine of
       a real app needs the value at reset at the start of each receive period. That value is 0.
       See docs/hardware-notes.md, "The INT_IRDA level is 0 during a transmission and in standby". */
    if (!rx_listen_active(ir)) {
        intc_clear_status_only(intc, INT_IRDA);
    }

    for (;;) {
        const ir_edge_t *front = queue_peek(&ir->rx_queue);
        ir_edge_t edge;
        if (!front || front->timestamp_cycles > ir->clock_cycles) {
            break;
        }
        queue_pop(&ir->rx_queue, &edge);
        debounce_edge(ir, intc, edge.level, edge.timestamp_cycles);
    }
    resolve_pending(ir, intc);
}

int ir_pop_tx_edge(ir_t *ir, ir_edge_t *out_edge) {
    return queue_pop(&ir->tx_queue, out_edge);
}

void ir_push_rx_edge(ir_t *ir, uint64_t timestamp_cycles, int level) {
    queue_push(&ir->rx_queue, timestamp_cycles, level);
}

uint64_t ir_get_clock_cycles(const ir_t *ir) {
    return ir->clock_cycles;
}
