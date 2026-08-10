// SPDX-FileCopyrightText: Copyright 2026 Eden Symbiosis Project
// SPDX-License-Identifier: GPL-3.0-or-later

// Retro presentation filter.
//
// Reproduces the *look* of older hardware by degrading the final image the way
// those machines actually did, rather than by applying a stylised overlay:
//
//   - Pixelation: the frame is resolved at a lower effective resolution, the
//     way a 256x224 console output was stretched to the screen.
//   - Colour depth: each channel is quantised to a fixed number of levels,
//     which is what "8-bit" and "16-bit" really meant.
//   - Palette clamping: optionally snap to a small total colour count.
//   - Dithering: an ordered Bayer matrix, applied *before* quantisation, which
//     is how period hardware faked missing shades.
//   - Scanlines and LCD grid: the display, not the console.
//
// Every parameter arrives through a push constant so a single pipeline serves
// every preset; there is no shader permutation explosion.

#version 460 core

layout(location = 0) in vec2 tex_coord;
layout(location = 0) out vec4 frag_color;

layout(binding = 0) uniform sampler2D color_texture;

// Parameters arrive as specialization constants: the presentation pipeline is
// already rebuilt whenever the filter changes, and push constants here are
// both full (128 bytes of vertex data) and vertex-stage only.
layout(constant_id = 0) const float VIRTUAL_WIDTH   = 0.0;   // 0 = no pixelation
layout(constant_id = 1) const float VIRTUAL_HEIGHT  = 0.0;
layout(constant_id = 2) const float COLOR_LEVELS    = 0.0;   // per channel, 0 = untouched
layout(constant_id = 3) const float DITHER_STRENGTH = 0.0;   // 0..1
layout(constant_id = 4) const float SCANLINE        = 0.0;   // 0..1
layout(constant_id = 5) const float LCD_GRID        = 0.0;   // 0..1
layout(constant_id = 6) const float SATURATION      = 1.0;
layout(constant_id = 7) const float CONTRAST        = 1.0;

// 4x4 ordered Bayer matrix, normalised to -0.5..0.5.
float BayerDither(ivec2 pixel) {
    const float bayer[16] = float[16](
         0.0,  8.0,  2.0, 10.0,
        12.0,  4.0, 14.0,  6.0,
         3.0, 11.0,  1.0,  9.0,
        15.0,  7.0, 13.0,  5.0
    );
    const int index = (pixel.y & 3) * 4 + (pixel.x & 3);
    return bayer[index] / 16.0 - 0.5;
}

void main() {
    const vec2 source_size = vec2(textureSize(color_texture, 0));
    vec2 coord = tex_coord;

    // --- 1. Pixelation ---------------------------------------------------
    // Snap the sample position to a coarse grid. Sampling at the centre of
    // each virtual texel avoids the shimmering that plain truncation causes.
    const vec2 virtual_res = vec2(VIRTUAL_WIDTH, VIRTUAL_HEIGHT);
    if (virtual_res.x > 0.0 && virtual_res.y > 0.0) {
        const vec2 blocks = max(virtual_res, vec2(1.0));
        coord = (floor(coord * blocks) + 0.5) / blocks;
    }

    vec4 colour = texture(color_texture, coord);

    // --- 2. Dither + colour quantisation ---------------------------------
    const float levels = COLOR_LEVELS;
    if (levels >= 2.0) {
        const float dither_strength = clamp(DITHER_STRENGTH, 0.0, 1.0);
        if (dither_strength > 0.0) {
            // Dither amplitude is one quantisation step: any more and the
            // image turns to noise, any less and banding survives.
            const float step_size = 1.0 / (levels - 1.0);
            const ivec2 pixel = ivec2(gl_FragCoord.xy);
            colour.rgb += BayerDither(pixel) * step_size * dither_strength;
        }
        colour.rgb = clamp(colour.rgb, 0.0, 1.0);
        colour.rgb = floor(colour.rgb * (levels - 1.0) + 0.5) / (levels - 1.0);
    }

    // --- 3. Saturation and contrast --------------------------------------
    // Handheld LCDs of the era were washed out; CRTs were the opposite. One
    // multiplier covers both directions.
    const float saturation = SATURATION;
    if (abs(saturation - 1.0) > 0.001) {
        const float grey = dot(colour.rgb, vec3(0.299, 0.587, 0.114));
        colour.rgb = clamp(mix(vec3(grey), colour.rgb, saturation), 0.0, 1.0);
    }

    const float contrast = CONTRAST;
    if (abs(contrast - 1.0) > 0.001) {
        colour.rgb = clamp(pow(colour.rgb, vec3(1.0 / max(contrast, 0.01))), 0.0, 1.0);
    }

    // --- 4. Display artefacts --------------------------------------------
    // Scanlines are tied to the *virtual* vertical resolution so they line up
    // with the emulated raster instead of the physical panel.
    const float scanline = clamp(SCANLINE, 0.0, 1.0);
    if (scanline > 0.0) {
        const float rows = virtual_res.y > 0.0 ? virtual_res.y : source_size.y;
        const float wave = sin(tex_coord.y * rows * 3.14159265);
        colour.rgb *= 1.0 - scanline * 0.5 * (1.0 - abs(wave));
    }

    // LCD grid: a faint dark border around every virtual pixel, which is what
    // made handheld screens look "gridded".
    const float grid = clamp(LCD_GRID, 0.0, 1.0);
    if (grid > 0.0 && virtual_res.x > 0.0 && virtual_res.y > 0.0) {
        const vec2 cell = fract(tex_coord * virtual_res);
        const vec2 edge = min(cell, 1.0 - cell);
        const float border = smoothstep(0.0, 0.12, min(edge.x, edge.y));
        colour.rgb *= mix(1.0 - grid * 0.35, 1.0, border);
    }

    frag_color = vec4(colour.rgb, 1.0);
}
