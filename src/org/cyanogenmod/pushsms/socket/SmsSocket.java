package org.cyanogenmod.pushsms.socket;

import android.content.Context;
import android.telephony.SmsManager;
import android.telephony.SmsMessage;

import com.koushikdutta.async.AsyncServer;
import com.koushikdutta.async.AsyncSocket;
import com.koushikdutta.async.ByteBufferList;
import com.koushikdutta.async.FilteredDataEmitter;
import com.koushikdutta.async.Util;
import com.koushikdutta.async.callback.CompletedCallback;
import com.koushikdutta.async.callback.WritableCallback;

import org.cyanogenmod.pushsms.bencode.BEncodedDictionary;

import java.nio.ByteBuffer;
import java.util.Hashtable;

/**
 * Created by koush on 6/21/13.
 */
public class SmsSocket extends FilteredDataEmitter implements AsyncSocket {
    AsyncServer server;
    Context context;
    String number;
    short smsPort;
    public SmsSocket(Context context, AsyncServer server, String number, short smsPort) {
        this.server = server;
        this.context = context;
        this.number = number;
        this.smsPort = smsPort;
    }

    public String getNumber() {
        if (originatingAddress != null)
            return originatingAddress;
        return number;
    }

    Hashtable<Integer, byte[][]> pending = new Hashtable<Integer, byte[][]>();
    String originatingAddress;

    public void onMessage(final SmsMessage message) {
        server.post(new Runnable() {
            @Override
            public void run() {
                try {
                    originatingAddress = message.getOriginatingAddress();

                    BEncodedDictionary dict = BEncodedDictionary.parseDictionary(ByteBuffer.wrap(message.getUserData()));
                    int thisMessageId = dict.getInt("m");
                    int count = dict.getInt("c");
                    int index = dict.getInt("i");
                    byte[] bytes = dict.getBytes("d");

                    byte[][] parts = pending.get(thisMessageId);
                    if (parts == null) {
                        parts = new byte[count][];
                        pending.put(thisMessageId, parts);
                    }

                    parts[index] = bytes;

                    ByteBufferList bb = new ByteBufferList();
                    for (byte[] part: parts) {
                        if (part == null)
                            return;
                        bb.add(ByteBuffer.wrap(part));
                    }

                    Util.emitAllData(SmsSocket.this, bb);
                }
                catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
    }

    @Override
    public void write(ByteBuffer bb) {
        write(new ByteBufferList(bb.duplicate()));

        bb.position(bb.limit());
    }

    int messageId;
    @Override
    public void write(ByteBufferList bb) {
        final int thisMessageId = messageId++;

        int parts = (bb.remaining() / 100) + 1;
        int i = 0;

        while (bb.hasRemaining()) {
            BEncodedDictionary dict = new BEncodedDictionary();
            dict.put("m", thisMessageId);
            dict.put("c", parts);
            dict.put("i", i++);
            byte[] bytes = new byte[Math.min(bb.remaining(), 100)];
            bb.get(bytes);
            dict.put("d", bytes);
            SmsManager.getDefault().sendDataMessage(number, null, smsPort, dict.toByteArray(), null, null);
        }
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
