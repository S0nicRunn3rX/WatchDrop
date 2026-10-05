package com.daniil.watchdrop;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

final class PermissionUtils {
    private PermissionUtils() {}

    static boolean hasBluetoothConnect(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S
                || context.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    static boolean hasNotificationPermission(Context context) {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                == PackageManager.PERMISSION_GRANTED;
    }
}
