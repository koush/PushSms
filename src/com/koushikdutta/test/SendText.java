package com.koushikdutta.test;

import android.app.PendingIntent;
import android.content.Context;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

class SendText {
    private static final String LOGTAG = "GCMSms";
    Context context;
    String destAddr;
    String scAddr;
    String text;
    PendingIntent sentIntent;
    PendingIntent deliveryIntent;
    String registration;

    public void manageFailure() {

    }

    public void send() {
        assert registration != null;

        JsonObject payload = new JsonObject();
        JsonArray regs = new JsonArray();
        regs.add(new JsonPrimitive(registration));
        payload.add("registration_ids", regs);
        JsonObject data = new JsonObject();
        payload.add("data", data);

        JsonObject json = new JsonObject();
        json.addProperty("destAddr", destAddr);
        json.addProperty("scAddr", scAddr);
        json.addProperty("text", text);
        data.addProperty("json", json.toString());

        Ion.with(context)
                .load("https://android.googleapis.com/gcm/send")
                .setHeader("Authorization", "key=AIzaSyCa9bXc1ppgNy9yVrBXYuCihLndXTPbQq4")
                .setJsonObjectBody(payload)
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        Log.i(LOGTAG, "Response from GCM send: " + result);
                        if (sentIntent != null) {
                            try {
                                sentIntent.send();
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    }
                });
    }
}