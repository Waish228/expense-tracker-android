package com.spendsense.app;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.RadioButton;
import android.widget.Spinner;
import android.widget.ArrayAdapter;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

public class AddTransactionActivity extends AppCompatActivity {
    private SupabaseHelper supabase;
    private EditText amountInput, descInput, dateInput;
    private RadioGroup typeGroup;
    private Spinner categorySpinner;
    private Calendar selectedDate;

    private static final String[] CATEGORIES = {
        "Food & Dining", "Transport", "Shopping", "Bills & Utilities",
        "Entertainment", "Health", "Education", "Salary",
        "Recharge", "Transfer", "Other"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_transaction);
        supabase = new SupabaseHelper(this);

        amountInput = findViewById(R.id.amount_input);
        descInput = findViewById(R.id.desc_input);
        dateInput = findViewById(R.id.date_input);
        typeGroup = findViewById(R.id.type_group);
        categorySpinner = findViewById(R.id.category_spinner);
        Button submitBtn = findViewById(R.id.submit_btn);
        Button cancelBtn = findViewById(R.id.cancel_btn);

        // Setup category spinner
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, CATEGORIES);
        categorySpinner.setAdapter(adapter);

        // Setup date picker — default to today
        selectedDate = Calendar.getInstance();
        updateDateDisplay();

        dateInput.setFocusable(false);
        dateInput.setClickable(true);
        dateInput.setOnClickListener(v -> showDatePicker());

        // Add selection animation to type toggle buttons
        typeGroup.setOnCheckedChangeListener((group, checkedId) -> {
            animateSelection(checkedId);
        });
        // Initial animation for default selection
        animateSelection(R.id.type_expense);

        submitBtn.setOnClickListener(v -> submit());
        cancelBtn.setOnClickListener(v -> finish());
    }

    private void animateSelection(int checkedId) {
        RadioButton selectedBtn = findViewById(checkedId);
        if (selectedBtn == null) return;

        // Scale animation
        selectedBtn.animate()
            .scaleX(1.05f)
            .scaleY(1.05f)
            .setDuration(150)
            .withEndAction(() -> {
                selectedBtn.animate()
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(150)
                    .start();
            })
            .start();
    }

    private void showDatePicker() {
        new DatePickerDialog(this,
                (view, year, month, dayOfMonth) -> {
                    selectedDate.set(Calendar.YEAR, year);
                    selectedDate.set(Calendar.MONTH, month);
                    selectedDate.set(Calendar.DAY_OF_MONTH, dayOfMonth);
                    updateDateDisplay();
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateDateDisplay() {
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        dateInput.setText(fmt.format(selectedDate.getTime()));
    }

    private void submit() {
        String amountStr = amountInput.getText().toString().trim();
        String desc = descInput.getText().toString().trim();

        if (amountStr.isEmpty()) {
            amountInput.setError(getString(R.string.error_enter_amount));
            return;
        }

        double amount;
        try {
            amount = Double.parseDouble(amountStr);
            if (amount <= 0) throw new NumberFormatException();
        } catch (NumberFormatException e) {
            amountInput.setError(getString(R.string.error_valid_amount));
            return;
        }

        int selectedId = typeGroup.getCheckedRadioButtonId();
        String type = selectedId == R.id.type_income ? "credit" : "debit";
        String category = categorySpinner.getSelectedItem().toString();
        if (desc.isEmpty()) desc = category;

        // Get selected date
        SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
        String transactionDate = fmt.format(selectedDate.getTime());

        Button btn = findViewById(R.id.submit_btn);
        btn.setEnabled(false);
        btn.setText(R.string.adding);

        supabase.insertTransaction(type, amount, category, desc, "manual", null, transactionDate,
            new SupabaseHelper.SimpleCallback() {
                @Override
                public void onSuccess() {
                    runOnUiThread(() -> {
                        Toast.makeText(AddTransactionActivity.this,
                                R.string.transaction_added_success, Toast.LENGTH_SHORT).show();
                        finish();
                    });
                }

                @Override
                public void onError(String message) {
                    runOnUiThread(() -> {
                        Toast.makeText(AddTransactionActivity.this,
                                getString(R.string.error_prefix, message), Toast.LENGTH_SHORT).show();
                        btn.setEnabled(true);
                        btn.setText(R.string.add_transaction);
                    });
                }
            });
    }
}
