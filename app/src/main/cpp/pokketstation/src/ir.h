/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_IR_H
#define PSEMU_IR_H

#include <stdint.h>

struct intc;

#define IR_REG_SPAN 0x10u

/* IRDA_MODE (offset 0) bits, IRDA_DATA (offset 4) bit 0, and IRDA_MISC (offset 0xC):
     IRDA_MODE bit0 IFMODE  0 = Receive, 1 = Transmit
     IRDA_MODE bit1 STDBY   0 = Active, 1 = Stand-by
     IRDA_MODE bit2 BGEN    0 = Enable the 40KHz carrier generator, 1 = Disable
     IRDA_MODE bit3 BFLT    0 = Enable the filter, 1 = Disable
     IRDA_DATA bit0 LED     Transmit: 0 = LED off, 1 = LED on. Receive: the demodulated line,
                            active low (0 = carrier present, 1 = idle).
     IRDA_MISC              Unknown and reserved. See the code for this register in ir_write below.

   A disassembly of a real IR app is the source of the STDBY, BGEN, and BFLT names, and the behavior
   of that app confirms them. Secondary register maps for this range do not agree with each other on
   the function of MODE bits 1 to 3. Thus this project does not use those maps as evidence. The
   behavior of the real app is the confirmation.

   The receive function of IRDA_DATA bit 0 has no documentation. It is an inference of this project,
   and the first inference (1 = carrier present) was incorrect. A disassembly of the receive handler
   of a real IR app gives the polarity. The handler reads the live line from INTC STATUS bit 12. It
   compares the line against an expected level that it holds in its own state. It arms itself for
   level 0 before a carrier arrives. It then measures the sync burst as the interval that ends when
   the line returns to 1. Thus a carrier burst reads 0, and an idle line reads 1. Real IR
   demodulator receivers also operate this way: their output is active low. With the first polarity,
   the handler rejected the edge that starts a sync burst. It then locked onto the short gap between
   pulses, and never went past sync detection. With this polarity, the handler continues to bit
   accumulation and assembles a full message. Real hardware does not confirm this polarity directly,
   but the polarity is no longer only an assumption. rx_level stays in physical terms (1 = carrier
   present). The inversion occurs where software reads the value: in ir_read, and in the INT_IRDA
   level of apply_rx_level.

   No source gives numeric timing for this peripheral. There are no microsecond pulse widths, and no
   carrier-to-pulse ratio more exact than "a long pulse is usually two times a short pulse". No
   source gets near the 184-tick transmit-timing gap that the hardware tests of this project examine
   (see docs/hardware-notes.md, "IR / IR Link"). Only measurement on real hardware can answer that
   question.

   Real pulses are long or short ON periods, with short OFF gaps between them. A long pulse is
   approximately two times a short pulse. There is a physical reason for this shape: real IR
   receiver hardware adapts to ambient light, and a long steady signal can look like a new ambient
   level instead of data. A trace of the transmitted signal of a real app shows this alternating
   shape (see the transmit-side analysis in tools/ir_probe.c).
   The real RX-IRQ handler (INT_IRDA, see intc.h) measures the length of an incoming pulse. It reads
   the live counter of Timer 2 (reload 0xFFFFh) at the interrupt. This is the usual technique for
   this measurement.

   The real BIOS has no IR functions, except basic initialization and power-down. The disassembly of
   this project confirms this. A real app writes to IRDA_MODE and IRDA_DATA directly from its own
   code, with its own interrupt handler. It does not use a BIOS SWI. Thus this project does not have
   to look for a BIOS-level IR interface.

   This file models IR as an asynchronous edge relay between two instances that have independent
   clocks. Real IR hardware operates the same way: two separate devices, two separate oscillators,
   and an optical signal between them. The two devices share no clock.
   There is no lockstep timing assumption between a local core and the source of its RX edges. That
   source is a loopback caller, or a second emulator instance on some transport.
   The core tracks only its own local monotonic time. It reacts to edges that carry a timestamp on
   that local timeline.

   The core stays platform-agnostic. It knows nothing about a second instance, a pipe, or a socket.
   It supplies only an edge-queue interface: ir_pop_tx_edge, ir_push_rx_edge, and
   ir_get_clock_cycles. The layer above the core operates that interface.
   psemu_get_audio_samples uses the same shape. It lets a frontend pull PCM data out, and the core
   knows nothing about the audio library.

   This file does not model the 40kHz square wave inside the "on" interval of a pulse. No observable
   behavior depends on the sub-carrier. Only the gated on/off envelope is important.
   Thus IR_CARRIER_HZ has one use. It gives the BFLT debounce window below.
   BGEN does not gate an edge. That bit selects whether the hardware divides the on envelope into a
   40kHz burst. It does not control whether the LED comes on. This file relays only the envelope, thus
   BGEN has nothing to gate. Two real apps that both operate on real hardware use opposite values of
   BGEN. See tx_emit_active in ir.c for those two apps, and for the fault that an earlier gate caused.

   The BFLT debounce window is approximately 2 carrier periods. This is an inferred constant, not a
   confirmed hardware measurement. This project marks all other unconfirmed assumptions the same
   way. See docs/hardware-notes.md. */
#define IR_MODE_IFMODE (1u << 0)
#define IR_MODE_STDBY (1u << 1)
#define IR_MODE_BGEN (1u << 2)
#define IR_MODE_BFLT (1u << 3)
#define IR_DATA_LED (1u << 0)

#define IR_CARRIER_HZ 40000u

/* A real transmit burst from one IR app (41 bytes) makes 658 edges (see the transmit-side analysis
   in tools/ir_probe.c). A capacity of 64 discarded 594 of those 658 edges when the CPU clock speeds
   of the two instances became different during setup. Each app controls its own clock speed
   (CLK_MODE). The receive queue then filled faster than the frontend drained it. A counter on the
   discard path confirms this directly. It is not an inference. This larger capacity holds more than
   four such messages before it discards an edge. */
#define IR_EDGE_QUEUE_CAPACITY 4096u

/* An edge is a transition of the demodulated IR signal.
   Level 1 means that the carrier or the LED went on. Level 0 means that it went off.
   Timestamps use the local monotonic clock of this ir_t instance (see ir_get_clock_cycles).
   They use the same reference-clock cycle units that psemu_run calculates for rtc_tick and dac_tick.
   They are not real microseconds.
   A frontend converts to and from wall-clock microseconds when it relays edges to a different
   instance. See psemu_ir_get_clock_us in psemu.h. */
typedef struct ir_edge {
    uint64_t timestamp_cycles;
    int level;
} ir_edge_t;

typedef struct ir_edge_queue {
    ir_edge_t entries[IR_EDGE_QUEUE_CAPACITY];
    uint32_t head;
    uint32_t count;
} ir_edge_queue_t;

typedef struct ir {
    uint32_t mode;
    uint32_t data;

    uint64_t clock_cycles; /* the local monotonic clock. Only ir_tick increases it. */

    int tx_led_state; /* the level of the last edge that this instance put onto tx_queue */
    uint64_t tx_last_edge_cycles; /* the last timestamp that went onto tx_queue. This value guards
                                      IR_TX_FALL_STRETCH_CYCLES (ir.c): a stretched falling edge must
                                      never come after the pulse that follows it. */

    ir_edge_queue_t tx_queue; /* edges from local CPU writes. ir_pop_tx_edge collects them. */
    ir_edge_queue_t rx_queue; /* edges from ir_push_rx_edge. They wait for their delivery time. */

    int rx_level; /* the demodulated level now. IRDA_DATA bit 0 shows this level in receive mode. */

    /* BFLT glitch-filter state. A new edge stays pending for IR_BFLT_DEBOUNCE_CYCLES.
       If no opposite edge arrives in that time, the filter accepts the edge.
       It then applies the edge to rx_level and to INT_IRDA. */
    int rx_pending_valid;
    int rx_pending_level;
    uint64_t rx_pending_since_cycles;
} ir_t;

/* Diagnostic flag. It uses the same pattern as psemu_intc_trace_enabled in intc.h.
   It is off by default, thus it has no cost in normal use.
   It records each real IR register access with its real PC.
   Those PCs identify the IR routines in the app that are useful to disassemble.
   A static disassembly alone cannot tell ARM code from Thumb code, because it does not track the
   mode during execution.
   This is permanent diagnostic equipment. It is not part of one investigation. */
extern int psemu_ir_trace_enabled;

void ir_init(ir_t *ir);
uint32_t ir_read(ir_t *ir, uint32_t offset);
void ir_write(ir_t *ir, uint32_t offset, uint32_t value);

/* Increases the local clock of ir by `cycles`.
   These are the same reference-rate cycle units that rtc_tick and dac_tick receive.
   This function then processes each rx_queue edge that is due.
   For each edge it applies the BFLT debounce and writes rx_level.
   It asserts INT_IRDA for each edge that passes the filter while receive mode is active (IFMODE = 0,
   STDBY = 0).
   It discards an edge that becomes due outside that mode. A real half-duplex transceiver operates the
   same way.
   Outside that mode it also holds the INT_IRDA level in STATUS at 0. The receive path gives a signal
   only while receive mode is active. A level from an earlier transfer must not stay for the next
   transfer. */
void ir_tick(ir_t *ir, struct intc *intc, uint32_t cycles);

/* Gets the next TX edge that this instance made.
   It returns 1 and fills *out_edge. It returns 0 if tx_queue is empty.
   Call this function at each frame. Convert each edge to wall-clock time, then relay the edge.
   This connects the IR transmit side of this instance to the IR receive side of a different
   instance. */
int ir_pop_tx_edge(ir_t *ir, ir_edge_t *out_edge);

/* Puts an RX edge from an external source into the queue.
   It delivers the edge at `timestamp_cycles`, on the local clock of this instance.
   See ir_get_clock_cycles for the conversion of a wall-clock timestamp into this timeline.
   It discards the edge if rx_queue is full.
   A full queue shows that the caller does not drain the queue at sufficient speed.
   To discard an old edge is better than to corrupt the order of the queue. */
void ir_push_rx_edge(ir_t *ir, uint64_t timestamp_cycles, int level);

uint64_t ir_get_clock_cycles(const ir_t *ir);

#endif
