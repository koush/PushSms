package org.cyanogenmod.pushsms;

import android.app.Activity;
import android.app.PendingIntent;
import android.content.Context;
import android.telephony.SmsManager;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import org.cyanogenmod.pushsms.bencode.BEncodedDictionary;
import org.cyanogenmod.pushsms.bencode.BEncodedList;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.interfaces.DSAPrivateKey;
import java.security.spec.DSAPrivateKeySpec;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;

class GcmText {
    private static final String LOGTAG = "GCMSms";
    String destAddr;
    String scAddr;
    ArrayList<String> texts = new ArrayList<String>();
    boolean multipart;

    public void manageFailure(final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents) {
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

    public static GcmText parse(String dataString, PrivateKey privateDecrytionKey, Registration registration) {
        try {
            // base64 decode the payload that contains the encrypted symmetric key
            // and the corresponding encryptedSignedMessage
            BEncodedDictionary payload = BEncodedDictionary.parseDictionary(Base64.decode(dataString, Base64.NO_WRAP));

            byte[] encryptedSymmetricKey = payload.getBytes("esk");
            byte[] encryptedSignedMessage = payload.getBytes("esm");

            // decrypt the symmetric key
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateDecrytionKey);
            byte[] symmetricKey = cipher.doFinal(encryptedSymmetricKey);

            // decrypt the message
            cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(symmetricKey, "AES"));
            BEncodedDictionary signedMessage = BEncodedDictionary.parseDictionary(cipher.doFinal(encryptedSignedMessage));

            // grab the signature and the signed data, and verify the sender authenticity
            byte[] signature = signedMessage.getBytes("s");
            byte[] signedData = signedMessage.getBytes("d");
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(registration.remotePublicKey);
            verifier.update(signedData);
            if (!verifier.verify(signature))
                throw new Exception("unable to verify signature");

            BEncodedDictionary unencryptedMessage = BEncodedDictionary.parseDictionary(signedData);

            int seq = unencryptedMessage.getInt("seq");
            if (registration.remoteSequenceNumber < seq) {
                // wtf? replay attack?
            }
            registration.remoteSequenceNumber = seq;

            GcmText ret = new GcmText();
            ret.destAddr = unencryptedMessage.getString("da");
            ret.scAddr = unencryptedMessage.getString("sa");
            BEncodedList texts = unencryptedMessage.getBEncodedList("ts");
            for (int i = 0; i < texts.size(); i++) {
                ret.texts.add(texts.getString(i));
            }

            return ret;
        }
        catch (Exception e) {
            return null;
        }
    }

    public void send(Context context, String from, PrivateKey privateSigningKey, String gcmApiKey, Registration registration, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents) {
        try {
            assert registration != null;

            // construct a gcm json post object
            JsonObject post = new JsonObject();
            JsonArray regs = new JsonArray();
            regs.add(new JsonPrimitive(registration.registrationId));
            post.add("registration_ids", regs);
            JsonObject data = new JsonObject();
            post.add("data", data);

            // serialize the unencrypted message
            BEncodedDictionary unencryptedMessage = new BEncodedDictionary();
            unencryptedMessage.put("da", destAddr);
            if (scAddr != null)
                unencryptedMessage.put("sa", scAddr);
            BEncodedList texts = new BEncodedList();
            unencryptedMessage.put("ts", texts);
            for (String text: this.texts) {
                texts.add(text);
            }
            // and include a sequence number to prevent replay attacks
            unencryptedMessage.put("seq", registration.localSequenceNumber++);

            // sign the data so authenticity can be verified
            BEncodedDictionary signedMessage = new BEncodedDictionary();
            byte[] signedData = unencryptedMessage.toByteArray();

            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(privateSigningKey);
            signer.update(signedData);
            byte[] signature = signer.sign();

            signedMessage.put("d", signedData);
            signedMessage.put("s", signature);

            // generate an symmetric key to be encrypted with the remote public key,
            // and encrypt that. Asymmetric keys have payload limitations.
            // http://en.wikipedia.org/wiki/Hybrid_cryptosystem
            // http://stackoverflow.com/questions/6788018/android-encryption-decryption-with-aes
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            SecureRandom secureRandom = SecureRandom.getInstance("SHA1PRNG");
            secureRandom.setSeed(new BigInteger(256, new Random()).toByteArray());
            keyGenerator.init(128, secureRandom);
            byte[] symmetricKey = keyGenerator.generateKey().getEncoded();

            // Signature AND message should both be encrypted, as much as possible should be opaque:
            // http://stackoverflow.com/questions/6587023/should-i-encrypt-the-signature
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(symmetricKey, "AES"));
            byte[] encryptedSignedMessage = cipher.doFinal(signedMessage.toByteArray());

            // Encrypt the asymmetric key so only the remote can decrypt it, and
            // thus decrypt the message payload
            cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, registration.remotePublicKey);
            byte[] encryptedSymmetricKey = cipher.doFinal(symmetricKey);

            // put the encrypted symmetric key and encrypted payload into the JSON, and send it off
            BEncodedDictionary payload = new BEncodedDictionary();
            payload.put("esk", encryptedSymmetricKey);
            payload.put("esm", encryptedSignedMessage);

            // now base64 the entire encrypted payload
            data.add("p", new JsonPrimitive(Base64.encodeToString(payload.toByteArray(), Base64.NO_WRAP)));

            // include our claimed number, as we would a text, so the other end can find us
            data.addProperty("f", from);

            // ship it
            Ion.with(context)
            .load("https://android.googleapis.com/gcm/send")
            .setHeader("Authorization", "key=" + gcmApiKey)
            .setJsonObjectBody(post)
            .asJsonObject()
            .setCallback(new FutureCallback<JsonObject>() {
                @Override
                public void onCompleted(Exception e, JsonObject result) {
                    if (e != null) {
                        manageFailure(sentIntents, deliveryIntents);
                        return;
                    }

                    Log.i(LOGTAG, "Response from GCM send: " + result);
                    if (sentIntents != null) {
                        try {
                            for (PendingIntent sentIntent : sentIntents) {
                                sentIntent.send(Activity.RESULT_OK);
                            }
                        } catch (Exception ex) {
                            ex.printStackTrace();
                        }
                    }
                }
            });
        }
        catch (Exception e) {
            manageFailure(sentIntents, deliveryIntents);
        }
    }
}