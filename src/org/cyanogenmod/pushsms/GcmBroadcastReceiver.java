package org.cyanogenmod.pushsms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GcmBroadcastReceiver extends ReceiverBase {
    @Override
    public void onReceive(Context context, Intent intent) {
        createWakelock(context).acquire(10000);
        intent.setClass(context, MiddlewareService.class);
        context.startService(intent);
    }
}