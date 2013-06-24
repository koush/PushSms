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

    public GcmConnectionManager(Context context, PrivateKey privateKey, String gcmApiKey) {
        this.context = context;
        this.privateKey = privateKey;
        this.gcmApiKey = gcmApiKey;
    }

    public GcmSocket findGcmSocket(Registration registration, String from) {
        GcmSocket gcmSocket = findGcmSocket(registration.endpoint);
        if (gcmSocket == null)
            return null;
        gcmSocket.from = from;
        return gcmSocket;
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
        GcmSocket ret = new GcmSocket(context, Ion.getDefault(context).getServer(), privateKey, from, gcmApiKey, registration);
        gcmSockets.add(ret);
        return ret;
    }

    ArrayList<GcmSocket> gcmSockets = new ArrayList<GcmSocket>();
}
