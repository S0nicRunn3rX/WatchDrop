package com.daniil.watchdrop;

import android.content.Context;
import android.content.SharedPreferences;

final class AppPrefs {
    private static final String PREFS = "watchdrop";
    private static final String KEY_RECEIVE_ENABLED = "receive_enabled";
    private static final String KEY_PREFERRED_DEVICE = "preferred_device";

    private AppPrefs() {}

    static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    static boolean isReceiveEnabled(Context context) {
        return prefs(context).getBoolean(KEY_RECEIVE_ENABLED, true);
    }

    static void setReceiveEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_RECEIVE_ENABLED, enabled).apply();
    }

    static String getPreferredDevice(Context context) {
        return prefs(context).getString(KEY_PREFERRED_DEVICE, null);
    }

    static void setPreferredDevice(Context context, String address) {
        prefs(context).edit().putString(KEY_PREFERRED_DEVICE, address).apply();
    }
}
