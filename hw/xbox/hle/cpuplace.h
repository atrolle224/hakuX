/*
 * hakuX HLE — the guest CPU, push-buffer decoder and render threads kept on
 * the fast cores of a big.LITTLE SoC, and an Android performance hint
 * session over them (cpuplace.c).
 */
#ifndef HW_XBOX_HLE_CPUPLACE_H
#define HW_XBOX_HLE_CPUPLACE_H

enum { HLE_CPU_VCPU, HLE_CPU_RENDER, HLE_CPU_DECODE, HLE_CPU_N };

/* The calling thread is slot's (once per thread; later calls return at once). */
void hle_cpu_thread(int slot);
/* The decode thread, at each guest frame: the frame's work reported. */
void hle_cpu_frame(void);

#endif
