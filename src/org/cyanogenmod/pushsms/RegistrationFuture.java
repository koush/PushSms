package org.cyanogenmod.pushsms;

import com.koushikdutta.async.future.FutureCallback;
import com.koushikdutta.async.future.SimpleFuture;

import java.util.ArrayList;

class RegistrationFuture extends SimpleFuture<Registration> {
    ArrayList<FutureCallback<Registration>> callbacks = new ArrayList<FutureCallback<Registration>>();
    FutureCallback<Registration> callback = new FutureCallback<Registration>() {
        @Override
        public void onCompleted(Exception e, Registration result) {
            ArrayList<FutureCallback<Registration>> cbs = callbacks;
            callbacks = new ArrayList<FutureCallback<Registration>>();
            for (FutureCallback<Registration> cb : cbs) {
                cb.onCompleted(e, result);
            }
        }
    };

    public RegistrationFuture() {
        setCallback(callback);
    }

    void addCallback(FutureCallback<Registration> cb) {
        callbacks.add(cb);
        setCallback(callback);
    }
}
