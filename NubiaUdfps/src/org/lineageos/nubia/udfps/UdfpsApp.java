/*
 * SPDX-FileCopyrightText: The LineageOS Project
 * SPDX-License-Identifier: Apache-2.0
 */

package org.lineageos.nubia.udfps;

import android.app.Application;
import android.os.Process;
import android.os.UserHandle;

public class UdfpsApp extends Application {
    @Override
    public void onCreate() {
        super.onCreate();
        // Persistent system apps start once per boot, for user 0.
        if (UserHandle.myUserId() == UserHandle.USER_SYSTEM
                && Process.myUid() == Process.SYSTEM_UID) {
            new UdfpsController(this).start();
        }
    }
}
