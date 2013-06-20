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
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.test.bencode.BEncodedDictionary;

import org.json.JSONObject;

import java.io.IOException;
import java.lang.reflect.Method;
import java.nio.ByteBuffer;
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
    private Hashtable<String, RegistrationFuture> numberToRegistration = new Hashtable<String, RegistrationFuture>();
    private short smsPort;
    private String registrationId;
    private GoogleCloudMessaging gcm;
    private ISms smsTransport;

    private void registerGcm() {
        new Thread() {
            @Override
            public void run() {
                gcm = GoogleCloudMessaging.getInstance(Service.this);
                try {
                    registrationId = gcm.register("960629859371");

                    RegistrationFuture future = new RegistrationFuture();
                    future.setComplete(registrationId);
                    numberToRegistration.put("2064951490", future);
                } catch (IOException e) {
                    e.printStackTrace();
                }
                Log.i(LOGTAG, "Registration iD: " + registrationId);
            }
        }.start();
    }

    private void registerSmsMiddleware() {
        try {
            Class sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            smsTransport = ISms.Stub.asInterface((IBinder)getService.invoke(null, "isms"));
            smsTransport.registerSmsMiddleware("interceptor", stub);
        }
        catch (Exception e) {
            Log.e(LOGTAG, "register error", e);
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        smsPort = Short.valueOf(getString(R.string.sms_port));

        registerGcm();
        registerSmsMiddleware();
    }

    void sendRegistration(String destAddr, String scAddr, BEncodedDictionary payload) {
        int partsNeeded = (registrationId.length() / 80) + 1;
        payload.put("rl", partsNeeded);

        int targetPartSize = registrationId.length() / partsNeeded;
        for (int i = 0; i < partsNeeded; i++) {
            int partSize = Math.min(registrationId.length(), targetPartSize * (i + 1));

            String part = registrationId.substring(i * targetPartSize, partSize);
            payload.put("r", part);
            payload.put("p", i);

            byte[] bytes = payload.toByteArray();
            SmsManager.getDefault().sendDataMessage(destAddr, scAddr, smsPort, bytes, null, null);
        }
    }

    ISmsMiddleware.Stub stub = new ISmsMiddleware.Stub() {
        @Override
        public boolean onSendText(String destAddr, String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent) throws RemoteException {
            List<PendingIntent> sentIntents = null;
            List<PendingIntent> deliveryIntents = null;
            if (sentIntent != null) {
                sentIntents = new ArrayList<PendingIntent>();
                sentIntents.add(sentIntent);
            }
            if (deliveryIntent != null) {
                deliveryIntents = new ArrayList<PendingIntent>();
                deliveryIntents.add(deliveryIntent);
            }

            ArrayList<String> texts = new ArrayList<String>();
            texts.add(text);
            return onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents);
        }

        @Override
        public boolean onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents) throws RemoteException {
            RegistrationFuture registration = findRegistration(destAddr);
            if (registration == NOT_SUPPORTED)
                return false;

            final GcmText sendText = new GcmText();
            sendText.destAddr = destAddr;
            sendText.scAddr = scAddr;
            sendText.texts.addAll(texts);
            if (registration == null) {
                final RegistrationFuture future = registration = new RegistrationFuture();
                numberToRegistration.put(destAddr, registration);

                // attempt to negotiate a registration id with the other end
                BEncodedDictionary payload = new BEncodedDictionary();
                payload.put("v", 1);
                payload.put("t", "rr");
                payload.put("y", destAddr);

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
                    sendText.send(Service.this, result, sentIntents, deliveryIntents);
                }
            });

            return true;
        }
    };

    private RegistrationFuture findRegistration(String address) {
        for (String number: numberToRegistration.keySet()) {
            if (PhoneNumberUtils.compare(number, address)) {
                return numberToRegistration.get(number);
            }
        }
        return null;
    }

    private void parseRegistration(SmsMessage message, BEncodedDictionary payload) {
        int part = payload.getInt("p");

        // if the requester is the this device, just respond right away.
        if ("rr".equals(payload.getString("t"))) {
            final String destAddr = message.getOriginatingAddress();

            // if the requester is the this device, just respond right away.
            String requester = payload.getString("y");
            if (PhoneNumberUtils.compare(requester, message.getOriginatingAddress())) {
                if (part != 0)
                    return;
                BEncodedDictionary response = new BEncodedDictionary();
                response.put("v", 1);
                response.put("t", "r");
                // let the requester know who we think they are.
                response.put("y", destAddr);
                sendRegistration(destAddr, null, response);
                return;
            }
        }

        RegistrationFuture registration = findRegistration(message.getOriginatingAddress());

        int partsNeeded = payload.getInt("rl");
        // no registration or new registration, let's set up listeners
        if (registration == null || registration.isDone() || registration.start < System.currentTimeMillis() - 300000L) {
            registration = new RegistrationFuture();

            // if registration is being requested, send a response once we have all their parts
            if ("rr".equals(payload.getString("t"))) {
                final String destAddr = message.getOriginatingAddress();

                registration.addCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        BEncodedDictionary response = new BEncodedDictionary();
                        response.put("v", 1);
                        response.put("t", "r");
                        // let the requester know who we think they are.
                        response.put("y", destAddr);
                        sendRegistration(destAddr, null, response);
                    }
                });
            }
            numberToRegistration.put(message.getOriginatingAddress(), registration);
        }

        if (registration.registrationParts == null)
            registration.registrationParts = new String[partsNeeded];

        String registrationPart = payload.getString("r");
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
                BEncodedDictionary payload = BEncodedDictionary.parseDictionary(ByteBuffer.wrap(bytes));
                Log.i(LOGTAG, "Got message; " + payload);
                String type = payload.getString("t");
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
            return START_STICKY;
        }
        else if ("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())) {
            String messageType = gcm.getMessageType(intent);
            Log.i(LOGTAG, "GCM: " + messageType);
            if (GoogleCloudMessaging.MESSAGE_TYPE_SEND_ERROR.equals(messageType)) {
            } else if (GoogleCloudMessaging.MESSAGE_TYPE_DELETED.equals(messageType)) {
            } else {
                try {
                    String data = intent.getStringExtra("bencoded");
                    GcmText gcmText = GcmText.parse(data);
                    if (gcmText != null)
                        smsTransport.synthesizeMessages(gcmText.destAddr, gcmText.scAddr, gcmText.texts, System.currentTimeMillis());
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }

        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
