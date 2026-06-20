package com.spendsense.app;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.annotation.NonNull;
import androidx.recyclerview.widget.DiffUtil;
import androidx.recyclerview.widget.RecyclerView;
import java.util.ArrayList;
import java.util.List;

public class TransactionAdapter extends RecyclerView.Adapter<TransactionAdapter.VH> {
    public interface OnDeleteListener { void onDelete(Transaction tx); }
    public interface OnEditListener { void onEdit(Transaction tx); }

    private final List<Transaction> items = new ArrayList<>();
    private final OnDeleteListener deleteListener;
    private final OnEditListener editListener;

    public TransactionAdapter(List<Transaction> items, OnDeleteListener deleteListener, OnEditListener editListener) {
        this.items.addAll(items);
        this.deleteListener = deleteListener;
        this.editListener = editListener;
    }

    public void updateItems(List<Transaction> newItems) {
        DiffUtil.DiffResult result = DiffUtil.calculateDiff(new DiffUtil.Callback() {
            @Override public int getOldListSize() { return items.size(); }
            @Override public int getNewListSize() { return newItems.size(); }
            @Override public boolean areItemsTheSame(int o, int n) {
                return items.get(o).id.equals(newItems.get(n).id);
            }
            @Override public boolean areContentsTheSame(int o, int n) {
                Transaction a = items.get(o), b = newItems.get(n);
                return a.amount == b.amount && safeEq(a.type, b.type)
                        && safeEq(a.category, b.category) && safeEq(a.description, b.description);
            }
        });
        items.clear();
        items.addAll(newItems);
        result.dispatchUpdatesTo(this);
    }

    private boolean safeEq(String a, String b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.equals(b);
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int type) {
        return new VH(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_transaction, parent, false));
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int pos) {
        Transaction tx = items.get(pos);

        h.icon.setText(tx.getCategoryIcon());
        h.desc.setText(tx.description != null && !tx.description.isEmpty()
                ? tx.description : (tx.category != null ? tx.category : ""));
        h.category.setText(tx.category != null ? tx.category : "Other");

        String sourceLabel = tx.source != null ? tx.source.toUpperCase(java.util.Locale.ROOT) : "MANUAL";
        h.source.setText(sourceLabel);

        try {
            if (tx.transactionDate != null && tx.transactionDate.length() >= 10) {
                h.date.setText(tx.transactionDate.substring(0, 10));
            } else { h.date.setText(""); }
        } catch (Exception e) { h.date.setText(""); }

        boolean isDebit = "debit".equals(tx.type);
        String sign = isDebit ? "−" : "+";
        h.amount.setText(h.itemView.getContext().getString(R.string.currency_format, sign, tx.amount));
        h.amount.setTextColor(h.itemView.getContext().getColor(isDebit ? R.color.red : R.color.green));

        // Source badge color
        int bgRes, textColorRes;
        if ("sms".equals(tx.source)) { bgRes = R.color.purple_bg; textColorRes = R.color.purple; }
        else if ("notification".equals(tx.source)) { bgRes = R.color.green_bg; textColorRes = R.color.green; }
        else { bgRes = R.color.blue_bg; textColorRes = R.color.blue; }
        h.source.setBackgroundResource(bgRes);
        h.source.setTextColor(h.itemView.getContext().getColor(textColorRes));

        // Tap to edit
        h.itemView.setOnClickListener(v -> {
            if (editListener != null) editListener.onEdit(tx);
        });

        // Long press to delete
        h.itemView.setOnLongClickListener(v -> {
            if (deleteListener != null) deleteListener.onDelete(tx);
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        TextView icon, desc, category, date, source, amount;
        VH(View v) {
            super(v);
            icon = v.findViewById(R.id.tx_icon);
            desc = v.findViewById(R.id.tx_desc);
            category = v.findViewById(R.id.tx_category);
            date = v.findViewById(R.id.tx_date);
            source = v.findViewById(R.id.tx_source);
            amount = v.findViewById(R.id.tx_amount);
        }
    }
}
