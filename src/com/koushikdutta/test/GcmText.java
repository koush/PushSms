package com.koushikdutta.test;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;
import com.koushikdutta.test.bencode.BEncodedDictionary;
import com.koushikdutta.test.bencode.BEncodedList;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

class GcmText {
    private static final String LOGTAG = "GCMSms";
    String destAddr;
    String scAddr;
    ArrayList<String> texts = new ArrayList<String>();

    public void manageFailure() {

    }

    public static GcmText parse(String data) {
        try {
            BEncodedDictionary bencoded = BEncodedDictionary.parseDictionary(ByteBuffer.wrap(data.getBytes()));

            GcmText ret = new GcmText();
            ret.destAddr = bencoded.getString("destAddr");
            ret.scAddr = bencoded.getString("scAddr");
            BEncodedList texts = bencoded.getBEncodedList("texts");
            for (int i = 0; i < texts.size(); i++) {
                ret.texts.add(texts.getString(i));
            }

            return ret;
        }
        catch (Exception e) {
            return null;
        }
    }

    public void send(Context context, String registration, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents) {
        assert registration != null;

        JsonObject payload = new JsonObject();
        JsonArray regs = new JsonArray();
        regs.add(new JsonPrimitive(registration));
        payload.add("registration_ids", regs);
        JsonObject data = new JsonObject();
        payload.add("data", data);

        BEncodedDictionary bencoded = new BEncodedDictionary();
        bencoded.put("destAddr", destAddr);
        if (scAddr != null)
            bencoded.put("scAddr", scAddr);
        BEncodedList texts = new BEncodedList();
        bencoded.put("texts", texts);
        for (String text: this.texts) {
            texts.add(text);
        }

        data.addProperty("bencoded", new String(bencoded.toByteArray()));

        Ion.with(context)
                .load("https://android.googleapis.com/gcm/send")
                .setHeader("Authorization", "key=AIzaSyCa9bXc1ppgNy9yVrBXYuCihLndXTPbQq4")
                .setJsonObjectBody(payload)
                .asString()
                .setCallback(new FutureCallback<String>() {
                    @Override
                    public void onCompleted(Exception e, String result) {
                        Log.i(LOGTAG, "Response from GCM send: " + result);
                        if (sentIntents != null) {
                            try {
                                for (PendingIntent sentIntent: sentIntents) {
                                    sentIntent.send(Activity.RESULT_OK);
                                }
                            } catch (Exception ex) {
                                ex.printStackTrace();
                            }
                        }
                    }
                });
    }
}