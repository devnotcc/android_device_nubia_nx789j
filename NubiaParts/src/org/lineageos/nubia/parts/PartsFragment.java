/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.parts;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.TimePickerDialog;
import android.content.SharedPreferences;
import android.os.HardwarePropertiesManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.text.format.DateFormat;
import android.widget.Toast;

import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceManager;

import java.util.Calendar;

public class PartsFragment extends PreferenceFragmentCompat {
    private static final long REFRESH_MS = 1500;

    private PartsController mController;
    private Preference mSliderState;
    private Preference mFanStatus;
    private Preference mPerfStatus;
    private Preference mChargeStatus;
    private Preference mLinuxBoot;
    private HardwarePropertiesManager mHardware;
    private HandlerThread mThread;
    private Handler mWorker;

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        setPreferencesFromResource(R.xml.parts_settings, rootKey);
        mController = PartsController.get(requireContext());
        mSliderState = findPreference("slider_state");
        mFanStatus = findPreference("fan_status");
        mPerfStatus = findPreference("perf_status");
        mChargeStatus = findPreference("charge_status");
        mLinuxBoot = findPreference("linux_boot");
        mLinuxBoot.setOnPreferenceClickListener(p -> {
            confirmLinuxBoot();
            return true;
        });
        mHardware = requireContext().getSystemService(HardwarePropertiesManager.class);
        if (!AmbientController.get(requireContext()).hasPickupSensor()) {
            findPreference(AmbientController.KEY_LIFT).setVisible(false);
        }
        setupTime(AmbientController.KEY_SCHEDULE_START, AmbientController.DEFAULT_START);
        setupTime(AmbientController.KEY_SCHEDULE_END, AmbientController.DEFAULT_END);
    }

    // Schedule times are stored as minutes after midnight (int prefs), so they are
    // plain Preferences with a time picker rather than persisted preference values.
    private void setupTime(String key, int def) {
        Preference pref = findPreference(key);
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(requireContext());
        pref.setSummary(formatTime(prefs.getInt(key, def)));
        pref.setOnPreferenceClickListener(p -> {
            int minutes = prefs.getInt(key, def);
            new TimePickerDialog(requireContext(), (view, hour, minute) -> {
                int value = hour * 60 + minute;
                prefs.edit().putInt(key, value).apply();
                p.setSummary(formatTime(value));
            }, minutes / 60, minutes % 60, DateFormat.is24HourFormat(requireContext())).show();
            return true;
        });
    }

    private String formatTime(int minutes) {
        Calendar c = Calendar.getInstance();
        c.set(Calendar.HOUR_OF_DAY, minutes / 60);
        c.set(Calendar.MINUTE, minutes % 60);
        return DateFormat.getTimeFormat(requireContext()).format(c.getTime());
    }

    @Override
    public void onResume() {
        super.onResume();
        mThread = new HandlerThread("NubiaPartsUi");
        mThread.start();
        mWorker = new Handler(mThread.getLooper());
        mWorker.post(mRefresh);
        mWorker.post(this::refreshLinux);
    }

    // Debian: the button is only enabled when the root helper reports a complete install
    // whose boot hook has run, so a restart cannot end up in a Debian that is not there.
    private void refreshLinux() {
        final LinuxBoot.State state = LinuxBoot.check();
        final Activity activity = getActivity();
        if (activity == null) return;
        activity.runOnUiThread(() -> {
            mLinuxBoot.setEnabled(state.ready());
            if (state.status == null) {
                mLinuxBoot.setSummary(R.string.linux_unknown);
            } else if (state.ready()) {
                mLinuxBoot.setSummary(state.linuxByDefault()
                        ? R.string.linux_ready_default : R.string.linux_ready);
            } else if (LinuxBoot.NOHOOK.equals(state.status)) {
                mLinuxBoot.setSummary(R.string.linux_nohook);
            } else {
                mLinuxBoot.setSummary(R.string.linux_missing);
            }
        });
    }

    private void confirmLinuxBoot() {
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.linux_confirm_title)
                .setMessage(R.string.linux_confirm_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.linux_confirm_ok, (d, w) -> {
                    mLinuxBoot.setEnabled(false);
                    final Activity activity = requireActivity();
                    mWorker.post(() -> {
                        // checks again right before arming; reboots on success
                        if (!LinuxBoot.restartIntoDebian(activity)) {
                            activity.runOnUiThread(() -> Toast.makeText(activity,
                                    R.string.linux_failed, Toast.LENGTH_LONG).show());
                            refreshLinux();
                        }
                    });
                })
                .show();
    }

    @Override
    public void onPause() {
        super.onPause();
        mThread.quitSafely();
    }

    private float readSkinTemp() {
        try {
            float[] temps = mHardware.getDeviceTemperatures(
                    HardwarePropertiesManager.DEVICE_TEMPERATURE_SKIN,
                    HardwarePropertiesManager.TEMPERATURE_CURRENT);
            float max = 0;
            for (float t : temps) {
                max = Math.max(max, t);
            }
            return max;
        } catch (Exception e) {
            return 0;
        }
    }

    // The RPM read blocks while the driver samples, so it stays off the main thread.
    private final Runnable mRefresh = new Runnable() {
        @Override
        public void run() {
            final boolean slider = mController.isSliderOn();
            final int level = mController.getFanLevel();
            final int rpm = level > 0 ? mController.readRpm() : 0;
            final float skin = readSkinTemp();
            final float battery = mController.getBatteryTemp() / 10f;
            final boolean batteryHot = mController.isBatteryHot();
            final boolean separation = mController.isSeparationActive();
            final int batteryMa = mController.getBatteryCurrentMa();
            final Activity activity = getActivity();
            if (activity != null) {
                activity.runOnUiThread(() -> {
                    mSliderState.setTitle(slider
                            ? R.string.slider_state_on : R.string.slider_state_off);
                    mFanStatus.setSummary(level > 0
                            ? getString(R.string.fan_status_on, level, rpm)
                            : getString(R.string.fan_status_off));
                    mPerfStatus.setTitle(batteryHot
                            ? R.string.perf_status_battery_hot : R.string.perf_status_title);
                    mPerfStatus.setSummary(getString(R.string.perf_status_temps, skin, battery));
                    mChargeStatus.setTitle(separation
                            ? R.string.charge_status_active : R.string.charge_status_inactive);
                    mChargeStatus.setSummary(getString(R.string.charge_status_current, batteryMa));
                });
            }
            mWorker.postDelayed(this, REFRESH_MS);
        }
    };
}
