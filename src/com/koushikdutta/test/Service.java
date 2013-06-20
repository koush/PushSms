package com.koushikdutta.test;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;
import android.util.Log;

import com.android.internal.telephony.ISms;
import com.android.internal.telephony.ISmsMiddleware;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.SimpleFuture;
import com.koushikdutta.ion.Ion;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;

/**
 * Created by koush on 6/18/13.
 */
public class Service extends android.app.Service {
    private static final String LOGTAG = "INTERCEPTOR";
    private static final RegistrationFuture NOT_SUPPORTED = new RegistrationFuture() {
        {
            setComplete(new Exception("not supported"));
        }
    };

    private final Handler handler = new Handler();

    static class RegistrationFuture extends SimpleFuture<String> {
        ArrayList<FutureCallback<String>> callbacks = new ArrayList<FutureCallback<String>>();
        long start = System.currentTimeMillis();
        String[] registrationParts;

        FutureCallback<String> callback = new FutureCallback<String>() {
            @Override
            public void onCompleted(Exception e, String result) {
                ArrayList<FutureCallback<String>> cbs = callbacks;
                callbacks = new ArrayList<FutureCallback<String>>();
                for (FutureCallback<String> cb: cbs) {
                    cb.onCompleted(e, result);
                }
            }
        };

        public RegistrationFuture() {
            setCallback(callback);
        }

        void addCallback(FutureCallback<String> cb) {
            callbacks.add(cb);
            setCallback(callback);
        }
    }

    Hashtable<String, RegistrationFuture> numberToRegistration = new Hashtable<String, RegistrationFuture>();


    private class SendText {
        String destAddr;
        String scAddr;
        String text;
        PendingIntent sentIntent;
        PendingIntent deliveryIntent;

        String registration;

        private void manageFailure() {

        }

        private void send() {
            assert registration != null;

            JsonObject payload = new JsonObject();
            JsonArray regs = new JsonArray();
            regs.add(new JsonPrimitive(registrationId));
            payload.add("registration_ids", regs);
            JsonObject data = new JsonObject();
            data.addProperty("foo", "bar");
            payload.add("data", data);

            Ion.with(Service.this)
                    .load("https://android.googleapis.com/gcm/send")
                    .setHeader("Authorization", "key=AIzaSyCa9bXc1ppgNy9yVrBXYuCihLndXTPbQq4")
                    .setJsonObjectBody(payload)
                    .asString().setCallback(new FutureCallback<String>() {
                @Override
                public void onCompleted(Exception e, String result) {
                    Log.i(LOGTAG, "Response from GCM send: " + result);
                }
            });
        }
    }

    short port;
    String registrationId;
    GoogleCloudMessaging gcm;
    @Override
    public void onCreate() {
        super.onCreate();

        port = Short.valueOf(getString(R.string.sms_port));

        try {
            Class sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            ISms transport = ISms.Stub.asInterface((IBinder)getService.invoke(null, "isms"));
            transport.registerSmsMiddleware("interceptor", stub);

            transport.synthesizeMessage("2064228017", "injector", "hello world", System.currentTimeMillis());
        }
        catch (Exception e) {
            Log.e(LOGTAG, "register error", e);
        }

        new Thread() {
            @Override
            public void run() {
                gcm = GoogleCloudMessaging.getInstance(Service.this);
                try {
                    registrationId = gcm.register("960629859371");
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Log.i(LOGTAG, "Registration iD: " + registrationId);

                JsonObject payload = new JsonObject();
                JsonArray regs = new JsonArray();
                regs.add(new JsonPrimitive(registrationId));
                payload.add("registration_ids", regs);
                JsonObject data = new JsonObject();
                data.addProperty("foo", "bar");
                payload.add("data", data);

                Ion.with(Service.this)
                .load("https://android.googleapis.com/gcm/send")
                .setHeader("Authorization", "key=AIzaSyCa9bXc1ppgNy9yVrBXYuCihLndXTPbQq4")
                .setJsonObjectBody(payload)
                .asString().setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        Log.i(LOGTAG, "Response from GCM send: " + result);
                    }
                });
            }
        }.start();
    }

    void sendRegistration(String destAddr, String scAddr, JsonObject payload) {

        int partsNeeded = (registrationId.length() / 80) + 1;
        payload.addProperty("rl", partsNeeded);

        int targetPartSize = registrationId.length() / partsNeeded;
        for (int i = 0; i < partsNeeded; i++) {
            int partSize = Math.min(registrationId.length(), targetPartSize * (i + 1));

            String part = registrationId.substring(i * targetPartSize, partSize);
            payload.addProperty("r", part);
            payload.addProperty("p", i);

            byte[] bytes = payload.toString().getBytes();
            SmsManager.getDefault().sendDataMessage(destAddr, scAddr, port, bytes, null, null);
        }
    }

    ISmsMiddleware.Stub stub = new ISmsMiddleware.Stub() {
        @Override
        public boolean onSendText(String destAddr, String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent) throws RemoteException {
            Log.i(LOGTAG, "Intercepted text: " + text);

            RegistrationFuture registration;
            if ((registration = numberToRegistration.get(destAddr)) == NOT_SUPPORTED) {
                Log.i(LOGTAG, "Number does not support gcm protocol");
                return false;
            }

            final SendText sendText = new SendText();
            sendText.destAddr = destAddr;
            sendText.scAddr = scAddr;
            sendText.text = text;
            sendText.sentIntent = sentIntent;
            sendText.deliveryIntent = deliveryIntent;
            if (registration == null) {
                final RegistrationFuture future = registration = new RegistrationFuture();
                numberToRegistration.put(destAddr, registration);

                // attempt to negotiate a registration id with the other end
                JsonObject payload = new JsonObject();
                payload.addProperty("v", 1);
                payload.addProperty("t", "rr");
                payload.addProperty("y", destAddr);

                sendRegistration(destAddr, scAddr, payload);

                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (!future.isDone())
                            future.setComplete(new Exception("no response"));
                    }
                }, 300000);
            }
            registration.addCallback(new FutureCallback<String>() {
                @Override
                public void onCompleted(Exception e, String result) {
                    if (e != null ) {
                        Log.e(LOGTAG, "registration exchange failed", e);
                        numberToRegistration.put(sendText.destAddr, NOT_SUPPORTED);
                        sendText.manageFailure();
                        return;
                    }

                    Log.i(LOGTAG, "registration exchange succeeded");
                    sendText.registration = result;
                    sendText.send();
                }
            });

            return true;
        }

        @Override
        public boolean onSendMultipartText(String destinationAddress, String scAddress, List<String> parts, List<PendingIntent> sentIntents, List<PendingIntent> deliveryIntents) throws RemoteException {
            for (String text: parts) {
                Log.i(LOGTAG, "Intercepted text: " + text);
            }
            return false;
        }
    };

    private void parseRegistration(SmsMessage message, JsonObject payload) {
        int part = payload.get("p").getAsInt();

        // if the requester is the this device, just respond right away.
        if ("rr".equals(payload.get("t").getAsString())) {
            final String destAddr = message.getOriginatingAddress();

            // if the requester is the this device, just respond right away.
            String requester = payload.get("y").getAsString();
            if (PhoneNumberUtils.compare(requester, message.getOriginatingAddress())) {
                if (part != 0)
                    return;
                JsonObject response = new JsonObject();
                response.addProperty("v", 1);
                response.addProperty("t", "r");
                // let the requester know who we think they are.
                response.addProperty("y", destAddr);
                sendRegistration(destAddr, null, response);
                return;
            }
        }

        RegistrationFuture registration = null;

        for (String number: numberToRegistration.keySet()) {
            if (PhoneNumberUtils.compare(number, message.getOriginatingAddress())) {
                registration = numberToRegistration.get(number);
                break;
            }
        }

        int partsNeeded = payload.get("rl").getAsInt();
        // no registration or new registration, let's set up listeners
        if (registration == null || registration.isDone() || registration.start < System.currentTimeMillis() - 300000L) {
            registration = new RegistrationFuture();

            // if registration is being requested, send a response once we have all their parts
            if ("rr".equals(payload.get("t").getAsString())) {
                final String destAddr = message.getOriginatingAddress();

                registration.addCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        JsonObject response = new JsonObject();
                        response.addProperty("v", 1);
                        response.addProperty("t", "r");
                        // let the requester know who we think they are.
                        response.addProperty("y", destAddr);
                        sendRegistration(destAddr, null, response);
                    }
                });
            }
            numberToRegistration.put(message.getOriginatingAddress(), registration);
        }

        if (registration.registrationParts == null)
            registration.registrationParts = new String[partsNeeded];

        String registrationPart = payload.get("r").getAsString();
        registration.registrationParts[part] = registrationPart;

        String fullRegistration = "";
        for (String regPart: registration.registrationParts) {
            if (regPart == null)
                return;
            fullRegistration += regPart;
        }

        // complete!
        registration.setComplete(fullRegistration);
    }

    private void handleDataMessage(Intent intent) {
        try {
            Bundle bundle = intent.getExtras();
            Object[] pdusObj = (Object[]) bundle.get("pdus");
            for (int i = 0; i < pdusObj.length; i++) {
                SmsMessage message = SmsMessage.createFromPdu((byte[]) pdusObj[i]);
                byte[] bytes = message.getUserData();
                JsonObject payload = new JsonParser().parse(new String(bytes)).getAsJsonObject();
                Log.i(LOGTAG, "Got message; " + payload);
                String type = payload.get("t").getAsString();
                if ("rr".equals(type) || "r".equals(type)) {
                    parseRegistration(message, payload);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if ("android.intent.action.DATA_SMS_RECEIVED".equals(intent.getAction())) {
            new Thread() {
                @Override
                public void run() {
                    handleDataMessage(intent);
                }
            }.start();
            return START_STICKY;
        }
        else if ("android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            Object[] pdus = (Object[]) intent.getSerializableExtra("pdus");
            for (Object pdu: pdus) {
                byte[] bytes = (byte[])pdu;
                try {
                    JSONObject json = new JSONObject(new String(bytes));

                    Class c = Class.forName("com.android.internal.telephony.SyntheticSmsMessage");
                    Method m = c.getMethod("isSyntheticPdu", byte[].class);
                    boolean syn = (Boolean)m.invoke(null, bytes);
                    Log.i(LOGTAG, "isSyn" + syn);
                }
                catch (Exception e) {
                    e.printStackTrace();;
                }
                SmsMessage message = SmsMessage.createFromPdu(bytes);
                System.out.println(message);
            }
            return START_STICKY;
        }
        else if ("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())) {
            String messageType = gcm.getMessageType(intent);
            Log.i(LOGTAG, "GCM: " + messageType);
            if (GoogleCloudMessaging.MESSAGE_TYPE_SEND_ERROR.equals(messageType)) {
            } else if (GoogleCloudMessaging.MESSAGE_TYPE_DELETED.equals(messageType)) {
            } else {
                String data = intent.getStringExtra("foo");
                Log.i(LOGTAG, "foo: " + data);
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
