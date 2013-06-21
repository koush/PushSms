package org.cyanogenmod.pushsms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

/**
 * Created by koush on 6/18/13.
 */
public class SmsReceiver extends BroadcastReceiver {
    @Override
    public void onReceive(Context context, Intent intent) {
        intent.setClass(context, Service.class);
        context.startService(intent);
    }
}
