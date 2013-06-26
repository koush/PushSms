package org.cyanogenmod.pushsms;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.PowerManager;
import android.provider.ContactsContract;
import android.util.Base64;

import java.security.MessageDigest;
import java.util.HashSet;

/**
 * Created by koush on 6/23/13.
 */
public class Helper {
    static HashSet<String> getEmailHashesForNumber(Context context, String number) {
        HashSet<String> emailHash = new HashSet<String>();
        Uri uri = Uri.withAppendedPath(ContactsContract.PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number));
        Cursor c = context.getContentResolver().query(uri, new String[]{ContactsContract.PhoneLookup._ID }, null, null, null);

        while (c != null && c.moveToNext()) {
            Cursor emailCursor = context.getContentResolver().query(ContactsContract.CommonDataKinds.Email.CONTENT_URI,
                    new String[] {ContactsContract.CommonDataKinds.Email.ADDRESS },
                    ContactsContract.CommonDataKinds.Email.CONTACT_ID + "=?",
                    new String[] { String.valueOf(c.getLong(c.getColumnIndex(ContactsContract.PhoneLookup._ID)))},
                    null
            );
            while (emailCursor != null && emailCursor.moveToNext()) {
                try {
                    MessageDigest digest = MessageDigest.getInstance("MD5");
                    String email = emailCursor.getString(emailCursor.getColumnIndex(ContactsContract.CommonDataKinds.Email.ADDRESS));
                    String authority = Base64.encodeToString(digest.digest(("email:" + email).getBytes()), Base64.NO_WRAP);
                    emailHash.add(authority);
                }
                catch (Exception e) {
                }
            }
            if (emailCursor != null)
                emailCursor.close();
        }
        if (c != null)
            c.close();

        return emailHash;
    }

    static PowerManager.WakeLock wakeLock;
    static WifiManager.WifiLock wifiLock;
    public static void acquireTemporaryWakelocks(Context context, long timeout) {
        if (wakeLock == null) {
            PowerManager pm = (PowerManager)context.getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ACQUIRE_CAUSES_WAKEUP | PowerManager.ON_AFTER_RELEASE, "PushSMS");
        }
        wakeLock.acquire(timeout);

        if (wifiLock == null) {
            WifiManager wm = (WifiManager)context.getSystemService(Context.WIFI_SERVICE);
            wifiLock = wm.createWifiLock("PushSMS");
        }


        wifiLock.acquire();
        new Handler().postDelayed(new Runnable() {
            @Override
            public void run() {
                wifiLock.release();
            }
        }, timeout);
    }
}
