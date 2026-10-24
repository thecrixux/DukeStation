/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "cpu.h"

#define THUMB_SWI_VECTOR 0x08u

static uint32_t sign_extend(uint32_t value, int bits) {
    uint32_t mask = 1u << (bits - 1);
    return (value ^ mask) - mask;
}

static void exec_move_shifted(arm7tdmi_t *cpu, uint16_t instr) {
    int op = (instr >> 11) & 0x3;
    uint32_t offset5 = (instr >> 6) & 0x1Fu;
    int rs = (instr >> 3) & 0x7;
    int rd = instr & 0x7;
    int carry_in = (cpu->cpsr & CPSR_C) != 0;

    arm_shift_result_t sr = arm_apply_shift(cpu->r[rs], op, offset5, carry_in, 1);
    cpu->r[rd] = sr.value;
    arm_set_nz(cpu, sr.value);
    cpu->cpsr = (cpu->cpsr & ~CPSR_C) | (sr.carry ? CPSR_C : 0u);
}

static void exec_add_sub(arm7tdmi_t *cpu, uint16_t instr) {
    int is_imm = (instr >> 10) & 1;
    int is_sub = (instr >> 9) & 1;
    uint32_t operand = (instr >> 6) & 0x7u; /* Rn or imm3, same bit position */
    int rs = (instr >> 3) & 0x7;
    int rd = instr & 0x7;
    uint32_t b = is_imm ? operand : cpu->r[operand];
    uint32_t a = cpu->r[rs];
    int carry, overflow;
    uint32_t result = is_sub ? arm_adc_raw(a, ~b, 1, &carry, &overflow) : arm_adc_raw(a, b, 0, &carry, &overflow);
    cpu->r[rd] = result;
    arm_set_nz(cpu, result);
    cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
}

static void exec_immediate_alu(arm7tdmi_t *cpu, uint16_t instr) {
    int op = (instr >> 11) & 0x3;
    int rd = (instr >> 8) & 0x7;
    uint32_t imm8 = instr & 0xFFu;
    uint32_t a = cpu->r[rd];
    int carry, overflow;
    uint32_t result;

    switch (op) {
    case 0: /* MOV */
        cpu->r[rd] = imm8;
        arm_set_nz(cpu, imm8);
        return;
    case 1: /* CMP */
        result = arm_adc_raw(a, ~imm8, 1, &carry, &overflow);
        arm_set_nz(cpu, result);
        cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        return;
    case 2: /* ADD */
        result = arm_adc_raw(a, imm8, 0, &carry, &overflow);
        break;
    default: /* SUB */
        result = arm_adc_raw(a, ~imm8, 1, &carry, &overflow);
        break;
    }
    cpu->r[rd] = result;
    arm_set_nz(cpu, result);
    cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
}

static void exec_alu(arm7tdmi_t *cpu, uint16_t instr) {
    int op = (instr >> 6) & 0xF;
    int rs = (instr >> 3) & 0x7;
    int rd = instr & 0x7;
    uint32_t a = cpu->r[rd];
    uint32_t b = cpu->r[rs];
    int carry = (cpu->cpsr & CPSR_C) != 0;
    int overflow = (cpu->cpsr & CPSR_V) != 0;
    uint32_t result;

    switch (op) {
    case 0x0: /* AND */
        result = a & b;
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        return;
    case 0x1: /* EOR */
        result = a ^ b;
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        return;
    case 0x2: { /* LSL */
        arm_shift_result_t sr = arm_apply_shift(a, 0, b & 0xFFu, carry, 0);
        cpu->r[rd] = sr.value;
        arm_set_nz(cpu, sr.value);
        cpu->cpsr = (cpu->cpsr & ~CPSR_C) | (sr.carry ? CPSR_C : 0u);
        arm7tdmi_add_cycles(cpu, 1u); /* shift-by-register: +1I */
        return;
    }
    case 0x3: { /* LSR */
        arm_shift_result_t sr = arm_apply_shift(a, 1, b & 0xFFu, carry, 0);
        cpu->r[rd] = sr.value;
        arm_set_nz(cpu, sr.value);
        cpu->cpsr = (cpu->cpsr & ~CPSR_C) | (sr.carry ? CPSR_C : 0u);
        arm7tdmi_add_cycles(cpu, 1u);
        return;
    }
    case 0x4: { /* ASR */
        arm_shift_result_t sr = arm_apply_shift(a, 2, b & 0xFFu, carry, 0);
        cpu->r[rd] = sr.value;
        arm_set_nz(cpu, sr.value);
        cpu->cpsr = (cpu->cpsr & ~CPSR_C) | (sr.carry ? CPSR_C : 0u);
        arm7tdmi_add_cycles(cpu, 1u);
        return;
    }
    case 0x5: /* ADC */
        result = arm_adc_raw(a, b, carry ? 1u : 0u, &carry, &overflow);
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        return;
    case 0x6: /* SBC */
        result = arm_adc_raw(a, ~b, carry ? 1u : 0u, &carry, &overflow);
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        return;
    case 0x7: { /* ROR */
        arm_shift_result_t sr = arm_apply_shift(a, 3, b & 0xFFu, carry, 0);
        cpu->r[rd] = sr.value;
        arm_set_nz(cpu, sr.value);
        cpu->cpsr = (cpu->cpsr & ~CPSR_C) | (sr.carry ? CPSR_C : 0u);
        arm7tdmi_add_cycles(cpu, 1u);
        return;
    }
    case 0x8: /* TST */
        arm_set_nz(cpu, a & b);
        return;
    case 0x9: /* NEG */
        result = arm_adc_raw(0, ~b, 1, &carry, &overflow);
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        return;
    case 0xA: /* CMP */
        result = arm_adc_raw(a, ~b, 1, &carry, &overflow);
        arm_set_nz(cpu, result);
        cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        return;
    case 0xB: /* CMN */
        result = arm_adc_raw(a, b, 0, &carry, &overflow);
        arm_set_nz(cpu, result);
        cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        return;
    case 0xC: /* ORR */
        result = a | b;
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        return;
    case 0xD: /* MUL: C/V are documented as unpredictable on ARMv4T, left unchanged */
        result = a * b;
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        /* Thumb MUL: 1S+mI. Same early-termination rule as ARM's MUL. */
        arm7tdmi_add_cycles(cpu, arm7tdmi_mul_m_cycles(b));
        return;
    case 0xE: /* BIC */
        result = a & ~b;
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        return;
    default: /* MVN */
        result = ~b;
        cpu->r[rd] = result;
        arm_set_nz(cpu, result);
        return;
    }
}

static void exec_hi_reg_ops(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc) {
    int op = (instr >> 8) & 0x3;
    int h1 = (instr >> 7) & 1;
    int h2 = (instr >> 6) & 1;
    int rs = ((instr >> 3) & 0x7) + (h2 ? 8 : 0);
    int rd = (instr & 0x7) + (h1 ? 8 : 0);
    uint32_t src = arm_read_reg(cpu, rs, pc, 1);

    int pc_written = 0;
    switch (op) {
    case 0: { /* ADD */
        uint32_t result = arm_read_reg(cpu, rd, pc, 1) + src;
        if (rd == 15) {
            cpu->r[15] = result & ~1u;
            pc_written = 1;
        } else {
            cpu->r[rd] = result;
        }
        break;
    }
    case 1: { /* CMP */
        int carry, overflow;
        uint32_t result = arm_adc_raw(arm_read_reg(cpu, rd, pc, 1), ~src, 1, &carry, &overflow);
        arm_set_nz(cpu, result);
        cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        break;
    }
    case 2: /* MOV */
        if (rd == 15) {
            cpu->r[15] = src & ~1u;
            pc_written = 1;
        } else {
            cpu->r[rd] = src;
        }
        break;
    default: { /* BX */
        uint32_t target = src;
        if (target & 1u) {
            cpu->cpsr |= CPSR_T;
            target &= ~1u;
        } else {
            cpu->cpsr &= ~CPSR_T;
            target &= ~3u;
        }
        cpu->r[15] = target;
        pc_written = 1;
        break;
    }
    }
    if (pc_written) {
        /* An ADD to the PC, a MOV to the PC, and BX all clear the
           pipeline: 2 more fetches at the new PC. Use the ARM or Thumb
           state that applies after the write. Only BX can change that
           state. On a real ARMv4T, an ADD or a MOV to the PC does not
           change the instruction set. It only jumps in the current
           state. */
        arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(cpu->r[15], (cpu->cpsr & CPSR_T) != 0));
    }
}

static void exec_pc_relative_load(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc) {
    int rd = (instr >> 8) & 0x7;
    uint32_t imm8 = instr & 0xFFu;
    uint32_t base = (pc + 4u) & ~3u;
    cpu->r[rd] = psemu_bus_read32(cpu->bus, base + imm8 * 4u);
    arm7tdmi_add_cycles(cpu, 1u); /* LDR: +1I, data access already counted */
}

static void exec_load_store_reg_offset(arm7tdmi_t *cpu, uint16_t instr) {
    int load = (instr >> 11) & 1;
    int byte = (instr >> 10) & 1;
    int ro = (instr >> 6) & 0x7;
    int rb = (instr >> 3) & 0x7;
    int rd = instr & 0x7;
    uint32_t addr = cpu->r[rb] + cpu->r[ro];

    if (load) {
        cpu->r[rd] = byte ? psemu_bus_read8(cpu->bus, addr) : psemu_bus_read32(cpu->bus, addr & ~3u);
        arm7tdmi_add_cycles(cpu, 1u); /* LDR/LDRB: +1I */
    } else if (byte) {
        psemu_bus_write8(cpu->bus, addr, (uint8_t)cpu->r[rd]);
    } else {
        psemu_bus_write32(cpu->bus, addr & ~3u, cpu->r[rd]);
    }
}

static void exec_load_store_sign_extended(arm7tdmi_t *cpu, uint16_t instr) {
    int h = (instr >> 11) & 1;
    int s = (instr >> 10) & 1;
    int ro = (instr >> 6) & 0x7;
    int rb = (instr >> 3) & 0x7;
    int rd = instr & 0x7;
    uint32_t addr = cpu->r[rb] + cpu->r[ro];

    if (!s && !h) { /* STRH */
        psemu_bus_write16(cpu->bus, addr & ~1u, (uint16_t)cpu->r[rd]);
        return;
    } else if (!s && h) { /* LDRH */
        cpu->r[rd] = psemu_bus_read16(cpu->bus, addr & ~1u);
    } else if (s && !h) { /* LDSB */
        int8_t b = (int8_t)psemu_bus_read8(cpu->bus, addr);
        cpu->r[rd] = (uint32_t)(int32_t)b;
    } else { /* LDSH */
        int16_t hw = (int16_t)psemu_bus_read16(cpu->bus, addr & ~1u);
        cpu->r[rd] = (uint32_t)(int32_t)hw;
    }
    arm7tdmi_add_cycles(cpu, 1u); /* LDRH/LDSB/LDSH: +1I */
}

static void exec_load_store_imm_offset(arm7tdmi_t *cpu, uint16_t instr) {
    int byte = (instr >> 12) & 1;
    int load = (instr >> 11) & 1;
    uint32_t imm5 = (instr >> 6) & 0x1Fu;
    int rb = (instr >> 3) & 0x7;
    int rd = instr & 0x7;
    uint32_t offset = byte ? imm5 : imm5 * 4u;
    uint32_t addr = cpu->r[rb] + offset;

    if (load) {
        cpu->r[rd] = byte ? psemu_bus_read8(cpu->bus, addr) : psemu_bus_read32(cpu->bus, addr & ~3u);
        arm7tdmi_add_cycles(cpu, 1u); /* LDR/LDRB: +1I */
    } else if (byte) {
        psemu_bus_write8(cpu->bus, addr, (uint8_t)cpu->r[rd]);
    } else {
        psemu_bus_write32(cpu->bus, addr & ~3u, cpu->r[rd]);
    }
}

static void exec_load_store_halfword_imm(arm7tdmi_t *cpu, uint16_t instr) {
    int load = (instr >> 11) & 1;
    uint32_t imm5 = (instr >> 6) & 0x1Fu;
    int rb = (instr >> 3) & 0x7;
    int rd = instr & 0x7;
    uint32_t addr = cpu->r[rb] + imm5 * 2u;

    if (load) {
        cpu->r[rd] = psemu_bus_read16(cpu->bus, addr & ~1u);
        arm7tdmi_add_cycles(cpu, 1u); /* LDRH: +1I */
    } else {
        psemu_bus_write16(cpu->bus, addr & ~1u, (uint16_t)cpu->r[rd]);
    }
}

static void exec_sp_relative(arm7tdmi_t *cpu, uint16_t instr) {
    int load = (instr >> 11) & 1;
    int rd = (instr >> 8) & 0x7;
    uint32_t imm8 = instr & 0xFFu;
    uint32_t addr = cpu->r[13] + imm8 * 4u;

    if (load) {
        cpu->r[rd] = psemu_bus_read32(cpu->bus, addr & ~3u);
        arm7tdmi_add_cycles(cpu, 1u); /* LDR: +1I */
    } else {
        psemu_bus_write32(cpu->bus, addr & ~3u, cpu->r[rd]);
    }
}

static void exec_load_address(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc) {
    int sp = (instr >> 11) & 1;
    int rd = (instr >> 8) & 0x7;
    uint32_t imm8 = instr & 0xFFu;
    uint32_t base = sp ? cpu->r[13] : ((pc + 4u) & ~3u);
    cpu->r[rd] = base + imm8 * 4u;
}

static void exec_add_sp_offset(arm7tdmi_t *cpu, uint16_t instr) {
    int negative = (instr >> 7) & 1;
    uint32_t offset = (instr & 0x7Fu) * 4u;
    cpu->r[13] = negative ? cpu->r[13] - offset : cpu->r[13] + offset;
}

static void exec_push_pop(arm7tdmi_t *cpu, uint16_t instr) {
    int load = (instr >> 11) & 1;
    int extra = (instr >> 8) & 1; /* LR (push) or PC (pop) */
    uint8_t rlist = (uint8_t)(instr & 0xFFu);

    if (load) { /* POP: low registers first, then PC (matches ascending addresses) */
        for (int i = 0; i < 8; i++) {
            if (rlist & (1u << i)) {
                cpu->r[i] = psemu_bus_read32(cpu->bus, cpu->r[13] & ~3u);
                cpu->r[13] += 4u;
            }
        }
        if (extra) {
            cpu->r[15] = psemu_bus_read32(cpu->bus, cpu->r[13] & ~3u) & ~1u;
            cpu->r[13] += 4u;
        }
        /* POP costs nS+1N+1I. This code already counted the n reads
           above, with one read32 call for each. Only +1I remains.
           Add a pipeline refill of +1S+1N (2 more fetches) if the code
           popped the PC. The CPU stays in the Thumb state: an LDM or a
           POP on a real ARMv4T does not change the instruction set at
           the PC. */
        uint32_t extra_cycles = 1u;
        if (extra) {
            extra_cycles += 2u * psemu_region_fetch_cycles(cpu->r[15], 1);
        }
        arm7tdmi_add_cycles(cpu, extra_cycles);
    } else { /* PUSH: low registers at the lowest addresses, LR just below the old SP */
        int count = extra ? 1 : 0;
        for (int i = 0; i < 8; i++) {
            if (rlist & (1u << i)) {
                count++;
            }
        }
        uint32_t addr = cpu->r[13] - (uint32_t)count * 4u;
        cpu->r[13] = addr;
        for (int i = 0; i < 8; i++) {
            if (rlist & (1u << i)) {
                psemu_bus_write32(cpu->bus, addr, cpu->r[i]);
                addr += 4u;
            }
        }
        if (extra) {
            psemu_bus_write32(cpu->bus, addr, cpu->r[14]);
        }
    }
}

static void exec_multiple_transfer(arm7tdmi_t *cpu, uint16_t instr) {
    int load = (instr >> 11) & 1;
    int rb = (instr >> 8) & 0x7;
    uint8_t rlist = (uint8_t)(instr & 0xFFu);
    uint32_t addr = cpu->r[rb];

    for (int i = 0; i < 8; i++) {
        if (rlist & (1u << i)) {
            if (load) {
                cpu->r[i] = psemu_bus_read32(cpu->bus, addr & ~3u);
            } else {
                psemu_bus_write32(cpu->bus, addr & ~3u, cpu->r[i]);
            }
            addr += 4u;
        }
    }
    cpu->r[rb] = addr;
    if (load) {
        arm7tdmi_add_cycles(cpu, 1u); /* LDMIA: +1I; STMIA needs no extra */
    }
}

static void exec_conditional_branch(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc) {
    uint32_t cond = (instr >> 8) & 0xFu;
    if (!arm_condition_passed(cpu, cond)) {
        return;
    }
    int32_t offset = (int32_t)sign_extend(instr & 0xFFu, 8) * 2;
    cpu->r[15] = (pc + 4u) + (uint32_t)offset;
    /* A conditional branch that the CPU takes costs 2S+1N. The first 1S is
       the opcode fetch, which this code already counted. A branch that the
       CPU does not take returned above, with no extra cost. The CPU stays
       in the Thumb state. */
    arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(cpu->r[15], 1));
}

static void exec_thumb_swi(arm7tdmi_t *cpu, uint32_t pc) {
    arm_enter_exception(cpu, ARM_MODE_SVC, THUMB_SWI_VECTOR, pc + 2u);
    /* Exception entry always goes to the ARM state.
       The pipeline-refill fetch cost uses the ARM-mode table. */
    arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(THUMB_SWI_VECTOR, 0));
}

static void exec_unconditional_branch(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc) {
    int32_t offset = (int32_t)sign_extend(instr & 0x7FFu, 11) * 2;
    cpu->r[15] = (pc + 4u) + (uint32_t)offset;
    arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(cpu->r[15], 1));
}

static void exec_long_branch_link(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc) {
    int high_half = !((instr >> 11) & 1); /* bits[15:11]: 11110 = first half, 11111 = second half */
    uint32_t offset11 = instr & 0x7FFu;

    if (high_half) {
        int32_t offset_high = (int32_t)(sign_extend(offset11, 11) << 12);
        cpu->r[14] = (pc + 4u) + (uint32_t)offset_high;
        /* The first half is a simple LR write, with no PC change.
           The cost is 1S only, which this code already counted. There is
           no extra cost. */
    } else {
        uint32_t target = cpu->r[14] + (offset11 << 1);
        /* This code sets bit 0 of LR, to mark the return address as a
           Thumb address. Thus a later "BX LR" correctly stays in the
           Thumb state, and does not change to the ARM state.
           This behavior has been true since ARMv4T introduced the Thumb
           BL instruction. It is not a feature of the ARMv5 BLX
           instruction alone. A comparison against the real ARMv4T
           architecture behavior confirms this. */
        cpu->r[14] = (pc + 2u) | 1u;
        cpu->r[15] = target;
        /* The second half actually branches: 2S+1N pipeline refill. */
        arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(cpu->r[15], 1));
    }
}

void thumb_execute(arm7tdmi_t *cpu, uint16_t instr, uint32_t pc) {
    uint16_t top3 = (uint16_t)((instr >> 13) & 0x7u);

    switch (top3) {
    case 0: { /* formats 1, 2 */
        uint16_t sub = (uint16_t)((instr >> 11) & 0x3u);
        if (sub == 0x3u) {
            exec_add_sub(cpu, instr);
        } else {
            exec_move_shifted(cpu, instr);
        }
        return;
    }
    case 1: /* format 3 */
        exec_immediate_alu(cpu, instr);
        return;
    case 2: { /* formats 4, 5, 6, 7, 8 */
        uint16_t nibble = (uint16_t)((instr >> 12) & 0xFu);
        if (nibble == 0x4u) {
            uint16_t six = (uint16_t)((instr >> 10) & 0x3Fu);
            if (six == 0x10u) {
                exec_alu(cpu, instr);
            } else if (six == 0x11u) {
                exec_hi_reg_ops(cpu, instr, pc);
            } else {
                exec_pc_relative_load(cpu, instr, pc);
            }
            return;
        }
        if (((instr >> 9) & 1u) == 0u) {
            exec_load_store_reg_offset(cpu, instr);
        } else {
            exec_load_store_sign_extended(cpu, instr);
        }
        return;
    }
    case 3: /* format 9 */
        exec_load_store_imm_offset(cpu, instr);
        return;
    case 4: /* formats 10, 11 */
        if (((instr >> 12) & 1u) == 0u) {
            exec_load_store_halfword_imm(cpu, instr);
        } else {
            exec_sp_relative(cpu, instr);
        }
        return;
    case 5: /* formats 12, 13, 14 */
        if (((instr >> 12) & 0xFu) == 0xAu) {
            exec_load_address(cpu, instr, pc);
        } else if ((instr & 0xFF00u) == 0xB000u) {
            exec_add_sp_offset(cpu, instr);
        } else if ((instr & 0xF600u) == 0xB400u) {
            exec_push_pop(cpu, instr);
        } else {
            cpu->unimplemented = 1;
        }
        return;
    case 6: /* formats 15, 16, 17 */
        if (((instr >> 12) & 0xFu) == 0xCu) {
            exec_multiple_transfer(cpu, instr);
        } else if ((instr & 0xFF00u) == 0xDF00u) {
            exec_thumb_swi(cpu, pc);
        } else {
            exec_conditional_branch(cpu, instr, pc);
        }
        return;
    default: /* formats 18, 19 */
        if (((instr >> 11) & 0x1Fu) == 0x1Cu) {
            exec_unconditional_branch(cpu, instr, pc);
        } else if (((instr >> 12) & 0xFu) == 0xFu) {
            exec_long_branch_link(cpu, instr, pc);
        } else {
            cpu->unimplemented = 1;
        }
        return;
    }
}
