package com.spendsense.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.security.MessageDigest;
import java.util.Map;

/**
 * Prevents duplicate transaction entries when both SMS and notification
 * fire for the same transaction, or when multiple notifications arrive.
 *
 * Unlike the original fingerprint-only approach (which required an exact
 * amount + exact 2-minute time bucket + exact merchant string match),
 * this version also keeps a short list of recent raw transactions and
 * compares against them with tolerance, since:
 *
 *  - SMS delivery can lag behind in-app notifications by several minutes
 *    on a congested network, missing a tight time-bucket match.
 *  - The SMS parser and the notification parser extract merchant names
 *    with different regexes and can produce slightly different strings
 *    for the same real transaction (e.g. "SWIGGY" vs "Swiggy Order").
 *  - Rounding/fee differences between channels can shift the amount by
 *    a small amount.
 *
 * Two transactions are now considered duplicates if they fall within a
 * widened time window AND have the same type AND amounts within a small
 * tolerance -- merchant match is treated as a bonus signal, not a hard
 * requirement, since it's the least reliable field across channels.
 */
public class TransactionDeduplicator {
    private static final String TAG = "TxDedup";
    private static final String PREFS_NAME = "trackmyspend_dedup";
    private static final String RECENT_PREFS_NAME = "trackmyspend_dedup_recent";

    // Widened from 2 minutes to 6 minutes to tolerate SMS delivery lag
    // relative to instant in-app notifications.
    private static final long WINDOW_MS = 6 * 60 * 1000;
    private static final long EXPIRY_MS = 15 * 60 * 1000; // clean up old entries after 15 min

    // Amount tolerance to absorb minor rounding/fee differences between
    // SMS and notification text for what is really the same transaction.
    private static final double AMOUNT_TOLERANCE = 1.0; // currency units

    private final SharedPreferences prefs;
    private final SharedPreferences recentPrefs;

    public TransactionDeduplicator(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        recentPrefs = context.getSharedPreferences(RECENT_PREFS_NAME, Context.MODE_PRIVATE);
    }

    /**
     * Returns true if this transaction is a duplicate (already seen).
     * Returns false if it's new (and records it).
     */
    public synchronized boolean isDuplicate(double amount, String type, String merchantHint) {
        cleanExpired();

        long now = System.currentTimeMillis();

        // Pass 1: exact fingerprint match (fast path, same as before)
        String fingerprint = generateFingerprint(amount, type, merchantHint);
        if (prefs.contains(fingerprint)) {
            long storedTime = prefs.getLong(fingerprint, 0);
            if (now - storedTime < EXPIRY_MS) {
                Log.d(TAG, "DUPLICATE detected (exact fingerprint): " + fingerprint);
                return true;
            }
        }

        // Pass 2: tolerant match against recently recorded raw transactions.
        // Catches cases where merchant text differs between SMS/notification
        // parsers, or the time bucket shifted due to SMS delivery lag.
        Map<String, ?> recent = recentPrefs.getAll();
        for (Map.Entry<String, ?> entry : recent.entrySet()) {
            String value = String.valueOf(entry.getValue());
            String[] parts = value.split("\\|", 3);
            if (parts.length != 3) continue;

            try {
                double storedAmount = Double.parseDouble(parts[0]);
                String storedType = parts[1];
                long storedTime = Long.parseLong(parts[2]);

                boolean withinWindow = Math.abs(now - storedTime) <= WINDOW_MS;
                boolean amountClose = Math.abs(storedAmount - amount) <= AMOUNT_TOLERANCE;
                boolean sameType = storedType.equals(type != null ? type : "debit");

                if (withinWindow && amountClose && sameType) {
                    Log.d(TAG, "DUPLICATE detected (tolerant match): amount=" + amount
                            + " type=" + type + " against stored=" + value);
                    return true;
                }
            } catch (NumberFormatException ignored) {
                // malformed entry, skip
            }
        }

        // Not a duplicate -- record both the exact fingerprint and the
        // raw values for tolerant matching against future events.
        prefs.edit().putLong(fingerprint, now).apply();
        String recentKey = "tx_" + now + "_" + (int) (Math.random() * 100000);
        recentPrefs.edit()
                .putString(recentKey, amount + "|" + (type != null ? type : "debit") + "|" + now)
                .apply();

        Log.d(TAG, "NEW transaction recorded: " + fingerprint);
        return false;
    }

    /**
     * Generates a fingerprint by hashing amount + type + time_window + merchant.
     * The time is normalized to time-window blocks so that an SMS arriving at :01
     * and a notification arriving at :02 produce the same fingerprint, as long
     * as both land in the same window.
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
            for (int i = 0; i < 8; i++) {
                sb.append(String.format("%02x", hash[i]));
            }
            return "fp_" + sb.toString();
        } catch (Exception e) {
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
     * Removes fingerprints and recent-transaction entries older than EXPIRY_MS.
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
        if (needsApply) editor.apply();

        Map<String, ?> recent = recentPrefs.getAll();
        SharedPreferences.Editor recentEditor = recentPrefs.edit();
        boolean needsApplyRecent = false;
        for (Map.Entry<String, ?> entry : recent.entrySet()) {
            String value = String.valueOf(entry.getValue());
            String[] parts = value.split("\\|", 3);
            if (parts.length != 3) {
                recentEditor.remove(entry.getKey());
                needsApplyRecent = true;
                continue;
            }
            try {
                long storedTime = Long.parseLong(parts[2]);
                if (now - storedTime > EXPIRY_MS) {
                    recentEditor.remove(entry.getKey());
                    needsApplyRecent = true;
                }
            } catch (NumberFormatException e) {
                recentEditor.remove(entry.getKey());
                needsApplyRecent = true;
            }
        }
        if (needsApplyRecent) recentEditor.apply();
    }

    /**
     * Clears all dedup state. Exposed for the "Clear Dedup Cache" debug tool.
     */
    public void clearAll() {
        prefs.edit().clear().apply();
        recentPrefs.edit().clear().apply();
    }
}