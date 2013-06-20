package com.koushikdutta.test;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.view.View;
import android.widget.Button;

import com.google.gson.JsonObject;

public class MyActivity extends Activity {
    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        startService(new Intent(this, Service.class));


//
//        SmsMessage.SubmitPdu submit = SmsMessage.getSubmitPdu("2064228017", "2065528017", "hello", false);
//
//        SmsMessage sms = SmsMessage.createFromPdu(submit.encodedMessage);
//        System.out.println(sms.getMessageBody());

        Button button = (Button)findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                SmsManager.getDefault().sendDataMessage("2064951490", null, Short.valueOf(getString(R.string.sms_port)), new byte[100], null, null);
                SmsManager.getDefault().sendTextMessage("2064951490", null, "hello world", null, null);
            }
        });
    }
}
