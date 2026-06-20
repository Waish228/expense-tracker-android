package com.spendsense.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;

/**
 * Prevents duplicate transaction entries when both SMS and notification
 * fire for the same transaction, or when multiple notifications arrive.
 *
 * Fingerprint = hash(amount + type + normalized_time + merchant_hint)
 * Time window = 2 minutes (normalized to 2-minute blocks)
 */
public class TransactionDeduplicator {
    private static final String TAG = "TxDedup";
    private static final String PREFS_NAME = "trackmyspend_dedup";
    private static final long WINDOW_MS = 2 * 60 * 1000; // 2 minutes
    private static final long EXPIRY_MS = 10 * 60 * 1000; // 10 minutes — clean up old entries

    private final SharedPreferences prefs;

    public TransactionDeduplicator(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns true if this transaction is a duplicate (already seen).
     * Returns false if it's new (and records it).
     */
    public synchronized boolean isDuplicate(double amount, String type, String merchantHint) {
        cleanExpired();

        String fingerprint = generateFingerprint(amount, type, merchantHint);
        long now = System.currentTimeMillis();

        // Check if we've seen this fingerprint recently
        if (prefs.contains(fingerprint)) {
            long storedTime = prefs.getLong(fingerprint, 0);
            if (now - storedTime < EXPIRY_MS) {
                Log.d(TAG, "DUPLICATE detected: " + fingerprint);
                return true;
            }
        }

        // Record this fingerprint
        prefs.edit().putLong(fingerprint, now).apply();
        Log.d(TAG, "NEW transaction recorded: " + fingerprint);
        return false;
    }

    /**
     * Generates a fingerprint by hashing amount + type + time_window + merchant.
     * The time is normalized to 2-minute windows so that an SMS arriving at :01
     * and a notification arriving at :02 produce the same fingerprint.
     */
    private String generateFingerprint(double amount, String type, String merchantHint) {
        long timeWindow = System.currentTimeMillis() / WINDOW_MS;
        String normalizedMerchant = normalizeMerchant(merchantHint);
        String raw = String.format(java.util.Locale.US, "%.2f|%s|%d|%s",
                amount, type != null ? type : "debit", timeWindow, normalizedMerchant);

        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(raw.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 8; i++) { // Use first 8 bytes for shorter key
                sb.append(String.format("%02x", hash[i]));
            }
            return "fp_" + sb.toString();
        } catch (Exception e) {
            // Fallback: use raw string as key
            return "fp_" + raw.hashCode();
        }
    }

    /**
     * Normalizes merchant names so "SWIGGY", "Swiggy", "swiggy" all match.
     */
    private String normalizeMerchant(String merchant) {
        if (merchant == null || merchant.isEmpty()) return "unknown";
        return merchant.toUpperCase(java.util.Locale.ROOT)
                .replaceAll("[^A-Z0-9]", "")
                .trim();
    }

    /**
     * Removes fingerprints older than EXPIRY_MS.
     */
    private void cleanExpired() {
        long now = System.currentTimeMillis();
        Map<String, ?> all = prefs.getAll();
        SharedPreferences.Editor editor = prefs.edit();
        boolean needsApply = false;

        for (Map.Entry<String, ?> entry : all.entrySet()) {
            if (entry.getValue() instanceof Long) {
                long storedTime = (Long) entry.getValue();
                if (now - storedTime > EXPIRY_MS) {
                    editor.remove(entry.getKey());
                    needsApply = true;
                }
            }
        }

        if (needsApply) {
            editor.apply();
        }
    }
}
