// Thread affinity helper.
//
// Android exposes no Java API for sched_setaffinity, so pinning the hot
// streaming threads to a device's fast cores needs a little native code.
//
// Deliberately plain C. The version this idea came from pulls in <fstream>,
// <vector> and <sstream>, which would force APP_STL onto the whole native
// build including the streaming core, for the sake of reading a handful of
// small integers out of sysfs. fopen and fscanf do the same job here without
// changing how anything else is packaged.
//
// Every failure path is a no-op. A device that will not report its topology,
// or refuses the call, keeps whatever scheduling it already had.

// cpu_set_t, CPU_SET and CPU_ISSET are GNU extensions. Bionic hides them
// behind __USE_GNU, which sys/cdefs.h only sets when _GNU_SOURCE is defined,
// so this must come before any include that reaches sched.h.
#define _GNU_SOURCE 1

#include <jni.h>
#include <sched.h>
#include <stdio.h>
#include <string.h>

// Bit per CPU in a jint, so the mask crosses to Java as a plain number.
#define MAX_CPUS 32

// A core counts as "fast" at or above this fraction of the highest reported
// maximum frequency. Loose enough to take a whole big cluster whose members
// differ slightly, tight enough to leave the little cluster out.
#define FAST_CORE_NUMERATOR   80
#define FAST_CORE_DENOMINATOR 100

static long read_long_file(const char *path) {
    FILE *f = fopen(path, "r");
    if (f == NULL) {
        return -1;
    }
    long value = -1;
    if (fscanf(f, "%ld", &value) != 1) {
        value = -1;
    }
    fclose(f);
    return value;
}

/**
 * Maximum frequency of one CPU, in kHz, or -1.
 *
 * cpuinfo_max_freq is preferred over scaling_max_freq because the scaling
 * value moves with the governor and with thermal caps: reading it while the
 * device is warm can make a big core look like a little one and invert the
 * whole decision.
 */
static long cpu_max_freq(int cpu) {
    char path[128];

    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/cpuinfo_max_freq", cpu);
    long freq = read_long_file(path);
    if (freq > 0) {
        return freq;
    }

    snprintf(path, sizeof(path),
             "/sys/devices/system/cpu/cpu%d/cpufreq/scaling_max_freq", cpu);
    return read_long_file(path);
}

JNIEXPORT jint JNICALL
Java_com_limelight_utils_CpuAffinity_nativeFastCoreMask(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    long freqs[MAX_CPUS];
    long peak = 0;
    int known = 0;

    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
        return 0;
    }

    for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
        // Only consider cores this process is actually allowed to run on. A
        // cpuset restriction is not ours to argue with.
        freqs[cpu] = CPU_ISSET(cpu, &allowed) ? cpu_max_freq(cpu) : -1;
        if (freqs[cpu] > 0) {
            known++;
            if (freqs[cpu] > peak) {
                peak = freqs[cpu];
            }
        }
    }

    if (peak <= 0 || known < 2) {
        // One core, or a kernel that will not say. Nothing useful to pin to.
        return 0;
    }

    long threshold = peak * FAST_CORE_NUMERATOR / FAST_CORE_DENOMINATOR;

    jint mask = 0;
    int fast = 0;
    for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
        if (freqs[cpu] >= threshold) {
            mask |= (jint) (1 << cpu);
            fast++;
        }
    }

    if (fast == known) {
        // Every core is equally fast, so this is not a big.LITTLE device and
        // pinning would only remove the scheduler's freedom for no gain.
        return 0;
    }

    return mask;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_utils_CpuAffinity_nativePinCurrentThread(JNIEnv *env, jclass clazz, jint mask) {
    (void) env;
    (void) clazz;

    if (mask == 0) {
        return JNI_FALSE;
    }

    cpu_set_t allowed;
    CPU_ZERO(&allowed);
    if (sched_getaffinity(0, sizeof(allowed), &allowed) != 0) {
        return JNI_FALSE;
    }

    // Intersect rather than assign. Asking for a core the process is not
    // permitted to use fails the whole call, and on a device where that
    // happens the thread would keep running wherever it already was with no
    // indication why.
    cpu_set_t target;
    CPU_ZERO(&target);
    int chosen = 0;
    for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
        if ((mask & (jint) (1 << cpu)) != 0 && CPU_ISSET(cpu, &allowed)) {
            CPU_SET(cpu, &target);
            chosen++;
        }
    }

    if (chosen == 0) {
        return JNI_FALSE;
    }

    // pid 0 means the calling thread, which is the one asking to be pinned.
    return sched_setaffinity(0, sizeof(target), &target) == 0 ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jboolean JNICALL
Java_com_limelight_utils_CpuAffinity_nativeResetCurrentThread(JNIEnv *env, jclass clazz) {
    (void) env;
    (void) clazz;

    // Hand every online CPU back. Used when the preference is turned off
    // mid-session so a pinned thread is not left narrowed for the rest of it.
    cpu_set_t all;
    CPU_ZERO(&all);
    for (int cpu = 0; cpu < MAX_CPUS; cpu++) {
        CPU_SET(cpu, &all);
    }
    return sched_setaffinity(0, sizeof(all), &all) == 0 ? JNI_TRUE : JNI_FALSE;
}
