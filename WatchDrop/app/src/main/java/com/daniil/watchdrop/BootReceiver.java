package com.daniil.watchdrop;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class BootReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        if (!AppPrefs.isReceiveEnabled(context) || !PermissionUtils.hasBluetoothConnect(context)) {
            return;
        }
        Intent service = new Intent(context, BluetoothTransferService.class);
        service.setAction(BluetoothTransferService.ACTION_START_LISTENER);
        context.startForegroundService(service);
    }
}
