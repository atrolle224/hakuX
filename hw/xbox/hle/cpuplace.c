/*
 * hakuX HLE — the emulation's busy threads on the fast cores, and Android
 * told how long their frames take (cpuplace.h).
 *
 * On a big.LITTLE phone or handheld (the RG477M's Dimensity 8300: four
 * Cortex-A510 at 2.2 GHz, four A715 at 3.2-3.35 GHz) the guest CPU, the
 * push-buffer decoder and the render thread each keep a core busy, but the
 * scheduler moves a thread to a little core whenever it naps (a vblank wait,
 * a GPU wait) and its clock then ramps up again from low: a frame that lands
 * there runs at half speed and arrives late. Two measures:
 * - Affinity: the three threads stay on the cores whose top clock is within
 *   3/4 of the fastest's (the A715s). Nothing else is pinned: the shader
 *   and texture workers, audio and the app use every core as before. Not on
 *   a SoC of one kind of core, or of three or fewer fast ones (the guest
 *   threads would crowd the phone's own UI threads off them).
 * - ADPF (Android 13+): a performance hint session over the three threads,
 *   its target the frame interval and each frame's work reported (the
 *   busiest thread's CPU time in it), so the governor raises their clocks
 *   before a frame runs late instead of after. The system's thermal limits
 *   still apply: the hint only asks for more speed within them.
 * HAKUX_BIGCORES=0 (the app's setting) turns both off.
 */
#ifndef _GNU_SOURCE
#define _GNU_SOURCE     /* sched_setaffinity, cpu_set_t (bionic: __USE_GNU) */
#endif
#include "hw/xbox/hle/base.h"
#include "hw/xbox/hle/cpuplace.h"
#include <pthread.h>
#include <stdatomic.h>
#include <stdbool.h>
#include <stdint.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <time.h>
#include <unistd.h>
#include "hw/xbox/hle/hle.h"

#ifdef __ANDROID__
#include <dlfcn.h>
#include <sched.h>
#include <sys/syscall.h>
#endif

#define TAG "hakuX-hle-cpu"
#define MAX_CPUS 32

static struct {
    pthread_once_t once;
    bool on;                        /* placement wanted and the SoC has fast cores */
#ifdef __ANDROID__
    cpu_set_t big;
#endif
    int nbig;
    _Atomic int tid[HLE_CPU_N];     /* the threads' ids, 0 until registered */
    clockid_t clk[HLE_CPU_N];
    _Atomic bool clk_ok[HLE_CPU_N];
    /* ADPF (decode thread only, at each frame) */
    void *session;
    bool adpf_tried;
    int64_t last_wall, last_cpu[HLE_CPU_N], target_ns, ema_ns;
    unsigned frames;
} g_cp = { .once = PTHREAD_ONCE_INIT };

#ifdef __ANDROID__
typedef void *(*GetManagerFn)(void);
typedef void *(*CreateSessionFn)(void *, const int32_t *, size_t, int64_t);
typedef int (*ReportFn)(void *, int64_t);
typedef int (*UpdateTargetFn)(void *, int64_t);
static ReportFn p_report;
static UpdateTargetFn p_target;

static long cpu_max_khz(int cpu)
{
    char path[96];
    snprintf(path, sizeof(path), "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    FILE *f = fopen(path, "r");
    if (!f) {
        return 0;
    }
    long v = 0;
    if (fscanf(f, "%ld", &v) != 1) {
        v = 0;
    }
    fclose(f);
    return v;
}
#endif

static void init(void)
{
    const char *e = getenv("HAKUX_BIGCORES");
    if (e && atoi(e) == 0) {
        hle_log(TAG, "fast-core placement off (setting)");
        return;
    }
#ifdef __ANDROID__
    long khz[MAX_CPUS], top = 0;
    int n = (int)sysconf(_SC_NPROCESSORS_CONF);
    if (n > MAX_CPUS) {
        n = MAX_CPUS;
    }
    for (int i = 0; i < n; i++) {
        khz[i] = cpu_max_khz(i);
        if (khz[i] > top) {
            top = khz[i];
        }
    }
    if (!top) {
        return;
    }
    CPU_ZERO(&g_cp.big);
    int slow = 0;
    for (int i = 0; i < n; i++) {
        if (khz[i] && khz[i] * 4 >= top * 3) {
            CPU_SET(i, &g_cp.big);
            g_cp.nbig++;
        } else {
            slow++;
        }
    }
    g_cp.on = slow > 0 && g_cp.nbig >= 4;
    hle_log(TAG, "%d of %d cores fast (top %ld MHz): %s", g_cp.nbig, n, top / 1000,
            g_cp.on ? "the guest CPU, decoder and render threads kept on them"
                    : "no placement (one kind of core, or too few fast ones)");
#endif
}

static __thread bool t_placed;

void hle_cpu_thread(int slot)
{
    if (t_placed || slot < 0 || slot >= HLE_CPU_N) {
        return;
    }
    t_placed = true;
    pthread_once(&g_cp.once, init);
    g_cp.clk_ok[slot] = pthread_getcpuclockid(pthread_self(), &g_cp.clk[slot]) == 0;
#ifdef __ANDROID__
    const int tid = (int)syscall(SYS_gettid);
    if (g_cp.on && sched_setaffinity(0, sizeof(g_cp.big), &g_cp.big) != 0) {
        hle_log(TAG, "thread %d: affinity refused", tid);
    }
    atomic_store(&g_cp.tid[slot], tid);
#else
    atomic_store(&g_cp.tid[slot], 1);
#endif
}

static int64_t now_ns(clockid_t c)
{
    struct timespec ts;
    clock_gettime(c, &ts);
    return (int64_t)ts.tv_sec * 1000000000 + ts.tv_nsec;
}

#ifdef __ANDROID__
static void adpf_start(int64_t target)
{
    g_cp.adpf_tried = true;
    void *lib = dlopen("libandroid.so", RTLD_NOW | RTLD_LOCAL);
    GetManagerFn get = lib ? (GetManagerFn)dlsym(lib, "APerformanceHint_getManager") : NULL;
    CreateSessionFn create = lib ? (CreateSessionFn)dlsym(lib, "APerformanceHint_createSession") : NULL;
    p_report = lib ? (ReportFn)dlsym(lib, "APerformanceHint_reportActualWorkDuration") : NULL;
    p_target = lib ? (UpdateTargetFn)dlsym(lib, "APerformanceHint_updateTargetWorkDuration") : NULL;
    if (!get || !create || !p_report || !p_target) {
        hle_log(TAG, "no performance hints (Android 13 or later has them)");
        return;
    }
    void *mgr = get();
    int32_t tids[HLE_CPU_N];
    size_t nt = 0;
    for (int i = 0; i < HLE_CPU_N; i++) {
        int t = atomic_load(&g_cp.tid[i]);
        if (t > 0) {
            tids[nt++] = t;
        }
    }
    g_cp.session = mgr && nt ? create(mgr, tids, nt, target) : NULL;
    g_cp.target_ns = target;
    hle_log(TAG, "performance hint session %s (%zu threads, target %.1f ms)",
            g_cp.session ? "on" : "refused", nt, target / 1e6);
}
#endif

void hle_cpu_frame(void)
{
    if (!g_cp.on) {
        return;
    }
    const int64_t wall = now_ns(CLOCK_MONOTONIC);
    int64_t busiest = 0;
    for (int i = 0; i < HLE_CPU_N; i++) {
        if (!atomic_load(&g_cp.clk_ok[i])) {
            continue;
        }
        const int64_t c = now_ns(g_cp.clk[i]);
        if (g_cp.last_cpu[i] && c - g_cp.last_cpu[i] > busiest) {
            busiest = c - g_cp.last_cpu[i];
        }
        g_cp.last_cpu[i] = c;
    }
    const int64_t interval = g_cp.last_wall ? wall - g_cp.last_wall : 0;
    g_cp.last_wall = wall;
    if (interval <= 0 || interval > 500000000) {
        return;                     /* the first frame, or after a pause or a load */
    }
    /* the frame interval the title keeps, in 60 Hz vblanks (16.7, 33.3, 50 ms):
     * from a slow average, rounded down, so frames running late don't
     * lengthen the target (and lower the clocks) as they do */
    g_cp.ema_ns = g_cp.ema_ns ? g_cp.ema_ns + (interval - g_cp.ema_ns) / 32 : interval;
    int vbl = (int)((g_cp.ema_ns + 2000000) / 16666667);
    if (vbl < 1) {
        vbl = 1;
    }
    if (vbl > 3) {
        vbl = 3;
    }
    const int64_t target = (int64_t)vbl * 16666667;
#ifdef __ANDROID__
    if (++g_cp.frames < 30) {
        return;                     /* the threads all started and the cadence settled */
    }
    if (!g_cp.adpf_tried) {
        adpf_start(target);
    }
    if (!g_cp.session) {
        return;
    }
    if (target != g_cp.target_ns && g_cp.frames % 30 == 0) {
        p_target(g_cp.session, target);
        g_cp.target_ns = target;
    }
    /* the frame's work: the busiest thread's CPU time, the frame's wall time at most */
    int64_t work = busiest < interval ? busiest : interval;
    if (work > 0) {
        p_report(g_cp.session, work);
    }
#else
    (void)target;
    (void)busiest;
#endif
}
