package org.cyanogenmod.pushsms;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.Activity;
import android.app.AlertDialog;
import android.app.Dialog;
import android.app.ProgressDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.telephony.SmsManager;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckedTextView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.Switch;

import com.google.android.gms.auth.GoogleAuthException;
import com.google.android.gms.auth.GoogleAuthUtil;
import com.google.android.gms.auth.GooglePlayServicesAvailabilityException;
import com.google.android.gms.auth.UserRecoverableAuthException;
import com.google.android.gms.common.GooglePlayServicesUtil;

import java.io.IOException;

public class MyActivity extends Activity {
    class AccountAdapter extends ArrayAdapter<Account> {
        AccountAdapter() {
            super(MyActivity.this, android.R.layout.simple_list_item_multiple_choice);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View view = super.getView(position, convertView, parent);

            CheckedTextView tv = (CheckedTextView) view.findViewById(android.R.id.text1);
            tv.setText(getItem(position).name);

            Account account = getItem(position);

            tv.setChecked(accounts.getBoolean(account.name, false));
            return view;
        }
    }

    AccountAdapter accountAdapter;
    SharedPreferences accounts;
    SharedPreferences settings;

    void login(final String accountName) {
        final ProgressDialog dlg = new ProgressDialog(this);
        dlg.setMessage(getString(R.string.requesting_authorization));
        dlg.setIndeterminate(true);
        dlg.setCancelable(false);
        dlg.show();

        new Thread() {
            @Override
            public void run() {
                getAndUseAuthTokenBlocking(accountName);
                dlg.dismiss();
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        accountAdapter.notifyDataSetInvalidated();
                    }
                });
                reregister();
            }
        }.start();
    }

    private String getNumber() {
        final TelephonyManager tm = (TelephonyManager) getSystemService(TELEPHONY_SERVICE);
        String ret = settings.getString("phone_number", tm.getLine1Number());
        if (TextUtils.isEmpty(ret))
            ret = tm.getLine1Number();
        return ret;
    }

    /**
     * Called when the activity is first created.
     */
    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        accounts = getSharedPreferences("accounts", MODE_PRIVATE);
        settings = getSharedPreferences("settings", MODE_PRIVATE);

        ListView lv = (ListView) findViewById(R.id.list);
        View header = getLayoutInflater().inflate(R.layout.header, null);
        lv.addHeaderView(header);
        lv.setAdapter(accountAdapter = new AccountAdapter());

        final Switch toggle = (Switch)header.findViewById(R.id.toggle);
        toggle.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                settings.edit().putBoolean("enabled", toggle.isChecked()).commit();
            }
        });
        toggle.setChecked(settings.getBoolean("enabled", true));

        final Button phoneNumber = (Button) header.findViewById(R.id.phone_number);
        phoneNumber.setText(getNumber());
        phoneNumber.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                final EditText input = new EditText(MyActivity.this);

                new AlertDialog.Builder(MyActivity.this)
                        .setTitle(R.string.phone_number)
                        .setView(input)
                        .setPositiveButton(android.R.string.ok, new DialogInterface.OnClickListener() {
                            public void onClick(DialogInterface dialog, int whichButton) {
                                settings.edit().putString("phone_number", input.getText().toString()).commit();
                                phoneNumber.setText(getNumber());
                            }
                        }).setNegativeButton(android.R.string.cancel, new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface dialog, int whichButton) {
                        // Do nothing.
                    }
                }).show();
            }
        });

        for (Account account : AccountManager.get(this).getAccountsByType("com.google")) {
            accountAdapter.add(account);
        }

        lv.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            @Override
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                CheckedTextView tv = (CheckedTextView) view.findViewById(android.R.id.text1);
                Account account = accountAdapter.getItem((int)id);
                if (tv.isChecked()) {
                    accounts.edit().putBoolean(account.name, false).commit();
                    tv.toggle();
                    reregister();
                    return;
                }

                login(account.name);
            }
        });

        startService(new Intent(this, MiddlewareService.class));

        Button button = (Button) findViewById(R.id.button);
        button.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
//                SmsManager.getDefault().sendDataMessage("2064951490", null, Short.valueOf(getString(R.string.sms_port)), new byte[100], null, null);
                SmsManager.getDefault().sendTextMessage("2064228017", null, "hello world " + System.currentTimeMillis(), null, null);
            }
        });
    }

    private void reregister() {
        settings.edit().putBoolean("needs_register", true).commit();
        Intent intent = new Intent(this, MiddlewareService.class);
        intent.setAction(MiddlewareService.ACTION_REGISTER);
        startService(intent);
    }

    private static final int REQUEST_AUTH = 5946;

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        if (requestCode == REQUEST_AUTH && resultCode == RESULT_OK && data != null) {
            final String accountName = data.getStringExtra(AccountManager.KEY_ACCOUNT_NAME);
            accounts.edit().putBoolean(accountName, true).commit();
            accountAdapter.notifyDataSetInvalidated();
            reregister();
        }
    }

    // Example of how to use the GoogleAuthUtil in a blocking, non-main thread context
    void getAndUseAuthTokenBlocking(final String accountName) {
        try {
            // Retrieve a token for the given account and scope. It will always return either
            // a non-empty String or throw an exception.
            GoogleAuthUtil.getToken(this, accountName, "oauth2:https://www.googleapis.com/auth/userinfo.email");
            accounts.edit().putBoolean(accountName, true).commit();
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
