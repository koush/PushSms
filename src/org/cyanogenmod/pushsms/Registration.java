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
class Registration {
    PublicKey remotePublicKey;
    long date;
    String registrationId;
    String endpoint;
    int remoteSequenceNumber;
    int localSequenceNumber;

    public boolean isRegistered() {
        return date > 0;
    }

    static Registration parse(String data) {
        try {
            byte[] bytes = Base64.decode(data, Base64.NO_WRAP);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            BEncodedDictionary dict = BEncodedDictionary.parseDictionary(ByteBuffer.wrap(bytes));

            Registration ret = new Registration();
            ret.date = dict.getLong("date");
            ret.registrationId = dict.getString("registration_id");
            ret.endpoint = dict.getString("endpoint");

            RSAPublicKeySpec rsaPublicKeySpec = new RSAPublicKeySpec(
                    new BigInteger(dict.getBytes("public_modulus")),
                    new BigInteger(dict.getBytes("public_exponent")));

            ret.remotePublicKey = keyFactory.generatePublic(rsaPublicKeySpec);

            return ret;

        }
        catch (Exception e) {
            return null;
        }

    }

    public String encode() {
        try {
            BEncodedDictionary dict = new BEncodedDictionary();

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");

            RSAPublicKeySpec publicKeySpec = keyFactory.getKeySpec(remotePublicKey, RSAPublicKeySpec.class);
            dict.put("public_modulus", publicKeySpec.getModulus().toByteArray());
            dict.put("public_exponent", publicKeySpec.getPublicExponent().toByteArray());
            dict.put("date", date);
            dict.put("endpoint", endpoint);
            dict.put("registration_id", registrationId);

            return Base64.encodeToString(dict.toByteArray(), Base64.NO_WRAP);
        }
        catch (Exception e) {
            return "";
        }
    }
}
