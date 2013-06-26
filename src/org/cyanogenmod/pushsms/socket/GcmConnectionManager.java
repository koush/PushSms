package org.cyanogenmod.pushsms.socket;

import android.content.Context;
import android.telephony.PhoneNumberUtils;

import com.koushikdutta.ion.Ion;

import org.cyanogenmod.pushsms.Registration;

import java.security.PrivateKey;
import java.util.ArrayList;

/**
 * Created by koush on 6/23/13.
 */
public class GcmConnectionManager {
    Context context;
    PrivateKey privateKey;
    String gcmApiKey;
    String from;

    public void setFrom(String from) {
        this.from = from;
    }

    public String getFrom() {
        return  from;
    }

    public String getGcmApiKey() {
        return gcmApiKey;
    }

    public void setGcmApiKey(String gcmApiKey) {
        this.gcmApiKey = gcmApiKey;
    }

    public GcmConnectionManager(Context context, PrivateKey privateKey, String gcmApiKey, String from) {
        this.context = context;
        this.privateKey = privateKey;
        this.gcmApiKey = gcmApiKey;
        this.from = from;
    }

    public GcmSocket findGcmSocket(Registration registration) {
        return findGcmSocket(registration.endpoint);
    }

    public GcmSocket findGcmSocket(String number) {
        for (GcmSocket gcmSocket: gcmSockets) {
            if (PhoneNumberUtils.compare(context, number, gcmSocket.getNumber())) {
                return gcmSocket;
            }
        }

        return null;
    }

    public void remove(String number) {
        GcmSocket gcmSocket = findGcmSocket(number);
        if (gcmSocket != null)
            gcmSockets.remove(gcmSocket);
    }

    public GcmSocket createGcmSocket(Registration registration, String from) {
        GcmSocket ret = new GcmSocket(context, Ion.getDefault(context).getServer(), this, registration);
        gcmSockets.add(ret);
        return ret;
    }

    ArrayList<GcmSocket> gcmSockets = new ArrayList<GcmSocket>();
}
