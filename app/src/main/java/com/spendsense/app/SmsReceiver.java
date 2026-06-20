package com.spendsense.app;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.telephony.SmsMessage;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;

public class SmsReceiver extends BroadcastReceiver {
    private static final String TAG = "SmsReceiver";
    private static final String CHANNEL_ID = "trackmyspend_sms";

    @Override
    public void onReceive(Context context, Intent intent) {
        Log.d(TAG, "=== SMS RECEIVED ===");

        if (intent == null || !"android.provider.Telephony.SMS_RECEIVED".equals(intent.getAction())) {
            Log.d(TAG, "Ignoring non-SMS broadcast: " + (intent != null ? intent.getAction() : "null intent"));
            return;
        }

        Bundle bundle = intent.getExtras();
        if (bundle == null) {
            Log.d(TAG, "Bundle is null");
            return;
        }

        SupabaseHelper supabase = new SupabaseHelper(context);
        if (!supabase.isLoggedIn()) {
            Log.d(TAG, "User not logged in - token: " + supabase.getAccessToken() + ", userId: " + supabase.getUserId());
            return; // Don't process if not logged in
        }
        Log.d(TAG, "User logged in: " + supabase.getUserId());

        Object[] pdus = (Object[]) bundle.get("pdus");
        if (pdus == null || pdus.length == 0) {
            Log.d(TAG, "PDUs is null or empty");
            return;
        }

        StringBuilder fullMessage = new StringBuilder();
        String sender = "";

        for (Object pdu : pdus) {
            try {
                SmsMessage sms = SmsMessage.createFromPdu((byte[]) pdu,
                        bundle.getString("format"));
                if (sms != null) {
                    sender = sms.getOriginatingAddress() != null ? sms.getOriginatingAddress() : "";
                    fullMessage.append(sms.getMessageBody());
                }
            } catch (Exception e) {
                Log.e(TAG, "Error parsing PDU: " + e.getMessage());
            }
        }

        String body = fullMessage.toString();
        Log.d(TAG, "Full message body: '" + body + "'");
        Log.d(TAG, "Sender: '" + sender + "'");

        if (body.isEmpty()) {
            Log.d(TAG, "Empty body, returning");
            return;
        }

        Log.d(TAG, "SMS from: " + sender + " → " + body);

        // Parse the SMS
        SmsParser.ParsedTransaction parsed = null;
        try {
            parsed = SmsParser.parse(sender, body);
        } catch (Exception e) {
            Log.e(TAG, "Error parsing SMS: " + e.getMessage(), e);
        }

        if (parsed == null) {
            Log.d(TAG, "parse() returned null - not a recognized financial SMS");
            return;
        }

        Log.d(TAG, "Parsed: " + parsed.type + " ₹" + parsed.amount + " — " + parsed.description);
        Log.d(TAG, "Merchant hint: " + parsed.merchantHint);

        // Deduplication check — prevents double-counting if notification also fires
        TransactionDeduplicator dedup = new TransactionDeduplicator(context);
        boolean isDup = false;
        try {
            isDup = dedup.isDuplicate(parsed.amount, parsed.type, parsed.merchantHint);
        } catch (Exception e) {
            Log.e(TAG, "Error in deduplication: " + e.getMessage());
        }

        Log.d(TAG, "Deduplication result: isDuplicate=" + isDup);
        if (isDup) {
            Log.d(TAG, "Duplicate SMS transaction, skipping (notification already recorded)");
            return;
        }

        // Auto-categorize based on keywords
        final String category = guessCategory(body);
        final SmsParser.ParsedTransaction finalParsed = parsed;

        final PendingResult pendingResult = goAsync();

        // Insert into Supabase
        Log.d(TAG, "Calling insertTransaction with: type=" + parsed.type + ", amount=" + parsed.amount + ", category=" + category);

        try {
            supabase.insertTransaction(
                    parsed.type,
                    parsed.amount,
                    category,
                    parsed.description,
                    "sms",
                    parsed.rawSms,
                    new SupabaseHelper.SimpleCallback() {
                        @Override
                        public void onSuccess() {
                            Log.d(TAG, "✓ Transaction saved from SMS! SUCCESS");
                            try {
                                showNotification(context, finalParsed);
                            } catch (Exception e) {
                                Log.e(TAG, "Error showing notification: " + e.getMessage());
                            }
                            try {
                                pendingResult.finish();
                            } catch (Exception e) {
                                Log.e(TAG, "Error finishing pending result: " + e.getMessage());
                            }
                        }

                        @Override
                        public void onError(String message) {
                            Log.e(TAG, "✗ Failed to save SMS transaction: " + message);
                            try {
                                pendingResult.finish();
                            } catch (Exception e) {
                                Log.e(TAG, "Error finishing pending result: " + e.getMessage());
                            }
                        }
                    }
            );
        } catch (Exception e) {
            Log.e(TAG, "Exception in insertTransaction call: " + e.getMessage(), e);
            pendingResult.finish();
        }
    }

    private String guessCategory(String body) {
        String upper = body.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("SWIGGY") || upper.contains("ZOMATO") || upper.contains("FOOD")
                || upper.contains("RESTAURANT") || upper.contains("HOTEL")
                || upper.contains("DOMINOS") || upper.contains("PIZZA")
                || upper.contains("MCDONALD") || upper.contains("KFC")) return "Food & Dining";
        if (upper.contains("UBER") || upper.contains("OLA") || upper.contains("RAPIDO")
                || upper.contains("METRO") || upper.contains("IRCTC")
                || upper.contains("FUEL") || upper.contains("PETROL")) return "Transport";
        if (upper.contains("AMAZON") || upper.contains("FLIPKART") || upper.contains("MYNTRA")
                || upper.contains("SHOPPING") || upper.contains("MEESHO")) return "Shopping";
        if (upper.contains("ELECTRIC") || upper.contains("WATER") || upper.contains("GAS")
                || upper.contains("BILL") || upper.contains("RENT")) return "Bills & Utilities";
        if (upper.contains("NETFLIX") || upper.contains("SPOTIFY") || upper.contains("HOTSTAR")
                || upper.contains("MOVIE") || upper.contains("GAME")) return "Entertainment";
        if (upper.contains("PHARMACY") || upper.contains("MEDICAL") || upper.contains("HOSPITAL")
                || upper.contains("DOCTOR")) return "Health";
        if (upper.contains("RECHARGE") || upper.contains("PREPAID") || upper.contains("AIRTEL")
                || upper.contains("JIO") || upper.contains("VI ")) return "Recharge";
        if (upper.contains("SALARY") || upper.contains("STIPEND") || upper.contains("WAGE")) return "Salary";
        if (upper.contains("TRANSFER") || upper.contains("NEFT") || upper.contains("IMPS")) return "Transfer";
        if (upper.contains("COURSE") || upper.contains("UDEMY") || upper.contains("SCHOOL")
                || upper.contains("EDUCATION")) return "Education";
        return "Other";
    }

    private void showNotification(Context context, SmsParser.ParsedTransaction parsed) {
        createChannel(context);

        String sign = "debit".equals(parsed.type) ? "−" : "+";
        String title = sign + "₹" + String.format(java.util.Locale.US, "%.0f", parsed.amount);
        String text = parsed.description + " • Auto-tracked via SMS";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    private void createChannel(Context context) {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "TrackMySpend SMS", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Auto-tracked transactions from bank SMS");
        NotificationManager manager = context.getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
