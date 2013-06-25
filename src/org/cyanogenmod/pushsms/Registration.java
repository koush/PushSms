package org.cyanogenmod.pushsms;

import android.util.Base64;

import org.cyanogenmod.pushsms.bencode.BEncodedDictionary;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.KeyFactory;
import java.security.PublicKey;
import java.security.spec.RSAPublicKeySpec;

/**
 * Created by koush on 6/22/13.
 */
public class Registration {
    public static final int STATE_REGISTERED = 0;
    public static final int STATE_UNREGISTERED = 1;
    public static final int STATE_INVALID = 2;
    public static final int STATE_NEEDS_REFRESH = 3;

    public PublicKey remotePublicKey;
    private long date = System.currentTimeMillis();
    public String registrationId;
    public String endpoint;
    public int remoteSequenceNumber;
    public int localSequenceNumber;
    public int state = STATE_REGISTERED;

    public boolean isRegistered() {
        return state == STATE_REGISTERED && !isRefresh();
    }

    public boolean isUnregistered() {
        return state == STATE_UNREGISTERED && !isRefresh();
    }

    public boolean isInvalid() {
        return state == STATE_INVALID && !isRefresh();
    }

    public void register() {
        date = System.currentTimeMillis();
        state = STATE_REGISTERED;
    }

    public void invalidate() {
        state = STATE_INVALID;
    }

    public void unregister() {
        state = STATE_UNREGISTERED;
    }

    public void refresh() {
        state = STATE_NEEDS_REFRESH;
    }

    // force a refresh every 3 days, regardless of status
    public static final long REFRESH_INTERVAL = 3L * 24L * 60L * 60L * 1000L;
    public boolean isRefresh() {
        return state == STATE_NEEDS_REFRESH || date < System.currentTimeMillis() - REFRESH_INTERVAL;
    }

    static Registration parse(String data) {
        try {
            byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
            BEncodedDictionary dict = BEncodedDictionary.parseDictionary(ByteBuffer.wrap(bytes));

            Registration ret = new Registration();
            ret.date = dict.getLong("date");
            ret.registrationId = dict.getString("registration_id");
            ret.endpoint = dict.getString("endpoint");
            ret.localSequenceNumber = dict.getInt("local_sequence_number");
            ret.remoteSequenceNumber = dict.getInt("remote_sequence_number");
            ret.state = dict.getInt("state");

            byte[] publicModulus = dict.getBytes("public_modulus");
            byte[] publicExponent = dict.getBytes("public_exponent");
            if (publicModulus != null && publicExponent != null) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                RSAPublicKeySpec rsaPublicKeySpec = new RSAPublicKeySpec(
                new BigInteger(publicModulus),
                new BigInteger(publicExponent));

                ret.remotePublicKey = keyFactory.generatePublic(rsaPublicKeySpec);
            }

            return ret;
        }
        catch (Exception e) {
            return null;
        }
    }

    public String encode() {
        try {
            BEncodedDictionary dict = new BEncodedDictionary();

            if (remotePublicKey != null) {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                RSAPublicKeySpec publicKeySpec = keyFactory.getKeySpec(remotePublicKey, RSAPublicKeySpec.class);
                dict.put("public_modulus", publicKeySpec.getModulus().toByteArray());
                dict.put("public_exponent", publicKeySpec.getPublicExponent().toByteArray());
            }
            dict.put("state", state);
            dict.put("date", date);
            dict.put("endpoint", endpoint);
            dict.put("registration_id", registrationId);
            dict.put("local_sequence_number", localSequenceNumber);
            dict.put("remote_sequence_number", remoteSequenceNumber);

            return Base64.encodeToString(dict.toByteArray(), Base64.NO_WRAP);
        }
        catch (Exception e) {
            return "";
        }
    }
}
