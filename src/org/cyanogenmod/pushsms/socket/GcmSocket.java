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

import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
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
    Registration registration;
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

    @Override
    protected void report(Exception e) {
        if (e != null) {
            // see if we're already backing off from an error
            if (nextAllowedAttempt > System.currentTimeMillis())
                return;
            nextAllowedAttempt = System.currentTimeMillis() + currentBackoff;
            currentBackoff *= 2;
            // backoff max is an hour
            currentBackoff = Math.max(currentBackoff, 60L * 60L * 1000L);
        }

        super.report(e);
        resetEnded();
    }

    public void onGcmMessage(String dataString, String from) {
        try {
            // base64 decode the payload that contains the encrypted symmetric key
            // and the corresponding encryptedSignedMessage
            BEncodedDictionary encryptedKeyMessagePair = BEncodedDictionary.parseDictionary(Base64.decode(dataString, Base64.NO_WRAP));

            byte[] encryptedSymmetricKey = encryptedKeyMessagePair.getBytes("esk");
            byte[] encryptedSignedMessage = encryptedKeyMessagePair.getBytes("esm");

            // decrypt the symmetric key
            Cipher cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding");
            cipher.init(Cipher.DECRYPT_MODE, privateKey);
            byte[] symmetricKey = cipher.doFinal(encryptedSymmetricKey);

            // decrypt the message
            cipher = Cipher.getInstance("AES");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(symmetricKey, "AES"));
            BEncodedDictionary signedMessage = BEncodedDictionary.parseDictionary(cipher.doFinal(encryptedSignedMessage));

            // grab the signature and the signed data, and verify the sender authenticity
            byte[] signature = signedMessage.getBytes("s");
            byte[] signedEnvelope = signedMessage.getBytes("d");
            Signature verifier = Signature.getInstance("SHA1withRSA");
            verifier.initVerify(registration.remotePublicKey);
            verifier.update(signedEnvelope);
            if (!verifier.verify(signature))
                throw new Exception("unable to verify signature");

            originatingNumber = from;

            BEncodedDictionary envelope = BEncodedDictionary.parseDictionary(signedEnvelope);

            int seq = envelope.getInt("seq");
            if (registration.remoteSequenceNumber < seq) {
                // wtf? replay attack?
                // how to handle? Ignore? prompt?
            }
            registration.remoteSequenceNumber = seq;

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
            // and include a sequence number to prevent replay attacks
            envelope.put("seq", registration.localSequenceNumber++);

            // sign the data so authenticity can be verified
            BEncodedDictionary signedMessage = new BEncodedDictionary();
            byte[] signedEnvelope = envelope.toByteArray();

            Signature signer = Signature.getInstance("SHA1withRSA");
            signer.initSign(privateKey);
            signer.update(signedEnvelope);
            byte[] signature = signer.sign();

            signedMessage.put("d", signedEnvelope);
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
            .as(GcmResult.class)
            .setCallback(new FutureCallback<GcmResult>() {
                @Override
                public void onCompleted(Exception e, GcmResult result) {
                    if (result == null || result.failure != 0 || result.success == 0) {
                        if (e == null)
                            e = new Exception("gcm server failure");
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

    public static class GcmResult {
        @SerializedName("failure")
        int failure;

        @SerializedName("success")
        int success;
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
