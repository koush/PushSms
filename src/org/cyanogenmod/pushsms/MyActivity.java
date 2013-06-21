package org.cyanogenmod.pushsms;

import android.accounts.AccountManager;
import android.app.Activity;
import android.app.Dialog;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.GooglePlayServicesAvailabilityException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.AccountPicker;
import com.google.android.gms.common.GooglePlayServicesUtil;

import java.io.IOException;

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
//                SmsManager.getDefault().sendTextMessage("2064951490", null, "hello world", null, null);

                Intent intent = AccountPicker.newChooseAccountIntent(null, null, new String[]{"com.google"},
                        false, null, null, null, null);
                startActivityForResult(intent, REQUEST_GOOGLE);
            }
        });
    }

    private static final int REQUEST_GOOGLE = 5945;
    private static final int REQUEST_AUTH = 5946;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_GOOGLE && resultCode == RESULT_OK && data != null) {
            final String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            if (accountName == null)
                return;

            new Thread() {
                @Override
                public void run() {
                    getAndUseAuthTokenBlocking(accountName);
                }
            }.start();
        }
        else if (requestCode == REQUEST_AUTH && resultCode == RESULT_OK && data != null) {
            System.out.println("token: " + data.getStringExtra(AccountManager.KEY_AUTHTOKEN));
        }
    }

    // Example of how to use the GoogleAuthUtil in a blocking, non-main thread context
    void getAndUseAuthTokenBlocking(final String accountName) {
        try {
            // Retrieve a token for the given account and scope. It will always return either
            // a non-empty String or throw an exception.
            final String token = GoogleAuthUtil.getToken(this, accountName, "oauth2:https://www.googleapis.com/auth/userinfo.email");
            GoogleAuthUtil.invalidateToken(this, token);
        } catch (GooglePlayServicesAvailabilityException playEx) {
            final Dialog alert = GooglePlayServicesUtil.getErrorDialog(
                    playEx.getConnectionStatusCode(),
                    this,
                    0);
            runOnUiThread(new Runnable() {
                @Override
                public void run() {
                    alert.show();
                }
            });
        } catch (UserRecoverableAuthException userAuthEx) {
            // Start the user recoverable action using the intent returned by
            // getIntent()
            startActivityForResult(
                    userAuthEx.getIntent(),
                    REQUEST_AUTH);
            return;
        } catch (IOException transientEx) {
            // network or server error, the call is expected to succeed if you try again later.
            // Don't attempt to call again immediately - the request is likely to
            // fail, you'll hit quotas or back-off.
            return;
        } catch (GoogleAuthException authEx) {
            // Failure. The call is not expected to ever succeed so it should not be
            // retried.
            return;
        }
    }
}
