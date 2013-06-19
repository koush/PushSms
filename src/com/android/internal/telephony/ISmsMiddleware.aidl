package com.android.internal.telephony;

import android.app.PendingIntent;

interface ISmsMiddleware {
    boolean onSendText(in String destAddr, in String scAddr, in String text,
            in PendingIntent sentIntent, in PendingIntent deliveryIntent);

    boolean onSendMultipartText(in String destinationAddress, in String scAddress,
            in List<String> parts, in List<PendingIntent> sentIntents,
            in List<PendingIntent> deliveryIntents);

}
