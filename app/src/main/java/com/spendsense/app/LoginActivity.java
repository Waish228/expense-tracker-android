package com.spendsense.app;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

public class LoginActivity extends AppCompatActivity {
    private SupabaseHelper supabase;
    private EditText emailInput, passwordInput, nameInput, confirmPasswordInput;
    private Button authBtn;
    private TextView toggleText, errorText, forgotPasswordText;
    private LinearLayout nameGroup, confirmPasswordGroup;

    // Verification section
    private LinearLayout verificationSection;
    private TextView verificationEmail;
    private Button resendBtn;
    private TextView verificationStatus;

    private boolean isLogin = true;
    private String pendingVerificationEmail = null;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        supabase = new SupabaseHelper(this);

        // Skip login if already logged in
        if (supabase.isLoggedIn()) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_login);

        emailInput = findViewById(R.id.email_input);
        passwordInput = findViewById(R.id.password_input);
        nameInput = findViewById(R.id.name_input);
        nameGroup = findViewById(R.id.name_group);
        confirmPasswordInput = findViewById(R.id.confirm_password_input);
        confirmPasswordGroup = findViewById(R.id.confirm_password_group);
        authBtn = findViewById(R.id.auth_btn);
        toggleText = findViewById(R.id.toggle_text);
        errorText = findViewById(R.id.error_text);
        forgotPasswordText = findViewById(R.id.forgot_password_text);

        // Verification section
        verificationSection = findViewById(R.id.verification_section);
        verificationEmail = findViewById(R.id.verification_email_display);
        resendBtn = findViewById(R.id.resend_btn);
        verificationStatus = findViewById(R.id.verification_status);

        authBtn.setOnClickListener(v -> doAuth());
        toggleText.setOnClickListener(v -> toggleMode());
        forgotPasswordText.setOnClickListener(v -> handleForgotPassword());

        resendBtn.setOnClickListener(v -> handleResendVerification());

        // "Back to Login" from verification section
        TextView backToLogin = findViewById(R.id.back_to_login);
        if (backToLogin != null) {
            backToLogin.setOnClickListener(v -> hideVerificationSection());
        }
    }

    private void handleForgotPassword() {
        String email = emailInput.getText().toString().trim();
        if (email.isEmpty()) {
            showError(getString(R.string.error_reset_password));
            return;
        }

        errorText.setVisibility(View.GONE);
        Toast.makeText(this, R.string.sending_reset_email, Toast.LENGTH_SHORT).show();

        supabase.resetPassword(email, new SupabaseHelper.AuthCallback() {
            @Override
            public void onSuccess(String userId, String accessToken) {
                runOnUiThread(() -> Toast.makeText(LoginActivity.this,
                        R.string.check_email_reset, Toast.LENGTH_LONG).show());
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> showError(message));
            }
        });
    }

    private void toggleMode() {
        isLogin = !isLogin;
        nameGroup.setVisibility(isLogin ? View.GONE : View.VISIBLE);
        confirmPasswordGroup.setVisibility(isLogin ? View.GONE : View.VISIBLE);
        authBtn.setText(isLogin ? R.string.login : R.string.create_account);
        toggleText.setText(isLogin ? R.string.toggle_signup : R.string.toggle_login);
        forgotPasswordText.setVisibility(isLogin ? View.VISIBLE : View.GONE);
        errorText.setVisibility(View.GONE);
        hideVerificationSection();
    }

    private void doAuth() {
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String name = nameInput.getText().toString().trim();

        if (email.isEmpty() || password.isEmpty()) {
            showError(getString(R.string.error_email_password_required));
            return;
        }

        if (!isLogin) {
            if (name.isEmpty()) {
                showError(getString(R.string.error_name_required));
                return;
            }
            if (password.length() < 6) {
                showError(getString(R.string.error_password_too_short));
                return;
            }
            String confirmPassword = confirmPasswordInput.getText().toString();
            if (!password.equals(confirmPassword)) {
                showError(getString(R.string.error_passwords_mismatch));
                return;
            }
        }

        authBtn.setEnabled(false);
        authBtn.setText(isLogin ? R.string.logging_in : R.string.creating_account);
        errorText.setVisibility(View.GONE);

        if (isLogin) {
            supabase.signIn(email, password, new SupabaseHelper.AuthCallback() {
                @Override
                public void onSuccess(String userId, String accessToken) {
                    runOnUiThread(() -> {
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        showError(message);
                        authBtn.setEnabled(true);
                        authBtn.setText(R.string.login);
                    });
                }
            });
        } else {
            supabase.signUp(email, password, name, new SupabaseHelper.SignUpCallback() {
                @Override
                public void onSuccess(String userId, String accessToken) {
                    runOnUiThread(() -> {
                        startActivity(new Intent(LoginActivity.this, MainActivity.class));
                        finish();
                    });
                }

                @Override
                public void onConfirmationRequired(String emailAddr) {
                    pendingVerificationEmail = emailAddr;
                    runOnUiThread(() -> {
                        showVerificationSection(emailAddr);
                        authBtn.setEnabled(true);
                        authBtn.setText(R.string.create_account);
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        showError(message);
                        authBtn.setEnabled(true);
                        authBtn.setText(R.string.create_account);
                    });
                }
            });
        }
    }

    private void showVerificationSection(String email) {
        verificationSection.setVisibility(View.VISIBLE);
        verificationEmail.setText(email);
        verificationStatus.setText("");

        // Hide the auth form
        nameGroup.setVisibility(View.GONE);
        confirmPasswordGroup.setVisibility(View.GONE);
        authBtn.setVisibility(View.GONE);
        toggleText.setVisibility(View.GONE);
        forgotPasswordText.setVisibility(View.GONE);
        errorText.setVisibility(View.GONE);
        emailInput.setVisibility(View.GONE);
        passwordInput.setVisibility(View.GONE);

        // Hide label TextViews (parent LinearLayout)
        View emailLabel = findViewById(R.id.email_label);
        View passwordLabel = findViewById(R.id.password_label);
        if (emailLabel != null) emailLabel.setVisibility(View.GONE);
        if (passwordLabel != null) passwordLabel.setVisibility(View.GONE);
    }

    private void hideVerificationSection() {
        verificationSection.setVisibility(View.GONE);

        // Restore auth form
        authBtn.setVisibility(View.VISIBLE);
        toggleText.setVisibility(View.VISIBLE);
        emailInput.setVisibility(View.VISIBLE);
        passwordInput.setVisibility(View.VISIBLE);

        View emailLabel = findViewById(R.id.email_label);
        View passwordLabel = findViewById(R.id.password_label);
        if (emailLabel != null) emailLabel.setVisibility(View.VISIBLE);
        if (passwordLabel != null) passwordLabel.setVisibility(View.VISIBLE);

        // Switch to login mode
        isLogin = true;
        nameGroup.setVisibility(View.GONE);
        confirmPasswordGroup.setVisibility(View.GONE);
        authBtn.setText(R.string.login);
        toggleText.setText(R.string.toggle_signup);
        forgotPasswordText.setVisibility(View.VISIBLE);
    }

    private void handleResendVerification() {
        if (pendingVerificationEmail == null) return;

        resendBtn.setEnabled(false);
        resendBtn.setText("Sending…");

        supabase.resendVerification(pendingVerificationEmail, new SupabaseHelper.SimpleCallback() {
            @Override
            public void onSuccess() {
                runOnUiThread(() -> {
                    verificationStatus.setText(getString(R.string.verification_email_sent));
                    verificationStatus.setTextColor(
                            getResources().getColor(R.color.green, getTheme()));
                    resendBtn.setEnabled(true);
                    resendBtn.setText(getString(R.string.resend_verification));
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    verificationStatus.setText(message);
                    verificationStatus.setTextColor(
                            getResources().getColor(R.color.red, getTheme()));
                    resendBtn.setEnabled(true);
                    resendBtn.setText(getString(R.string.resend_verification));
                });
            }
        });
    }

    private void showError(String msg) {
        errorText.setText(msg);
        errorText.setVisibility(View.VISIBLE);
    }
}
