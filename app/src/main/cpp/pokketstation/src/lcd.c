/* SPDX-FileCopyrightText: Copyright (c) 2026 Darien Liu (mentalfoundry)
   SPDX-License-Identifier: MIT */

#include "lcd.h"

#include <string.h>

static uint32_t lcd_reverse_bits32(uint32_t v) {
    uint32_t r = 0;
    int i;
    for (i = 0; i < 32; i++) {
        r |= ((v >> i) & 1u) << (31 - i);
    }
    return r;
}

static void lcd_recompute_presented(lcd_t *lcd) {
    uint32_t rows = LCD_VRAM_SIZE / 4u;
    uint32_t row;

    if (!(lcd->mode & LCD_MODE_DISON)) {
        memset(lcd->presented, 0, sizeof(lcd->presented));
        return;
    }
    if (!(lcd->mode & LCD_MODE_ROT)) {
        memcpy(lcd->presented, lcd->vram, sizeof(lcd->presented));
        return;
    }

    /* ROT: rotate the display 180 degrees.
       Reverse the order of the scanlines, and reverse the 32 pixels in each scanline. Thus bit 0
       exchanges with bit 31, and so on. */
    for (row = 0; row < rows; row++) {
        uint32_t src_word = (uint32_t)lcd->vram[row * 4u] | ((uint32_t)lcd->vram[row * 4u + 1u] << 8) |
                             ((uint32_t)lcd->vram[row * 4u + 2u] << 16) | ((uint32_t)lcd->vram[row * 4u + 3u] << 24);
        uint32_t reversed = lcd_reverse_bits32(src_word);
        uint32_t dst_row = (rows - 1u) - row;
        lcd->presented[dst_row * 4u] = (uint8_t)reversed;
        lcd->presented[dst_row * 4u + 1u] = (uint8_t)(reversed >> 8);
        lcd->presented[dst_row * 4u + 2u] = (uint8_t)(reversed >> 16);
        lcd->presented[dst_row * 4u + 3u] = (uint8_t)(reversed >> 24);
    }
}

void lcd_init(lcd_t *lcd) {
    memset(lcd->vram, 0, sizeof(lcd->vram));
    lcd->mode = LCD_MODE_DISON;
    lcd->cal = 0;
    lcd->dirty = 0;
    lcd_recompute_presented(lcd);
}

uint8_t lcd_read8(lcd_t *lcd, uint32_t offset) {
    return lcd->vram[offset % LCD_VRAM_SIZE];
}

void lcd_write8(lcd_t *lcd, uint32_t offset, uint8_t value) {
    lcd->vram[offset % LCD_VRAM_SIZE] = value;
    lcd_recompute_presented(lcd);
    lcd->dirty = 1;
}

uint8_t lcd_mode_read8(lcd_t *lcd, uint32_t offset) {
    uint32_t word_index = offset / 4u;
    uint32_t shift = (offset % 4u) * 8u;
    uint32_t reg = (word_index == 1u) ? lcd->cal : lcd->mode;
    return (uint8_t)(reg >> shift);
}

void lcd_mode_write8(lcd_t *lcd, uint32_t offset, uint8_t value) {
    uint32_t word_index = offset / 4u;
    uint32_t shift = (offset % 4u) * 8u;

    if (word_index == 1u) { /* LCD_CAL: a stored value with no known side effect */
        lcd->cal = (lcd->cal & ~(0xFFu << shift)) | ((uint32_t)value << shift);
        return;
    }

    lcd->mode = (lcd->mode & ~(0xFFu << shift)) | ((uint32_t)value << shift);
    lcd_recompute_presented(lcd);
    lcd->dirty = 1;
}
