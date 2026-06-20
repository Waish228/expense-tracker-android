package com.spendsense.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Context;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import androidx.core.app.NotificationCompat;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Listens for notifications from UPI and banking apps to automatically
 * capture transaction data in real-time.
 *
 * Supports: PhonePe, Google Pay, Paytm, Amazon Pay, CRED, MobiKwik,
 * WhatsApp Pay, BHIM, Samsung Pay, and all major bank apps.
 */
public class TransactionNotificationListener extends NotificationListenerService {
    private static final String TAG = "TxNotifListener";
    private static final String CHANNEL_ID = "trackmyspend_notif";

    // Package names of UPI & banking apps to monitor
    private static final Set<String> MONITORED_PACKAGES = new HashSet<>(Arrays.asList(
            // UPI Apps
            "com.phonepe.app",                   // PhonePe
            "com.google.android.apps.nbu.paisa.user", // Google Pay
            "net.one97.paytm",                   // Paytm
            "in.amazon.mShop.android.shopping",  // Amazon Pay
            "com.dreamplug.androidapp",          // CRED
            "com.mobikwik_new",                  // MobiKwik
            "com.whatsapp",                      // WhatsApp Pay
            "in.org.npci.upiapp",                // BHIM UPI
            "com.samsung.android.spay",          // Samsung Pay
            "com.myairtelapp",                   // Airtel Payments Bank
            "com.jio.myjio",                     // JioMoney
            "in.finshell.app",                   // FreeCharge
            "com.freecharge.android",            // FreeCharge old
            "com.csam.icici.bank.imobile",       // iMobile Pay (ICICI)
            "com.ofss.fcdb",                     // IDFC First Bank
            "com.axis.mobile",                   // Axis Mobile
            "com.sbi.SBIFreedomPlus",            // YONO SBI
            "com.unionbankofindia.uMobile",      // Union Bank
            "com.BOI.BOIMobile",                 // Bank of India
            "com.msf.kbank.mobile",              // Kotak
            "com.snapwork.hdfc",                 // HDFC Bank
            "com.infrasofttech.indianbank",      // Indian Bank
            "com.canaaboretree.mobilebanking",   // Canara Bank
            "com.bob.banking",                   // Bank of Baroda
            "com.pnb.nb.stamped"                 // PNB
    ));

    // Regex patterns for extracting amounts from notification text
    private static final Pattern AMOUNT_PATTERN = Pattern.compile(
            "(?:Rs\\.?|INR|₹)\\s*([\\d,]+(?:\\.\\d{1,2})?)", Pattern.CASE_INSENSITIVE);

    // Keywords indicating debit
    private static final String[] DEBIT_KEYWORDS = {
            "debited", "debit", "sent", "paid", "payment of", "spent",
            "withdrawn", "purchase", "charged", "transferred to"
    };

    // Keywords indicating credit
    private static final String[] CREDIT_KEYWORDS = {
            "credited", "credit", "received", "cashback", "refund",
            "deposited", "reward", "transferred from"
    };

    // Keywords to SKIP (not financial)
    private static final String[] SKIP_KEYWORDS = {
            "otp", "verification", "promo", "offer", "download",
            "update", "install", "rate us", "rate the", "reminder"
    };

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        if (sbn == null) return;

        String packageName = sbn.getPackageName();
        if (!MONITORED_PACKAGES.contains(packageName)) return;

        Notification notification = sbn.getNotification();
        if (notification == null) return;

        Bundle extras = notification.extras;
        if (extras == null) return;

        CharSequence titleCs = extras.getCharSequence(Notification.EXTRA_TITLE);
        CharSequence textCs = extras.getCharSequence(Notification.EXTRA_TEXT);
        CharSequence bigTextCs = extras.getCharSequence(Notification.EXTRA_BIG_TEXT);

        String title = titleCs != null ? titleCs.toString() : "";
        String text = textCs != null ? textCs.toString() : "";
        String bigText = bigTextCs != null ? bigTextCs.toString() : "";

        // Combine all text for parsing
        String fullText = title + " " + text + " " + bigText;
        if (fullText.trim().isEmpty()) return;

        Log.d(TAG, "Notification from " + packageName + ": " + fullText);

        // Check if logged in
        SupabaseHelper supabase = new SupabaseHelper(this);
        if (!supabase.isLoggedIn()) return;

        // Parse the notification
        ParsedNotification parsed = parseNotification(packageName, title, text, bigText);
        if (parsed == null) return;

        Log.d(TAG, "Parsed notification: " + parsed.type + " ₹" + parsed.amount
                + " — " + parsed.description);

        // Deduplication check
        TransactionDeduplicator dedup = new TransactionDeduplicator(this);
        if (dedup.isDuplicate(parsed.amount, parsed.type, parsed.merchantHint)) {
            Log.d(TAG, "Duplicate notification, skipping");
            return;
        }

        // Auto-categorize
        String category = guessCategory(fullText);

        // Insert into Supabase
        supabase.insertTransaction(
                parsed.type,
                parsed.amount,
                category,
                parsed.description,
                "notification",
                fullText, // store raw notification text
                new SupabaseHelper.SimpleCallback() {
                    @Override
                    public void onSuccess() {
                        Log.d(TAG, "Transaction saved from notification!");
                        showFeedbackNotification(parsed);
                    }

                    @Override
                    public void onError(String message) {
                        Log.e(TAG, "Failed to save notification transaction: " + message);
                    }
                }
        );
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Not needed
    }

    // ── Notification Parsing ──

    private static class ParsedNotification {
        String type;
        double amount;
        String description;
        String merchantHint;
    }

    private ParsedNotification parseNotification(String pkg, String title, String text, String bigText) {
        String combined = (title + " " + text + " " + bigText).trim();
        String upper = combined.toUpperCase(java.util.Locale.ROOT);

        // Skip non-financial notifications
        for (String skip : SKIP_KEYWORDS) {
            if (upper.contains(skip.toUpperCase(java.util.Locale.ROOT))) {
                return null;
            }
        }

        // Extract amount
        double amount = extractAmount(combined);
        if (amount <= 0) return null;

        // Determine type
        String type = null;
        for (String kw : DEBIT_KEYWORDS) {
            if (upper.contains(kw.toUpperCase(java.util.Locale.ROOT))) {
                type = "debit";
                break;
            }
        }
        if (type == null) {
            for (String kw : CREDIT_KEYWORDS) {
                if (upper.contains(kw.toUpperCase(java.util.Locale.ROOT))) {
                    type = "credit";
                    break;
                }
            }
        }

        // If type still not detected, try app-specific heuristics
        if (type == null) {
            type = inferTypeFromApp(pkg, upper);
        }

        if (type == null) return null;

        // Build description from app name
        String appName = getAppName(pkg);
        String description = appName;

        // Try to extract merchant/recipient
        String merchant = extractMerchant(combined);
        if (merchant != null && !merchant.isEmpty()) {
            description += " → " + merchant;
        }

        ParsedNotification parsed = new ParsedNotification();
        parsed.type = type;
        parsed.amount = amount;
        parsed.description = description;
        parsed.merchantHint = merchant != null ? merchant : appName;
        return parsed;
    }

    private double extractAmount(String text) {
        Matcher m = AMOUNT_PATTERN.matcher(text);
        if (m.find()) {
            String amountStr = m.group(1).replace(",", "");
            try {
                return Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                return 0;
            }
        }
        return 0;
    }

    private String extractMerchant(String text) {
        // Try "to <merchant>" pattern
        Pattern toPattern = Pattern.compile("(?:to|from|at|for)\\s+([A-Za-z][\\w\\s&'.@-]{1,30})",
                Pattern.CASE_INSENSITIVE);
        Matcher m = toPattern.matcher(text);
        if (m.find()) {
            String merchant = m.group(1).trim();
            // Clean up common suffixes
            merchant = merchant.replaceAll("(?i)\\s*(via|using|through|on|from|upi|ref).*$", "").trim();
            if (!merchant.isEmpty() && merchant.length() > 1) {
                return merchant;
            }
        }
        return null;
    }

    private String inferTypeFromApp(String pkg, String upperText) {
        // PhonePe: "Paid ₹100" → debit, "Received ₹100" → credit
        // Google Pay: "You paid" → debit, "received" → credit
        // Default heuristic: if contains "paid" or "pay" → debit
        if (upperText.contains("PAY") || upperText.contains("PAYMENT")) return "debit";
        if (upperText.contains("RECEIVE") || upperText.contains("GOT")) return "credit";
        return null;
    }

    private String getAppName(String packageName) {
        switch (packageName) {
            case "com.phonepe.app": return "PhonePe";
            case "com.google.android.apps.nbu.paisa.user": return "Google Pay";
            case "net.one97.paytm": return "Paytm";
            case "in.amazon.mShop.android.shopping": return "Amazon Pay";
            case "com.dreamplug.androidapp": return "CRED";
            case "com.mobikwik_new": return "MobiKwik";
            case "com.whatsapp": return "WhatsApp Pay";
            case "in.org.npci.upiapp": return "BHIM UPI";
            case "com.samsung.android.spay": return "Samsung Pay";
            case "com.myairtelapp": return "Airtel Payments";
            case "com.jio.myjio": return "JioMoney";
            case "com.csam.icici.bank.imobile": return "iMobile Pay";
            case "com.sbi.SBIFreedomPlus": return "YONO SBI";
            case "com.axis.mobile": return "Axis Mobile";
            case "com.snapwork.hdfc": return "HDFC Bank";
            case "com.unionbankofindia.uMobile": return "Union Bank";
            default: return "UPI Payment";
        }
    }

    // ── Category Guessing ──
    private String guessCategory(String body) {
        String upper = body.toUpperCase(java.util.Locale.ROOT);
        if (upper.contains("SWIGGY") || upper.contains("ZOMATO") || upper.contains("FOOD")
                || upper.contains("RESTAURANT") || upper.contains("HOTEL")
                || upper.contains("DOMINOS") || upper.contains("PIZZA")
                || upper.contains("MCDONALD") || upper.contains("KFC")
                || upper.contains("STARBUCKS") || upper.contains("CAFE")) return "Food & Dining";
        if (upper.contains("UBER") || upper.contains("OLA") || upper.contains("RAPIDO")
                || upper.contains("METRO") || upper.contains("IRCTC")
                || upper.contains("REDBUS") || upper.contains("FUEL")
                || upper.contains("PETROL") || upper.contains("DIESEL")) return "Transport";
        if (upper.contains("AMAZON") || upper.contains("FLIPKART") || upper.contains("MYNTRA")
                || upper.contains("SHOPPING") || upper.contains("MEESHO")
                || upper.contains("AJIO") || upper.contains("NYKAA")) return "Shopping";
        if (upper.contains("ELECTRIC") || upper.contains("WATER") || upper.contains("GAS")
                || upper.contains("BILL") || upper.contains("RENT")
                || upper.contains("BROADBAND") || upper.contains("WIFI")) return "Bills & Utilities";
        if (upper.contains("NETFLIX") || upper.contains("SPOTIFY") || upper.contains("HOTSTAR")
                || upper.contains("MOVIE") || upper.contains("GAME")
                || upper.contains("YOUTUBE") || upper.contains("PRIME")) return "Entertainment";
        if (upper.contains("PHARMACY") || upper.contains("MEDICAL") || upper.contains("HOSPITAL")
                || upper.contains("DOCTOR") || upper.contains("APOLLO")
                || upper.contains("1MG") || upper.contains("NETMEDS")) return "Health";
        if (upper.contains("RECHARGE") || upper.contains("PREPAID") || upper.contains("AIRTEL")
                || upper.contains("JIO") || upper.contains("VI ")) return "Recharge";
        if (upper.contains("SALARY") || upper.contains("STIPEND") || upper.contains("WAGE")) return "Salary";
        if (upper.contains("TRANSFER") || upper.contains("NEFT") || upper.contains("IMPS")) return "Transfer";
        if (upper.contains("COURSE") || upper.contains("UDEMY") || upper.contains("SCHOOL")
                || upper.contains("COLLEGE") || upper.contains("EDUCATION")) return "Education";
        return "Other";
    }

    // ── User Feedback ──
    private void showFeedbackNotification(ParsedNotification parsed) {
        createChannel();

        String sign = "debit".equals(parsed.type) ? "−" : "+";
        String title = sign + "₹" + String.format(java.util.Locale.US, "%.0f", parsed.amount);
        String text = parsed.description + " • Auto-tracked";

        NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true);

        NotificationManager manager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager != null) {
            manager.notify((int) System.currentTimeMillis(), builder.build());
        }
    }

    private void createChannel() {
        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID, "TrackMySpend Auto-Track", NotificationManager.IMPORTANCE_HIGH);
        channel.setDescription("Auto-tracked transactions from UPI and banking apps");
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager != null) {
            manager.createNotificationChannel(channel);
        }
    }
}
