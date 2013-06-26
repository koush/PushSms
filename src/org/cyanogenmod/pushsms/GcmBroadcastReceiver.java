package org.cyanogenmod.pushsms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

public class GcmBroadcastReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        Helper.acquireTemporaryWakelocks(context, 10000);
        intent.setClass(context, MiddlewareService.class);
        context.startService(intent);
    }
}