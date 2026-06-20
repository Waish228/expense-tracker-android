package com.spendsense.app;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Parses bank SMS messages and UPI notification text to extract transaction details.
 * Supports: Union Bank, BHIM, Supermoney, Mobikwik, Amazon Pay, PhonePe,
 * Google Pay, Paytm, CRED, and generic UPI/bank patterns.
 */
public class SmsParser {

    public static class ParsedTransaction {
        public String type;           // "credit" or "debit"
        public double amount;
        public String description;
        public String rawSms;
        public String merchantHint;   // For deduplication fingerprinting

        public ParsedTransaction(String type, double amount, String description,
                                 String rawSms, String merchantHint) {
            this.type = type;
            this.amount = amount;
            this.description = description;
            this.rawSms = rawSms;
            this.merchantHint = merchantHint;
        }
    }

    // Keywords that indicate a debit, used to anchor amount extraction near
    // the right number when a message contains multiple currency figures
    // (e.g. transaction amount AND available balance).
    private static final String[] DEBIT_ANCHORS = {
            "debited", "debit", "sent", "paid", "withdrawn", "purchase", "charged"
    };
    private static final String[] CREDIT_ANCHORS = {
            "credited", "credit", "received", "deposited", "cashback", "refund"
    };

    // Words that indicate a number is a BALANCE, not the transaction amount.
    // Used to actively exclude matches near these words.
    private static final String[] BALANCE_MARKERS = {
            "avail bal", "available balance", "avl bal", "a/c bal", "account balance",
            "closing balance", "bal:", "balance:", "avl. balance"
    };

    /**
     * Returns a ParsedTransaction if the text looks like a bank/UPI message, or null otherwise.
     */
    public static ParsedTransaction parse(String sender, String body) {
        if (body == null || body.isEmpty()) return null;

        String upper = body.toUpperCase(java.util.Locale.ROOT);

        // Skip OTP, promotional & informational messages
        if (upper.contains("OTP") || upper.contains("VERIFICATION")
                || upper.contains("PROMO") || upper.contains("OFFER")
                || upper.contains("DEAR CUSTOMER YOUR OTP")
                || upper.contains("DO NOT SHARE")) {
            return null;
        }

        String type = null;
        double amount = 0;
        String description = "";
        String merchant = "";

        // ── UNION BANK OF INDIA ──
        if (upper.contains("UNION BANK") || upper.contains("UNIONBANK") || containsSender(sender, "UBIBNK", "UBOI")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Union Bank " + (type != null ? type : "transaction");
            merchant = "UnionBank";
        }

        // ── BHIM UPI ──
        else if (upper.contains("BHIM") || containsSender(sender, "BHIM")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "BHIM UPI";
            merchant = extractRecipient(body);
            if (merchant != null && !merchant.isEmpty()) {
                description += " → " + merchant;
            } else {
                merchant = "BHIM";
            }
        }

        // ── PHONEPE ──
        else if (upper.contains("PHONEPE") || containsSender(sender, "PHONEPE", "PHNEPE")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "PhonePe";
            merchant = extractRecipient(body);
            if (merchant != null && !merchant.isEmpty()) {
                description += " → " + merchant;
            } else {
                merchant = "PhonePe";
            }
        }

        // ── GOOGLE PAY ──
        else if (upper.contains("GOOGLE PAY") || upper.contains("GPAY")
                || containsSender(sender, "GOOGLEPAY", "GPAY")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Google Pay";
            merchant = extractRecipient(body);
            if (merchant != null && !merchant.isEmpty()) {
                description += " → " + merchant;
            } else {
                merchant = "GooglePay";
            }
        }

        // ── PAYTM ──
        else if (upper.contains("PAYTM") || containsSender(sender, "PAYTM")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Paytm";
            merchant = extractRecipient(body);
            if (merchant != null && !merchant.isEmpty()) {
                description += " → " + merchant;
            } else {
                merchant = "Paytm";
            }
        }

        // ── CRED ──
        else if (upper.contains("CRED") || containsSender(sender, "CRED")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "CRED";
            merchant = "CRED";
        }

        // ── SUPERMONEY ──
        else if (upper.contains("SUPERMONEY") || containsSender(sender, "SUPRMNY")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Supermoney";
            merchant = "Supermoney";
        }

        // ── MOBIKWIK ──
        else if (upper.contains("MOBIKWIK") || containsSender(sender, "MBKWIK")) {
            type = detectType(upper);
            if (upper.contains("ADDED") || upper.contains("CASHBACK")) type = "credit";
            amount = extractAmount(body, type);
            description = "MobiKwik";
            merchant = "MobiKwik";
        }

        // ── AMAZON PAY ──
        else if (upper.contains("AMAZON PAY") || upper.contains("AMAZONPAY")
                || containsSender(sender, "AMAZON")) {
            type = detectType(upper);
            if (upper.contains("CASHBACK") || upper.contains("REFUND")) type = "credit";
            amount = extractAmount(body, type);
            description = "Amazon Pay";
            merchant = "AmazonPay";
        }

        // ── SBI / YONO ──
        else if (containsSender(sender, "SBI", "SBIINB", "SBIPSG")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "SBI";
            merchant = "SBI";
        }

        // ── HDFC ──
        else if (containsSender(sender, "HDFC", "HDFCBK")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "HDFC Bank";
            merchant = "HDFC";
        }

        // ── ICICI ──
        else if (containsSender(sender, "ICICI", "ICICIB")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "ICICI Bank";
            merchant = "ICICI";
        }

        // ── AXIS ──
        else if (containsSender(sender, "AXIS", "AXISBK")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Axis Bank";
            merchant = "Axis";
        }

        // ── KOTAK ──
        else if (containsSender(sender, "KOTAK", "KOTAKB")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Kotak Bank";
            merchant = "Kotak";
        }

        // ── BOI ──
        else if (containsSender(sender, "BOI", "BOIIND")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Bank of India";
            merchant = "BOI";
        }

        // ── PNB ──
        else if (containsSender(sender, "PNB", "PNBSMS")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "PNB";
            merchant = "PNB";
        }

        // ── CANARA ──
        else if (containsSender(sender, "CANARA", "CNRBKS")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Canara Bank";
            merchant = "Canara";
        }

        // ── BOB ──
        else if (containsSender(sender, "BOB", "BARODA")) {
            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Bank of Baroda";
            merchant = "BOB";
        }

        // ── GENERIC UPI / BANK PATTERN ──
        else if ((upper.contains("DEBITED") || upper.contains("CREDITED")
                || upper.contains("DEBIT") || upper.contains("CREDIT"))
                && (upper.contains("RS") || upper.contains("INR") || upper.contains("₹"))) {

            type = detectType(upper);
            amount = extractAmount(body, type);
            description = "Bank Transaction";
            merchant = "Bank";

            // Try to identify bank from sender
            if (sender != null) {
                String senderUpper = sender.toUpperCase(java.util.Locale.ROOT);
                if (senderUpper.contains("SBI")) { description = "SBI"; merchant = "SBI"; }
                else if (senderUpper.contains("HDFC")) { description = "HDFC Bank"; merchant = "HDFC"; }
                else if (senderUpper.contains("ICICI")) { description = "ICICI Bank"; merchant = "ICICI"; }
                else if (senderUpper.contains("AXIS")) { description = "Axis Bank"; merchant = "Axis"; }
                else if (senderUpper.contains("BOI")) { description = "Bank of India"; merchant = "BOI"; }
                else if (senderUpper.contains("KOTAK")) { description = "Kotak Bank"; merchant = "Kotak"; }
            }
        }

        // ── GENERIC UPI SENT/RECEIVED ──
        else if ((upper.contains("SENT") || upper.contains("RECEIVED") || upper.contains("PAID"))
                && (upper.contains("RS") || upper.contains("INR") || upper.contains("₹"))
                && upper.contains("UPI")) {

            if (upper.contains("SENT") || upper.contains("PAID")) type = "debit";
            else type = "credit";

            amount = extractAmount(body, type);
            description = "UPI Payment";
            merchant = extractRecipient(body);
            if (merchant == null || merchant.isEmpty()) merchant = "UPI";
        }

        // Not a financial message
        if (type == null || amount <= 0) return null;

        return new ParsedTransaction(type, amount, description, body, merchant);
    }

    /**
     * Detects transaction type from uppercase message text.
     */
    private static String detectType(String upper) {
        if (upper.contains("DEBITED") || upper.contains("DEBIT")
                || upper.contains("SENT") || upper.contains("PAID")
                || upper.contains("WITHDRAWN") || upper.contains("PURCHASE")
                || upper.contains("CHARGED")) {
            return "debit";
        }
        if (upper.contains("CREDITED") || upper.contains("CREDIT")
                || upper.contains("RECEIVED") || upper.contains("DEPOSITED")
                || upper.contains("CASHBACK") || upper.contains("REFUND")) {
            return "credit";
        }
        return null;
    }

    /**
     * Checks if sender contains any of the given keywords.
     */
    private static boolean containsSender(String sender, String... keywords) {
        if (sender == null) return false;
        String upper = sender.toUpperCase(java.util.Locale.ROOT);
        for (String kw : keywords) {
            if (upper.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Extracts the to/from recipient from message body.
     */
    private static String extractRecipient(String body) {
        Pattern toFrom = Pattern.compile("(?:to|from)\\s+([\\w@.]+)", Pattern.CASE_INSENSITIVE);
        Matcher m = toFrom.matcher(body);
        if (m.find()) {
            return m.group(1);
        }
        return null;
    }

    /**
     * Extracts the monetary amount from text.
     * Handles: Rs.500, Rs 500.00, Rs:40.00, INR 500, ₹500, Rs.1,500.00, etc.
     *
     * Bank SMS frequently contain MORE THAN ONE currency figure in the same
     * message -- typically the transaction amount AND the resulting account
     * balance (e.g. "Rs.500.00 debited... Avail Bal: Rs.10,000.00"). Taking
     * the first match in the string is unreliable because some bank templates
     * put the balance first. This version:
     *
     *   1. Finds ALL currency-amount matches in the text along with their
     *      position.
     *   2. Filters out any match that sits within a few characters of a
     *      known "balance" marker (avail bal, closing balance, etc).
     *   3. Among the remaining candidates, prefers the one closest to a
     *      type-appropriate anchor keyword (debited/credited/sent/etc) if
     *      a type was already detected; otherwise falls back to the first
     *      remaining candidate.
     */
    private static double extractAmount(String text, String knownType) {
        Pattern p = Pattern.compile(
                "(?:Rs\\.?|INR|₹)[:\\s]*([\\d,]+(?:\\.\\d{1,2})?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);

        java.util.List<int[]> candidatePositions = new java.util.ArrayList<>(); // [start, end]
        java.util.List<Double> candidateAmounts = new java.util.ArrayList<>();

        while (m.find()) {
            int start = m.start();
            int end = m.end();
            String amountStr = m.group(1).replace(",", "");
            double value;
            try {
                value = Double.parseDouble(amountStr);
            } catch (NumberFormatException e) {
                continue;
            }

            // Check a small window around this match for a balance marker.
            int windowStart = Math.max(0, start - 25);
            String window = text.substring(windowStart, start).toLowerCase(java.util.Locale.ROOT);
            boolean nearBalanceMarker = false;
            for (String marker : BALANCE_MARKERS) {
                if (window.contains(marker)) {
                    nearBalanceMarker = true;
                    break;
                }
            }
            if (nearBalanceMarker) continue;

            candidatePositions.add(new int[]{start, end});
            candidateAmounts.add(value);
        }

        if (candidateAmounts.isEmpty()) {
            // Fall back to the old behavior (first match overall) rather
            // than returning 0, in case every candidate happened to sit
            // near a balance-like word due to an unusual template.
            Matcher fallback = p.matcher(text);
            if (fallback.find()) {
                String amountStr = fallback.group(1).replace(",", "");
                try {
                    return Double.parseDouble(amountStr);
                } catch (NumberFormatException ignored) {}
            }
            return 0;
        }

        if (candidateAmounts.size() == 1) {
            return candidateAmounts.get(0);
        }

        // Multiple remaining candidates: prefer the one nearest a
        // type-appropriate anchor keyword.
        String[] anchors = "credit".equals(knownType) ? CREDIT_ANCHORS : DEBIT_ANCHORS;
        String lowerText = text.toLowerCase(java.util.Locale.ROOT);

        int bestIndex = -1;
        int bestDistance = Integer.MAX_VALUE;
        for (String anchor : anchors) {
            int anchorPos = lowerText.indexOf(anchor);
            if (anchorPos < 0) continue;
            for (int i = 0; i < candidatePositions.size(); i++) {
                int candStart = candidatePositions.get(i)[0];
                int distance = Math.abs(candStart - anchorPos);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }
        }

        if (bestIndex >= 0) {
            return candidateAmounts.get(bestIndex);
        }

        // No anchor found at all -- fall back to the first remaining candidate.
        return candidateAmounts.get(0);
    }

    /**
     * Checks if a sender ID looks like a bank/financial sender.
     */
    public static boolean isFinancialSender(String sender) {
        if (sender == null) return false;
        String upper = sender.toUpperCase(java.util.Locale.ROOT);
        return upper.contains("BANK") || upper.contains("UBI") || upper.contains("BHIM")
                || upper.contains("PAYTM") || upper.contains("AMAZON") || upper.contains("MBKWIK")
                || upper.contains("SUPRM") || upper.contains("PHONEPE") || upper.contains("GPAY")
                || upper.contains("CRED") || upper.contains("SBI") || upper.contains("HDFC")
                || upper.contains("ICICI") || upper.contains("AXIS") || upper.contains("KOTAK")
                || upper.contains("PNB") || upper.contains("BOI") || upper.contains("BOB")
                || upper.matches(".*[A-Z]{2}-[A-Z]{6}.*");
    }
}