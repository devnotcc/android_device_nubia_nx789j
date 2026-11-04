/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.parts;

import android.app.AlarmManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.os.SystemClock;
import android.os.UEventObserver;
import android.os.UserHandle;
import android.provider.Settings;
import android.util.Log;

import androidx.preference.PreferenceManager;

import java.io.FileWriter;
import java.util.Calendar;

/**
 * Ambient display extras, each with its own switch:
 *
 *  - Tap to show: with /proc/touchscreen/single_aod = 1 the Goodix THP reports a
 *    single tap on the dark screen as the zte_touch uevent single_tap=true. That
 *    asks SystemUI for a doze pulse, which shows the AOD for a few seconds.
 *  - Lift to show: the ZTE pickup sensor (zte.sensor.zte_pickup, a wake-up
 *    special-trigger sensor on the ADSP, so nothing streams while it waits) does
 *    the same. Registered only while the screen is off.
 *  - Schedule: turns the always-on display off between two times of day and back
 *    on afterwards. It only touches the AOD setting at the window edges and only
 *    restores it if it was the schedule that turned it off.
 *
 * Measured 2026-10-22: AOD costs about 60 mA (85-95 mA screen off vs ~28 mA
 * without), most of it the panel itself, so a pulse on demand saves almost all
 * of that.
 */
public class AmbientController {
    private static final String TAG = "NubiaAmbient";

    static final String KEY_TAP = "aod_tap";
    static final String KEY_LIFT = "aod_lift";
    static final String KEY_SCHEDULE = "aod_schedule";
    static final String KEY_SCHEDULE_START = "aod_schedule_start";
    static final String KEY_SCHEDULE_END = "aod_schedule_end";
    // Set while the schedule holds the AOD off, so only its own change is undone.
    private static final String KEY_SCHEDULE_SUSPENDED = "aod_schedule_suspended";

    // Minutes after midnight.
    static final int DEFAULT_START = 0;
    static final int DEFAULT_END = 7 * 60;

    private static final String ACTION_SCHEDULE = "org.lineageos.nubia.parts.AOD_SCHEDULE";
    private static final String ACTION_DOZE_PULSE = "com.android.systemui.doze.pulse";
    private static final String SINGLE_AOD = "/proc/touchscreen/single_aod";
    private static final String PICKUP_TYPE = "zte.sensor.zte_pickup";
    // Taps and lifts closer together than this only pulse once.
    private static final long PULSE_DEBOUNCE_MS = 1500;

    private static AmbientController sInstance;

    private final Context mContext;
    private final SharedPreferences mPrefs;
    private final Handler mHandler;
    private final PowerManager mPower;
    private final SensorManager mSensors;
    private final AlarmManager mAlarms;
    private final Sensor mPickup;

    private boolean mScreenOn = true;
    private boolean mPickupListening;
    private boolean mTouchObserving;
    private long mLastPulse;

    static synchronized AmbientController get(Context context) {
        if (sInstance == null) {
            sInstance = new AmbientController(context.getApplicationContext());
        }
        return sInstance;
    }

    private AmbientController(Context context) {
        mContext = context;
        mPrefs = PreferenceManager.getDefaultSharedPreferences(context);
        HandlerThread thread = new HandlerThread(TAG);
        thread.start();
        mHandler = new Handler(thread.getLooper());
        mPower = context.getSystemService(PowerManager.class);
        mSensors = context.getSystemService(SensorManager.class);
        mAlarms = context.getSystemService(AlarmManager.class);
        mPickup = findSensor(PICKUP_TYPE);
        if (mPickup == null) {
            Log.w(TAG, "No " + PICKUP_TYPE + " sensor, lift to show is unavailable");
        }
    }

    boolean hasPickupSensor() {
        return mPickup != null;
    }

    void start() {
        IntentFilter screen = new IntentFilter();
        screen.addAction(Intent.ACTION_SCREEN_ON);
        screen.addAction(Intent.ACTION_SCREEN_OFF);
        mContext.registerReceiver(mScreenReceiver, screen, null, mHandler);

        IntentFilter time = new IntentFilter();
        time.addAction(ACTION_SCHEDULE);
        time.addAction(Intent.ACTION_TIME_CHANGED);
        time.addAction(Intent.ACTION_TIMEZONE_CHANGED);
        mContext.registerReceiver(mTimeReceiver, time, null, mHandler,
                Context.RECEIVER_NOT_EXPORTED);

        mPrefs.registerOnSharedPreferenceChangeListener(mPrefListener);
        mScreenOn = mPower.isInteractive();
        mHandler.post(this::applyAll);
    }

    private void applyAll() {
        applyTap();
        applyLift();
        applySchedule();
    }

    private final SharedPreferences.OnSharedPreferenceChangeListener mPrefListener =
            new SharedPreferences.OnSharedPreferenceChangeListener() {
                @Override
                public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
                    if (KEY_TAP.equals(key)) {
                        mHandler.post(AmbientController.this::applyTap);
                    } else if (KEY_LIFT.equals(key)) {
                        mHandler.post(AmbientController.this::applyLift);
                    } else if (KEY_SCHEDULE.equals(key) || KEY_SCHEDULE_START.equals(key)
                            || KEY_SCHEDULE_END.equals(key)) {
                        mHandler.post(AmbientController.this::applySchedule);
                    }
                }
            };

    private final BroadcastReceiver mScreenReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            mScreenOn = Intent.ACTION_SCREEN_ON.equals(intent.getAction());
            // zte_tpd ignores writes while the touch panel is suspended, so the tap
            // flag is re-sent at every screen change, before the panel goes down.
            applyTap();
            applyLift();
        }
    };

    private final BroadcastReceiver mTimeReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            applySchedule();
        }
    };

    // ---- Tap to show ---------------------------------------------------------

    private void applyTap() {
        boolean on = mPrefs.getBoolean(KEY_TAP, false);
        write(SINGLE_AOD, on ? "1" : "0");
        if (on && !mTouchObserving) {
            mTouchObserver.startObserving("DEVPATH=/devices/platform/zte_touch");
            mTouchObserving = true;
        } else if (!on && mTouchObserving) {
            mTouchObserver.stopObserving();
            mTouchObserving = false;
        }
    }

    private final UEventObserver mTouchObserver = new UEventObserver() {
        @Override
        public void onUEvent(UEvent event) {
            if ("true".equals(event.get("single_tap"))) {
                mHandler.post(() -> pulse("tap"));
            }
        }
    };

    // ---- Lift to show --------------------------------------------------------

    private void applyLift() {
        boolean want = mPickup != null && !mScreenOn && mPrefs.getBoolean(KEY_LIFT, false);
        if (want && !mPickupListening) {
            mPickupListening = mSensors.registerListener(mPickupListener, mPickup,
                    SensorManager.SENSOR_DELAY_NORMAL, mHandler);
        } else if (!want && mPickupListening) {
            mSensors.unregisterListener(mPickupListener);
            mPickupListening = false;
        }
    }

    private final SensorEventListener mPickupListener = new SensorEventListener() {
        @Override
        public void onSensorChanged(SensorEvent event) {
            if (event.values.length > 0 && event.values[0] != 0) {
                pulse("lift");
            }
        }

        @Override
        public void onAccuracyChanged(Sensor sensor, int accuracy) {}
    };

    private Sensor findSensor(String type) {
        for (Sensor s : mSensors.getSensorList(Sensor.TYPE_ALL)) {
            if (type.equals(s.getStringType()) && s.isWakeUpSensor()) {
                return s;
            }
        }
        return null;
    }

    // Shows the AOD for a few seconds, but only on a dark, locked screen: with the
    // always-on display already showing there is nothing to bring up.
    private void pulse(String why) {
        if (mPower.isInteractive() || isAodOn()) return;
        long now = SystemClock.elapsedRealtime();
        if (now - mLastPulse < PULSE_DEBOUNCE_MS) return;
        mLastPulse = now;
        Log.i(TAG, "Pulse (" + why + ")");
        mContext.sendBroadcastAsUser(new Intent(ACTION_DOZE_PULSE)
                .setPackage("com.android.systemui"), UserHandle.CURRENT);
    }

    private boolean isAodOn() {
        return Settings.Secure.getIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, 1, UserHandle.USER_CURRENT) != 0;
    }

    // ---- Schedule ------------------------------------------------------------

    private void applySchedule() {
        PendingIntent next = PendingIntent.getBroadcast(mContext, 0,
                new Intent(ACTION_SCHEDULE).setPackage(mContext.getPackageName()),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        mAlarms.cancel(next);

        boolean enabled = mPrefs.getBoolean(KEY_SCHEDULE, false);
        int start = mPrefs.getInt(KEY_SCHEDULE_START, DEFAULT_START);
        int end = mPrefs.getInt(KEY_SCHEDULE_END, DEFAULT_END);
        Calendar now = Calendar.getInstance();
        int minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE);
        boolean inWindow = enabled && start != end && inWindow(minute, start, end);
        boolean suspended = mPrefs.getBoolean(KEY_SCHEDULE_SUSPENDED, false);

        if (inWindow && !suspended && isAodOn()) {
            setAod(false);
            mPrefs.edit().putBoolean(KEY_SCHEDULE_SUSPENDED, true).apply();
            Log.i(TAG, "Schedule: AOD off");
        } else if (!inWindow && suspended) {
            setAod(true);
            mPrefs.edit().putBoolean(KEY_SCHEDULE_SUSPENDED, false).apply();
            Log.i(TAG, "Schedule: AOD back on");
        }

        if (enabled && start != end) {
            int target = inWindow ? end : start;
            Calendar at = (Calendar) now.clone();
            at.set(Calendar.HOUR_OF_DAY, target / 60);
            at.set(Calendar.MINUTE, target % 60);
            at.set(Calendar.SECOND, 0);
            at.set(Calendar.MILLISECOND, 0);
            if (!at.after(now)) at.add(Calendar.DAY_OF_MONTH, 1);
            mAlarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
                    at.getTimeInMillis(), next);
        }
    }

    // The window may wrap past midnight (23:00-07:00).
    static boolean inWindow(int minute, int start, int end) {
        return start < end ? minute >= start && minute < end
                : minute >= start || minute < end;
    }

    private void setAod(boolean on) {
        Settings.Secure.putIntForUser(mContext.getContentResolver(),
                Settings.Secure.DOZE_ALWAYS_ON, on ? 1 : 0, UserHandle.USER_CURRENT);
    }

    private static void write(String path, String value) {
        try (FileWriter w = new FileWriter(path)) {
            w.write(value);
        } catch (Exception e) {
            Log.w(TAG, "write " + path + ": " + e.getMessage());
        }
    }
}
