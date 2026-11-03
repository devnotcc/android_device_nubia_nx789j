/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.udfps;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.Context;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.PowerManager;
import android.provider.Settings;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.biometrics.BiometricStateListener;
import android.hardware.display.BrightnessInfo;
import android.hardware.display.DisplayManager;
import android.view.Display;
import android.hardware.fingerprint.FingerprintManager;
import android.content.BroadcastReceiver;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.Parcel;
import android.os.ServiceManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UEventObserver;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import com.android.internal.display.BrightnessSynchronizer;

/**
 * Stand-in for the ZTE pieces LineageOS does not have.
 *
 * The Goodix HAL (vendor.zte.fingerprint.ztecmdaidl-service) only captures an
 * image after it is told the sensor area is lit. On stock that is done by
 * services.jar UdfpsDecoupleController together with FingerprintSensorUi_ZTE
 * and a ZTE-modified SurfaceFlinger:
 *
 *   touch uevent areameet_down      -> zteCmd(10, 1)  finger down
 *   dsi uevent HBM_STATUS=FG_PRESS  -> zteCmd(22, 1)
 *   dsi uevent HBM_STATUS=ON        -> zteCmd(21, 1)  HAL: sensor UI ready
 *   touch uevent areameet_up        -> zteCmd(10, 0)
 *
 * The HAL drops zteCmd(21, 1) as "UI is not ready" unless 22,1 came first.
 * IZtecmdDaemon is the extension binder of IFingerprint/default; zteCmd is
 * oneway transaction 2 taking (int, int, int, String).
 *
 * Illumination: /proc/driver/lcd_hbm is GLOBAL HBM on this panel
 * (ro.vendor.feature.zte_fingerprint_global_hbm) - it boosts the whole screen.
 * It is hidden by a full-screen black mask whose opacity comes from the stock
 * table below, with the white sensor circle on top. An app cannot time the HBM
 * switch to the frame the mask appears in (builds 24-27 all flashed), so HBM is
 * switched by our patched SurfaceFlinger (patches/frameworks_native, enabled by
 * ro.surface_flinger.udfps_hbm_node): ON while both the NubiaUdfpsMask and
 * NubiaUdfpsCircle layers are visible, OFF otherwise. This app only changes the
 * WINDOW alpha of those layers (View alpha stays inside the buffer and is
 * invisible to SurfaceFlinger):
 *
 *   press:   mask alpha = table, circle alpha = 1  -> SF: HBM on after that frame
 *   release: circle alpha = 0 (mask kept)          -> SF: HBM off, mask still up
 *            HBM_STATUS=OFF + a frame              -> mask alpha = 0
 *
 * The AOSP UDFPS touch path is kept out of the way by
 * persist.vendor.fingerprint.virtual.udfps.display_touch=true. With it false,
 * the HAL's virtual-engine code reports a fake ACQUIRED_GOOD 100 ms after
 * onPointerDown and SystemUI switches its illumination off before capture.
 *
 * AOD (rewritten 2026-09-25 from kernel + input traces on EvoX):
 *   - Once DozeService switches the display to DOZE, the panel enters LP mode and
 *     the Goodix THP goes to gesture mode. A double tap is then reported ONLY as
 *     the zte_touch uevent double_tap=true - no KEY_WAKEUP input event (verified
 *     with getevent). Stock ZTE SystemUI wakes the device on that uevent; we do
 *     the same here (wakeUp, WAKE_REASON_GESTURE) when double_tap_to_wake is on.
 *   - Nothing of ours is drawn during doze: no idle ring, no mask, no in-doze
 *     illumination. The old ring appeared exactly when DT2W died, and in-doze
 *     illumination wedged the touch controller on LineageOS.
 *   - A press on the sensor during doze wakes the device instead of lighting it;
 *     if the finger is still down when the display is ON again, the normal
 *     lit-screen flow below starts at once (press-and-hold unlocks from AOD).
 */
public class UdfpsController {
    private static final String TAG = "NubiaUdfps";
    private static final String CAL_TAG = "NubiaUdfpsCal";
    private static final String ACTION_CALIBRATE = "org.lineageos.nubia.udfps.CALIBRATE";
    // Sent by (patched) SystemUI UdfpsOverlayInteractor.setHandleTouches():
    // whether the UDFPS affordance is live (lockscreen icon shown, not bouncer /
    // shade / dialog; or an enroll / BiometricPrompt overlay).
    private static final String ACTION_HANDLE_TOUCHES = "org.lineageos.nubia.udfps.HANDLE_TOUCHES";
    // After waking from AOD, wait at most this long for SystemUI to show the icon.
    private static final long AFFORDANCE_WAIT_MS = 1000;
    // SystemUI DozeTriggers pulse request (PULSE_REASON_INTENT).
    private static final String ACTION_DOZE_PULSE = "com.android.systemui.doze.pulse";
    // If the display is not ON this long after a pulse request, wake fully.
    private static final long PULSE_FALLBACK_MS = 800;

    private static final String HAL =
            "android.hardware.biometrics.fingerprint.IFingerprint/default";
    private static final String ZTECMD = "vendor.zte.fingerprint.ztecmdaidl.IZtecmdDaemon";
    private static final int TRANSACTION_ZTE_CMD = 2;

    private static final int CMD_FINGER = 10;
    private static final int CMD_HBM_ON = 21;
    private static final int CMD_FG_PRESS = 22;

    // If the panel never reports HBM_STATUS=ON, declare the UI ready anyway.
    private static final long HBM_READY_TIMEOUT_MS = 150;
    // Upper bound on keeping the mask up while waiting for HBM_STATUS=OFF.
    private static final long HIDE_FALLBACK_MS = 150;
    // SurfaceFlinger ignores a zero-alpha layer; keep the mask counted at full
    // brightness, where the stock table goes to 0.
    private static final float MIN_MASK_ALPHA = 0.01f;
    // A doze sensor press waits this long for the display to come ON before it is
    // dropped (the finger may be long gone by then).
    private static final long DOZE_PRESS_TIMEOUT_MS = 1500;

    // Mask opacity per (0-255 brightness / 2), verbatim from stock
    // FingerprintSensorUi_ZTE Utils.getMaskViewAlphas(), branch
    // isFingerprintG7Cali() && isSM8750LCD() (lcd_id BF237_RM692H5_6P8_DEV0_DSC_CMD).
    private static final float[] MASK_ALPHAS = {
        0.9099f, 0.9099f, 0.9079f, 0.9059f, 0.9031f, 0.903f, 0.8929f, 0.8919f,
        0.8905f, 0.8881f, 0.8781f, 0.8681f, 0.8601f, 0.8581f, 0.852f, 0.848f,
        0.845f, 0.8377f, 0.8337f, 0.8257f, 0.8199f, 0.8107f, 0.8067f, 0.7986f,
        0.7902f, 0.7851f, 0.7806f, 0.7686f, 0.7656f, 0.7626f, 0.752f, 0.748f,
        0.745f, 0.7387f, 0.73f, 0.7242f, 0.7163f, 0.7103f, 0.6983f, 0.6826f,
        0.6767f, 0.6703f, 0.6658f, 0.6601f, 0.6543f, 0.6487f, 0.6438f, 0.6383f,
        0.6312f, 0.6262f, 0.6188f, 0.6103f, 0.6064f, 0.5942f, 0.5892f, 0.5806f,
        0.5767f, 0.5697f, 0.5602f, 0.5548f, 0.5465f, 0.5381f, 0.5302f, 0.5252f,
        0.5195f, 0.5102f, 0.5036f, 0.4895f, 0.4743f, 0.4714f, 0.4695f, 0.4688f,
        0.4639f, 0.4568f, 0.4481f, 0.4397f, 0.4303f, 0.4231f, 0.4153f, 0.4096f,
        0.4036f, 0.3981f, 0.39f, 0.3844f, 0.3792f, 0.37f, 0.3602f, 0.3505f,
        0.3433f, 0.3373f, 0.333f, 0.323f, 0.3169f, 0.31f, 0.3049f, 0.2955f,
        0.2888f, 0.2797f, 0.2689f, 0.2603f, 0.2547f, 0.2431f, 0.2331f, 0.2198f,
        0.2088f, 0.1979f, 0.192f, 0.1862f, 0.1734f, 0.1684f, 0.1573f, 0.1573f,
        0.1145f, 0.1145f, 0.0741f, 0.0741f, 0.0265f, 0.0265f, 0.0265f, 0.0f,
        0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f,
    };

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager mWindowManager;
    private final int mCenterX;
    private final int mCenterY;
    private final int mRadius;

    private IBinder mZtecmd;
    private int mBiometricState = BiometricStateListener.STATE_IDLE;
    private boolean mFingerDown;
    private boolean mUiReadySent;
    private boolean mHidePending;
    private long mDownTime;
    private View mMask;
    private View mCircle;
    private ValueAnimator mMaskFade;
    private boolean mCalibrating;
    // Sensor pressed during doze: device woken, finger possibly still down.
    private boolean mDozePress;
    private boolean mSensorTouched;
    // SystemUI's UDFPS "handle touches" state: 0 no, 1 yes, -1 unknown (keeps the
    // old ungated behaviour). Starts at 0: after boot, until the first PIN unlock,
    // keyguard runs no fingerprint session and SystemUI never sends anything -
    // the sensor must stay dark then too. Bench fallback for an unpatched
    // SystemUI: setprop debug.nubia.udfps.affordance_default -1 (+ restart app).
    private int mAffordance = prop("affordance_default", 0);
    private long mResumeDeadline;
    // The pending doze press was answered with a pulse (AOD at full power), not a
    // full wake to the lockscreen.
    private boolean mPulsing;

    public UdfpsController(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mCenterX = SystemProperties.getInt(
                "ro.vendor.feature.fingerprint_sensorui_position_center_x", 608);
        mCenterY = SystemProperties.getInt(
                "ro.vendor.feature.fingerprint_sensorui_position_center_y", 2024);
        mRadius = SystemProperties.getInt(
                "ro.vendor.feature.fingerprint_sensorui_position_center_r", 94);
    }

    public void start() {
        FingerprintManager fpm = mContext.getSystemService(FingerprintManager.class);
        if (fpm == null) {
            Log.e(TAG, "No FingerprintManager");
            return;
        }
        fpm.registerBiometricStateListener(new BiometricStateListener() {
            @Override
            public void onStateChanged(int newState) {
                mHandler.post(() -> onBiometricState(newState));
            }
        });
        mTouchObserver.startObserving("MODALIAS=platform:zte_touch");
        mDisplayObserver.startObserving("DRIVER=msm-dsi-display");
        registerCalibration();
        registerSettingsObserver();
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                boolean handle = intent.getBooleanExtra("handle", false);
                mHandler.post(() -> onAffordance(handle));
            }
        // Only SystemUI (holds STATUS_BAR_SERVICE) may say the affordance is live.
        }, new IntentFilter(ACTION_HANDLE_TOUCHES),
                android.Manifest.permission.STATUS_BAR_SERVICE, mHandler,
                Context.RECEIVER_EXPORTED);
        // AOSP does not replay the current biometric state to a newly
        // registered listener. If this process (re)starts while keyguard
        // auth is already running, mBiometricState stays IDLE and fingerDown
        // no-ops — HBM never toggles, only SystemUI's icon shows.
        mHandler.post(this::syncFromKeyguard);
        mHandler.postDelayed(this::syncFromKeyguard, 1000);
        mHandler.postDelayed(this::syncFromKeyguard, 3000);
        IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        screen.addAction(Intent.ACTION_USER_PRESENT);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                syncFromKeyguard();
            }
        }, screen, Context.RECEIVER_EXPORTED);
        DisplayManager dm = mContext.getSystemService(DisplayManager.class);
        if (dm != null) {
            dm.registerDisplayListener(new DisplayManager.DisplayListener() {
                @Override public void onDisplayAdded(int displayId) {}
                @Override public void onDisplayRemoved(int displayId) {}
                @Override public void onDisplayChanged(int displayId) {
                    mHandler.post(() -> onDisplayStateChanged());
                }
            }, mHandler);
        }
        applyWakeGesture();
        Log.i(TAG, "Started, sensor at " + mCenterX + "," + mCenterY + " r=" + mRadius);
    }

    private void registerSettingsObserver() {
        ContentObserver obs = new ContentObserver(mHandler) {
            @Override
            public void onChange(boolean selfChange, Uri uri) {
                applyWakeGesture();
                syncFromKeyguard();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.Secure.getUriFor(Settings.Secure.DOUBLE_TAP_TO_WAKE), false, obs);
    }

    static void writeSysfs(String path, String value) {
        try (java.io.FileWriter w = new java.io.FileWriter(path)) {
            w.write(value);
        } catch (Exception ignored) {}
    }

    void applyWakeGesture() {
        boolean on = Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOUBLE_TAP_TO_WAKE, 1) != 0;
        writeSysfs("/proc/touchscreen/wake_gesture", on ? "1" : "0");
        writeSysfs("/proc/touchscreen/screen_off_awake", "1");
        // Stock "fingerprint unlock with screen off" (FingerprintSensorUi_ZTE,
        // setting fingerprint_unlock_for_screen): makes the THP report sensor
        // presses in doze as aod_areameet_down. Without it AOD presses are silent.
        writeSysfs("/proc/touchscreen/single_tap", "1");
    }

    private boolean isLocked() {
        KeyguardManager kg = mContext.getSystemService(KeyguardManager.class);
        return kg != null && kg.isKeyguardLocked();
    }

    private boolean isInteractive() {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        return pm != null && pm.isInteractive();
    }

    private int displayState() {
        Display d = mContext.getDisplay();
        return d == null ? Display.STATE_UNKNOWN : d.getState();
    }

    /** True only in AOD, not on the interactive lockscreen (PIN/pattern). */
    private boolean isDozing() {
        int s = displayState();
        return s == Display.STATE_DOZE || s == Display.STATE_DOZE_SUSPEND;
    }

    /** Enroll, BiometricPrompt or other auth running (not keyguard, not idle). */
    private boolean authActive() {
        return mBiometricState == BiometricStateListener.STATE_ENROLLING
                || mBiometricState == BiometricStateListener.STATE_BP_AUTH
                || mBiometricState == BiometricStateListener.STATE_AUTH_OTHER;
    }

    private boolean dt2wOn() {
        return Settings.Secure.getInt(mContext.getContentResolver(),
                Settings.Secure.DOUBLE_TAP_TO_WAKE, 1) != 0;
    }

    private void syncFromKeyguard() {
        if (!isLocked()) {
            // Only drop keyguard auth; an enroll or BiometricPrompt runs unlocked.
            if (mBiometricState == BiometricStateListener.STATE_KEYGUARD_AUTH) {
                Log.i(TAG, "Unlocked, disarming");
                onBiometricState(BiometricStateListener.STATE_IDLE);
            }
            return;
        }
        if (mBiometricState == BiometricStateListener.STATE_IDLE) {
            Log.i(TAG, "Keyguard locked while IDLE, arming");
            onBiometricState(BiometricStateListener.STATE_KEYGUARD_AUTH);
        }
        onDisplayStateChanged();
    }

    // ---------------------------------------------------------------- AOD / doze

    /**
     * Nothing of ours may be on screen while dozing. Hide with GONE, not alpha 0:
     * msm_drm refuses backlight updates while an aod_layer / hbm_overlay is still
     * in the mix (dim pause after AOD unlock). When the display is ON again and a
     * doze press is still being held, start the normal lit-screen flow.
     */
    private void onDisplayStateChanged() {
        if (isDozing()) {
            if (!mFingerDown) hideAllLayers();
            return;
        }
        if (mDozePress && mSensorTouched && isLocked() && !mFingerDown) {
            mDozePress = false;
            mHandler.removeCallbacks(mDropDozePress);
            // The panel is still ramping its backlight up from the AOD level when
            // the display reports ON, but maskAlpha() sizes the mask for the target
            // brightness - so HBM shone through ("too bright" from AOD only). Let it
            // settle first. Bench: debug.nubia.udfps.doze_press_delay_ms.
            mHandler.removeCallbacks(mResumeDozePress);
            mHandler.removeCallbacks(mPulseFallback);
            // Both leave LP straight to ON now that config_displayBlanksAfterDoze is
            // false (it made a pulse go lcd_off first, ~400 ms of black); the
            // delay only covers the backlight ramp from the AOD level.
            int delay = mPulsing ? prop("pulse_press_delay_ms", 150)
                    : prop("doze_press_delay_ms", 150);
            mResumeDeadline = SystemClock.uptimeMillis() + delay + AFFORDANCE_WAIT_MS;
            mHandler.postDelayed(mResumeDozePress, delay);
        }
    }

    private final Runnable mDropDozePress = () -> mDozePress = false;

    private final Runnable mResumeDozePress = new Runnable() {
        @Override
        public void run() {
            if (!mSensorTouched || !isLocked() || mFingerDown || isDozing()) return;
            // After an AOD wake SystemUI fades the fingerprint icon in; it only
            // counts as live above 90% alpha. Wait for it (bounded).
            // On a pulsed AOD there is no bouncer, shade or dialog to protect, so
            // don't hold the scan hostage to SystemUI's icon alpha.
            if (!mPulsing && mAffordance == 0
                    && SystemClock.uptimeMillis() < mResumeDeadline) {
                mHandler.postDelayed(this, 50);
                return;
            }
            Log.i(TAG, "Display on with finger still down, starting auth"
                    + (mPulsing ? " (pulse)" : " (woken)"));
            startAuth(mPulsing);
        }
    };

    private void onAffordance(boolean handle) {
        int value = handle ? 1 : 0;
        if (value == mAffordance) return;
        Log.d(TAG, "UDFPS affordance " + (handle ? "live" : "gone"));
        mAffordance = value;
        // Shade pulled down / bouncer shown mid-press: drop the illumination.
        if (!handle && mFingerDown) fingerUp();
    }

    /** Wake from doze through the same path as the power key would. */
    private void wakeFromDoze(int reason, String details) {
        PowerManager pm = mContext.getSystemService(PowerManager.class);
        if (pm == null) return;
        Log.i(TAG, "Waking from doze: " + details);
        pm.wakeUp(SystemClock.uptimeMillis(), reason, details);
    }

    /**
     * DT2W while dozing or with the display fully off (AOD disabled or scheduled
     * off): the THP only sends the double_tap uevent (no KEY_WAKEUP), in both states.
     */
    private void onDoubleTap() {
        if (isInteractive() || !dt2wOn()) return;
        wakeFromDoze(PowerManager.WAKE_REASON_GESTURE, "nubia:dt2w");
    }

    /**
     * Sensor press while dozing (panel in LP mode, where we must not illuminate).
     * Preferred: ask SystemUI for a doze PULSE - the AOD comes back at full power
     * (STATE_ON, still dozing, no lockscreen) and the scan then runs over the AOD
     * like it does in the first seconds after locking. Fallback, if no pulse
     * happens: a full wake to the lockscreen. debug.nubia.udfps.aod_pulse=0 forces
     * the old full-wake behaviour.
     */
    private void onDozeSensorPress() {
        mDozePress = true;
        mHandler.removeCallbacks(mDropDozePress);
        mHandler.postDelayed(mDropDozePress, DOZE_PRESS_TIMEOUT_MS);
        if (prop("aod_pulse", 1) != 0) {
            mPulsing = true;
            Log.i(TAG, "Doze press: requesting pulse");
            mContext.sendBroadcast(new Intent(ACTION_DOZE_PULSE).setPackage("com.android.systemui"));
            mHandler.removeCallbacks(mPulseFallback);
            mHandler.postDelayed(mPulseFallback, PULSE_FALLBACK_MS);
            return;
        }
        mPulsing = false;
        wakeFromDoze(PowerManager.WAKE_REASON_BIOMETRIC, "nubia:udfps");
    }

    // No pulse (still in LP) but the finger is still down: wake fully instead.
    private final Runnable mPulseFallback = () -> {
        if (!mDozePress || !isDozing() || !mSensorTouched) return;
        Log.i(TAG, "No pulse, falling back to full wake");
        mPulsing = false;
        wakeFromDoze(PowerManager.WAKE_REASON_BIOMETRIC, "nubia:udfps");
    };

    private void onBiometricState(int state) {
        Log.d(TAG, "Biometric state " + mBiometricState + " -> " + state);
        mBiometricState = state;
        if (state == BiometricStateListener.STATE_IDLE) {
            fingerUp();
            disarmOverlays();
        } else {
            armOverlays();
        }
    }

    private final UEventObserver mTouchObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            // Trace every zte_touch uevent (adb logcat -s NubiaUdfps): the THP's
            // doze behaviour is only knowable from what it actually sends.
            Log.d(TAG, "touch uevent " + event);
            if ("true".equals(event.get("areameet_down"))) {
                mHandler.post(() -> {
                    mSensorTouched = true;
                    fingerDown();
                });
            } else if ("true".equals(event.get("aod_areameet_down"))) {
                // Doze/LP (TP_POWER_STATUS=3): the THP names the press differently
                // and only reports it with /proc/touchscreen/single_tap = 1.
                mHandler.post(() -> {
                    mSensorTouched = true;
                    fingerDown();
                });
            } else if ("true".equals(event.get("areameet_up"))) {
                mHandler.post(() -> {
                    mSensorTouched = false;
                    fingerUp();
                });
            } else if ("true".equals(event.get("double_tap"))) {
                mHandler.post(UdfpsController.this::onDoubleTap);
            }
        }
    };

    private final UEventObserver mDisplayObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            String status = event.get("HBM_STATUS");
            if (status == null) return;
            if ("ON".equals(status)) {
                mHandler.post(UdfpsController.this::uiReady);
            } else if ("OFF".equals(status)) {
                mHandler.post(UdfpsController.this::onHbmOff);
            }
        }
    };

    private final Runnable mReadyTimeout = this::uiReady;
    private final Runnable mHideMask = this::hideMask;

    private void fingerDown() {
        startAuth(false);
    }

    /** @param pulsedAod press answered by an AOD pulse: skip the affordance gate. */
    private void startAuth(boolean pulsedAod) {
        // TP reports every press of the sensor area, including on the launcher.
        // Light up only while a fingerprint operation wants it: keyguard locked,
        // or an enroll / BiometricPrompt / other auth running (unlocked). Keyguard
        // only was not enough - enrollment in Settings never lit the sensor.
        if (mFingerDown || !(isLocked() || authActive())) return;
        // Never illuminate in doze/LP mode (it wedged the touch controller on
        // LineageOS): wake instead; onDisplayStateChanged resumes if still held.
        if (isDozing()) {
            onDozeSensorPress();
            return;
        }
        // Only where SystemUI actually offers fingerprint: not on the PIN/pattern
        // bouncer, emergency dialer, an expanded shade, or before the first
        // unlock after boot. (Keyguard auth kept running there, so a press lit
        // the sensor AND unlocked.) Unknown (-1) keeps the old behaviour.
        if (mAffordance == 0 && !(pulsedAod && isLocked())) return;
        if (mBiometricState == BiometricStateListener.STATE_IDLE) {
            onBiometricState(BiometricStateListener.STATE_KEYGUARD_AUTH);
        }
        mFingerDown = true;
        mUiReadySent = false;
        mHidePending = false;
        mHandler.removeCallbacks(mHideMask);
        if (mMaskFade != null) {
            ValueAnimator fade = mMaskFade;
            mMaskFade = null;
            fade.cancel();
        }
        mDownTime = SystemClock.uptimeMillis();

        zteCmd(CMD_FINGER, 1);
        zteCmd(CMD_FG_PRESS, 1);
        armOverlays();
        setWindowAlpha(mMask, maskAlpha());
        setWindowAlpha(mCircle, 1f);
        mHandler.postDelayed(mReadyTimeout, HBM_READY_TIMEOUT_MS);
    }

    private void uiReady() {
        if (!mFingerDown || mUiReadySent) return;
        mHandler.removeCallbacks(mReadyTimeout);
        mUiReadySent = true;
        zteCmd(CMD_HBM_ON, 1);
        mark("UI ready");
    }

    private void fingerUp() {
        if (!mFingerDown) return;
        mFingerDown = false;
        mHandler.removeCallbacks(mReadyTimeout);
        if (mUiReadySent) zteCmd(CMD_HBM_ON, 0);
        zteCmd(CMD_FG_PRESS, 0);
        zteCmd(CMD_FINGER, 0);
        mark("Finger up");
        if (prop("release_together", 1) != 0) {
            // Circle and mask in the same window-alpha pass, so both leave in one
            // SurfaceFlinger frame and SF drops HBM on that frame. Keeping the mask
            // until HBM_STATUS=OFF (below) left one or two dark frames on device.
            setWindowAlpha(mCircle, 0f);
            setWindowAlpha(mMask, 0f);
            mHidePending = false;
            if (mBiometricState == BiometricStateListener.STATE_IDLE) disarmOverlays();
            return;
        }
        // Circle first: SurfaceFlinger drops HBM while the mask is still shown.
        setWindowAlpha(mCircle, 0f);
        mHidePending = true;
        mHandler.postDelayed(mHideMask, HIDE_FALLBACK_MS);
    }

    private void onHbmOff() {
        if (!mHidePending) return;
        mHandler.removeCallbacks(mHideMask);
        mark("HBM off");
        afterFrames(prop("release_frames", 2), () -> mHandler.postDelayed(
                mHideMask, prop("release_delay_ms", 0)));
    }

    private void hideMask() {
        if (mFingerDown || !mHidePending) return;
        mHidePending = false;
        mHandler.removeCallbacks(mHideMask);
        // The panel ramps out of HBM over a few frames; dropping the mask in one
        // step shows as a brightness jump (worst during the unlock animation).
        // Fade it out instead. Bench tuning: debug.nubia.udfps.release_fade_ms.
        int fadeMs = prop("release_fade_ms", 100);
        WindowManager.LayoutParams lp = mMask == null ? null
                : (WindowManager.LayoutParams) mMask.getLayoutParams();
        if (fadeMs <= 0 || lp == null || lp.alpha <= 0f) {
            setWindowAlpha(mMask, 0f);
            if (mBiometricState == BiometricStateListener.STATE_IDLE) disarmOverlays();
            return;
        }
        mMaskFade = ValueAnimator.ofFloat(lp.alpha, 0f);
        mMaskFade.setDuration(fadeMs);
        mMaskFade.addUpdateListener(a -> setWindowAlpha(mMask, (float) a.getAnimatedValue()));
        mMaskFade.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                if (mMaskFade != animation) return;
                mMaskFade = null;
                if (mFingerDown) return;
                setWindowAlpha(mMask, 0f);
                if (mBiometricState == BiometricStateListener.STATE_IDLE) disarmOverlays();
            }
        });
        mMaskFade.start();
    }

    /**
     * Windows stay attached and VISIBLE (window alpha 0) for as long as an enroll
     * or authentication runs, so a press is a pure layer-alpha change. Toggling
     * GONE/VISIBLE per press recreates the surface and shows it frames late.
     */
    private void armOverlays() {
        if (mMask == null) {
            View mask = new View(mContext);
            mask.setBackgroundColor(Color.BLACK);
            mask.setForceDarkAllowed(false);
            WindowManager.LayoutParams lp = overlayParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.setTitle("NubiaUdfpsMask");
            try {
                // Added before the circle so the circle stays on top.
                mWindowManager.addView(mask, lp);
                mMask = mask;
            } catch (RuntimeException e) {
                Log.e(TAG, "mask addView failed", e);
            }
        }
        if (mCircle == null) {
            View circle = new CircleView(mContext);
            int size = mRadius * 2;
            WindowManager.LayoutParams lp = overlayParams(size, size);
            lp.gravity = Gravity.TOP | Gravity.LEFT;
            lp.x = mCenterX - mRadius;
            lp.y = mCenterY - mRadius;
            lp.setTitle("NubiaUdfpsCircle");
            try {
                mWindowManager.addView(circle, lp);
                mCircle = circle;
            } catch (RuntimeException e) {
                Log.e(TAG, "circle addView failed", e);
            }
        }
        if (mMask != null) mMask.setVisibility(View.VISIBLE);
        if (mCircle != null) mCircle.setVisibility(View.VISIBLE);
    }

    private void disarmOverlays() {
        if (mFingerDown || mHidePending) return;
        hideAllLayers();
    }

    private void hideAllLayers() {
        setWindowAlpha(mCircle, 0f);
        setWindowAlpha(mMask, 0f);
        setConsumeTouches(mMask, false);
        if (mCircle != null) mCircle.setVisibility(View.GONE);
        if (mMask != null) mMask.setVisibility(View.GONE);
        writeSysfs("/proc/driver/lcd_hbm", "0");
    }

    private void setConsumeTouches(View view, boolean consume) {
        if (view == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
        if (lp == null) return;
        int flags = lp.flags;
        if (consume) {
            flags &= ~WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        } else {
            flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        }
        if (flags == lp.flags) return;
        lp.flags = flags;
        mWindowManager.updateViewLayout(view, lp);
    }

    private void setWindowAlpha(View view, float alpha) {
        if (view == null) return;
        WindowManager.LayoutParams lp = (WindowManager.LayoutParams) view.getLayoutParams();
        if (lp == null || lp.alpha == alpha) return;
        lp.alpha = alpha;
        mWindowManager.updateViewLayout(view, lp);
    }

    /**
     * Bench calibration of the mask opacity, measured with the under-display
     * ambient light sensor (it sees the panel's own light: ~30 lux -> ~245 lux
     * when global HBM turns on unmasked).
     *
     *   adb shell am broadcast -a org.lineageos.nubia.udfps.CALIBRATE \
     *       --ei alpha 620 --ei hold_ms 1500
     *
     * Measures the light sensor with the screen as it is, then with mask (given
     * alpha in thousandths) + circle shown - which makes SurfaceFlinger enable
     * HBM - and logs both averages under the tag NubiaUdfpsCal. The right
     * opacity for the current brightness is the one where the two match.
     */
    private void registerCalibration() {
        IntentFilter filter = new IntentFilter(ACTION_CALIBRATE);
        mContext.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                calibrate(intent.getIntExtra("alpha", 600), intent.getIntExtra("hold_ms", 1500));
            }
        // Senders must hold DUMP: adb shell has it, ordinary apps cannot get it,
        // so no installed app can make the panel flash HBM.
        }, filter, android.Manifest.permission.DUMP, mHandler, Context.RECEIVER_EXPORTED);
    }

    private void calibrate(int alphaPermille, int holdMs) {
        if (mCalibrating || mFingerDown) {
            Log.w(CAL_TAG, "busy");
            return;
        }
        SensorManager sm = mContext.getSystemService(SensorManager.class);
        Sensor light = sm == null ? null : sm.getDefaultSensor(Sensor.TYPE_LIGHT);
        if (light == null) {
            Log.e(CAL_TAG, "no light sensor");
            return;
        }
        mCalibrating = true;
        final float alpha = Math.max(MIN_MASK_ALPHA, Math.min(1f, alphaPermille / 1000f));
        final double[] sum = new double[2];
        final int[] count = new int[2];
        final int[] phase = {-1}; // -1 settle, 0 baseline, 1 masked settle, 2 masked
        SensorEventListener listener = new SensorEventListener() {
            @Override
            public void onSensorChanged(SensorEvent event) {
                int p = phase[0];
                if (p == 0 || p == 2) {
                    sum[p / 2] += event.values[0];
                    count[p / 2]++;
                }
            }

            @Override
            public void onAccuracyChanged(Sensor sensor, int accuracy) {}
        };
        sm.registerListener(listener, light, SensorManager.SENSOR_DELAY_FASTEST, mHandler);
        final long settleMs = 400;
        mHandler.postDelayed(() -> phase[0] = 0, settleMs);
        mHandler.postDelayed(() -> {
            phase[0] = 1;
            armOverlays();
            setWindowAlpha(mMask, alpha);
            setWindowAlpha(mCircle, 1f);
        }, settleMs + holdMs);
        mHandler.postDelayed(() -> phase[0] = 2, settleMs + holdMs + settleMs);
        mHandler.postDelayed(() -> {
            phase[0] = -1;
            setWindowAlpha(mCircle, 0f);
            setWindowAlpha(mMask, 0f);
            if (mBiometricState == BiometricStateListener.STATE_IDLE) disarmOverlays();
            sm.unregisterListener(listener);
            float brightness = -1f;
            BrightnessInfo info = mContext.getDisplay().getBrightnessInfo();
            if (info != null) brightness = info.brightness;
            Log.i(CAL_TAG, String.format(java.util.Locale.US,
                    "CAL brightness=%.4f alpha=%.3f base=%.2f n=%d masked=%.2f n=%d",
                    brightness, alpha,
                    count[0] == 0 ? -1 : sum[0] / count[0], count[0],
                    count[1] == 0 ? -1 : sum[1] / count[1], count[1]));
            mCalibrating = false;
        }, settleMs + holdMs + settleMs + holdMs);
    }

    private float maskAlpha() {
        float brightness = 0.5f;
        try {
            BrightnessInfo info = mContext.getDisplay().getBrightnessInfo();
            if (info != null) brightness = info.brightness;
        } catch (RuntimeException e) {
            Log.w(TAG, "No brightness info: " + e);
        }
        // Stock indexes the table with the 0-255 brightness value halved.
        int index = BrightnessSynchronizer.brightnessFloatToInt(brightness) / 2;
        index = Math.max(0, Math.min(MASK_ALPHAS.length - 1, index));
        // Bench tuning: offset in thousandths, e.g. 30 = +0.03. 10 was tuned by eye
        // on EvoX 2026-09-25 (brightness 47/255: 0 slightly bright, 20 too dark).
        float alpha = MASK_ALPHAS[index] + prop("alpha_offset", 10) / 1000f;
        return Math.max(MIN_MASK_ALPHA, Math.min(1f, alpha));
    }

    private static WindowManager.LayoutParams overlayParams(int width, int height) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                width, height,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.alpha = 0f;
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.setFitInsetsTypes(0);
        return lp;
    }

    private static void afterFrames(int frames, Runnable action) {
        if (frames <= 0) {
            action.run();
            return;
        }
        Choreographer.getInstance().postFrameCallback(t -> afterFrames(frames - 1, action));
    }

    // Bench tuning without rebuilding: adb shell setprop debug.nubia.udfps.<name> <value>
    private static int prop(String name, int def) {
        return SystemProperties.getInt("debug.nubia.udfps." + name, def);
    }

    private void mark(String what) {
        Log.d(TAG, what + " @" + (SystemClock.uptimeMillis() - mDownTime) + " ms");
    }

    private void zteCmd(int cmd, int param) {
        IBinder binder = getZtecmd();
        if (binder == null) return;
        Parcel data = Parcel.obtain();
        try {
            data.writeInterfaceToken(ZTECMD);
            data.writeInt(cmd);
            data.writeInt(param);
            data.writeInt(0);
            data.writeString("fptest_cmd");
            binder.transact(TRANSACTION_ZTE_CMD, data, null, IBinder.FLAG_ONEWAY);
        } catch (Exception e) {
            Log.e(TAG, "zteCmd(" + cmd + "," + param + ") failed: " + e);
            mZtecmd = null;
        } finally {
            data.recycle();
        }
    }

    private IBinder getZtecmd() {
        if (mZtecmd != null && mZtecmd.isBinderAlive()) return mZtecmd;
        try {
            IBinder hal = ServiceManager.getService(HAL);
            mZtecmd = hal == null ? null : hal.getExtension();
        } catch (Exception e) {
            Log.e(TAG, "No ztecmd extension: " + e);
            mZtecmd = null;
        }
        return mZtecmd;
    }

    private static class CircleView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        CircleView(Context context) {
            super(context);
            mPaint.setColor(Color.WHITE);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, mPaint);
        }
    }
}
