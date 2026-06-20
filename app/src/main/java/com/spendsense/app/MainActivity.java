package com.spendsense.app;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout;

import com.github.mikephil.charting.charts.LineChart;
import com.github.mikephil.charting.charts.PieChart;
import com.github.mikephil.charting.components.XAxis;
import com.github.mikephil.charting.data.Entry;
import com.github.mikephil.charting.data.LineData;
import com.github.mikephil.charting.data.LineDataSet;
import com.github.mikephil.charting.data.PieData;
import com.github.mikephil.charting.data.PieDataSet;
import com.github.mikephil.charting.data.PieEntry;
import com.github.mikephil.charting.formatter.IndexAxisValueFormatter;
import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.floatingactionbutton.FloatingActionButton;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MainActivity extends AppCompatActivity {
    private static final String TAG = "MainActivity";
    private static final int SMS_PERMISSION_CODE = 100;
    private static final int REQUEST_ADD = 200;
    private static final int REQUEST_EDIT = 201;

    private SupabaseHelper supabase;
    private TransactionAdapter recentAdapter, fullAdapter;
    private List<Transaction> allTransactions = new ArrayList<>();
    private List<Transaction> recentTransactions = new ArrayList<>(); // top 5
    private List<Transaction> filteredTransactions = new ArrayList<>();

    // Dashboard views
    private TextView todaySpent, todayIncome, monthSpent, monthIncome, budgetLeft;
    private SwipeRefreshLayout swipeRefresh;
    private EditText searchInput;
    private LineChart trendChart;
    private PieChart categoryChart;

    // Pages
    private View pageDashboard, pageTransactions;
    private BottomNavigationView bottomNav;

    // Filters
    private String currentFilter = "all";
    private String currentSearch = "";
    private Button filterAll, filterDebit, filterCredit, filterSms, filterNotif;
    private double monthlyBudget = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        supabase = new SupabaseHelper(this);

        if (!supabase.isLoggedIn()) {
            startActivity(new Intent(this, LoginActivity.class));
            finish();
            return;
        }

        initViews();
        setupBottomNav();
        setupCharts();
        setupFilters();
        setupSearch();
        setupRecyclerViews();
        requestSmsPermission();
    }

    private void initViews() {
        todaySpent = findViewById(R.id.today_spent);
        todayIncome = findViewById(R.id.today_income);
        monthSpent = findViewById(R.id.month_spent);
        monthIncome = findViewById(R.id.month_income);
        budgetLeft = findViewById(R.id.budget_left);
        trendChart = findViewById(R.id.chart_trend);
        categoryChart = findViewById(R.id.chart_category);
        swipeRefresh = findViewById(R.id.swipe_refresh);
        searchInput = findViewById(R.id.search_input);
        pageDashboard = findViewById(R.id.swipe_refresh);
        pageTransactions = findViewById(R.id.page_transactions);
        bottomNav = findViewById(R.id.bottom_nav);

        filterAll = findViewById(R.id.filter_all);
        filterDebit = findViewById(R.id.filter_debit);
        filterCredit = findViewById(R.id.filter_credit);
        filterSms = findViewById(R.id.filter_sms);
        filterNotif = findViewById(R.id.filter_notif);

        swipeRefresh.setColorSchemeColors(ContextCompat.getColor(this, R.color.blue));
        swipeRefresh.setOnRefreshListener(this::loadData);

        FloatingActionButton fab = findViewById(R.id.fab);
        fab.setOnClickListener(v ->
                startActivityForResult(new Intent(this, AddTransactionActivity.class), REQUEST_ADD));

        findViewById(R.id.btn_settings).setOnClickListener(v -> {
            bottomNav.setSelectedItemId(R.id.nav_settings);
        });

        // View All button on dashboard
        TextView viewAll = findViewById(R.id.btn_view_all);
        if (viewAll != null) {
            viewAll.setOnClickListener(v -> bottomNav.setSelectedItemId(R.id.nav_transactions));
        }
    }

    // ── Bottom Navigation ──
    private void setupBottomNav() {
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_dashboard) {
                showPage("dashboard");
                return true;
            } else if (id == R.id.nav_transactions) {
                showPage("transactions");
                return true;
            } else if (id == R.id.nav_settings) {
                startActivity(new Intent(this, SettingsActivity.class));
                return true;
            }
            return false;
        });
    }

    private void showPage(String page) {
        pageDashboard.setVisibility(page.equals("dashboard") ? View.VISIBLE : View.GONE);
        pageTransactions.setVisibility(page.equals("transactions") ? View.VISIBLE : View.GONE);
    }

    // ── Recycler Views ──
    private void setupRecyclerViews() {
        // Dashboard: recent transactions (top 5)
        RecyclerView recentRv = findViewById(R.id.recent_tx_recycler);
        recentRv.setNestedScrollingEnabled(false);
        recentRv.setLayoutManager(new LinearLayoutManager(this));
        recentAdapter = new TransactionAdapter(recentTransactions,
                tx -> deleteTx(tx), tx -> editTx(tx));
        recentRv.setAdapter(recentAdapter);

        // Transactions page: full filtered list
        RecyclerView fullRv = findViewById(R.id.tx_recycler);
        fullRv.setLayoutManager(new LinearLayoutManager(this));
        fullAdapter = new TransactionAdapter(filteredTransactions,
                tx -> deleteTx(tx), tx -> editTx(tx));
        fullRv.setAdapter(fullAdapter);
    }

    private void deleteTx(Transaction tx) {
        new android.app.AlertDialog.Builder(this)
            .setTitle("Delete Transaction")
            .setMessage("Delete this ₹" + String.format(Locale.US, "%.0f", tx.amount) + " transaction?")
            .setPositiveButton("Delete", (d, w) -> {
                supabase.deleteTransaction(tx.id, new SupabaseHelper.SimpleCallback() {
                    @Override public void onSuccess() { runOnUiThread(() -> loadData()); }
                    @Override public void onError(String m) {
                        runOnUiThread(() -> Toast.makeText(MainActivity.this,
                                getString(R.string.error_delete_prefix, m), Toast.LENGTH_SHORT).show());
                    }
                });
            })
            .setNegativeButton("Cancel", null)
            .show();
    }

    private void editTx(Transaction tx) {
        Intent intent = new Intent(this, EditTransactionActivity.class);
        intent.putExtra("tx_id", tx.id);
        intent.putExtra("tx_type", tx.type != null ? tx.type : "debit");
        intent.putExtra("tx_amount", tx.amount);
        intent.putExtra("tx_category", tx.category != null ? tx.category : "Other");
        intent.putExtra("tx_description", tx.description != null ? tx.description : "");
        intent.putExtra("tx_date", tx.transactionDate != null ? tx.transactionDate : "");
        startActivityForResult(intent, REQUEST_EDIT);
    }

    // ── Search ──
    private void setupSearch() {
        searchInput.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                currentSearch = s.toString().trim().toLowerCase(Locale.ROOT);
                applyFilter(currentFilter);
            }
            @Override public void afterTextChanged(Editable s) {}
        });
    }

    // ── Filters ──
    private void setupFilters() {
        View.OnClickListener listener = v -> {
            int id = v.getId();
            if (id == R.id.filter_all) applyFilter("all");
            else if (id == R.id.filter_debit) applyFilter("debit");
            else if (id == R.id.filter_credit) applyFilter("credit");
            else if (id == R.id.filter_sms) applyFilter("sms");
            else if (id == R.id.filter_notif) applyFilter("notification");
        };
        filterAll.setOnClickListener(listener);
        filterDebit.setOnClickListener(listener);
        filterCredit.setOnClickListener(listener);
        filterSms.setOnClickListener(listener);
        filterNotif.setOnClickListener(listener);
    }

    private void applyFilter(String filter) {
        currentFilter = filter;
        ColorStateList blueList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.blue));
        ColorStateList darkList = ColorStateList.valueOf(ContextCompat.getColor(this, R.color.card_dark));
        int white = ContextCompat.getColor(this, R.color.white);
        int muted = ContextCompat.getColor(this, R.color.text_muted);

        filterAll.setBackgroundTintList(filter.equals("all") ? blueList : darkList);
        filterAll.setTextColor(filter.equals("all") ? white : muted);
        filterDebit.setBackgroundTintList(filter.equals("debit") ? blueList : darkList);
        filterDebit.setTextColor(filter.equals("debit") ? white : muted);
        filterCredit.setBackgroundTintList(filter.equals("credit") ? blueList : darkList);
        filterCredit.setTextColor(filter.equals("credit") ? white : muted);
        filterSms.setBackgroundTintList(filter.equals("sms") ? blueList : darkList);
        filterSms.setTextColor(filter.equals("sms") ? white : muted);
        filterNotif.setBackgroundTintList(filter.equals("notification") ? blueList : darkList);
        filterNotif.setTextColor(filter.equals("notification") ? white : muted);

        filteredTransactions.clear();
        for (Transaction tx : allTransactions) {
            boolean matchesFilter = filter.equals("all")
                    || (filter.equals("debit") && "debit".equals(tx.type))
                    || (filter.equals("credit") && "credit".equals(tx.type))
                    || (filter.equals("sms") && "sms".equals(tx.source))
                    || (filter.equals("notification") && "notification".equals(tx.source));

            if (matchesFilter && matchesSearch(tx)) {
                filteredTransactions.add(tx);
            }
        }
        fullAdapter.updateItems(filteredTransactions);
    }

    private boolean matchesSearch(Transaction tx) {
        if (currentSearch.isEmpty()) return true;
        if (tx.description != null && tx.description.toLowerCase(Locale.ROOT).contains(currentSearch)) return true;
        if (tx.category != null && tx.category.toLowerCase(Locale.ROOT).contains(currentSearch)) return true;
        if (tx.source != null && tx.source.toLowerCase(Locale.ROOT).contains(currentSearch)) return true;
        if (String.valueOf(tx.amount).contains(currentSearch)) return true;
        return false;
    }

    // ── Data Loading ──
    @Override
    protected void onResume() {
        super.onResume();
        if (supabase != null && supabase.isLoggedIn()) {
            loadData();
            // Reset bottom nav if coming back from settings
            if (bottomNav != null) {
                int selectedId = bottomNav.getSelectedItemId();
                if (selectedId == R.id.nav_settings) {
                    bottomNav.setSelectedItemId(R.id.nav_dashboard);
                }
            }
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if ((requestCode == REQUEST_ADD || requestCode == REQUEST_EDIT) && resultCode == RESULT_OK) {
            loadData();
        }
    }

    private void loadData() {
        swipeRefresh.setRefreshing(true);
        supabase.getProfile(new SupabaseHelper.ProfileCallback() {
            @Override
            public void onSuccess(String fullName, double budget) {
                monthlyBudget = budget;
                runOnUiThread(() -> {
                    TextView greeting = findViewById(R.id.greeting_text);
                    if (greeting != null && fullName != null && !fullName.isEmpty()) {
                        greeting.setText("Welcome, " + fullName);
                    }
                });
                supabase.fetchTransactions(new SupabaseHelper.TransactionsCallback() {
                    @Override
                    public void onSuccess(List<Transaction> txs) {
                        runOnUiThread(() -> {
                            try {
                                allTransactions.clear();
                                allTransactions.addAll(txs);

                                // Update recent (top 5)
                                recentTransactions.clear();
                                for (int i = 0; i < Math.min(5, txs.size()); i++) {
                                    recentTransactions.add(txs.get(i));
                                }
                                if (recentAdapter != null) {
                                    recentAdapter.updateItems(recentTransactions);
                                }

                                applyFilter(currentFilter);
                                updateSummaryAndCharts();
                                swipeRefresh.setRefreshing(false);
                            } catch (Exception e) {
                                Log.e(TAG, "Error processing transactions: " + e.getMessage());
                                swipeRefresh.setRefreshing(false);
                            }
                        });
                    }

                    @Override
                    public void onError(String message) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    getString(R.string.load_error_prefix, message),
                                    Toast.LENGTH_SHORT).show();
                            swipeRefresh.setRefreshing(false);
                        });
                    }
                });
            }

            @Override
            public void onError(String message) {
                runOnUiThread(() -> {
                    Toast.makeText(MainActivity.this,
                            getString(R.string.profile_load_error_prefix, message),
                            Toast.LENGTH_SHORT).show();
                    swipeRefresh.setRefreshing(false);
                });
            }
        });
    }

    // ── Summary & Charts ──
    private void updateSummaryAndCharts() {
        String today = new SimpleDateFormat("yyyy-MM-dd", Locale.US).format(new Date());
        String monthPrefix = today.substring(0, 7);
        double tSpent = 0, tIncome = 0, mSpent = 0, mIncome = 0;

        for (Transaction tx : allTransactions) {
            String txDate = "";
            if (tx.transactionDate != null && tx.transactionDate.length() >= 10) {
                txDate = tx.transactionDate.substring(0, 10);
            }
            boolean isDebit = "debit".equals(tx.type);
            boolean isCredit = "credit".equals(tx.type);

            if (txDate.equals(today)) {
                if (isDebit) tSpent += tx.amount;
                else if (isCredit) tIncome += tx.amount;
            }
            if (txDate.startsWith(monthPrefix)) {
                if (isDebit) mSpent += tx.amount;
                else if (isCredit) mIncome += tx.amount;
            }
        }

        todaySpent.setText(getString(R.string.currency_format_no_sign, tSpent));
        todayIncome.setText(getString(R.string.currency_format_no_sign, tIncome));
        monthSpent.setText(getString(R.string.currency_format_no_sign, mSpent));
        monthIncome.setText(getString(R.string.currency_format_no_sign, mIncome));

        if (monthlyBudget > 0) {
            double remaining = Math.max(0, monthlyBudget - mSpent);
            budgetLeft.setText(getString(R.string.currency_format_no_sign, remaining));
        } else {
            budgetLeft.setText(R.string.no_budget);
        }

        updateTrendChart();
        updateCategoryChart(monthPrefix);
    }

    private void setupCharts() {
        if (trendChart == null || categoryChart == null) {
            Log.w(TAG, "Chart views not initialized, skipping setup");
            return;
        }

        int textColor = Color.parseColor("#8892A8");
        int gridColor = Color.parseColor("#2A2D3A");

        try {
            trendChart.getDescription().setEnabled(false);
            trendChart.getLegend().setEnabled(false);
            trendChart.setTouchEnabled(false);
            XAxis xAxis = trendChart.getXAxis();
            xAxis.setPosition(XAxis.XAxisPosition.BOTTOM);
            xAxis.setTextColor(textColor);
            xAxis.setDrawGridLines(false);
            trendChart.getAxisLeft().setTextColor(textColor);
            trendChart.getAxisLeft().setGridColor(gridColor);
            trendChart.getAxisRight().setEnabled(false);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up trend chart: " + e.getMessage());
        }

        try {
            categoryChart.getDescription().setEnabled(false);
            categoryChart.getLegend().setTextColor(textColor);
            categoryChart.getLegend().setWordWrapEnabled(true);
            categoryChart.setDrawHoleEnabled(true);
            categoryChart.setHoleColor(Color.TRANSPARENT);
            categoryChart.setTransparentCircleRadius(0f);
            categoryChart.setHoleRadius(65f);
            categoryChart.setCenterTextColor(textColor);
        } catch (Exception e) {
            Log.e(TAG, "Error setting up category chart: " + e.getMessage());
        }
    }

    private void updateTrendChart() {
        if (trendChart == null) return;

        try {
            List<Entry> entries = new ArrayList<>();
            List<String> labels = new ArrayList<>();
            Calendar cal = Calendar.getInstance();
            cal.add(Calendar.DAY_OF_YEAR, -29);
            SimpleDateFormat fmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
            SimpleDateFormat lblFmt = new SimpleDateFormat("dd MMM", Locale.US);

            for (int i = 0; i < 30; i++) {
                String dateStr = fmt.format(cal.getTime());
                labels.add(lblFmt.format(cal.getTime()));
                double dayTotal = 0;
                for (Transaction tx : allTransactions) {
                    if ("debit".equals(tx.type) && tx.transactionDate != null
                            && tx.transactionDate.startsWith(dateStr)) {
                        dayTotal += tx.amount;
                    }
                }
                entries.add(new Entry(i, (float) dayTotal));
                cal.add(Calendar.DAY_OF_YEAR, 1);
            }

            LineDataSet dataSet = new LineDataSet(entries, "Spending");
            dataSet.setColor(ContextCompat.getColor(this, R.color.blue));
            dataSet.setLineWidth(2f);
            dataSet.setDrawCircles(false);
            dataSet.setDrawValues(false);
            dataSet.setMode(LineDataSet.Mode.CUBIC_BEZIER);
            dataSet.setDrawFilled(true);
            dataSet.setFillColor(ContextCompat.getColor(this, R.color.blue));
            dataSet.setFillAlpha(40);

            trendChart.setData(new LineData(dataSet));
            trendChart.getXAxis().setValueFormatter(new IndexAxisValueFormatter(labels));
            trendChart.getXAxis().setLabelCount(5);
            trendChart.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error updating trend chart: " + e.getMessage());
        }
    }

    private void updateCategoryChart(String monthPrefix) {
        if (categoryChart == null) return;

        try {
            Map<String, Float> catTotals = new HashMap<>();
            for (Transaction tx : allTransactions) {
                if ("debit".equals(tx.type) && tx.transactionDate != null
                        && tx.transactionDate.startsWith(monthPrefix)) {
                    String cat = tx.category != null ? tx.category : "Other";
                    catTotals.put(cat, catTotals.getOrDefault(cat, 0f) + (float) tx.amount);
                }
            }

            List<PieEntry> entries = new ArrayList<>();
            List<Integer> colors = new ArrayList<>();
            for (Map.Entry<String, Float> entry : catTotals.entrySet()) {
                if (entry.getValue() > 0) {
                    entries.add(new PieEntry(entry.getValue(), entry.getKey()));
                    colors.add(getCategoryColor(entry.getKey()));
                }
            }

            PieDataSet dataSet = new PieDataSet(entries, "");
            dataSet.setColors(colors);
            dataSet.setDrawValues(false);
            dataSet.setSliceSpace(3f);
            dataSet.setSelectionShift(5f);
            categoryChart.setData(new PieData(dataSet));
            categoryChart.setCenterText(entries.isEmpty() ? getString(R.string.no_expenses_center_text) : "");
            categoryChart.invalidate();
        } catch (Exception e) {
            Log.e(TAG, "Error updating category chart: " + e.getMessage());
        }
    }

    private int getCategoryColor(String category) {
        if (category == null) return Color.parseColor("#64748B");
        switch (category) {
            case "Food & Dining": return Color.parseColor("#EF4444");
            case "Transport": return Color.parseColor("#F59E0B");
            case "Shopping": return Color.parseColor("#EC4899");
            case "Bills & Utilities": return Color.parseColor("#8B5CF6");
            case "Entertainment": return Color.parseColor("#06B6D4");
            case "Health": return Color.parseColor("#10B981");
            case "Education": return Color.parseColor("#3B82F6");
            case "Salary": return Color.parseColor("#22C55E");
            case "Recharge": return Color.parseColor("#6366F1");
            case "Transfer": return Color.parseColor("#A855F7");
            default: return Color.parseColor("#64748B");
        }
    }

    // ── Permissions ──
    private void requestSmsPermission() {
        try {
            List<String> perms = new ArrayList<>();
            perms.add(Manifest.permission.RECEIVE_SMS);
            perms.add(Manifest.permission.READ_SMS);
            if (android.os.Build.VERSION.SDK_INT >= 33) {
                perms.add(Manifest.permission.POST_NOTIFICATIONS);
            }
            boolean needRequest = false;
            for (String p : perms) {
                if (ContextCompat.checkSelfPermission(this, p) != PackageManager.PERMISSION_GRANTED) {
                    needRequest = true;
                    break;
                }
            }
            if (needRequest) {
                ActivityCompat.requestPermissions(this, perms.toArray(new String[0]), SMS_PERMISSION_CODE);
            } else {
                Log.d(TAG, "All SMS permissions already granted");
            }
        } catch (Exception e) {
            Log.e(TAG, "Error requesting SMS permission: " + e.getMessage());
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, @NonNull String[] p, @NonNull int[] r) {
        super.onRequestPermissionsResult(code, p, r);
        if (code == SMS_PERMISSION_CODE) {
            if (r.length > 0 && r[0] == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, R.string.sms_tracking_enabled, Toast.LENGTH_SHORT).show();
            } else {
                Toast.makeText(this, R.string.sms_tracking_denied, Toast.LENGTH_LONG).show();
            }
        }
    }
}
