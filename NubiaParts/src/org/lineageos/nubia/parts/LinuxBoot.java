/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.parts;

import android.content.Context;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;

/**
 * Restart into Debian (the nx789j-linux install in /data/nx-linux). That directory is
 * root-only, so this app asks init through properties (init.nubia.parts.rc): a root helper
 * reports whether Debian is installed, and init writes the one-boot request that the
 * KernelSU boot hook reads. All calls block for up to a few seconds: worker thread only.
 */
final class LinuxBoot {
    static final String READY = "ready";
    static final String NOHOOK = "nohook";
    static final String MISSING = "missing";

    private static final String PROP_STATUS = "sys.nubia.linux_status";
    private static final String PROP_MODE = "sys.nubia.linux_mode";
    private static final String PROP_BOOT = "sys.nubia.linux_boot";
    private static final String REQUEST = "request";
    private static final long TIMEOUT_MS = 5000;

    /** Result of {@link #check}: status is null when the helper did not answer. */
    static final class State {
        final String status;
        final String mode;

        State(String status, String mode) {
            this.status = status;
            this.mode = mode;
        }

        boolean ready() {
            return READY.equals(status);
        }

        /** Debian starts on every boot already (bootmode "linux"): a plain restart is enough. */
        boolean linuxByDefault() {
            return "linux".equals(mode);
        }
    }

    private LinuxBoot() {
    }

    static State check() {
        // the trigger fires on a change: clear first, so a second check runs the helper again
        SystemProperties.set(PROP_STATUS, "");
        SystemProperties.set(PROP_STATUS, REQUEST);
        String status = waitFor(PROP_STATUS, REQUEST);
        return new State(status, SystemProperties.get(PROP_MODE, "android"));
    }

    /**
     * Checks again, arms the next boot for Debian and restarts. Returns false (and changes
     * nothing) when Debian is not ready or init did not confirm the request.
     */
    static boolean restartIntoDebian(Context context) {
        State state = check();
        if (!state.ready()) return false;
        if (!state.linuxByDefault()) {
            SystemProperties.set(PROP_BOOT, "");
            SystemProperties.set(PROP_BOOT, REQUEST);
            if (!"armed".equals(waitFor(PROP_BOOT, REQUEST))) return false;
            // confirm init really wrote it (the helper reads the file back)
            if (!"linux-once".equals(check().mode)) return false;
        }
        context.getSystemService(PowerManager.class).reboot(null);
        return true;
    }

    /** Waits until the property is no longer {@code pending}; null on timeout. */
    private static String waitFor(String prop, String pending) {
        long end = SystemClock.uptimeMillis() + TIMEOUT_MS;
        while (SystemClock.uptimeMillis() < end) {
            String v = SystemProperties.get(prop, "");
            if (!v.isEmpty() && !pending.equals(v)) return v;
            SystemClock.sleep(100);
        }
        return null;
    }
}
