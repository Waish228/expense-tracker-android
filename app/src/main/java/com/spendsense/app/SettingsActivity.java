package com.spendsense.app;

import android.content.ComponentName;
import android.content.Intent;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class SettingsActivity extends AppCompatActivity {
    private SupabaseHelper supabase;
    private EditText budgetInput, nameInput;
    private Button saveBtn, logoutBtn, notifAccessBtn;
    private TextView notifStatus, userNameDisplay, userEmail, userInitial;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);

        supabase = new SupabaseHelper(this);

        budgetInput = findViewById(R.id.budget_input);
        nameInput = findViewById(R.id.name_input);
        saveBtn = findViewById(R.id.save_btn);
        logoutBtn = findViewById(R.id.logout_btn);
        notifStatus = findViewById(R.id.notif_status);
        userNameDisplay = findViewById(R.id.user_name_display);
        userEmail = findViewById(R.id.user_email);
        userInitial = findViewById(R.id.user_initial);

        findViewById(R.id.btn_back).setOnClickListener(v -> finish());

        loadProfile();
        updateNotificationStatus();

        saveBtn.setOnClickListener(v -> saveProfile());

        // Notification access row click
        View notifAccessRow = findViewById(R.id.notif_access_row);
        if (notifAccessRow != null) {
            notifAccessRow.setOnClickListener(v -> openNotificationSettings());
        }

        // ── DEBUG TOOLS ──
        // These rows (Test SMS Parser, Clear Dedup Cache) are developer
        // utilities and must not be visible to end users in a release
        // build. Hide the entire "Debug Tools" section in production.
        View testSmsRow = findViewById(R.id.test_sms_row);
        View clearDedupRow = findViewById(R.id.clear_dedup_row);
        View debugSectionLabel = findViewById(R.id.debug_tools_label); // optional id, see layout note below
        View debugSectionCard = findViewById(R.id.debug_tools_card);   // optional id, see layout note below

        if (BuildConfig.DEBUG) {
            if (testSmsRow != null) {
                testSmsRow.setOnClickListener(v -> testSmsParser());
            }
            if (clearDedupRow != null) {
                clearDedupRow.setOnClickListener(v -> clearDedupCache());
            }
        } else {
            // Production build: hide debug-only UI entirely rather than
            // just disabling the click listeners, so users never see them.
            if (testSmsRow != null) testSmsRow.setVisibility(View.GONE);
            if (clearDedupRow != null) clearDedupRow.setVisibility(View.GONE);
            if (debugSectionLabel != null) debugSectionLabel.setVisibility(View.GONE);
            if (debugSectionCard != null) debugSectionCard.setVisibility(View.GONE);
        }

        logoutBtn.setOnClickListener(v -> showLogoutConfirmation());
    }

    @Override
    protected void onResume() {
        super.onResume();
        updateNotificationStatus();
    }

    private void openNotificationSettings() {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        startActivity(intent);
    }

    private void updateNotificationStatus() {
        boolean enabled = isNotificationListenerEnabled();
        if (notifStatus != null) {
            if (enabled) {
                notifStatus.setText(R.string.notification_access_enabled);
                notifStatus.setTextColor(getResources().getColor(R.color.green, getTheme()));
            } else {
                notifStatus.setText(R.string.notification_access_disabled);
                notifStatus.setTextColor(getResources().getColor(R.color.text_muted, getTheme()));
            }
        }
    }

    private boolean isNotificationListenerEnabled() {
        String flat = Settings.Secure.getString(getContentResolver(),
                "enabled_notification_listeners");
        if (flat == null) return false;
        ComponentName cn = new ComponentName(this, TransactionNotificationListener.class);
        return flat.contains(cn.flattenToString());
    }

    private void testSmsParser() {
        String[] testMessages = {
            "UNION BANK OF INDIA: Rs.500.00 debited from account ending 1234 via UPI to MERCHANT. Avail Bal: Rs.10,000.00",
            "PhonePe: You paid Rs.150 to Swiggy Food. Available balance: Rs.5000",
            "SBI: INR 1000.00 credited to A/c 12345678901 via NEFT from JOHN. Avail: Rs.25000.00",
            "HDFC Bank: Rs.250 debited to Amazon Pay. Balance: Rs.8000"
        };

        StringBuilder result = new StringBuilder();
        for (String sms : testMessages) {
            SmsParser.ParsedTransaction parsed = SmsParser.parse("BANK", sms);
            if (parsed != null) {
                result.append("✓ ").append(parsed.type).append(" ₹").append(parsed.amount)
                      .append(" - ").append(parsed.description).append("\n");
            } else {
                result.append("✗ Not parsed: ").append(sms.substring(0, Math.min(40, sms.length()))).append("\n");
            }
        }

        new android.app.AlertDialog.Builder(this)
            .setTitle("SMS Parser Test Results")
            .setMessage(result.toString())
            .setPositiveButton("OK", null)
            .setNeutralButton("Test Custom", (d, w) -> showCustomSmsTest())
            .show();
    }

    private void showCustomSmsTest() {
        EditText input = new EditText(this);
        input.setHint("Paste your bank SMS here");
        input.setMinLines(3);

        new android.app.AlertDialog.Builder(this)
            .setTitle("Test Custom SMS")
            .setMessage("Paste your actual bank SMS message:")
            .setView(input)
            .setPositiveButton("Test", (d, w) -> {
                String sms = input.getText().toString().trim();
                if (sms.isEmpty()) {
                    Toast.makeText(this, "Please enter SMS text", Toast.LENGTH_SHORT).show();
                    return;
                }
                SmsParser.ParsedTransaction parsed = SmsParser.parse("BANK", sms);
                String result;
                if (parsed != null) {
                    result = "✓ SUCCESS\n\nType: " + parsed.type +
                             "\nAmount: ₹" + parsed.amount +
                             "\nDescription: " + parsed.description +
                             "\nMerchant: " + parsed.merchantHint;
                } else {
                    result = "✗ FAILED TO PARSE\n\nYour SMS was not recognized. " +
                             "Make sure it contains keywords like DEBITED/CREDITED and Rs/INR/₹";
                }
                new android.app.AlertDialog.Builder(this)
                    .setTitle("Parse Result")
                    .setMessage(result)
                    .setPositiveButton("OK", null)
                    .show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void clearDedupCache() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Clear Dedup Cache")
            .setMessage("This will remove all duplicate prevention data. SMS transactions from the last few minutes may be counted twice. Are you sure?")
            .setPositiveButton("Clear", (d, w) -> {
                // Use the dedicated helper so BOTH the fingerprint cache
                // and the newer "recent transactions" tolerant-match cache
                // are cleared together.
                new TransactionDeduplicator(this).clearAll();
                Toast.makeText(this, R.string.cleared_dedup, Toast.LENGTH_SHORT).show();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void loadProfile() {
        supabase.getProfile(new SupabaseHelper.ProfileCallback() {
            @Override
            public void onSuccess(String fullName, double monthlyBudget) {
                runOnUiThread(() -> {
                    nameInput.setText(fullName);
                    userNameDisplay.setText(fullName.isEmpty() ? getString(R.string.user_name_placeholder) : fullName);

                    if (monthlyBudget > 0) {
                        budgetInput.setText(String.valueOf(monthlyBudget));
                    }

                    // Set user initial
                    if (!fullName.isEmpty()) {
                        userInitial.setText(fullName.substring(0, 1).toUpperCase());
                    }

                    // Email is now stored at login/signup time in SupabaseHelper
                    // (see SupabaseHelper.getEmail()). Previously this read
                    // getAccessToken() by mistake and the real email was never
                    // saved anywhere, so this always showed a placeholder.
                    String email = supabase.getEmail();
                    userEmail.setText((email != null && !email.isEmpty())
                            ? email
                            : getString(R.string.user_email_placeholder));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                        getString(R.string.error_loading_profile, message),
                        Toast.LENGTH_SHORT).show());
            }
        });
    }

    private void saveProfile() {
        String name = nameInput.getText().toString().trim();
        String budgetStr = budgetInput.getText().toString().trim();
        double budget = 0;

        if (!budgetStr.isEmpty()) {
            try {
                budget = Double.parseDouble(budgetStr);
            } catch (NumberFormatException e) {
                budgetInput.setError(getString(R.string.error_invalid_budget));
                return;
            }
        }

        saveBtn.setEnabled(false);
        saveBtn.setText(R.string.saving);

        supabase.updateProfile(name, budget, new SupabaseHelper.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    Toast.makeText(SettingsActivity.this,
                            R.string.settings_saved, Toast.LENGTH_SHORT).show();
                    saveBtn.setEnabled(true);
                    saveBtn.setText(R.string.save_changes);
                    userNameDisplay.setText(name.isEmpty() ? getString(R.string.user_name_placeholder) : name);
                    if (!name.isEmpty()) {
                        userInitial.setText(name.substring(0, 1).toUpperCase());
                    }
                    setResult(RESULT_OK);
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(SettingsActivity.this,
                            getString(R.string.failed_prefix, message),
                            Toast.LENGTH_SHORT).show();
                    saveBtn.setEnabled(true);
                    saveBtn.setText(R.string.save_changes);
                });
            }
        });
    }

    private void showLogoutConfirmation() {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Log Out")
            .setMessage("Are you sure you want to log out?")
            .setPositiveButton("Log Out", (d, w) -> {
                supabase.signOut();
                Intent intent = new Intent(this, LoginActivity.class);
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK);
                startActivity(intent);
                finish();
            })
            .setNegativeButton("Cancel", null)
            .show();
    }
}