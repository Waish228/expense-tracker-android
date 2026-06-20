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
            amount = extractAmount(body);
            description = "Union Bank " + (type != null ? type : "transaction");
            merchant = "UnionBank";
        }

        // ── BHIM UPI ──
        else if (upper.contains("BHIM") || containsSender(sender, "BHIM")) {
            type = detectType(upper);
            amount = extractAmount(body);
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
            amount = extractAmount(body);
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
            amount = extractAmount(body);
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
            amount = extractAmount(body);
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
            amount = extractAmount(body);
            description = "CRED";
            merchant = "CRED";
        }

        // ── SUPERMONEY ──
        else if (upper.contains("SUPERMONEY") || containsSender(sender, "SUPRMNY")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "Supermoney";
            merchant = "Supermoney";
        }

        // ── MOBIKWIK ──
        else if (upper.contains("MOBIKWIK") || containsSender(sender, "MBKWIK")) {
            type = detectType(upper);
            if (upper.contains("ADDED") || upper.contains("CASHBACK")) type = "credit";
            amount = extractAmount(body);
            description = "MobiKwik";
            merchant = "MobiKwik";
        }

        // ── AMAZON PAY ──
        else if (upper.contains("AMAZON PAY") || upper.contains("AMAZONPAY")
                || containsSender(sender, "AMAZON")) {
            type = detectType(upper);
            if (upper.contains("CASHBACK") || upper.contains("REFUND")) type = "credit";
            amount = extractAmount(body);
            description = "Amazon Pay";
            merchant = "AmazonPay";
        }

        // ── SBI / YONO ──
        else if (containsSender(sender, "SBI", "SBIINB", "SBIPSG")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "SBI";
            merchant = "SBI";
        }

        // ── HDFC ──
        else if (containsSender(sender, "HDFC", "HDFCBK")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "HDFC Bank";
            merchant = "HDFC";
        }

        // ── ICICI ──
        else if (containsSender(sender, "ICICI", "ICICIB")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "ICICI Bank";
            merchant = "ICICI";
        }

        // ── AXIS ──
        else if (containsSender(sender, "AXIS", "AXISBK")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "Axis Bank";
            merchant = "Axis";
        }

        // ── KOTAK ──
        else if (containsSender(sender, "KOTAK", "KOTAKB")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "Kotak Bank";
            merchant = "Kotak";
        }

        // ── BOI ──
        else if (containsSender(sender, "BOI", "BOIIND")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "Bank of India";
            merchant = "BOI";
        }

        // ── PNB ──
        else if (containsSender(sender, "PNB", "PNBSMS")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "PNB";
            merchant = "PNB";
        }

        // ── CANARA ──
        else if (containsSender(sender, "CANARA", "CNRBKS")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "Canara Bank";
            merchant = "Canara";
        }

        // ── BOB ──
        else if (containsSender(sender, "BOB", "BARODA")) {
            type = detectType(upper);
            amount = extractAmount(body);
            description = "Bank of Baroda";
            merchant = "BOB";
        }

        // ── GENERIC UPI / BANK PATTERN ──
        else if ((upper.contains("DEBITED") || upper.contains("CREDITED")
                || upper.contains("DEBIT") || upper.contains("CREDIT"))
                && (upper.contains("RS") || upper.contains("INR") || upper.contains("₹"))) {

            type = detectType(upper);
            amount = extractAmount(body);
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

            amount = extractAmount(body);
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
     */
    private static double extractAmount(String text) {
        Pattern p = Pattern.compile(
                "(?:Rs\\.?|INR|₹)[:\\s]*([\\d,]+(?:\\.\\d{1,2})?)",
                Pattern.CASE_INSENSITIVE);
        Matcher m = p.matcher(text);
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
