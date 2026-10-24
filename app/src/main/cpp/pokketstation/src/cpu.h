/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_CPU_H
#define PSEMU_CPU_H

#include <stdint.h>

#include "memory.h"

#define CPSR_N (1u << 31)
#define CPSR_Z (1u << 30)
#define CPSR_C (1u << 29)
#define CPSR_V (1u << 28)
#define CPSR_I (1u << 7)
#define CPSR_F (1u << 6)
#define CPSR_T (1u << 5)
#define CPSR_MODE_MASK 0x1Fu

#define ARM_MODE_USR 0x10u
#define ARM_MODE_FIQ 0x11u
#define ARM_MODE_IRQ 0x12u
#define ARM_MODE_SVC 0x13u
#define ARM_MODE_ABT 0x17u
#define ARM_MODE_UND 0x1Bu
#define ARM_MODE_SYS 0x1Fu

#define ARM_IRQ_VECTOR 0x18u
#define ARM_FIQ_VECTOR 0x1Cu

/* Bank slots: 0 is fiq, 1 is irq, 2 is svc, 3 is abt, 4 is und, and 5 is usr
   and sys together (shared, as the specification gives). */
#define ARM_BANK_COUNT 6
#define ARM_BANK_FIQ 0
#define ARM_BANK_USR 5

/* Ring buffer of recent (pc, cpsr) pairs.
   arm7tdmi_step records one entry at each step, for all callers.
   psemu_write_crash_report (psemu.c and psemu.h) uses this buffer to
   show the events before a fault. A frontend needs no trace function of
   its own for this. See docs/hardware-notes.md for the crash
   investigation that caused this project to add the buffer.
   The buffer first held 256 entries. A real crash report showed a long
   straight-line drift through memory that is not code. This drift
   filled the full 256-entry window and erased the history of the bad
   jump that caused the drift. This project increased the size to 8192
   entries (64KB for each instance) to correct that. */
#define PSEMU_TRACE_SIZE 8192

typedef struct {
    uint32_t pc;
    uint32_t cpsr;
} psemu_trace_entry_t;

typedef struct {
    uint32_t r[16]; /* r0-r14 general purpose (the banked view for the current mode). r15 is the pc. */
    uint32_t cpsr;

    uint32_t r13_bank[ARM_BANK_COUNT];
    uint32_t r14_bank[ARM_BANK_COUNT];
    uint32_t spsr_bank[ARM_BANK_COUNT];
    /* r8-r12 are also banked, but only for FIQ: all other modes use one shared copy. Index 0 is that
       shared copy, and index 1 is the FIQ copy. Only the set that is not in r[] at this time holds
       live data. See arm_set_mode, which exchanges the two sets at FIQ entry and at FIQ exit.

       This bank is the reason that a real FIQ handler can use r8-r12 as scratch registers without a
       save. It is also why a "fast interrupt" is fast. This emulator banked only r13 and r14 for a
       long time. Thus a FIQ handler destroyed r8-r12 of the interrupted code and gave no error. One
       music app drives its audio from Timer2, which routes to FIQ (INT_FIQ_MASK). That app is the
       most exposed app that this project can operate. */
    uint32_t r8_12_bank[2][5];

    psemu_bus_t *bus;
    int halted;
    int unimplemented; /* set when the CPU meets an opcode it does not recognize. It stays set until the caller clears it. */

    psemu_trace_entry_t trace[PSEMU_TRACE_SIZE];
    uint32_t trace_pos;    /* the next slot to write. This value wraps, thus it counts the total steps, modulo PSEMU_TRACE_SIZE */
    uint64_t total_steps;  /* a count of arm7tdmi_step calls that only increases. In practice it never wraps. */
} arm7tdmi_t;

typedef struct {
    uint32_t value;
    int carry;
} arm_shift_result_t;

void arm7tdmi_init(arm7tdmi_t *cpu, psemu_bus_t *bus);
void arm7tdmi_reset(arm7tdmi_t *cpu, uint32_t reset_vector);

/* Executes one instruction, or delivers a pending FIQ or IRQ in place of
   the instruction.
   Returns the real wait-state cycle cost of that step. This cost is the
   region-costed opcode fetch, and all data-access, internal, and
   pipeline-refill cycles that the instruction needed. See
   docs/hardware-notes.md, "Memory access timing".
   A step that is halted, or that has an unimplemented-opcode fault,
   returns 1. Nothing real executes in that condition. */
uint32_t arm7tdmi_step(arm7tdmi_t *cpu);

/* Diagnostic hook: the PC of the instruction that executes at this time.
   arm7tdmi_step writes this value at the start of each step.
   Register read traces and write traces use this value to record the
   real PC and the CPU mode for each access. Examples are
   psemu_intc_trace_enabled in intc.c and other optional trace flags, the
   CLK_MODE and DAC_CTRL logs in memory.c, and tools/inspect.c.
   This data is important because the real BIOS mixes ARM code and Thumb
   code. A static disassembly that does not track the mode cannot follow
   this mix.
   This project added the hook for one investigation (see
   docs/hardware-notes.md). The hook is now permanent, general-purpose
   diagnostic equipment. */
extern uint32_t psemu_debug_current_pc;

/* Diagnostic hook: this callback occurs one time for each instruction
   that executes. It gives the PC of the instruction and the CPSR that
   applies to the instruction, before the instruction executes.
   The callback is NULL by default. It is compiled in only for the
   psemu_trace target (see PSEMU_TRACE_HOOKS in core/CMakeLists.txt),
   thus frontends have no cost.
   The trace ring above answers the question "which steps occurred before
   this point" for the last PSEMU_TRACE_SIZE steps. This callback answers
   a different question: does execution get to a given address at any
   time during a full run? A ring buffer cannot answer that question,
   because a run of sufficient length writes over the ring many times.
   The watch list in tools/ir_probe.c uses this callback to make a
   distinction between "the app never called its flash-write routine" and
   "the app called the routine and the write was discarded". */
extern void (*psemu_exec_trace_cb)(uint32_t pc, uint32_t cpsr);

/* Shared helpers for arm_exec.c and thumb_exec.c. cpu.c contains them. */
int arm_condition_passed(arm7tdmi_t *cpu, uint32_t cond);
uint32_t arm_read_reg(arm7tdmi_t *cpu, int n, uint32_t pc, int thumb);
void arm_write_reg(arm7tdmi_t *cpu, int n, uint32_t value);
void arm_set_mode(arm7tdmi_t *cpu, uint32_t new_mode);
int arm_current_bank(arm7tdmi_t *cpu);
void arm_set_nz(arm7tdmi_t *cpu, uint32_t result);
uint32_t arm_adc_raw(uint32_t a, uint32_t b, uint32_t carry_in, int *carry_out, int *overflow);
arm_shift_result_t arm_apply_shift(uint32_t value, int shift_type, uint32_t amount, int carry_in, int is_immediate_encoding);
void arm_enter_exception(arm7tdmi_t *cpu, uint32_t mode, uint32_t vector, uint32_t return_addr);

/* Adds `n` cycles to the automatic opcode-fetch cost and data-access
   cost.
   This function applies to two conditions:
   - Internal (I) cycles that have no bus access: a register-specified
     shift, the data-dependent extra cycles of a multiply, or the fixed
     one extra cycle of LDR and LDM.
   - Pipeline-refill fetches when an instruction changes the PC:
     branches, BX, a data-processing, LDR, or LDM instruction that writes
     the PC, or exception entry.
   See docs/hardware-notes.md, "Memory access timing", and the calls in
   arm_exec.c and thumb_exec.c. */
void arm7tdmi_add_cycles(arm7tdmi_t *cpu, uint32_t n);
/* The real early-termination cycle count of the ARM7TDMI multiply
   (m = 1 to 4), for a given Rs value. See the definition in arm_exec.c
   for the rule.
   MUL, MLA, UMULL, UMLAL, SMULL, and SMLAL in arm_exec.c use this
   function. MUL in thumb_exec.c also uses it. */
uint32_t arm7tdmi_mul_m_cycles(uint32_t rs);

void arm_execute(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc);
void thumb_execute(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc);

#endif
