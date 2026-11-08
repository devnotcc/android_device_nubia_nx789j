/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.parts;

import android.app.ActivityTaskManager;
import android.app.ActivityTaskManager.RootTaskInfo;
import android.app.NotificationManager;
import android.app.TaskStackListener;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.database.ContentObserver;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.SystemProperties;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.ArraySet;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.FileWriter;

/**
 * Drives the two RedMagic extras the stock kernel modules expose but nothing in
 * AOSP uses:
 *
 *  - cooling fan (soc_fan.ko): /sys/kernel/fan/fan_enable (0/1) and
 *    fan_speed_level (1..5, about 4000..19500 RPM); fan_speed_count reads the
 *    measured RPM.
 *  - side slider (gpio_keys_nubia.ko): input switch 0x0f. InputManagerService
 *    forwards changes as ACTION_GAME_SWITCH (frameworks/base patch); the current
 *    position is readable from GamekeyStatus.
 *
 * The fan runs while the screen is on and any of these holds: the manual switch
 * is on, the slider action is "fan" and the slider is on, or "in games" is
 * enabled and the focused app is in the EvoX Game Space list. It also runs,
 * screen on or off, while the battery is fast charging (if enabled).
 *
 * Performance modes raise the skin-temperature throttling of the vendor thermal
 * daemon: setting sys.nubia.thermal_profile makes init bind-mount the matching
 * profile over /vendor/etc/thermal-engine.conf and restart the daemon
 * (init.nubia.parts.rc). The fan is forced on in a raised mode, and a hot battery
 * drops back to the stock limits until it has cooled down. Each mode also has
 * scheduler/GPU/bus tuning (sys.nubia.perf_tuning, same rc), applied only while
 * the screen is on.
 *
 * Charge separation stops charging the battery while a charger is connected, so
 * the phone runs from the charger alone (sys.nubia.charge_separation, same rc).
 */
public class PartsController {
    private static final String TAG = "NubiaParts";

    static final String ACTION_GAME_SWITCH = "org.lineageos.nubia.parts.GAME_SWITCH";
    static final String EXTRA_ON = "on";

    static final String KEY_SLIDER_ACTION = "slider_action";
    static final String KEY_FAN_MANUAL = "fan_manual";
    static final String KEY_FAN_LEVEL = "fan_level";
    static final String KEY_FAN_GAMES = "fan_games";
    static final String KEY_FAN_CHARGING = "fan_charging";
    static final String KEY_PERF_MODE = "perf_mode";
    static final String KEY_CHARGE_SEPARATION = "charge_separation";

    static final String SLIDER_FAN = "fan";
    static final String SLIDER_DND = "dnd";
    static final String SLIDER_TORCH = "torch";
    static final String SLIDER_PERF = "perf";

    static final String PERF_BALANCED = "balanced";
    static final String PERF_EXTREME = "extreme";
    static final String PERF_ULTRA = "ultra";

    private static final String PROP_THERMAL_PROFILE = "sys.nubia.thermal_profile";
    private static final String PROP_PERF_TUNING = "sys.nubia.perf_tuning";
    private static final String PROP_CHARGE_SEPARATION = "sys.nubia.charge_separation";

    static final String SEPARATION_OFF = "off";
    static final String SEPARATION_GAMING = "gaming";
    static final String SEPARATION_ALWAYS = "always";

    // Below this level the battery is charged normally whatever the setting says, so
    // a weak charger cannot run the phone flat; it resumes once the level is back up.
    private static final int SEPARATION_MIN_LEVEL = 20;
    private static final int SEPARATION_RESUME_LEVEL = 25;

    // Measured on the device: if charging is held off before the charger has
    // negotiated its fast profile, it stays at 5 V / 0.15 A and the battery carries
    // the whole load. Held off after the ramp, the charger follows the load (2 W
    // idle, 13-14 W busy) and the battery current stays at 0 mA. So charging runs
    // normally for this long after plug-in before the battery is separated...
    private static final long SEPARATION_ARM_MS = 15_000;
    // ...and if the battery is still found discharging at more than this (the fuel
    // gauge reports discharge as negative) on two checks in a row, the sequence is
    // repeated, at most once per SEPARATION_REARM_MS.
    private static final int SEPARATION_DRAIN_MA = -400;
    private static final long SEPARATION_CHECK_MS = 10_000;
    private static final long SEPARATION_REARM_MS = 120_000;

    // Battery guard for the raised modes, in tenths of a degree: lithium cells are
    // rated to about 60 C while discharging, so stop short of that.
    private static final int BATTERY_HOT = 550;
    private static final int BATTERY_COOL = 480;

    private static final String FAN_ENABLE = "/sys/kernel/fan/fan_enable";
    private static final String FAN_LEVEL = "/sys/kernel/fan/fan_speed_level";
    private static final String FAN_RPM = "/sys/kernel/fan/fan_speed_count";
    private static final String SLIDER_STATE =
            "/sys/devices/platform/soc/soc:gpio_keys_nubia/GamekeyStatus";

    // Fast charging = at least this much power, taken from the charger's negotiated
    // limit or from the measured battery current; the lower bound keeps the fan from
    // toggling as the charge tapers off near full.
    private static final long FAST_CHARGE_ON_UW = 15_000_000L;
    private static final long FAST_CHARGE_OFF_UW = 8_000_000L;

    // EvoX Game Space: "pkg=mode;pkg=mode"
    private static final String GAME_LIST = "gamespace_game_list";

    private static PartsController sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler;
    private final ArraySet<String> mGames = new ArraySet<>();

    private volatile boolean mSliderOn;
    private boolean mInGame;
    private boolean mFastCharging;
    private volatile int mBatteryTemp; // tenths of a degree C
    private volatile boolean mBatteryHot;
    private volatile String mProfile = PERF_BALANCED; // stock file until we say otherwise
    private String mTuning = PERF_BALANCED;
    private boolean mPlugged;
    private int mBatteryLevel = 100;
    private boolean mBatteryLow;
    private volatile boolean mSeparation;
    private boolean mArming;
    private long mPlugTime = SystemClock.uptimeMillis();
    private long mLastRearm;
    private int mDrainCount;
    private volatile int mFanLevel; // what we last wrote, 0 = off

    static synchronized PartsController get(Context context) {
        if (sInstance == null) {
            sInstance = new PartsController(context.getApplicationContext());
        }
        return sInstance;
    }

    private PartsController(Context context) {
        mContext = context;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
    }

    void start() {
        IntentFilter filter = new IntentFilter(ACTION_GAME_SWITCH);
        filter.addAction(Intent.ACTION_SCREEN_ON);
        filter.addAction(Intent.ACTION_SCREEN_OFF);
        filter.addAction(Intent.ACTION_BATTERY_CHANGED);
        mContext.registerReceiver(mReceiver, filter, null, mHandler, Context.RECEIVER_EXPORTED);

        mPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);

        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(GAME_LIST), false, new ContentObserver(mHandler) {
                    @Override
                    public void onChange(boolean selfChange) {
                        loadGames();
                        updateFocusedApp();
                    }
                }, UserHandle.USER_ALL);

        try {
            ActivityTaskManager.getService().registerTaskStackListener(mTaskListener);
        } catch (Exception e) {
            Log.e(TAG, "registerTaskStackListener failed", e);
        }

        mHandler.post(() -> {
            mSliderOn = "1".equals(read(SLIDER_STATE));
            Log.i(TAG, "start: slider=" + mSliderOn);
            loadGames();
            updateFocusedApp();
            apply();
        });
    }

    /** The thermal profile currently in effect (may differ from the selected one). */
    String getProfile() {
        return mProfile;
    }

    boolean isBatteryHot() {
        return mBatteryHot;
    }

    /** True while the battery is being bypassed (charger connected, charging held off). */
    boolean isSeparationActive() {
        return mSeparation;
    }

    /** Battery current in mA as the fuel gauge reports it; the sign is vendor specific. */
    int getBatteryCurrentMa() {
        return mContext.getSystemService(BatteryManager.class)
                .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW) / 1000;
    }

    /**
     * Battery temperature in tenths of a degree C. Read from the sticky battery broadcast, so it is
     * right in any user's process: the receiver that keeps mBatteryTemp current only runs in the
     * owner's (started at boot), and a second user's settings screen used to show 0.0 °C.
     */
    int getBatteryTemp() {
        Intent battery = mContext.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        return battery != null
                ? battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, mBatteryTemp)
                : mBatteryTemp;
    }

    boolean isSliderOn() {
        return mSliderOn;
    }

    int getFanLevel() {
        return mFanLevel;
    }

    /** Measured RPM; the driver samples for a moment, so call off the main thread. */
    int readRpm() {
        try {
            return Integer.parseInt(read(FAN_RPM));
        } catch (Exception e) {
            return 0;
        }
    }

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (ACTION_GAME_SWITCH.equals(intent.getAction())) {
                onSliderChanged(intent.getBooleanExtra(EXTRA_ON, false));
            } else if (Intent.ACTION_BATTERY_CHANGED.equals(intent.getAction())) {
                onBatteryChanged(intent);
            } else {
                apply();
            }
        }
    };

    // Held in a field: SharedPreferences only keeps a weak reference to listeners.
    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    mHandler.post(PartsController.this::apply);
                }
            };

    private final TaskStackListener mTaskListener = new TaskStackListener() {
        @Override
        public void onTaskStackChanged() {
            mHandler.post(PartsController.this::updateFocusedApp);
        }

        @Override
        public void onTaskFocusChanged(int taskId, boolean focused) {
            mHandler.post(PartsController.this::updateFocusedApp);
        }
    };

    private void onSliderChanged(boolean on) {
        if (on == mSliderOn) {
            return;
        }
        mSliderOn = on;
        String action = mPrefs.getString(KEY_SLIDER_ACTION, SLIDER_FAN);
        Log.i(TAG, "slider " + (on ? "on" : "off") + ", action=" + action);
        if (SLIDER_DND.equals(action)) {
            mContext.getSystemService(NotificationManager.class).setZenMode(
                    on ? Settings.Global.ZEN_MODE_IMPORTANT_INTERRUPTIONS
                            : Settings.Global.ZEN_MODE_OFF, null, TAG);
        } else if (SLIDER_TORCH.equals(action)) {
            setTorch(on);
        }
        apply();
    }

    private void onBatteryChanged(Intent intent) {
        mBatteryTemp = intent.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0);
        boolean hot = mBatteryTemp >= (mBatteryHot ? BATTERY_COOL : BATTERY_HOT);
        if (hot != mBatteryHot) {
            mBatteryHot = hot;
            Log.w(TAG, "battery hot: " + hot + " (" + mBatteryTemp / 10f + " C)");
            apply();
        }

        boolean plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0;
        mBatteryLevel = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, 100);
        boolean low = mBatteryLevel < (mBatteryLow ? SEPARATION_RESUME_LEVEL : SEPARATION_MIN_LEVEL);
        if (plugged != mPlugged || low != mBatteryLow) {
            if (plugged && !mPlugged) {
                mPlugTime = SystemClock.uptimeMillis();
            }
            mPlugged = plugged;
            mBatteryLow = low;
            apply();
        }

        boolean charging = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
                && intent.getIntExtra(BatteryManager.EXTRA_STATUS,
                        BatteryManager.BATTERY_STATUS_UNKNOWN)
                        == BatteryManager.BATTERY_STATUS_CHARGING;
        long microWatts = 0;
        if (charging) {
            // What the charger negotiated (uA * uV)...
            long negotiated = (long) intent.getIntExtra(
                    BatteryManager.EXTRA_MAX_CHARGING_CURRENT, 0) / 1000
                    * (intent.getIntExtra(BatteryManager.EXTRA_MAX_CHARGING_VOLTAGE, 0) / 1000);
            // ...and what is actually going into the battery (uA * mV); the sign of
            // the current is vendor specific.
            long measured = Math.abs((long) mContext.getSystemService(BatteryManager.class)
                    .getIntProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)) / 1000
                    * intent.getIntExtra(BatteryManager.EXTRA_VOLTAGE, 0);
            microWatts = Math.max(negotiated, measured);
        }
        boolean fast = microWatts >= (mFastCharging ? FAST_CHARGE_OFF_UW : FAST_CHARGE_ON_UW);
        if (fast != mFastCharging) {
            mFastCharging = fast;
            Log.i(TAG, "fast charging: " + fast + " (" + microWatts / 1000 + " mW)");
            apply();
        }
    }

    private void setTorch(boolean on) {
        CameraManager cm = mContext.getSystemService(CameraManager.class);
        try {
            for (String id : cm.getCameraIdList()) {
                CameraCharacteristics c = cm.getCameraCharacteristics(id);
                Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
                Integer facing = c.get(CameraCharacteristics.LENS_FACING);
                if (flash != null && flash && facing != null
                        && facing == CameraCharacteristics.LENS_FACING_BACK) {
                    cm.setTorchMode(id, on);
                    return;
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "setTorch failed", e);
        }
    }

    private void loadGames() {
        mGames.clear();
        String list = Settings.System.getStringForUser(mContext.getContentResolver(),
                GAME_LIST, UserHandle.USER_CURRENT);
        if (list == null) {
            return;
        }
        for (String entry : list.split(";")) {
            String pkg = entry.split("=")[0].trim();
            if (!pkg.isEmpty()) {
                mGames.add(pkg);
            }
        }
    }

    private void updateFocusedApp() {
        boolean inGame = false;
        try {
            RootTaskInfo info = ActivityTaskManager.getService().getFocusedRootTaskInfo();
            if (info != null && info.topActivity != null) {
                inGame = mGames.contains(info.topActivity.getPackageName());
            }
        } catch (Exception e) {
            Log.e(TAG, "getFocusedRootTaskInfo failed", e);
        }
        if (inGame != mInGame) {
            mInGame = inGame;
            Log.i(TAG, "game in foreground: " + inGame);
            apply();
        }
    }

    private void apply() {
        String profile = applyProfile();
        applyFan(profile);
        applySeparation(profile);
    }

    private void applySeparation(String profile) {
        String mode = mPrefs.getString(KEY_CHARGE_SEPARATION, SEPARATION_OFF);
        boolean want = mPlugged && !mBatteryLow
                && (SEPARATION_ALWAYS.equals(mode)
                        || (SEPARATION_GAMING.equals(mode)
                                && (mInGame || !PERF_BALANCED.equals(profile))));
        if (!want) {
            mHandler.removeCallbacks(mArmSeparation);
            mHandler.removeCallbacks(mCheckSeparation);
            mArming = false;
            if (mSeparation) {
                Log.i(TAG, "charge separation off (level " + mBatteryLevel + ")");
                SystemProperties.set(PROP_CHARGE_SEPARATION, "0");
                mSeparation = false;
            }
            return;
        }
        if (mSeparation || mArming) {
            return;
        }
        long wait = mPlugTime + SEPARATION_ARM_MS - SystemClock.uptimeMillis();
        if (wait > 0) {
            mArming = true;
            mHandler.postDelayed(mArmSeparation, wait);
            return;
        }
        Log.i(TAG, "charge separation on (level " + mBatteryLevel + ")");
        SystemProperties.set(PROP_CHARGE_SEPARATION, "1");
        mSeparation = true;
        mDrainCount = 0;
        mHandler.postDelayed(mCheckSeparation, SEPARATION_CHECK_MS);
    }

    private final Runnable mArmSeparation = new Runnable() {
        @Override
        public void run() {
            mArming = false;
            apply();
        }
    };

    private final Runnable mCheckSeparation = new Runnable() {
        @Override
        public void run() {
            if (!mSeparation) {
                return;
            }
            int ma = getBatteryCurrentMa();
            mDrainCount = ma < SEPARATION_DRAIN_MA ? mDrainCount + 1 : 0;
            long now = SystemClock.uptimeMillis();
            if (mDrainCount >= 2 && now - mLastRearm > SEPARATION_REARM_MS) {
                // The charger is not carrying the load: charge normally for a
                // moment so it ramps up, then separate again.
                Log.w(TAG, "battery draining at " + ma + " mA while separated, re-arming");
                mLastRearm = now;
                SystemProperties.set(PROP_CHARGE_SEPARATION, "0");
                mSeparation = false;
                mPlugTime = now;
                apply();
                return;
            }
            mHandler.postDelayed(this, SEPARATION_CHECK_MS);
        }
    };

    private String applyProfile() {
        String profile = mPrefs.getString(KEY_PERF_MODE, PERF_BALANCED);
        // With the slider bound to it, the selected mode only applies while it is on.
        if (SLIDER_PERF.equals(mPrefs.getString(KEY_SLIDER_ACTION, SLIDER_FAN)) && !mSliderOn) {
            profile = PERF_BALANCED;
        }
        if (mBatteryHot) {
            profile = PERF_BALANCED;
        }
        if (!profile.equals(mProfile)) {
            Log.i(TAG, "thermal profile " + mProfile + " -> " + profile);
            SystemProperties.set(PROP_THERMAL_PROFILE, profile);
            mProfile = profile;
        }
        // The tuning raises frequency floors, so it is dropped while the screen is off.
        String tuning = mContext.getSystemService(PowerManager.class).isInteractive()
                ? profile : PERF_BALANCED;
        if (!tuning.equals(mTuning)) {
            Log.i(TAG, "tuning " + mTuning + " -> " + tuning);
            SystemProperties.set(PROP_PERF_TUNING, tuning);
            mTuning = tuning;
        }
        return profile;
    }

    private void applyFan(String profile) {
        boolean raised = !PERF_BALANCED.equals(profile);
        boolean want = mPrefs.getBoolean(KEY_FAN_MANUAL, false)
                || raised
                || (mSliderOn && SLIDER_FAN.equals(mPrefs.getString(KEY_SLIDER_ACTION, SLIDER_FAN)))
                || (mInGame && mPrefs.getBoolean(KEY_FAN_GAMES, true));
        if (!mContext.getSystemService(PowerManager.class).isInteractive()) {
            want = false;
        }
        // Charging usually happens with the screen off, so this one ignores it.
        want |= mFastCharging && mPrefs.getBoolean(KEY_FAN_CHARGING, true);
        int level = want ? Math.max(1, Math.min(5, mPrefs.getInt(KEY_FAN_LEVEL, 3))) : 0;
        if (level > 0 && (PERF_EXTREME.equals(profile) || PERF_ULTRA.equals(profile))) {
            level = 5;
        }
        if (level == mFanLevel) {
            return;
        }
        Log.i(TAG, "fan level " + mFanLevel + " -> " + level);
        if (level > 0) {
            write(FAN_ENABLE, "1");
            write(FAN_LEVEL, Integer.toString(level));
        } else {
            write(FAN_LEVEL, "0");
            write(FAN_ENABLE, "0");
        }
        mFanLevel = level;
    }

    private static String read(String path) {
        try (BufferedReader r = new BufferedReader(new FileReader(path))) {
            String line = r.readLine();
            return line == null ? "" : line.trim();
        } catch (Exception e) {
            Log.e(TAG, "read " + path + " failed: " + e);
            return "";
        }
    }

    private static void write(String path, String value) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(value);
        } catch (Exception e) {
            Log.e(TAG, "write " + path + "=" + value + " failed: " + e);
        }
    }
}
