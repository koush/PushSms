package org.cyanogenmod.pushsms.socket;

import android.content.Context;
import android.util.Base64;
import android.util.Log;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.google.gson.annotations.SerializedName;
import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.FilteredDataEmitter;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.WritableCallback;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import org.cyanogenmod.pushsms.Registration;
import org.cyanogenmod.pushsms.bencode.BEncodedDictionary;
import org.cyanogenmod.pushsms.bencode.BEncodedList;

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.spec.SecretKeySpec;

/**
 * Created by koush on 6/21/13.
 */
public class GcmSocket extends FilteredDataEmitter implements AsyncSocket {
    private static final String LOGTAG = "GCMSocket";
    AsyncServer server;
    Context context;
    public Registration registration;
    PrivateKey privateKey;
    String from;
    String gcmApiKey;
    public GcmSocket(Context context, AsyncServer server, PrivateKey privateKey, String from, String gcmApiKey, Registration registration) {
        this.server = server;
        this.context = context;
        this.registration = registration;
        this.privateKey = privateKey;
        this.from = from;
        this.gcmApiKey = gcmApiKey;
    }

    public String getNumber() {
        if (originatingNumber != null)
            return originatingNumber;
        return registration.endpoint;
    }

    String originatingNumber;

    // on failure, wait 1 minute before attempting to use GCM again
    private static final long GRACE_DEFAULT = 60000L;
    // start by assuming the socket is healthy
    long nextAllowedAttempt;
    long currentBackoff = GRACE_DEFAULT;
    public boolean isHealthy() {
        return nextAllowedAttempt < System.currentTimeMillis();
    }

    public void fail() {
        // see if we're already backing off from an error
        if (nextAllowedAttempt > System.currentTimeMillis())
            return;
        nextAllowedAttempt = System.currentTimeMillis() + currentBackoff;
        currentBackoff *= 2;
        // backoff max is an hour
        currentBackoff = Math.max(currentBackoff, 60L * 60L * 1000L);
    }

    @Override
    protected void report(Exception e) {
        if (e != null) {
            fail();
        }

        super.report(e);
        resetEnded();
    }

    public void onGcmMessage(String dataString, String from) {
        try {
            // base64 decode the payload that contains the encrypted symmetric key
            // and the corresponding encryptedSignedMessage
            BEncodedDictionary encryptedKeyMessagePair = BEncodedDictionary.parseDictionary(Base64.decode(dataString, Base64.NO_WRAP));

            BEncodedList encryptedSymmetricKeys = encryptedKeyMessagePair.getBEncodedList("esk");
            byte[] encryptedSignedMessage = encryptedKeyMessagePair.getBytes("esm");

            // to support multi recipient scenarios, the protocol sends an array
            // of encrypted symmetric keys. each key is encrypted once per intended recipient.
            // attempt to decode all of them, use the one that works.
            byte[] symmetricKey = null;
            for (int i = 0; i < encryptedSymmetricKeys.size(); i++) {
                try {
                    byte[] encryptedSymmetricKey = encryptedSymmetricKeys.getBytes(i);
                    // decrypt the symmetric key
                    Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
                    cipher.init(Cipher.DECRYPT_MODE, privateKey);
                    symmetricKey = cipher.doFinal(encryptedSymmetricKey);
                }
                catch (Exception e) {
                }
            }
            if (symmetricKey == null)
                throw new Exception("could not decrypt symmetric key");

            // decrypt the message
            Cipher cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(symmetricKey, "AES"));
            BEncodedDictionary signedMessage = BEncodedDictionary.parseDictionary(cipher.doFinal(encryptedSignedMessage));

            // grab the signature and the signed data, and verify the sender authenticity
            byte[] signature = signedMessage.getBytes("s");
            byte[] signedEnvelope = signedMessage.getBytes("d");
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(registration.remotePublicKey);
            verifier.update(signedEnvelope);
            if (!verifier.verify(signature)) {
                // keys or something changed? force a server refresh
                registration.refresh();
                throw new Exception("unable to verify signature");
            }

            originatingNumber = from;

            BEncodedDictionary envelope = BEncodedDictionary.parseDictionary(signedEnvelope);

            int seq = envelope.getInt("lsn");
            if (registration.remoteSequenceNumber <= seq) {
                // wtf? replay attack?
                // how to handle? Ignore? prompt?
            }
            registration.remoteSequenceNumber = seq;

            // the remote end will send us what they think our local sequence number is... just use
            // the max of the two
            registration.localSequenceNumber = Math.max(envelope.getInt("rsn"), registration.localSequenceNumber);

            Util.emitAllData(this, new ByteBufferList(envelope.getBytes("p")));
        }
        catch (Exception e) {
            Log.e(LOGTAG, "Parse exception", e);
        }
    }

    @Override
    public void write(ByteBuffer bb) {
        write(new ByteBufferList(bb.duplicate()));
        bb.position(bb.limit());
    }

    @Override
    public void write(ByteBufferList bb) {
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
            BEncodedDictionary envelope = new BEncodedDictionary();
            // plop in the data we want to deliver
            envelope.put("p", bb.getAllByteArray());
            // include a sequence number to prevent replay attacks
            envelope.put("lsn", registration.localSequenceNumber++);
            // let the other end know what their sequence number is
            // just in case we're out of sync
            envelope.put("rsn", registration.remoteSequenceNumber);

            // sign the data so authenticity can be verified
            BEncodedDictionary signedMessage = new BEncodedDictionary();
            byte[] signedEnvelope = envelope.toByteArray();

            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(privateKey);
            signer.update(signedEnvelope);
            byte[] signature = signer.sign();

            signedMessage.put("d", signedEnvelope);
            signedMessage.put("s", signature);

            // generate a symmetric key to be encrypted by the remote public key,
            // and encrypt that. Asymmetric keys have payload limitations.
            // http://en.wikipedia.org/wiki/Hybrid_cryptosystem
            // http://stackoverflow.com/questions/6788018/android-encryption-decryption-with-aes
            // see also the added benefit of multi recipient scenarios:
            // http://security.stackexchange.com/questions/20134/in-pgp-why-not-just-encrypt-message-with-recipients-public-key-why-the-meta-e/20145#20145
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
            // though right now we only put in a single encrypted symmetric key,
            // wrap that key in an array. this will future proof the protocol
            // for later possible multi recipient scenarios.
            BEncodedList encryptedSymmetricKeys = new BEncodedList();
            encryptedSymmetricKeys.add(encryptedSymmetricKey);
            payload.put("esk", encryptedSymmetricKeys);
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
            .as(GcmResults.class)
            .setCallback(new FutureCallback<GcmResults>() {
                @Override
                public void onCompleted(Exception e, GcmResults result) {
                    if (result == null || result.failure != 0 || result.success == 0) {
                        if (e == null) {
                            e = new Exception("gcm server failure");
                            // if registered, mark it as unregistered
                            // to trigger a refresh
                            if (registration.isRegistered())
                                registration.unregister();
                        }
                        report(e);
                        return;
                    }
                    currentBackoff = GRACE_DEFAULT;
                }
            });
        }
        catch (Exception e) {
            Log.e(LOGTAG, "Send exception", e);
            report(e);
        }
    }

    public static class GcmUnregisteredException extends Exception {
    }

    public static class GcmResult {
        @SerializedName("message_id")
        String messageId;
        @SerializedName("error")
        String error;
    }

    public static class GcmResults {
        @SerializedName("failure")
        int failure;

        @SerializedName("success")
        int success;

        @SerializedName("results")
        ArrayList<GcmResult> results = new ArrayList<GcmResult>();
    }

    @Override
    public void setWriteableCallback(WritableCallback handler) {

    }

    @Override
    public WritableCallback getWriteableCallback() {
        return null;
    }

    @Override
    public boolean isOpen() {
        return true;
    }

    @Override
    public void end() {
    }

    @Override
    public void setClosedCallback(CompletedCallback handler) {
    }

    @Override
    public CompletedCallback getClosedCallback() {
        return null;
    }

    @Override
    public boolean isChunked() {
        return true;
    }

    @Override
    public boolean isPaused() {
        return false;
    }
}
