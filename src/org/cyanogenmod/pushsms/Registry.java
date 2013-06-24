package org.cyanogenmod.pushsms;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.util.Hashtable;
import java.util.Map;

/**
 * Created by koush on 6/19/13.
 */
public class Registry {
    private Context mContext;
    private SharedPreferences mSharedPreferences;
    private SharedPreferences.Editor mEditor;
    public Registry(Context context) {
        mContext = context;
        mSharedPreferences = mContext.getSharedPreferences("registry", Context.MODE_PRIVATE);
        mEditor = mSharedPreferences.edit();
    }

    public void register(String number, Registration registration) {
        mEditor.putString(number, registration.encode());
        mEditor.commit();
    }

    public void load(Hashtable<String, RegistrationFuture> numberToRegistration) {
        try {
            Map<String, ?> current = mSharedPreferences.getAll();
            for (String number: current.keySet()) {
                Object r = current.get(number);
                if (r instanceof String) {
                    try {
                        Registration registration = Registration.parse((String)r);
                        if (registration == null)
                            continue;
                        RegistrationFuture future = new RegistrationFuture();
                        future.setComplete(registration);
                        numberToRegistration.put(number, future);
                    }
                    catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        }
        catch (Exception e) {
            e.printStackTrace();
        }
    }
}
