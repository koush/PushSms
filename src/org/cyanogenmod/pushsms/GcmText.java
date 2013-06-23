package org.cyanogenmod.pushsms;

import android.app.PendingIntent;
import android.telephony.SmsManager;
import android.util.Log;

import com.google.gson.annotations.SerializedName;
import com.koushikdutta.async.ByteBufferList;

import org.cyanogenmod.pushsms.bencode.BEncodedDictionary;
import org.cyanogenmod.pushsms.bencode.BEncodedList;
import org.cyanogenmod.pushsms.socket.GcmSocket;

import java.util.ArrayList;
import java.util.List;

class GcmText {
    private static final String LOGTAG = "GCMSms";
    String destAddr;
    String scAddr;
    ArrayList<String> texts = new ArrayList<String>();
    boolean multipart;
    List<PendingIntent> sentIntents;
    List<PendingIntent> deliveryIntents;

    public void manageFailure() {
        if (multipart)
            SmsManager.getDefault().sendMultipartTextMessage(
                    destAddr, scAddr, texts,
                    sentIntents != null ? new ArrayList<PendingIntent>(sentIntents) : null,
                    deliveryIntents != null ? new ArrayList<PendingIntent>(deliveryIntents) : null);
        else
            SmsManager.getDefault().sendTextMessage(
                    destAddr, scAddr, texts.get(0),
                    sentIntents != null ? sentIntents.get(0) : null,
                    deliveryIntents != null ? deliveryIntents.get(0) : null);
    }

    public static GcmText parse(GcmSocket gcmSocket, BEncodedDictionary message) {
        try {
            GcmText ret = new GcmText();
            ret.destAddr = gcmSocket.getNumber();
            ret.scAddr = message.getString("sca");
            BEncodedList texts = message.getBEncodedList("ts");
            for (int i = 0; i < texts.size(); i++) {
                ret.texts.add(texts.getString(i));
            }

            return ret;
        }
        catch (Exception e) {
            Log.e(LOGTAG, "Parse exception", e);
            return null;
        }
    }

    public void send(GcmSocket gcmSocket, String id) {
        // serialize the message
        BEncodedDictionary message = new BEncodedDictionary();
        // mark the type as a message
        message.put("t", MessageTypes.MESSAGE);
        // grant an id to acknowledge
        message.put("id", id);
        message.put("sca", scAddr);
        BEncodedList texts = new BEncodedList();
        message.put("ts", texts);
        for (String text: this.texts) {
            texts.add(text);
        }
        gcmSocket.write(new ByteBufferList(message.toByteArray()));
    }
}