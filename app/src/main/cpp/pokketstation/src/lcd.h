/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#ifndef PSEMU_LCD_H
#define PSEMU_LCD_H

#include <stdint.h>

#define LCD_VRAM_SIZE 128u
/* LCD_MODE (+0x0, read and write) and LCD_CAL (+0x4).
   Bit 6 of LCD_MODE is DISON, which sets the display on or off.
   Bit 7 of LCD_MODE is ROT, which rotates the display 180 degrees. Real hardware sets ROT for docked
   mode, to agree with the docking flag in INT_INPUT bit 11. Both bits are real hardware bits.

   History: this address range had no bus handler before. Writes to the range were discarded and gave
   no error, and psemu_get_framebuffer always returned the raw VRAM. */
#define LCD_MODE_REG_SPAN 0x8u

#define LCD_MODE_DISON 0x40u
#define LCD_MODE_ROT 0x80u

/* VRAM is the packed 1bpp framebuffer: 32 rows of 4 bytes each.
   Bit 0 is the leftmost pixel. 0 is white, and 1 is black.

   `presented` uses the same format, after this emulator applies the DISON and ROT bits of LCD_MODE.
   psemu_get_framebuffer returns `presented`. It does not return the raw VRAM.
   This emulator calculates `presented` again at each VRAM write and each LCD_MODE write.

   The default value of `mode` has DISON set and ROT clear.
   The real power-on-reset value has no documentation. This default is not that value.
   This default agrees with each validated trace of a real BIOS. Those traces always showed the VRAM
   on the screen. This default keeps that behavior for each app that does not write to LCD_MODE. */
typedef struct lcd {
    uint8_t vram[LCD_VRAM_SIZE];
    uint8_t presented[LCD_VRAM_SIZE];
    uint32_t mode;
    uint32_t cal;
    int dirty;
} lcd_t;

void lcd_init(lcd_t *lcd);
uint8_t lcd_read8(lcd_t *lcd, uint32_t offset);
void lcd_write8(lcd_t *lcd, uint32_t offset, uint8_t value);
uint8_t lcd_mode_read8(lcd_t *lcd, uint32_t offset);
void lcd_mode_write8(lcd_t *lcd, uint32_t offset, uint8_t value);

#endif
