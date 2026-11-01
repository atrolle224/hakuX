/*
 * hakuX HLE GPU — DXT1/3/5 (BC1/2/3) decoding on the CPU, for GPUs without
 * the BC formats (Mali, PowerVR's B-Series): pb/pbres.c.
 *
 * Self-contained (no HLE headers), so tests/hle can check it against the
 * reference decoder it replaced, byte for byte.
 */
#ifndef HW_XBOX_HLE_TEX_DXT_H
#define HW_XBOX_HLE_TEX_DXT_H

#include <stdbool.h>
#include <stdint.h>

/* The block layouts: DXT1 8 bytes (punch-through alpha), DXT3 16 (explicit
 * 4-bit alpha), DXT5 16 (interpolated alpha). */
typedef enum HleDxtKind { HLE_DXT1 = 1, HLE_DXT3 = 3, HLE_DXT5 = 5 } HleDxtKind;

/* A w x h level's blocks (row by row) to w x h A8R8G8B8 texels (bytes B, G,
 * R, A), rows w texels apart. The same texels as the per-texel decoder it
 * replaced; large levels split their block rows across worker threads. */
void hle_dxt_decode_argb(HleDxtKind kind, const uint8_t *src, uint32_t w, uint32_t h,
                         uint32_t *dst);

/* Whether none of n DXT1 blocks uses its transparent texel (three-colour
 * mode, index 3), so the texture decodes the same without an alpha channel. */
bool hle_dxt1_opaque(const uint8_t *src, uint32_t nblocks);

/* An opaque DXT1 level (hle_dxt1_opaque) to w x h R5G6B5 texels, each colour
 * rounded to the nearest 5/6/5-bit value: half the size of A8R8G8B8, so half
 * the memory and the bandwidth the GPU samples it with. The two endpoint
 * colours come back exactly; the interpolated ones within half a step. */
void hle_dxt1_decode_565(const uint8_t *src, uint32_t w, uint32_t h, uint16_t *dst);

#endif
