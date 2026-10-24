/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "cpu.h"

#define SWI_VECTOR 0x08u

static uint32_t decode_operand2(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc, int *carry_out) {
    int carry_in = (cpu->cpsr & CPSR_C) != 0;

    if (instr & (1u << 25)) { /* immediate: ROR(imm8, rotate*2) */
        uint32_t imm8 = instr & 0xFFu;
        uint32_t rot = ((instr >> 8) & 0xFu) * 2u;
        uint32_t value = (rot == 0) ? imm8 : ((imm8 >> rot) | (imm8 << (32 - rot)));
        *carry_out = (rot == 0) ? carry_in : (int)((value >> 31) & 1u);
        return value;
    }

    int rm = instr & 0xF;
    int shift_type = (int)((instr >> 5) & 0x3u);
    uint32_t rm_value = arm_read_reg(cpu, rm, pc, 0);
    uint32_t amount;
    int is_immediate_encoding;

    if (instr & (1u << 4)) { /* shift amount from register (low byte) */
        int rs = (int)((instr >> 8) & 0xFu);
        amount = arm_read_reg(cpu, rs, pc, 0) & 0xFFu;
        is_immediate_encoding = 0;
    } else {
        amount = (instr >> 7) & 0x1Fu;
        is_immediate_encoding = 1;
    }

    arm_shift_result_t sr = arm_apply_shift(rm_value, shift_type, amount, carry_in, is_immediate_encoding);
    *carry_out = sr.carry;
    return sr.value;
}

static void exec_data_processing(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    uint32_t opcode = (instr >> 21) & 0xFu;
    int set_flags = (int)((instr >> 20) & 1u);
    int rn = (int)((instr >> 16) & 0xFu);
    int rd = (int)((instr >> 12) & 0xFu);
    /* A shift amount from a register costs one more internal cycle:
       "1S+1I" in place of "1S". An immediate shift amount has no such
       cost. The shifter must wait for a register read before it can
       operate. */
    int reg_shift = !(instr & (1u << 25)) && (instr & (1u << 4));

    int shifter_carry;
    uint32_t op2 = decode_operand2(cpu, instr, pc, &shifter_carry);
    uint32_t op1 = arm_read_reg(cpu, rn, pc, 0);
    uint32_t c_in = (cpu->cpsr & CPSR_C) ? 1u : 0u;

    uint32_t result = 0;
    int write_result = 1;
    int carry = shifter_carry;
    int overflow = (cpu->cpsr & CPSR_V) != 0;

    switch (opcode) {
    case 0x0:
        result = op1 & op2;
        break; /* AND */
    case 0x1:
        result = op1 ^ op2;
        break; /* EOR */
    case 0x2:
        result = arm_adc_raw(op1, ~op2, 1, &carry, &overflow);
        break; /* SUB */
    case 0x3:
        result = arm_adc_raw(op2, ~op1, 1, &carry, &overflow);
        break; /* RSB */
    case 0x4:
        result = arm_adc_raw(op1, op2, 0, &carry, &overflow);
        break; /* ADD */
    case 0x5:
        result = arm_adc_raw(op1, op2, c_in, &carry, &overflow);
        break; /* ADC */
    case 0x6:
        result = arm_adc_raw(op1, ~op2, c_in, &carry, &overflow);
        break; /* SBC */
    case 0x7:
        result = arm_adc_raw(op2, ~op1, c_in, &carry, &overflow);
        break; /* RSC */
    case 0x8:
        result = op1 & op2;
        write_result = 0;
        break; /* TST */
    case 0x9:
        result = op1 ^ op2;
        write_result = 0;
        break; /* TEQ */
    case 0xA:
        result = arm_adc_raw(op1, ~op2, 1, &carry, &overflow);
        write_result = 0;
        break; /* CMP */
    case 0xB:
        result = arm_adc_raw(op1, op2, 0, &carry, &overflow);
        write_result = 0;
        break; /* CMN */
    case 0xC:
        result = op1 | op2;
        break; /* ORR */
    case 0xD:
        result = op2;
        break; /* MOV */
    case 0xE:
        result = op1 & ~op2;
        break; /* BIC */
    default:
        result = ~op2;
        break; /* MVN */
    }

    if (set_flags) {
        uint32_t mode = cpu->cpsr & CPSR_MODE_MASK;
        if (rd == 15 && mode != ARM_MODE_USR && mode != ARM_MODE_SYS) {
            /* "MOVS PC, ..." or "SUBS PC, ..." in a privileged mode is
               the standard exception-return method.
               It restores the full CPSR (the mode, I, F, T, and NZCV)
               from the SPSR of this mode. It does not write only the
               flags. */
            uint32_t spsr = cpu->spsr_bank[arm_current_bank(cpu)];
            arm_set_mode(cpu, spsr & CPSR_MODE_MASK);
            cpu->cpsr = spsr;
        } else {
            arm_set_nz(cpu, result);
            cpu->cpsr = (cpu->cpsr & ~(CPSR_C | CPSR_V)) | (carry ? CPSR_C : 0u) | (overflow ? CPSR_V : 0u);
        }
    }
    if (write_result) {
        arm_write_reg(cpu, rd, result);
    }

    uint32_t extra = reg_shift ? 1u : 0u;
    if (write_result && rd == 15) {
        /* A write to the PC clears the pipeline: 2 more fetches at the
           new PC. Use the ARM or Thumb state that applies after this
           instruction executes. A "MOVS PC,..." that restores the SPSR
           can change that state. */
        extra += 2u * psemu_region_fetch_cycles(cpu->r[15], (cpu->cpsr & CPSR_T) != 0);
    }
    if (extra) {
        arm7tdmi_add_cycles(cpu, extra);
    }
}

/* A real ARM7TDMI multiply stops early. The stop time depends on the
   value of Rs. When more of the high bytes of Rs are all 0 or all 1, the
   multiply costs less internal cycles (m = 1 to 4).
   The ARM 32x32->32 and 32x32->64 multiply forms below use this function.
   The Thumb MUL instruction (thumb_exec.c) also uses it. */
uint32_t arm7tdmi_mul_m_cycles(uint32_t rs) {
    if ((rs >> 8) == 0u || (rs >> 8) == 0x00FFFFFFu) {
        return 1u;
    }
    if ((rs >> 16) == 0u || (rs >> 16) == 0x0000FFFFu) {
        return 2u;
    }
    if ((rs >> 24) == 0u || (rs >> 24) == 0x000000FFu) {
        return 3u;
    }
    return 4u;
}

static void exec_multiply(arm7tdmi_t *cpu, uint32_t instr) {
    int rd = (int)((instr >> 16) & 0xFu);
    int rn = (int)((instr >> 12) & 0xFu); /* accumulate operand */
    int rs = (int)((instr >> 8) & 0xFu);
    int rm = (int)(instr & 0xFu);
    int accumulate = (int)((instr >> 21) & 1u);
    int set_flags = (int)((instr >> 20) & 1u);

    uint32_t m = arm7tdmi_mul_m_cycles(cpu->r[rs]);
    uint32_t result = cpu->r[rm] * cpu->r[rs];
    if (accumulate) {
        result += cpu->r[rn];
    }
    cpu->r[rd] = result;
    if (set_flags) {
        arm_set_nz(cpu, result);
    }
    /* MUL costs 1S+mI. MLA (accumulate) costs 1S+(m+1)I. The 1S is the
       opcode fetch, which this code already counted. */
    arm7tdmi_add_cycles(cpu, accumulate ? m + 1u : m);
}

static void exec_long_multiply(arm7tdmi_t *cpu, uint32_t instr) {
    int rdhi = (int)((instr >> 16) & 0xFu);
    int rdlo = (int)((instr >> 12) & 0xFu);
    int rs = (int)((instr >> 8) & 0xFu);
    int rm = (int)(instr & 0xFu);
    int is_signed = (int)((instr >> 22) & 1u);
    int accumulate = (int)((instr >> 21) & 1u);
    int set_flags = (int)((instr >> 20) & 1u);
    uint64_t result;
    uint32_t m = arm7tdmi_mul_m_cycles(cpu->r[rs]); /* read before rdlo/rdhi writeback in case Rs aliases them */

    if (is_signed) {
        result = (uint64_t)((int64_t)(int32_t)cpu->r[rm] * (int64_t)(int32_t)cpu->r[rs]);
    } else {
        result = (uint64_t)cpu->r[rm] * (uint64_t)cpu->r[rs];
    }
    if (accumulate) {
        result += ((uint64_t)cpu->r[rdhi] << 32) | (uint64_t)cpu->r[rdlo];
    }
    cpu->r[rdlo] = (uint32_t)result;
    cpu->r[rdhi] = (uint32_t)(result >> 32);
    if (set_flags) {
        cpu->cpsr = (cpu->cpsr & ~(CPSR_N | CPSR_Z)) | ((result & 0x8000000000000000ull) ? CPSR_N : 0u) |
                    (result == 0 ? CPSR_Z : 0u);
    }
    /* UMULL/SMULL: 1S+(m+1)I. UMLAL/SMLAL (accumulate): 1S+(m+2)I. */
    arm7tdmi_add_cycles(cpu, accumulate ? m + 2u : m + 1u);
}

static void exec_swap(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    int byte = (int)((instr >> 22) & 1u);
    int rn = (int)((instr >> 16) & 0xFu);
    int rd = (int)((instr >> 12) & 0xFu);
    int rm = (int)(instr & 0xFu);
    uint32_t addr = arm_read_reg(cpu, rn, pc, 0);

    if (byte) {
        uint8_t old = psemu_bus_read8(cpu->bus, addr);
        psemu_bus_write8(cpu->bus, addr, (uint8_t)cpu->r[rm]);
        cpu->r[rd] = old;
    } else {
        uint32_t old = psemu_bus_read32(cpu->bus, addr & ~3u);
        psemu_bus_write32(cpu->bus, addr & ~3u, cpu->r[rm]);
        cpu->r[rd] = old;
    }
    /* SWP costs 1S+2N+1I.
       This code already counted the opcode fetch (1S) and both data
       accesses (2N), through the opcode fetch and the read and write
       calls above. Only the extra internal cycle remains. */
    arm7tdmi_add_cycles(cpu, 1u);
}

static void exec_bx(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    int rm = (int)(instr & 0xFu);
    uint32_t target = arm_read_reg(cpu, rm, pc, 0);
    if (target & 1u) {
        cpu->cpsr |= CPSR_T;
        target &= ~1u;
    } else {
        cpu->cpsr &= ~CPSR_T;
        target &= ~3u;
    }
    cpu->r[15] = target;
    /* BX costs 2S+1N. The first 1S is the opcode fetch, which this code
       already counted. The remaining "1S+1N" is the pipeline refill.
       This emulator models the refill as 2 more fetches at the target.
       This hardware has no difference between an S cost and an N cost.
       See docs/hardware-notes.md. */
    arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(cpu->r[15], (cpu->cpsr & CPSR_T) != 0));
}

static void exec_mrs(arm7tdmi_t *cpu, uint32_t instr) {
    int rd = (int)((instr >> 12) & 0xFu);
    int from_spsr = (int)((instr >> 22) & 1u);
    cpu->r[rd] = from_spsr ? cpu->spsr_bank[arm_current_bank(cpu)] : cpu->cpsr;
}

static void exec_msr(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    int immediate = (int)((instr >> 25) & 1u);
    int to_spsr = (int)((instr >> 22) & 1u);
    uint32_t field_mask = (instr >> 16) & 0xFu;
    uint32_t value;

    if (immediate) {
        uint32_t imm8 = instr & 0xFFu;
        uint32_t rot = ((instr >> 8) & 0xFu) * 2u;
        value = (rot == 0) ? imm8 : ((imm8 >> rot) | (imm8 << (32 - rot)));
    } else {
        value = arm_read_reg(cpu, (int)(instr & 0xFu), pc, 0);
    }

    uint32_t byte_mask = 0;
    if (field_mask & 0x1u) byte_mask |= 0x000000FFu; /* control: mode, I, F, T */
    if (field_mask & 0x2u) byte_mask |= 0x0000FF00u; /* unused on ARMv4T */
    if (field_mask & 0x4u) byte_mask |= 0x00FF0000u; /* unused on ARMv4T */
    if (field_mask & 0x8u) byte_mask |= 0xFF000000u; /* flags: N,Z,C,V */

    if (to_spsr) {
        uint32_t *spsr = &cpu->spsr_bank[arm_current_bank(cpu)];
        *spsr = (*spsr & ~byte_mask) | (value & byte_mask);
        return;
    }

    if ((cpu->cpsr & CPSR_MODE_MASK) == ARM_MODE_USR) {
        /* On real ARM7TDMI and ARMv4T silicon, an MSR to the CPSR from
           User mode can change only the condition flags (the highest
           byte).
           The control byte (the mode, T, F, and I) is protected. Real
           hardware ignores a User-mode write to that byte and gives no
           error. This is true for each control bit that the instruction
           selects.
           This is fixed CPU behavior. It is not specific to the
           PocketStation. This emulator did not apply the rule before the
           correction.
           A real homebrew app (pk_timing_bench) depended on this behavior
           without intent. It wrote CPSR_I and CPSR_F from User mode, and
           expected no effect on real hardware (see start.s: "kept anyway
           since it's harmless"). Before the correction, this emulator
           applied the write. Thus it masked the interrupts globally for
           the full runtime of the app. Real hardware never does this. */
        byte_mask &= 0xFF000000u;
    }

    if ((byte_mask & 0xFFu) && (value & CPSR_MODE_MASK) != (cpu->cpsr & CPSR_MODE_MASK)) {
        arm_set_mode(cpu, value & CPSR_MODE_MASK); /* re-banks r13/r14 for the new mode */
    }
    cpu->cpsr = (cpu->cpsr & ~byte_mask) | (value & byte_mask);
}

static void exec_single_transfer(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    int immediate = !(instr & (1u << 25));
    int pre_index = (int)((instr >> 24) & 1u);
    int up = (int)((instr >> 23) & 1u);
    int byte = (int)((instr >> 22) & 1u);
    int writeback = (int)((instr >> 21) & 1u);
    int load = (int)((instr >> 20) & 1u);
    int rn = (int)((instr >> 16) & 0xFu);
    int rd = (int)((instr >> 12) & 0xFu);

    uint32_t offset;
    if (immediate) {
        offset = instr & 0xFFFu;
    } else {
        int shift_carry;
        offset = decode_operand2(cpu, instr & ~(1u << 25), pc, &shift_carry);
    }

    uint32_t base = arm_read_reg(cpu, rn, pc, 0);
    uint32_t address = pre_index ? (up ? base + offset : base - offset) : base;

    if (load) {
        uint32_t value;
        if (byte) {
            value = psemu_bus_read8(cpu->bus, address);
        } else {
            value = psemu_bus_read32(cpu->bus, address & ~3u);
            if (address & 3u) {
                uint32_t rot = (address & 3u) * 8u;
                value = (value >> rot) | (value << (32 - rot));
            }
        }
        arm_write_reg(cpu, rd, value);
    } else {
        uint32_t value = arm_read_reg(cpu, rd, pc, 0);
        if (byte) {
            psemu_bus_write8(cpu->bus, address, (uint8_t)value);
        } else {
            psemu_bus_write32(cpu->bus, address & ~3u, value);
        }
    }

    uint32_t final_address = pre_index ? address : (up ? base + offset : base - offset);
    if ((!pre_index || writeback) && rn != 15) {
        cpu->r[rn] = final_address;
    }

    if (load) {
        /* LDR costs 1S+1N+1I. This code already counted the fetch and the
           data read. Only +1I remains. Add a +1S+1N pipeline refill if
           the target was the PC. */
        uint32_t extra = 1u;
        if (rd == 15) {
            extra += 2u * psemu_region_fetch_cycles(cpu->r[15], (cpu->cpsr & CPSR_T) != 0);
        }
        arm7tdmi_add_cycles(cpu, extra);
    }
    /* STR costs 2N. This code already counted the fetch, which is the
       first N, and the data write. There is no extra cost. */
}

static void exec_halfword_transfer(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    int pre_index = (int)((instr >> 24) & 1u);
    int up = (int)((instr >> 23) & 1u);
    int imm_offset = (int)((instr >> 22) & 1u);
    int writeback = (int)((instr >> 21) & 1u);
    int load = (int)((instr >> 20) & 1u);
    int rn = (int)((instr >> 16) & 0xFu);
    int rd = (int)((instr >> 12) & 0xFu);
    int sh = (int)((instr >> 5) & 0x3u);

    uint32_t offset =
        imm_offset ? (((instr >> 8) & 0xFu) << 4) | (instr & 0xFu) : cpu->r[instr & 0xFu];

    uint32_t base = arm_read_reg(cpu, rn, pc, 0);
    uint32_t address = pre_index ? (up ? base + offset : base - offset) : base;

    if (load) {
        uint32_t value;
        switch (sh) {
        case 1: { /* LDRH */
            /* A confirmed hardware quirk of the real ARM7TDMI. Other work
               on the same core records this quirk, and a test against a
               real app confirms it here. See docs/hardware-notes.md,
               "CPU".
               A misaligned halfword read (with address bit 0 set) does
               not round down to the halfword below. Real silicon rotates
               the loaded halfword right by 8 bits. Thus it exchanges the
               two bytes before the value gets to the register.
               This emulator read the aligned halfword with no rotation
               before. For a caller that removes the high byte, that
               result looks the same as the correct rotated odd-address
               read. The font-glyph routine of a real PocketStation
               homebrew app uses this exact pattern: an LDRH with a
               post-increment of 1, masked to the low byte, to read a
               byte-packed table one byte at a time. Thus every second
               byte was incorrect. */
            uint16_t h = psemu_bus_read16(cpu->bus, address & ~1u);
            if (address & 1u) {
                h = (uint16_t)((h >> 8) | (h << 8));
            }
            value = h;
            break;
        }
        case 2: {
            int8_t b = (int8_t)psemu_bus_read8(cpu->bus, address);
            value = (uint32_t)(int32_t)b;
            break; /* LDRSB */
        }
        case 3: { /* LDRSH */
            if (address & 1u) {
                /* Real ARM7TDMI silicon does not rotate a misaligned LDRSH
                   and then extend its sign, in the way that it rotates an
                   LDRH.
                   Instead, the instruction operates as a sign-extended
                   byte read (LDRSB) from the odd address. This is the
                   same family of quirk as the LDRH quirk above. */
                int8_t b = (int8_t)psemu_bus_read8(cpu->bus, address);
                value = (uint32_t)(int32_t)b;
            } else {
                int16_t h = (int16_t)psemu_bus_read16(cpu->bus, address);
                value = (uint32_t)(int32_t)h;
            }
            break;
        }
        default:
            value = 0;
            break;
        }
        arm_write_reg(cpu, rd, value);
    } else {
        uint32_t value = arm_read_reg(cpu, rd, pc, 0);
        psemu_bus_write16(cpu->bus, address & ~1u, (uint16_t)value); /* STRH */
    }

    uint32_t final_address = pre_index ? address : (up ? base + offset : base - offset);
    if ((!pre_index || writeback) && rn != 15) {
        cpu->r[rn] = final_address;
    }

    if (load) {
        /* LDRH, LDRSB, and LDRSH have the same
           "1S+1N+1I, plus a pipeline refill" shape as LDR. See
           exec_single_transfer. */
        uint32_t extra = 1u;
        if (rd == 15) {
            extra += 2u * psemu_region_fetch_cycles(cpu->r[15], (cpu->cpsr & CPSR_T) != 0);
        }
        arm7tdmi_add_cycles(cpu, extra);
    }
}

/* Reads register `reg` from the USER bank, for each CPU mode. This is the "^" form of LDM and STM,
   where S is set and the PC is not in the list. r0-r7 and r15 are never banked, thus only r8-r14 need
   a redirection. r8-r12 need a redirection only when the current mode is FIQ. In User mode or System
   mode, the current registers ARE the user bank, thus this function does a simple read. */
static uint32_t read_user_bank_reg(arm7tdmi_t *cpu, int reg, uint32_t pc) {
    uint32_t mode = cpu->cpsr & CPSR_MODE_MASK;
    if (reg == 15) {
        return arm_read_reg(cpu, 15, pc, 0);
    }
    if (mode == ARM_MODE_USR || mode == ARM_MODE_SYS) {
        return cpu->r[reg];
    }
    if (reg == 13 || reg == 14) {
        return (reg == 13) ? cpu->r13_bank[ARM_BANK_USR] : cpu->r14_bank[ARM_BANK_USR];
    }
    if (reg >= 8 && reg <= 12 && mode == ARM_MODE_FIQ) {
        return cpu->r8_12_bank[0][reg - 8]; /* index 0 is the copy every non-FIQ mode shares */
    }
    return cpu->r[reg];
}

static void write_user_bank_reg(arm7tdmi_t *cpu, int reg, uint32_t value) {
    uint32_t mode = cpu->cpsr & CPSR_MODE_MASK;
    if (mode == ARM_MODE_USR || mode == ARM_MODE_SYS) {
        cpu->r[reg] = value;
        return;
    }
    if (reg == 13 || reg == 14) {
        if (reg == 13) {
            cpu->r13_bank[ARM_BANK_USR] = value;
        } else {
            cpu->r14_bank[ARM_BANK_USR] = value;
        }
        return;
    }
    if (reg >= 8 && reg <= 12 && mode == ARM_MODE_FIQ) {
        cpu->r8_12_bank[0][reg - 8] = value;
        return;
    }
    cpu->r[reg] = value;
}

static void exec_block_transfer(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    int pre_index = (int)((instr >> 24) & 1u);
    int up = (int)((instr >> 23) & 1u);
    int s_bit = (int)((instr >> 22) & 1u);
    int writeback = (int)((instr >> 21) & 1u);
    int load = (int)((instr >> 20) & 1u);
    int rn = (int)((instr >> 16) & 0xFu);
    uint32_t reg_list = instr & 0xFFFFu;

    uint32_t base = cpu->r[rn];
    int count = 0;
    for (int i = 0; i < 16; i++) {
        if (reg_list & (1u << i)) {
            count++;
        }
    }
    if (count == 0) {
        return; /* empty register list is architecturally unpredictable */
    }

    /* S set with the PC absent from the list is the "^" user-bank form. The transfer moves the
       registers of User mode, and not the banked registers of the current mode. This is the method
       that a privileged handler uses to save and restore r13 and r14 of the interrupted code, and
       r8-r12 from FIQ. The handler does not have to change the mode to reach those registers. The real
       BIOS uses this form: "STMIA r0!,{r13,r14}^" at 0x04001944, and the matching LDM at 0x04001B90.
       No app that this project can operate has executed those instructions.

       In the architecture, a base writeback together with "^" is UNPREDICTABLE. The BIOS does this
       operation. The base register that it writes back is never a banked register. Thus in practice
       the usual writeback below has one clear result, and this code does not change it. */
    int user_bank = s_bit && !(reg_list & 0x8000u);

    uint32_t addr = base;
    int step = up ? 4 : -4;
    for (int i = 0; i < 16; i++) {
        int reg = up ? i : (15 - i); /* keeps lowest register at the lowest address either way */
        if (!(reg_list & (1u << reg))) {
            continue;
        }
        if (pre_index) {
            addr = (uint32_t)((int32_t)addr + step);
        }
        if (load) {
            uint32_t value = psemu_bus_read32(cpu->bus, addr & ~3u);
            if (user_bank) {
                write_user_bank_reg(cpu, reg, value);
            } else {
                cpu->r[reg] = value;
            }
        } else {
            uint32_t value;
            if (user_bank) {
                value = read_user_bank_reg(cpu, reg, pc);
            } else {
                value = (reg == 15) ? arm_read_reg(cpu, 15, pc, 0) : cpu->r[reg];
            }
            psemu_bus_write32(cpu->bus, addr & ~3u, value);
        }
        if (!pre_index) {
            addr = (uint32_t)((int32_t)addr + step);
        }
    }

    if (writeback && rn != 15) {
        cpu->r[rn] = up ? base + (uint32_t)count * 4u : base - (uint32_t)count * 4u;
    }

    /* "LDM ...,{...,PC}^" is the second standard exception-return method.
       The first method is "MOVS PC,LR" or "SUBS PC,LR".
       An S bit that is set, with the PC in the register list, also
       restores the full CPSR from the SPSR of this mode. An S bit that is
       set WITHOUT the PC in the list is the different user-bank form.
       `user_bank` above covers that form.
       This restore operation occurs before the cycle count below. Thus a
       PC-refill fetch cost uses the ARM or Thumb state after the
       restore. */
    if (load && s_bit && (reg_list & 0x8000u)) {
        uint32_t mode = cpu->cpsr & CPSR_MODE_MASK;
        if (mode != ARM_MODE_USR && mode != ARM_MODE_SYS) {
            uint32_t spsr = cpu->spsr_bank[arm_current_bank(cpu)];
            arm_set_mode(cpu, spsr & CPSR_MODE_MASK);
            cpu->cpsr = spsr;
        }
    }

    if (load) {
        /* LDM costs nS+1N+1I. This code already counted the n register
           loads above, with one read32 call for each. Only +1I remains.
           Add a pipeline refill of +1S+1N (2 more fetches) if the PC was
           one of the loaded registers. */
        uint32_t extra = 1u;
        if (reg_list & 0x8000u) {
            extra += 2u * psemu_region_fetch_cycles(cpu->r[15], (cpu->cpsr & CPSR_T) != 0);
        }
        arm7tdmi_add_cycles(cpu, extra);
    }
    /* STM: (n-1)S+2N. The n writes are already counted. No extra cost applies. */
}

static void exec_branch(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    int link = (int)((instr >> 24) & 1u);
    int32_t offset = (int32_t)((instr & 0xFFFFFFu) << 8) >> 6; /* sign-extend 24-bit field, x4 */
    uint32_t target = (pc + 8u) + (uint32_t)offset;
    if (link) {
        cpu->r[14] = pc + 4u;
    }
    cpu->r[15] = target;
    /* B and BL cost 2S+1N. The first 1S is the opcode fetch, which this
       code already counted. The target is always in the ARM state,
       because a simple branch does not change the instruction set. */
    arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(target, 0));
}

void arm_execute(arm7tdmi_t *cpu, uint32_t instr, uint32_t pc) {
    uint32_t cond = instr >> 28;
    if (!arm_condition_passed(cpu, cond)) {
        return;
    }

    if ((instr & 0x0FFFFFF0u) == 0x012FFF10u) {
        exec_bx(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0FC000F0u) == 0x00000090u) {
        exec_multiply(cpu, instr);
        return;
    }
    if ((instr & 0x0F8000F0u) == 0x00800090u) {
        exec_long_multiply(cpu, instr);
        return;
    }
    if ((instr & 0x0FB000F0u) == 0x01000090u) {
        exec_swap(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0E000090u) == 0x00000090u && ((instr >> 5) & 0x3u) != 0) {
        exec_halfword_transfer(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0FBF0FFFu) == 0x010F0000u) {
        exec_mrs(cpu, instr);
        return;
    }
    if ((instr & 0x0DB0F000u) == 0x0120F000u) {
        exec_msr(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0C000000u) == 0x00000000u) {
        exec_data_processing(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0C000000u) == 0x04000000u) {
        exec_single_transfer(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0E000000u) == 0x08000000u) {
        exec_block_transfer(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0E000000u) == 0x0A000000u) {
        exec_branch(cpu, instr, pc);
        return;
    }
    if ((instr & 0x0F000000u) == 0x0F000000u) {
        arm_enter_exception(cpu, ARM_MODE_SVC, SWI_VECTOR, pc + 4u);
        /* Exception entry pipeline refill: same shape as B/BL above. */
        arm7tdmi_add_cycles(cpu, 2u * psemu_region_fetch_cycles(SWI_VECTOR, 0));
        return;
    }
    cpu->unimplemented = 1;
}
