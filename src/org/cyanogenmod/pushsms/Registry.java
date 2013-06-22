package org.cyanogenmod.pushsms;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Hashtable;
import java.util.Map;

/**
 * Created by koush on 6/19/13.
 */
public class Registry {
    public static final RegistrationFuture NOT_REGISTERED = new RegistrationFuture() {
        {
            setComplete(new Exception("not supported"));
        }
    };

    private Context mContext;
    private SharedPreferences mSharedPreferences;
    private SharedPreferences.Editor mEditor;
    public Registry(Context context) {
        mContext = context;
        mSharedPreferences = mContext.getSharedPreferences("registry", Context.MODE_PRIVATE);
        mEditor = mSharedPreferences.edit();
    }

    public void register(String number, String registration) {
        mEditor.putString(number, registration);
        mEditor.commit();
    }

    public void unregister(String number) {
        mEditor.putString(number, "not_registered");
        mEditor.commit();
    }

    public void load(Hashtable<String, RegistrationFuture> numberToRegistration) {
        try {
            Map<String, ?> current = mSharedPreferences.getAll();
            for (String number: current.keySet()) {
                Object registration = current.get(number);
                if (registration instanceof String) {
                    RegistrationFuture future;
                    String regString = (String)registration;
                    if ("not_registered".equals((String)regString)) {
                        future = NOT_REGISTERED;
                    }
                    else {
                        future = new RegistrationFuture();
                        future.setComplete(regString);
                    }
                    numberToRegistration.put(number, future);
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
