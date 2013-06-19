package com.koushikdutta.test;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.google.android.gms.gcm.GoogleCloudMessaging;

public class GcmBroadcastReceiver extends BroadcastReceiver {
    static final String TAG = "GCMDemo";
    Context ctx;
    @Override
    public void onReceive(Context context, Intent intent) {
        intent.setClass(context, Service.class);
        context.startService(intent);
//        GoogleCloudMessaging gcm = GoogleCloudMessaging.getInstance(context);
//        ctx = context;
//        String messageType = gcm.getMessageType(intent);
//        if (GoogleCloudMessaging.MESSAGE_TYPE_SEND_ERROR.equals(messageType)) {
//        } else if (GoogleCloudMessaging.MESSAGE_TYPE_DELETED.equals(messageType)) {
//        } else {
//        }
//        setResultCode(Activity.RESULT_OK);
    }
}