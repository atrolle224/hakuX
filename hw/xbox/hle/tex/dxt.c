/*
 * hakuX HLE GPU — DXT1/3/5 decoding on the CPU (see dxt.h).
 *
 * The per-texel decoder this replaced (pb/pbres.c's dxt_block, kept there
 * for volumes) rebuilt the colour and alpha palettes per texel's block and
 * bounds-checked every texel; on a GPU without BC every DXT texture a title
 * uploads went through it on the decoder thread, one core. Here a block's
 * palette is built once with its alpha folded in, whole blocks are written
 * row by row, and a large level's block rows are shared with a few worker
 * threads. The texels are the same, byte for byte (tests/hle).
 */
#include "hw/xbox/hle/tex/dxt.h"

#include <pthread.h>
#include <sched.h>
#include <stdatomic.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

/* ---- palettes (the reference decoder's arithmetic, exactly) ---------------- */

static inline uint32_t rgb565(uint32_t c)
{
    uint32_t r = (c >> 11) & 31, g = (c >> 5) & 63, b = c & 31;
    return ((r << 3 | r >> 2) << 16) | ((g << 2 | g >> 4) << 8) | (b << 3 | b >> 2);
}

static inline uint32_t mix(uint32_t a, uint32_t b, unsigned wa, unsigned wb, unsigned div)
{
    return (((a >> 16 & 0xFF) * wa + (b >> 16 & 0xFF) * wb) / div) << 16 |
           (((a >> 8 & 0xFF) * wa + (b >> 8 & 0xFF) * wb) / div) << 8 |
           (((a & 0xFF) * wa + (b & 0xFF) * wb) / div);
}

/* The colour half of a block (cb: its 8 bytes): the four colours, alpha 0;
 * returns whether it is in four-colour mode. DXT3/5's colour half always is. */
static inline bool colour_pal(const uint8_t *cb, bool dxt1, uint32_t pal[4])
{
    uint32_t c0 = cb[0] | cb[1] << 8, c1 = cb[2] | cb[3] << 8;
    bool four = !dxt1 || c0 > c1;
    pal[0] = rgb565(c0);
    pal[1] = rgb565(c1);
    pal[2] = four ? mix(pal[0], pal[1], 2, 1, 3) : mix(pal[0], pal[1], 1, 1, 2);
    pal[3] = four ? mix(pal[0], pal[1], 1, 2, 3) : 0;
    return four;
}

static inline uint32_t le32(const uint8_t *p)
{
    return p[0] | p[1] << 8 | p[2] << 16 | (uint32_t)p[3] << 24;
}

/* ---- one block -------------------------------------------------------------- */

/* Block s to the bw x bh texels at o (rows stride texels apart). */
static inline void block_argb(HleDxtKind kind, const uint8_t *s, uint32_t *o, uint32_t stride,
                              uint32_t bw, uint32_t bh)
{
    uint32_t pal[4];
    if (kind == HLE_DXT1) {
        bool four = colour_pal(s, true, pal);
        pal[0] |= 0xFF000000u;
        pal[1] |= 0xFF000000u;
        pal[2] |= 0xFF000000u;
        if (four) {
            pal[3] |= 0xFF000000u;      /* else 0: transparent black */
        }
        uint32_t idx = le32(s + 4);
        for (uint32_t y = 0; y < bh; y++, o += stride) {
            uint32_t row = idx >> (8 * y);
            for (uint32_t x = 0; x < bw; x++) {
                o[x] = pal[(row >> (2 * x)) & 3];
            }
        }
        return;
    }
    colour_pal(s + 8, false, pal);
    uint32_t idx = le32(s + 12);
    if (kind == HLE_DXT3) {
        for (uint32_t y = 0; y < bh; y++, o += stride) {
            uint32_t row = idx >> (8 * y);
            uint32_t al = s[2 * y] | s[2 * y + 1] << 8;     /* four 4-bit alphas */
            for (uint32_t x = 0; x < bw; x++) {
                uint32_t v = (al >> (4 * x)) & 15;
                o[x] = pal[(row >> (2 * x)) & 3] | (v << 4 | v) << 24;
            }
        }
        return;
    }
    uint32_t a0 = s[0], a1 = s[1], ap[8] = { a0, a1 };
    for (unsigned i = 2; i < 8; i++) {
        ap[i] = a0 > a1 ? ((8 - i) * a0 + (i - 1) * a1) / 7 :
                i < 6 ? ((6 - i) * a0 + (i - 1) * a1) / 5 : i == 6 ? 0 : 255;
    }
    uint64_t bits = 0;
    for (unsigned i = 0; i < 6; i++) {
        bits |= (uint64_t)s[2 + i] << (8 * i);
    }
    for (uint32_t y = 0; y < bh; y++, o += stride) {
        uint32_t row = idx >> (8 * y);
        uint32_t arow = (uint32_t)(bits >> (12 * y));
        for (uint32_t x = 0; x < bw; x++) {
            o[x] = pal[(row >> (2 * x)) & 3] | ap[(arow >> (3 * x)) & 7] << 24;
        }
    }
}

/* 8-bit channel to n bits, rounded to nearest */
static inline uint32_t to_bits(uint32_t v, uint32_t max)
{
    return (v * max + 127) / 255;
}

static inline void block_565(const uint8_t *s, uint16_t *o, uint32_t stride, uint32_t bw,
                             uint32_t bh)
{
    uint32_t pal[4];
    colour_pal(s, true, pal);
    uint16_t p16[4];
    for (unsigned i = 0; i < 4; i++) {
        p16[i] = (uint16_t)(to_bits(pal[i] >> 16 & 0xFF, 31) << 11 |
                            to_bits(pal[i] >> 8 & 0xFF, 63) << 5 | to_bits(pal[i] & 0xFF, 31));
    }
    uint32_t idx = le32(s + 4);
    for (uint32_t y = 0; y < bh; y++, o += stride) {
        uint32_t row = idx >> (8 * y);
        for (uint32_t x = 0; x < bw; x++) {
            o[x] = p16[(row >> (2 * x)) & 3];
        }
    }
}

/* ---- block rows, and the workers that share them ---------------------------- */

typedef struct DxtJob {
    HleDxtKind kind;
    bool to565;
    const uint8_t *src;
    void *dst;
    uint32_t w, h, bcols, brows, bb;
    atomic_uint next;                   /* the next block row to take */
    atomic_uint done;                   /* block rows finished */
} DxtJob;

static void rows(DxtJob *j)
{
    for (;;) {
        uint32_t by = atomic_fetch_add_explicit(&j->next, 1, memory_order_relaxed);
        if (by >= j->brows) {
            return;
        }
        const uint8_t *s = j->src + (size_t)by * j->bcols * j->bb;
        uint32_t bh = j->h - by * 4 < 4 ? j->h - by * 4 : 4;
        for (uint32_t bx = 0; bx < j->bcols; bx++, s += j->bb) {
            uint32_t bw = j->w - bx * 4 < 4 ? j->w - bx * 4 : 4;
            size_t at = (size_t)by * 4 * j->w + bx * 4;
            if (j->to565) {
                block_565(s, (uint16_t *)j->dst + at, j->w, bw, bh);
            } else {
                block_argb(j->kind, s, (uint32_t *)j->dst + at, j->w, bw, bh);
            }
        }
        atomic_fetch_add_explicit(&j->done, 1, memory_order_release);
    }
}

/* A level this many blocks or larger (256 x 256) is shared out: below it the
 * hand-off costs about what a worker saves. */
#define DXT_SHARE_BLOCKS 4096u
#define DXT_MAX_WORKERS 3

static struct {
    pthread_once_t once;
    pthread_mutex_t lock;               /* one shared job at a time */
    pthread_mutex_t m;
    pthread_cond_t go, left;
    DxtJob *job;
    unsigned gen, nworkers, inside;     /* inside: workers holding job */
} g_dxt = { .once = PTHREAD_ONCE_INIT, .lock = PTHREAD_MUTEX_INITIALIZER,
            .m = PTHREAD_MUTEX_INITIALIZER, .go = PTHREAD_COND_INITIALIZER,
            .left = PTHREAD_COND_INITIALIZER };

static void *worker(void *arg)
{
    (void)arg;
    unsigned seen = 0;
    for (;;) {
        pthread_mutex_lock(&g_dxt.m);
        while (g_dxt.gen == seen) {
            pthread_cond_wait(&g_dxt.go, &g_dxt.m);
        }
        seen = g_dxt.gen;
        DxtJob *j = g_dxt.job;
        if (!j) {
            pthread_mutex_unlock(&g_dxt.m);
            continue;
        }
        g_dxt.inside++;
        pthread_mutex_unlock(&g_dxt.m);
        rows(j);
        pthread_mutex_lock(&g_dxt.m);
        if (--g_dxt.inside == 0) {
            pthread_cond_signal(&g_dxt.left);
        }
        pthread_mutex_unlock(&g_dxt.m);
    }
    return NULL;
}

static void start_workers(void)
{
    long cpus = sysconf(_SC_NPROCESSORS_ONLN);
    /* leave the guest CPU's thread and the GPU back end their cores */
    unsigned n = cpus > 4 ? (unsigned)(cpus - 4) : 0;
    if (n > DXT_MAX_WORKERS) {
        n = DXT_MAX_WORKERS;
    }
    /* HAKUX_DXT_WORKERS: another count (0: decode on the caller only), for A/B */
    const char *e = getenv("HAKUX_DXT_WORKERS");
    if (e && *e) {
        n = (unsigned)atoi(e) > 8 ? 8 : (unsigned)atoi(e);
    }
    for (unsigned i = 0; i < n; i++) {
        pthread_t t;
        pthread_attr_t at;
        pthread_attr_init(&at);
        pthread_attr_setdetachstate(&at, PTHREAD_CREATE_DETACHED);
        if (pthread_create(&t, &at, worker, NULL) == 0) {
            g_dxt.nworkers++;
        }
        pthread_attr_destroy(&at);
    }
}

static void run(DxtJob *j)
{
    atomic_init(&j->next, 0);
    atomic_init(&j->done, 0);
    if (j->bcols * j->brows >= DXT_SHARE_BLOCKS) {
        pthread_once(&g_dxt.once, start_workers);
        if (g_dxt.nworkers && pthread_mutex_trylock(&g_dxt.lock) == 0) {
            pthread_mutex_lock(&g_dxt.m);
            g_dxt.job = j;
            g_dxt.gen++;
            pthread_cond_broadcast(&g_dxt.go);
            pthread_mutex_unlock(&g_dxt.m);
            rows(j);
            /* j lives on the caller's stack: no worker may still hold it */
            pthread_mutex_lock(&g_dxt.m);
            g_dxt.job = NULL;
            while (g_dxt.inside) {
                pthread_cond_wait(&g_dxt.left, &g_dxt.m);
            }
            pthread_mutex_unlock(&g_dxt.m);
            pthread_mutex_unlock(&g_dxt.lock);
            return;
        }
    }
    rows(j);
}

/* ---- the interface ------------------------------------------------------------ */

void hle_dxt_decode_argb(HleDxtKind kind, const uint8_t *src, uint32_t w, uint32_t h,
                         uint32_t *dst)
{
    DxtJob j = { .kind = kind, .src = src, .dst = dst, .w = w, .h = h,
                 .bcols = (w + 3) / 4, .brows = (h + 3) / 4, .bb = kind == HLE_DXT1 ? 8 : 16 };
    run(&j);
}

void hle_dxt1_decode_565(const uint8_t *src, uint32_t w, uint32_t h, uint16_t *dst)
{
    DxtJob j = { .kind = HLE_DXT1, .to565 = true, .src = src, .dst = dst, .w = w, .h = h,
                 .bcols = (w + 3) / 4, .brows = (h + 3) / 4, .bb = 8 };
    run(&j);
}

bool hle_dxt1_opaque(const uint8_t *src, uint32_t nblocks)
{
    for (uint32_t i = 0; i < nblocks; i++, src += 8) {
        uint32_t c0 = src[0] | src[1] << 8, c1 = src[2] | src[3] << 8;
        if (c0 <= c1) {
            /* three-colour mode: index 3 is transparent; any texel with both
             * of its index bits set uses it */
            uint32_t idx = le32(src + 4);
            if (idx & (idx >> 1) & 0x55555555u) {
                return false;
            }
        }
    }
    return true;
}
