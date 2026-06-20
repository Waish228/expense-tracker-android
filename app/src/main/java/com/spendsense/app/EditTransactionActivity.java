package com.spendsense.app;

import android.app.DatePickerDialog;
import android.os.Bundle;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.RadioGroup;
import android.widget.Spinner;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Calendar;
import java.util.Locale;

public class EditTransactionActivity extends AppCompatActivity {
    private SupabaseHelper supabase;
    private EditText amountInput, descInput, dateInput;
    private RadioGroup typeGroup;
    private Spinner categorySpinner;
    private Calendar selectedDate;
    private String transactionId;

    private static final String[] CATEGORIES = {
            "Food & Dining", "Transport", "Shopping", "Bills & Utilities",
            "Entertainment", "Health", "Education", "Salary",
            "Recharge", "Transfer", "Other"
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_edit_transaction);
        supabase = new SupabaseHelper(this);

        amountInput = findViewById(R.id.amount_input);
        descInput = findViewById(R.id.desc_input);
        dateInput = findViewById(R.id.date_input);
        typeGroup = findViewById(R.id.type_group);
        categorySpinner = findViewById(R.id.category_spinner);
        Button saveBtn = findViewById(R.id.save_btn);
        Button cancelBtn = findViewById(R.id.cancel_btn);

        ArrayAdapter<String> adapter = new ArrayAdapter<>(this,
                android.R.layout.simple_spinner_dropdown_item, CATEGORIES);
        categorySpinner.setAdapter(adapter);

        selectedDate = Calendar.getInstance();
        dateInput.setFocusable(false);
        dateInput.setClickable(true);
        dateInput.setOnClickListener(v -> showDatePicker());

        // Pre-populate from intent extras
        transactionId = getIntent().getStringExtra("tx_id");
        String txType = getIntent().getStringExtra("tx_type");
        double txAmount = getIntent().getDoubleExtra("tx_amount", 0);
        String txCategory = getIntent().getStringExtra("tx_category");
        String txDesc = getIntent().getStringExtra("tx_description");
        String txDate = getIntent().getStringExtra("tx_date");

        // Set type
        if ("credit".equals(txType)) {
            typeGroup.check(R.id.type_income);
        } else {
            typeGroup.check(R.id.type_expense);
        }

        // Set amount
        if (txAmount > 0) {
            amountInput.setText(String.valueOf(txAmount));
        }

        // Set category
        if (txCategory != null) {
            int idx = Arrays.asList(CATEGORIES).indexOf(txCategory);
            if (idx >= 0) categorySpinner.setSelection(idx);
        }

        // Set description
        if (txDesc != null) {
            descInput.setText(txDesc);
        }

        // Set date
        if (txDate != null && txDate.length() >= 10) {
            try {
                String datePart = txDate.substring(0, 10);
                String[] parts = datePart.split("-");
                selectedDate.set(Calendar.YEAR, Integer.parseInt(parts[0]));
                selectedDate.set(Calendar.MONTH, Integer.parseInt(parts[1]) - 1);
                selectedDate.set(Calendar.DAY_OF_MONTH, Integer.parseInt(parts[2]));
            } catch (Exception ignored) {}
        }
        updateDateDisplay();

        saveBtn.setOnClickListener(v -> save());
        cancelBtn.setOnClickListener(v -> finish());
    }

    private void showDatePicker() {
        new DatePickerDialog(this,
                (view, year, month, day) -> {
                    selectedDate.set(Calendar.YEAR, year);
                    selectedDate.set(Calendar.MONTH, month);
                    selectedDate.set(Calendar.DAY_OF_MONTH, day);
                    updateDateDisplay();
                },
                selectedDate.get(Calendar.YEAR),
                selectedDate.get(Calendar.MONTH),
                selectedDate.get(Calendar.DAY_OF_MONTH)
        ).show();
    }

    private void updateDateDisplay() {
        dateInput.setText(new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedDate.getTime()));
    }

    private void save() {
        String amountStr = amountInput.getText().toString().trim();
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

        String type = typeGroup.getCheckedRadioButtonId() == R.id.type_income ? "credit" : "debit";
        String category = categorySpinner.getSelectedItem().toString();
        String desc = descInput.getText().toString().trim();
        if (desc.isEmpty()) desc = category;
        String date = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(selectedDate.getTime());

        Button btn = findViewById(R.id.save_btn);
        btn.setEnabled(false);
        btn.setText(R.string.saving);

        supabase.updateTransaction(transactionId, type, amount, category, desc, date,
                new SupabaseHelper.SimpleCallback() {
                    @Override
                    public void onSuccess() {
                        runOnUiThread(() -> {
                            Toast.makeText(EditTransactionActivity.this,
                                    "Transaction updated!", Toast.LENGTH_SHORT).show();
                            setResult(RESULT_OK);
                            finish();
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            Toast.makeText(EditTransactionActivity.this,
                                    getString(R.string.error_prefix, message), Toast.LENGTH_SHORT).show();
                            btn.setEnabled(true);
                            btn.setText("Save Changes");
                        });
                    }
                });
    }
}
