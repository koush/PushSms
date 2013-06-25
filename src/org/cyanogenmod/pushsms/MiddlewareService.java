package org.cyanogenmod.pushsms;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.RemoteException;
import android.telephony.PhoneNumberUtils;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import com.android.internal.telephony.ISms;
import com.android.internal.telephony.ISmsMiddleware;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.gcm.GoogleCloudMessaging;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.DataEmitter;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.DataCallback;
import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.ion.Ion;

import org.cyanogenmod.pushsms.bencode.BEncodedDictionary;
import org.cyanogenmod.pushsms.socket.GcmConnectionManager;
import org.cyanogenmod.pushsms.socket.GcmSocket;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.lang.reflect.Method;
import java.math.BigInteger;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.RSAPrivateKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.List;

/**
 * Created by koush on 6/18/13.
 */
public class MiddlewareService extends android.app.Service {
    private static final int ACK_TIMEOUT = 30000;
    private static final String LOGTAG = "PushSMS";
    public static final String ACTION_REGISTER = "org.cyanogenmod.intent.action.REGISTER";

    private final Handler handler = new Handler();
    private Hashtable<String, RegistrationFuture> numberToRegistration = new Hashtable<String, RegistrationFuture>();
    private RegistrationFuture selfRegistrationFuture = new RegistrationFuture();
    private GoogleCloudMessaging gcm;
    private ISms smsTransport;
    private Registry registry;
    private SharedPreferences settings;
    private SharedPreferences accounts;
    private KeyPair keyPair;
    private RSAPublicKeySpec rsaPublicKeySpec;
    private String gcmApiKey;
    private String gcmSenderId;
    private GcmConnectionManager gcmConnectionManager;
    private Hashtable<String, GcmText> messagesAwaitingAck = new Hashtable<String, GcmText>();
    private SmsManager smsManager;

    private static final String SERVER_API_URL = "https://cmmessaging.appspot.com/api/v1";
    private static final String GCM_URL = SERVER_API_URL + "/gcm";
    private static final String FIND_URL = SERVER_API_URL + "/find";
    private static final String REGISTER_URL = SERVER_API_URL + "/register";

    // get the sender id and authorization keys from the cmmesaging server,
    // then register with google play services
    private void getGcmInfo() {
        new Thread() {
            @Override
            public void run() {
                try {
                    JsonObject result = Ion.with(MiddlewareService.this)
                    .load(GCM_URL)
                    .asJsonObject()
                    .get();

                    gcmSenderId = result.get("sender_id").getAsString();
                    gcmApiKey = result.get("api_key").getAsString();
                    settings.edit()
                    .putString("gcm_sender_id", gcmSenderId)
                    .putString("gcm_api_key", gcmApiKey)
                    .commit();
                }
                catch (Exception e) {
                }

                long sleep = 1000;
                while (true) {
                    try {
                        final String r = gcm.register(gcmSenderId);
                        Registration self = new Registration();
                        self.registrationId = r;
                        selfRegistrationFuture.setComplete(self);
//                    numberToRegistration.put("2064951490", selfRegistrationFuture);
                        Log.i(LOGTAG, "Registration ID: " + r);
                        break;
                    } catch (IOException e) {
                        Log.e(LOGTAG, "GCM Registration error", e);
                        // backoff and try again.
                        try {
                            sleep = Math.max(sleep * 2L, 30L * 60L * 1000L);
                            Thread.sleep(sleep);
                        }
                        catch (Exception ex) {
                        }
                    }
                }
            }
        }.start();
    }

    // hook into sms manager to intercept outgoing sms
    private void registerSmsMiddleware() {
        try {
            Class sm = Class.forName("android.os.ServiceManager");
            Method getService = sm.getMethod("getService", String.class);
            smsTransport = ISms.Stub.asInterface((IBinder)getService.invoke(null, "isms"));
            smsTransport.registerSmsMiddleware("interceptor", stub);
        }
        catch (Exception e) {
            Log.e(LOGTAG, "register error", e);
        }
    }

    // create/read the keypair as necessary
    private void getOrCreateKeyPair() {
        String encodedKeyPair = settings.getString("keypair", null);
        if (encodedKeyPair != null) {
            try {
                KeyFactory keyFactory = KeyFactory.getInstance("RSA");

                ByteArrayInputStream bin = new ByteArrayInputStream(Base64.decode(encodedKeyPair, Base64.DEFAULT));
                ObjectInputStream in = new ObjectInputStream(bin);

                rsaPublicKeySpec = new RSAPublicKeySpec((BigInteger)in.readObject(), (BigInteger)(in.readObject()));
                RSAPrivateKeySpec rsaPrivateKeySpec = new RSAPrivateKeySpec((BigInteger)in.readObject(), (BigInteger)(in.readObject()));

                PublicKey pub = keyFactory.generatePublic(rsaPublicKeySpec);
                PrivateKey priv = keyFactory.generatePrivate(rsaPrivateKeySpec);

                keyPair = new KeyPair(pub, priv);
                return;
            }
            catch (Exception e) {
                Log.e(LOGTAG, "KeyPair load error", e);
            }
        }

        try {
            KeyPairGenerator gen = KeyPairGenerator.getInstance("RSA");
            gen.initialize(2048);
            keyPair = gen.generateKeyPair();

            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            rsaPublicKeySpec = keyFactory.getKeySpec(keyPair.getPublic(), RSAPublicKeySpec.class);
            RSAPrivateKeySpec privateKeySpec = keyFactory.getKeySpec(keyPair.getPrivate(), RSAPrivateKeySpec.class);

            ByteArrayOutputStream bout = new ByteArrayOutputStream();
            ObjectOutputStream out = new ObjectOutputStream(bout);

            out.writeObject(rsaPublicKeySpec.getModulus());
            out.writeObject(rsaPublicKeySpec.getPublicExponent());

            out.writeObject(privateKeySpec.getModulus());
            out.writeObject(privateKeySpec.getPrivateExponent());

            out.flush();

            settings.edit().putString("keypair", Base64.encodeToString(bout.toByteArray(), Base64.DEFAULT)).commit();
            settings.edit().putBoolean("needs_register", true).commit();
        }
        catch (Exception e) {
            Log.e(LOGTAG, "KeyPair generation error", e);
            keyPair = null;
        }
    }

    private void setupGcmConnectionManager() {
        selfRegistrationFuture.addCallback(new FutureCallback<Registration>() {
            @Override
            public void onCompleted(Exception e, Registration result) {
                gcmConnectionManager = new GcmConnectionManager(MiddlewareService.this, keyPair.getPrivate(), gcmApiKey);
            }
        });
    }

    PowerManager.WakeLock wakeLock;
    private void createWakelock() {
        PowerManager pm = (PowerManager)getSystemService(POWER_SERVICE);
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "SMSDispatcher");
        wakeLock.setReferenceCounted(true);
    }

    private void registerSmsReceiver() {
        IntentFilter filter = new IntentFilter("android.provider.Telephony.SMS_RECEIVED");
        filter.addCategory(Intent.CATEGORY_DEFAULT);
        filter.setPriority(Integer.MAX_VALUE);

        registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                // steal messages as necessary
            }
        }, filter);
    }

    @Override
    public void onCreate() {
        super.onCreate();

        registerSmsReceiver();
        createWakelock();
        settings = getSharedPreferences("settings", MODE_PRIVATE);
        accounts = getSharedPreferences("accounts", MODE_PRIVATE);
        smsManager = SmsManager.getDefault();
        getOrCreateKeyPair();

        gcm = GoogleCloudMessaging.getInstance(MiddlewareService.this);
        gcmApiKey = settings.getString("gcm_api_key", null);
        gcmSenderId = settings.getString("gcm_sender_id", "494395756847");
        getGcmInfo();
        setupGcmConnectionManager();

        registerSmsMiddleware();

        registry = new Registry(this);
        registry.load(numberToRegistration);

        if (settings.getBoolean("needs_register", false))
            registerEndpoints();
    }

    // this is the middleware that processes all outgoing messages
    // as they enter the SmsManager
    ISmsMiddleware.Stub stub = new ISmsMiddleware.Stub() {
        public void logd(String string) {
            Log.d(LOGTAG, "on*Text " + string);
        }

        @Override
        public boolean onSendText(String destAddr, String scAddr, String text, PendingIntent sentIntent, PendingIntent deliveryIntent) throws RemoteException {
            List<PendingIntent> sentIntents = null;
            List<PendingIntent> deliveryIntents = null;
            if (sentIntent != null) {
                sentIntents = new ArrayList<PendingIntent>();
                sentIntents.add(sentIntent);
            }
            if (deliveryIntent != null) {
                deliveryIntents = new ArrayList<PendingIntent>();
                deliveryIntents.add(deliveryIntent);
            }

            ArrayList<String> texts = new ArrayList<String>();
            texts.add(text);
            return onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, false);
        }

        @Override
        public boolean onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents) throws RemoteException {
            return onSendMultipartText(destAddr, scAddr, texts, sentIntents, deliveryIntents, true);
        }

        public boolean onSendMultipartText(String destAddr, String scAddr, List<String> texts, final List<PendingIntent> sentIntents, final List<PendingIntent> deliveryIntents, boolean multipart) throws RemoteException {
            if (keyPair == null) {
                logd("no keypair");
                return false;
            }

            if (!selfRegistrationFuture.isDone()) {
                logd("awaiting gcm registration");
                return false;
            }

            if (gcmConnectionManager == null) {
                logd("no gcm connection manager available");
                return false;
            }

            // compute a deterministic message id
            StringBuilder builder = new StringBuilder();
            for (String text: texts) {
                builder.append(text);
            }
            String lookup;
            try {
                lookup = Base64.encodeToString(MessageDigest.getInstance("MD5").digest(builder.toString().getBytes()), Base64.NO_WRAP);
            }
            catch (Exception e) {
                lookup = "";
            }
            lookup = destAddr + ":" + lookup;
            final String lookupFinal = lookup;

            // see if this one failed delivery, and just ignore it
            if (messagesAwaitingAck.containsKey(lookup)) {
                messagesAwaitingAck.remove(lookup);
                logd("resending failed message");
                return false;
            }

            // try to fail out synchronously by verifying
            // that the registration is invalid or the gcm socket is unhealthy
            Registration existingResolved = findImmediateRegistration(destAddr);
            if (existingResolved != null) {
                if (existingResolved.isInvalid()) {
                    logd("invalid registration");
                    return false;
                }

                GcmSocket gcmSocket = gcmConnectionManager.findGcmSocket(existingResolved, getNumber());
                if (gcmSocket != null && !gcmSocket.isHealthy()) {
                    logd("unhealthy gcm socket");
                    return false;
                }
            }

            // create a future that will provide the registration id of the destination
            // if it exists
            RegistrationFuture future = findOrCreateRegistration(destAddr);

            // construct a text that we can attempt to send,
            // once we determine how to reach the number
            final GcmText sendText = new GcmText();
            sendText.destAddr = destAddr;
            sendText.scAddr = scAddr;
            sendText.texts.addAll(texts);
            sendText.multipart = multipart;
            sendText.deliveryIntents = deliveryIntents;
            sendText.sentIntents = sentIntents;

            // put this message in the pending ack queue, and set a timeout
            // to resend the message via the regular sms transport if no
            // ack is received.
            messagesAwaitingAck.put(lookupFinal, sendText);
            handler.postDelayed(new Runnable() {
                @Override
                public void run() {
                    // if not acked, attempt normal delivery
                    if (messagesAwaitingAck.containsKey(lookupFinal)) {
                        Log.e(LOGTAG, "Timeout awaiting ack of sms");
                        sendText.manageFailure(handler, smsManager);

                        GcmSocket gcmSocket = gcmConnectionManager.findGcmSocket(sendText.destAddr);
                        if (gcmSocket != null) {
                            Log.e(LOGTAG, "Marking GCM socket failure");
                            gcmSocket.fail();
                            registry.register(gcmSocket.registration.endpoint, gcmSocket.registration);
                        }
                    }
                }
            }, ACK_TIMEOUT);

            // wait for the registration result, and set up a peer to peer
            // gcm connection if possible
            future.addCallback(new FutureCallback<Registration>() {
                @Override
                public void onCompleted(Exception e, Registration result) {
                    if (e != null || !result.isRegistered()) {
                        sendText.manageFailure(handler, smsManager);
                        return;
                    }

                    // create a gcm connection with the peer and send a text
                    sendText.send(findOrCreateGcmSocket(result), lookupFinal);
                }
            });

            return true;
        }
    };

    private void firePendingIntents(List<PendingIntent> intents) {
        if (intents == null)
            return;
        for (PendingIntent pi: intents) {
            try {
                if (pi == null)
                    continue;
                pi.send(Activity.RESULT_OK);
            }
            catch (Exception e) {
                Log.e(LOGTAG, "Error delivering pending intent", e);
            }
        }
    }

    private void parseGcmMessage(GcmSocket gcmSocket, ByteBufferList bb) {
        try {
            BEncodedDictionary message = BEncodedDictionary.parseDictionary(bb.getAllByteArray());
            String messageType = message.getString("t");

            if (MessageTypes.MESSAGE.equals(messageType)) {
                // incoming text via gcm
                GcmText gcmText = GcmText.parse(gcmSocket, message);
                if (gcmText == null)
                    return;

                // ack the message
                String messageId = message.getString("id");
                if (messageId != null) {
                    BEncodedDictionary ack = new BEncodedDictionary();
                    ack.put("t", MessageTypes.ACK);
                    ack.put("id", messageId);
                    gcmSocket.write(new ByteBufferList(ack.toByteArray()));
                }

                // synthesize a fake message for the android system
                smsTransport.synthesizeMessages(gcmSocket.getNumber(), gcmText.scAddr, gcmText.texts, System.currentTimeMillis());
            }
            else if (MessageTypes.ACK.equals(messageType)) {
                // incoming ack
                String messageId = message.getString("id");
                if (messageId == null)
                    return;
                GcmText gcmText = messagesAwaitingAck.remove(messageId);
                if (gcmText == null)
                    return;
                firePendingIntents(gcmText.sentIntents);
                firePendingIntents(gcmText.deliveryIntents);
            }
        }
        catch (Exception e) {
            Log.e(LOGTAG, "Error handling GCM socket message", e);
        }
    }

    // given a registration, find/create the gcm socket that manages
    // the secure connection between the two devices.
    private GcmSocket findOrCreateGcmSocket(Registration registration) {
        GcmSocket ret = gcmConnectionManager.findGcmSocket(registration, getNumber());
        if (ret == null) {
            final GcmSocket gcmSocket = ret = gcmConnectionManager.createGcmSocket(registration, getNumber());

            // parse data from the gcm connection as we get it
            ret.setDataCallback(new DataCallback() {
                @Override
                public void onDataAvailable(DataEmitter emitter, ByteBufferList bb) {
                    parseGcmMessage(gcmSocket, bb);
                    // save the registration info (sequence numbers changed, etc)
                    registry.register(gcmSocket.registration.endpoint, gcmSocket.registration);
                }
            });

            // on error, fail over any pending messages for this number
            ret.setEndCallback(new CompletedCallback() {
                @Override
                public void onCompleted(Exception ex) {
                    for (String pending: new ArrayList<String>(messagesAwaitingAck.keySet())) {
                        String numberPart = pending.split(":")[0];
                        if (!PhoneNumberUtils.compare(MiddlewareService.this, numberPart, gcmSocket.getNumber()))
                            continue;
                        GcmText gcmText = messagesAwaitingAck.get(pending);
                        if (gcmText == null)
                            continue;
                        gcmText.manageFailure(handler, smsManager);
                    }
                }
            });
        }
        return ret;
    }

    private void logd(String string) {
        Log.d(LOGTAG, string);
    }

    // fetch/create the gcm and public key info for a phone number
    // from the server
    private RegistrationFuture createRegistration(final String address, final Registration existing) {
        final RegistrationFuture ret = new RegistrationFuture();
        numberToRegistration.put(address, ret);

        // the server will need to know all the email/number combos when we're attempting
        // to locate the gcm registration id for a given number.
        // this will return HASHED emails, not actual emails. this way the server is not privy
        // to your contact information.
        HashSet<String> emailHash = Helper.findEmailsForNumber(this, address);
        if (emailHash.size() == 0) {
            ret.setComplete(new Exception("no emails"));
            return ret;
        }

        JsonObject post = new JsonObject();
        JsonArray authorities = new JsonArray();
        post.add("authorities", authorities);
        post.addProperty("endpoint", address);

        for (String authority: emailHash) {
            authorities.add(new JsonPrimitive(authority));
        }

        logd("Fetching registration for " + address);
        Ion.with(this)
        .load(FIND_URL)
        .setJsonObjectBody(post)
        .asJsonObject().setCallback(new FutureCallback<JsonObject>() {
            @Override
            public void onCompleted(Exception e, JsonObject result) {
                Registration registration;
                boolean wasUnregistered = false;
                String oldRegistrationId = null;
                // if we're reregistering/refreshing, grab the relevant bits
                // from the old registration
                if (existing != null) {
                    oldRegistrationId = existing.registrationId;
                    wasUnregistered = existing.isUnregistered();
                    // reuse the existing registration to preserve sequence numbers, etc.
                    registration = existing;
                    registration.register();
                }
                else {
                    registration = new Registration();
                }

                try {
                    if (e != null) {
                        // this throws down to the catch that marks
                        // the registration as invalid willy nilly..
                        // this is probably bad. errors here are
                        // potentially caused by server failures
                        // or lack of network access on the phone, etc.
                        throw e;
                    }

                    if (result.has("error"))
                        throw new Exception(result.toString());

                    String newRegistrationId = result.get("registration_id").getAsString();

                    // the number is available for an encrypted connection, grab
                    // the registration info.
                    registration.endpoint = address;
                    registration.registrationId = newRegistrationId;
                    BigInteger publicExponent = new BigInteger(Base64.decode(result.get("public_exponent").getAsString(), Base64.DEFAULT));
                    BigInteger publicModulus = new BigInteger(Base64.decode(result.get("public_modulus").getAsString(), Base64.DEFAULT));
                    RSAPublicKeySpec publicKeySpec = new RSAPublicKeySpec(publicModulus, publicExponent);
                    KeyFactory keyFactory = KeyFactory.getInstance("RSA");
                    registration.remotePublicKey = keyFactory.generatePublic(publicKeySpec);

                    logd("Registration complete for " + registration.endpoint);

                    // if we're refreshing a NotRegistered event, and get the same registration...
                    // just mark the peer as invalid, until they gcm us back or the refresh interval
                    // gets hit.
                    if (wasUnregistered && TextUtils.equals(newRegistrationId, oldRegistrationId))
                        throw new Exception("unregistered registration was refreshed, still invalid");
                }
                catch (Exception ex) {
                    // mark this number as invalid
                    Log.e(LOGTAG, "registration fetch failure", ex);
                    registration.invalidate();
                }
                registry.register(address, registration);
                ret.setComplete(registration);

                // remove any older gcm connections to force creation of a new one
                // that will leverage the new registration id and potentially public key
                if (gcmConnectionManager != null)
                    gcmConnectionManager.remove(address);
            }
        });

        return ret;
    }

    // find the gcm info, public key, etc, for a given phone number
    private RegistrationFuture findRegistration(String address) {
        for (String number: numberToRegistration.keySet()) {
            if (PhoneNumberUtils.compare(number, address)) {
                return numberToRegistration.get(number);
            }
        }
        return null;
    }

    private Registration findImmediateRegistration(String address) {
        RegistrationFuture future = findRegistration(address);

        if (future != null) {
            try {
                if (future.isDone())
                    return future.get();
            }
            catch (Exception e) {
            }
        }
        return null;
    }

    private RegistrationFuture findOrCreateRegistration(String address) {
        RegistrationFuture future = findRegistration(address);

        Registration existing = null;
        // check the existing result if possible
        if (future != null) {
            if (!future.isDone())
                return future;

            // if unregistered or needing a refresh, do a refresh
            try {
                existing = future.get();
                if (existing.isRegistered() || existing.isInvalid())
                    return future;
            }
            catch (Exception e) {
                // huh? this shouldn't actually ever happen
                return future;
            }
        }
        return createRegistration(address, existing);
    }

    @Override
    public int onStartCommand(final Intent intent, int flags, int startId) {
        if (intent == null)
            return START_STICKY;

        if ("com.google.android.c2dm.intent.RECEIVE".equals(intent.getAction())) {
            // keep us alive for 10 seconds to ack any incoming message
            wakeLock.acquire(10000);
            String messageType = gcm.getMessageType(intent);
            if (GoogleCloudMessaging.MESSAGE_TYPE_MESSAGE.equals(messageType)) {
                final String payload = intent.getStringExtra("p");
                final String from = intent.getStringExtra("f");
                if (from == null || payload == null)
                    return START_STICKY;

                // if we get a message from a number that we previously thought
                // was not in commission, force a refresh to get the new info, if any.
                Registration existingRegistration = findImmediateRegistration(from);
                if (existingRegistration != null && existingRegistration.isInvalid()) {
                    existingRegistration.refresh();
                }

                findOrCreateRegistration(from)
                .addCallback(new FutureCallback<Registration>() {
                    @Override
                    public void onCompleted(Exception e, Registration result) {
                        if (result == null)
                            return;
                        findOrCreateGcmSocket(result).onGcmMessage(payload, from);
                    }
                });
            }
        }
        else if (ACTION_REGISTER.equals(intent.getAction())) {
            registerEndpoints();
        }

        return START_STICKY;
    }

    // figure out the number of the user's phone. may be detect automatically or manually entered.
    private String getNumber() {
        final TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String ret = settings.getString("phone_number", tm.getLine1Number());
        if (TextUtils.isEmpty(ret))
            ret = tm.getLine1Number();
        return ret;
    }

    boolean registering = false;
    // register our gcm registration id with the cmmessaging server
    void withRegistration(Exception e, final Registration registration) {
        if (registering || e != null)
            return;
        registering = true;
        JsonObject post = new JsonObject();
        post.addProperty("registration_id", registration.registrationId);
        JsonObject tokens = new JsonObject();
        post.add("access_tokens", tokens);
        // grab all authorized accounts
        for (Account account : AccountManager.get(MiddlewareService.this).getAccountsByType("com.google")) {
            if (accounts.getAll().containsKey(account.name)) {
                try {
                    String token = GoogleAuthUtil.getToken(MiddlewareService.this, account.name, "oauth2:https://www.googleapis.com/auth/userinfo.email");
                    // take note whether we want to use this account
                    tokens.addProperty(token, accounts.getBoolean(account.name, false));
                }
                catch (Exception ex) {
                    Log.e(LOGTAG, "token error", ex);
                }
            }
        }
        // send up our public key
        post.addProperty("endpoint", getNumber());
        post.addProperty("public_modulus", Base64.encodeToString(rsaPublicKeySpec.getModulus().toByteArray(), Base64.NO_WRAP));
        post.addProperty("public_exponent", Base64.encodeToString(rsaPublicKeySpec.getPublicExponent().toByteArray(), Base64.NO_WRAP));

        Ion.with(MiddlewareService.this)
        .load(REGISTER_URL)
        .setJsonObjectBody(post)
        .asJsonObject()
        .setCallback(new FutureCallback<JsonObject>() {
            @Override
            public void onCompleted(Exception e, JsonObject result) {
                registering = false;
                if (e != null || result.has("error"))
                    return;
                settings.edit().putBoolean("needs_register", false).commit();
            }
        });
    }

    // register our gcm info once we have it
    private void registerEndpoints() {
        selfRegistrationFuture.addCallback(new FutureCallback<Registration>() {
            @Override
            public void onCompleted(final Exception e, final Registration result) {
                new Thread() {
                    @Override
                    public void run() {
                        withRegistration(e, result);
                    }
                }.start();
            }
        });
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
