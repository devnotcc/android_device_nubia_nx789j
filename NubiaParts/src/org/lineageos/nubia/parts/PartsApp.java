/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.parts;

import android.app.Application;
import android.os.UserHandle;

/** Persistent process: owns the slider/fan controller and the front-camera ring. */
public class PartsApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM) {
            PartsController.get(this).start();
            new UdcRingController(this).start();
            AmbientController.get(this).start();
        }
    }
}
