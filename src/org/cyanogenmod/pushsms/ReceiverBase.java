package org.cyanogenmod.pushsms;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;

/**
 * Created by koush on 6/24/13.
 */
public abstract class ReceiverBase extends BroadcastReceiver {
    PowerManager.WakeLock wakeLock;
    protected PowerManager.WakeLock createWakelock(Context context) {
        if (wakeLock != null)
            return wakeLock;
        PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMSDispatcher");
        wakeLock.setReferenceCounted(true);
        return wakeLock;
    }
}
