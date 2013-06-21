package org.cyanogenmod.pushsms;

import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.SimpleFuture;

import java.util.ArrayList;

class RegistrationFuture extends SimpleFuture<String> {
    ArrayList<FutureCallback<String>> callbacks = new ArrayList<FutureCallback<String>>();
    long start = System.currentTimeMillis();
    String[] registrationParts;
    FutureCallback<String> callback = new FutureCallback<String>() {
        @Override
        public void onCompleted(Exception e, String result) {
            ArrayList<FutureCallback<String>> cbs = callbacks;
            callbacks = new ArrayList<FutureCallback<String>>();
            for (FutureCallback<String> cb : cbs) {
                cb.onCompleted(e, result);
            }
        }
    };

    public RegistrationFuture() {
        setCallback(callback);
    }

    void addCallback(FutureCallback<String> cb) {
        callbacks.add(cb);
        setCallback(callback);
    }
}
