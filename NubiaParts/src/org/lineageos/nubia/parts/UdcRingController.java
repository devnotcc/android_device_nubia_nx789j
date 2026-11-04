/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.parts;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemProperties;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;

import java.util.HashSet;
import java.util.Set;

/**
 * Black ring over the under-display front camera while a front camera is open. Stock draws
 * it from SystemUI (com.zte.feature.fake.FakeNotchFeature, "front camera work"); without it
 * whatever is on screen above the sensor shines into the lens.
 *
 * Geometry in physical pixels of the 1216x2688 panel, "centerX,centerY,radius", from
 * persist.sys.nubia.udc_ring (live tuning) or ro.nubia.udc_ring (build default).
 * persist.sys.nubia.udc_ring=off disables the ring.
 */
final class UdcRingController {
    private static final String TAG = "NubiaUdcRing";
    private static final String PROP = "persist.sys.nubia.udc_ring";
    private static final String DEFAULT_PROP = "ro.nubia.udc_ring";
    private static final String FALLBACK = "608,75,34"; // tuned on the device 2026-10-18

    private final Context mContext;
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private final WindowManager mWindowManager;
    private final CameraManager mCameraManager;
    private final Set<String> mFrontIds = new HashSet<>();
    private final Set<String> mOpenFront = new HashSet<>();
    private RingView mView;

    UdcRingController(Context context) {
        mContext = context;
        mWindowManager = context.getSystemService(WindowManager.class);
        mCameraManager = context.getSystemService(CameraManager.class);
    }

    void start() {
        try {
            for (String id : mCameraManager.getCameraIdListNoLazy()) {
                Integer facing = mCameraManager.getCameraCharacteristics(id)
                        .get(CameraCharacteristics.LENS_FACING);
                if (facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT) {
                    mFrontIds.add(id);
                }
            }
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "camera enumeration failed", e);
        }
        Log.i(TAG, "front cameras " + mFrontIds);
        mCameraManager.registerAvailabilityCallback(mCallback, mHandler);
    }

    private final CameraManager.AvailabilityCallback mCallback =
            new CameraManager.AvailabilityCallback() {
        @Override
        public void onCameraAvailable(String id) {
            if (mOpenFront.remove(id)) update();
        }

        @Override
        public void onCameraUnavailable(String id) {
            // Unavailable == opened by some client. Ids exposed only to privileged camera
            // apps are not in the list above, so check them when they show up.
            if (!mFrontIds.contains(id) && isFront(id)) mFrontIds.add(id);
            if (mFrontIds.contains(id) && mOpenFront.add(id)) update();
        }
    };

    private boolean isFront(String id) {
        try {
            Integer facing = mCameraManager.getCameraCharacteristics(id)
                    .get(CameraCharacteristics.LENS_FACING);
            return facing != null && facing == CameraCharacteristics.LENS_FACING_FRONT;
        } catch (CameraAccessException | RuntimeException e) {
            return false;
        }
    }

    private int[] geometry() {
        String spec = SystemProperties.get(PROP, SystemProperties.get(DEFAULT_PROP, FALLBACK));
        if ("off".equals(spec)) return null;
        String[] p = spec.split(",");
        try {
            return new int[] {Integer.parseInt(p[0].trim()), Integer.parseInt(p[1].trim()),
                    Integer.parseInt(p[2].trim())};
        } catch (RuntimeException e) {
            Log.w(TAG, "bad " + PROP + "=" + spec);
            return null;
        }
    }

    private void update() {
        if (!mOpenFront.isEmpty()) show(); else hide();
    }

    private void show() {
        int[] g = geometry();
        if (g == null) {
            hide();
            return;
        }
        int size = g[2] * 2;
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(size, size,
                WindowManager.LayoutParams.TYPE_SECURE_SYSTEM_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.LEFT;
        lp.x = g[0] - g[2];
        lp.y = g[1] - g[2];
        lp.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        lp.privateFlags |= WindowManager.LayoutParams.SYSTEM_FLAG_SHOW_FOR_ALL_USERS
                | WindowManager.LayoutParams.PRIVATE_FLAG_IS_ROUNDED_CORNERS_OVERLAY;
        lp.setFitInsetsTypes(0);
        lp.setTitle("NubiaUdcRing");
        try {
            if (mView == null) {
                mView = new RingView(mContext);
                mWindowManager.addView(mView, lp);
            } else {
                mWindowManager.updateViewLayout(mView, lp);
            }
        } catch (RuntimeException e) {
            Log.w(TAG, "show failed", e);
            mView = null;
        }
    }

    private void hide() {
        if (mView == null) return;
        try {
            mWindowManager.removeView(mView);
        } catch (RuntimeException e) {
            Log.w(TAG, "hide failed", e);
        }
        mView = null;
    }

    private static final class RingView extends View {
        private final Paint mPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

        RingView(Context context) {
            super(context);
            mPaint.setColor(0xff000000);
            setForceDarkAllowed(false);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float r = Math.min(getWidth(), getHeight()) / 2f;
            canvas.drawCircle(getWidth() / 2f, getHeight() / 2f, r, mPaint);
        }
    }
}
