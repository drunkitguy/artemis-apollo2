package com.limelight.utils;

import com.limelight.LimeLog;

/**
 * Pins the hot streaming threads to a device's fast cores.
 *
 * On a big.LITTLE handheld the scheduler is free to park the decoder or the
 * renderer on a little core, and the frame that lands there arrives late. The
 * cost is that fast cores draw more power, so a long session runs warmer and
 * may throttle. Which of those matters more depends on the device and the
 * session, which is why this is a preference rather than a default.
 *
 * Everything here degrades to doing nothing. A device without the native
 * library, without a readable topology, or that refuses the call keeps the
 * scheduling it already had.
 */
public final class CpuAffinity {

    private static final boolean AVAILABLE;
    /** Computed once: reading sysfs per thread would be wasteful and identical. */
    private static final int FAST_CORE_MASK;

    static {
        boolean loaded = false;
        try {
            System.loadLibrary("cpuaffinity");
            loaded = true;
        } catch (Throwable e) {
            LimeLog.warning("CPU affinity unavailable: " + e);
        }
        AVAILABLE = loaded;

        int mask = 0;
        if (loaded) {
            try {
                mask = nativeFastCoreMask();
            } catch (Throwable e) {
                LimeLog.warning("Could not read CPU topology: " + e);
            }
        }
        FAST_CORE_MASK = mask;

        if (loaded) {
            LimeLog.info("CPU affinity: fast core mask 0x" + Integer.toHexString(mask));
        }
    }

    private CpuAffinity() {
    }

    /** True when this device reports distinct fast cores worth pinning to. */
    public static boolean isSupported() {
        return AVAILABLE && FAST_CORE_MASK != 0;
    }

    /** How many cores the mask covers, for the diagnostics screen. */
    public static int fastCoreCount() {
        return Integer.bitCount(FAST_CORE_MASK);
    }

    public static int fastCoreMask() {
        return FAST_CORE_MASK;
    }

    /**
     * Pins the calling thread to the fast cores.
     *
     * Must be called from the thread being pinned, since that is what the
     * native side acts on.
     *
     * @param what name for the log line, so a device specific report says
     *             which thread did or did not take
     * @return true when the thread was actually moved
     */
    public static boolean pinCurrentThread(String what) {
        if (!isSupported()) {
            return false;
        }
        try {
            boolean pinned = nativePinCurrentThread(FAST_CORE_MASK);
            LimeLog.info("CPU affinity: " + what + (pinned ? " pinned to fast cores" : " could not be pinned"));
            return pinned;
        } catch (Throwable e) {
            LimeLog.warning("CPU affinity: " + what + " failed: " + e);
            return false;
        }
    }

    /** Gives the calling thread every core back. */
    public static boolean unpinCurrentThread() {
        if (!AVAILABLE) {
            return false;
        }
        try {
            return nativeResetCurrentThread();
        } catch (Throwable e) {
            return false;
        }
    }

    private static native int nativeFastCoreMask();

    private static native boolean nativePinCurrentThread(int mask);

    private static native boolean nativeResetCurrentThread();
}
