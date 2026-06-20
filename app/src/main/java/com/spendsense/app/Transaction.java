package com.spendsense.app;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Objects;

public class Transaction {
    public String id;
    public String userId;
    public String type;       // "credit" or "debit"
    public double amount;
    public String category;
    public String description;
    public String source;      // "sms", "notification", or "manual"
    public String smsRaw;
    public String transactionDate;
    public String createdAt;

    // Safe JSON string extraction
    private static String safeString(JsonObject obj, String key, String fallback) {
        if (obj.has(key)) {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull()) {
                return el.getAsString();
            }
        }
        return fallback;
    }

    private static double safeDouble(JsonObject obj, String key, double fallback) {
        if (obj.has(key)) {
            JsonElement el = obj.get(key);
            if (el != null && !el.isJsonNull()) {
                try {
                    return el.getAsDouble();
                } catch (NumberFormatException e) {
                    return fallback;
                }
            }
        }
        return fallback;
    }

    public static Transaction fromJson(JsonObject obj) {
        Transaction tx = new Transaction();
        tx.id = safeString(obj, "id", "");
        tx.userId = safeString(obj, "user_id", "");
        tx.type = safeString(obj, "type", "debit");
        tx.amount = safeDouble(obj, "amount", 0);
        tx.category = safeString(obj, "category", "Other");
        tx.description = safeString(obj, "description", "");
        tx.source = safeString(obj, "source", "manual");
        tx.smsRaw = safeString(obj, "sms_raw", null);
        tx.transactionDate = safeString(obj, "transaction_date", "");
        tx.createdAt = safeString(obj, "created_at", "");
        return tx;
    }

    public String getCategoryIcon() {
        if (category == null) return "📁";
        switch (category) {
            case "Food & Dining": return "🍔";
            case "Transport": return "🚗";
            case "Shopping": return "🛍️";
            case "Bills & Utilities": return "⚡";
            case "Entertainment": return "🎬";
            case "Health": return "💊";
            case "Education": return "📚";
            case "Salary": return "💰";
            case "Recharge": return "📱";
            case "Transfer": return "💸";
            default: return "📁";
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        Transaction that = (Transaction) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }
}
