package com.watlis.app;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.watlis.app.data.ProgressHistoryEntity;
import com.watlis.app.data.WatlisRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

/** A bounded database page at a time, with recycled text-only rows. */
final class ProgressHistoryDialog {
    private static final int PAGE_SIZE = 30, TEXT = Color.rgb(243,246,241), MUTED = Color.rgb(166,176,168);
    private final Activity activity;
    private final WatlisRepository repository;
    private final Executor executor;
    private final long mediaId;
    private final List<ProgressHistoryEntity> entries = new ArrayList<>();
    private final HistoryAdapter adapter = new HistoryAdapter();
    private TextView footer, undo, empty;
    private RecyclerView list;
    private boolean loading, finished, closed;
    private long cursor = Long.MAX_VALUE;

    static void show(Activity activity, WatlisRepository repository, Executor executor, long mediaId,
                     String title, int accent, Consumer<ProgressHistoryEntity> onUndo) {
        new ProgressHistoryDialog(activity, repository, executor, mediaId).open(title, accent, onUndo);
    }

    private ProgressHistoryDialog(Activity activity, WatlisRepository repository, Executor executor, long mediaId) {
        this.activity = activity; this.repository = repository; this.executor = executor; this.mediaId = mediaId;
    }

    private int dp(float size) { return Math.round(size * activity.getResources().getDisplayMetrics().density); }
    private TextView text(String value, float size, int color) {
        TextView view = new TextView(activity); view.setText(value); view.setTextSize(size); view.setTextColor(color);
        return view;
    }

    private void open(String title, int accent, Consumer<ProgressHistoryEntity> onUndo) {
        LinearLayout body = new LinearLayout(activity); body.setOrientation(LinearLayout.VERTICAL);
        body.setPadding(dp(20), dp(12), dp(20), 0);
        TextView subtitle = text(title, 14, MUTED); subtitle.setMaxLines(2);
        subtitle.setEllipsize(android.text.TextUtils.TruncateAt.END); body.addView(subtitle);
        undo = text(activity.getString(R.string.history_undo_latest), 14, accent); undo.setGravity(Gravity.CENTER);
        undo.setMinHeight(dp(48)); undo.setVisibility(View.INVISIBLE);
        body.addView(undo, new LinearLayout.LayoutParams(-1,-2));
        list = new RecyclerView(activity); list.setContentDescription(activity.getString(R.string.history_entries));
        LinearLayoutManager layout = new LinearLayoutManager(activity); list.setLayoutManager(layout);
        adapter.setHasStableIds(true); list.setAdapter(adapter); list.setItemAnimator(null);
        int height = Math.min(dp(400), Math.round(activity.getResources().getDisplayMetrics().heightPixels * .45f));
        LinearLayout.LayoutParams listParams = new LinearLayout.LayoutParams(-1,height); listParams.topMargin=dp(12);
        android.widget.FrameLayout viewport = new android.widget.FrameLayout(activity);
        viewport.addView(list, new android.widget.FrameLayout.LayoutParams(-1,-1));
        empty = text(activity.getString(R.string.history_empty),16,MUTED);
        empty.setGravity(Gravity.CENTER); empty.setPadding(dp(16),dp(16),dp(16),dp(16)); empty.setVisibility(View.GONE);
        viewport.addView(empty,new android.widget.FrameLayout.LayoutParams(-1,-1));
        body.addView(viewport, listParams);
        footer = text(activity.getString(R.string.history_loading), 13, MUTED); footer.setGravity(Gravity.CENTER); footer.setMinHeight(dp(48));
        body.addView(footer, new LinearLayout.LayoutParams(-1,-2));
        AlertDialog dialog = new MaterialAlertDialogBuilder(activity).setTitle(R.string.history_title)
                .setView(body).setPositiveButton(R.string.history_close,null).create();
        dialog.setOnDismissListener(d -> closed=true);
        undo.setOnClickListener(v -> {
            if (entries.isEmpty()) return;
            ProgressHistoryEntity change = entries.get(0);
            dialog.dismiss(); onUndo.accept(change);
        });
        list.addOnScrollListener(new RecyclerView.OnScrollListener() {
            @Override public void onScrolled(RecyclerView recycler, int dx, int dy) {
                if (dy > 0 && layout.findLastVisibleItemPosition() >= entries.size()-5) loadNext();
            }
        });
        dialog.show(); loadNext();
    }

    private void loadNext() {
        if (closed || loading || finished) return;
        loading=true; footer.setEnabled(false); footer.setText(R.string.history_loading);
        long before=cursor;
        executor.execute(() -> {
            try {
                List<ProgressHistoryEntity> page=repository.progressHistory(mediaId,before,PAGE_SIZE);
                activity.runOnUiThread(() -> {
                    if(closed || activity.isDestroyed() || activity.isFinishing())return;
                    int start=entries.size(); entries.addAll(page);
                    if(!page.isEmpty())cursor=page.get(page.size()-1).id;
                    loading=false; finished=page.size()<PAGE_SIZE;
                    adapter.notifyItemRangeInserted(start,page.size());
                    empty.setVisibility(entries.isEmpty() ? View.VISIBLE : View.GONE);
                    undo.setVisibility(!entries.isEmpty() && !"undo".equals(entries.get(0).kind) ? View.VISIBLE : View.INVISIBLE);
                    footer.setText(entries.isEmpty() ? "" : activity.getString(finished ? R.string.history_beginning : R.string.history_scroll));
                });
            } catch(Exception error) {
                android.util.Log.e("Watlis","Could not load progress history",error);
                activity.runOnUiThread(() -> {
                    if(closed || activity.isDestroyed() || activity.isFinishing())return;
                    loading=false; footer.setText(R.string.history_error); footer.setEnabled(true);
                    footer.setOnClickListener(v -> loadNext());
                });
            }
        });
    }

    private static String number(double value) { return java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString(); }

    private class HistoryAdapter extends RecyclerView.Adapter<HistoryHolder> {
        @Override public long getItemId(int position) { return entries.get(position).id; }
        @Override public int getItemCount() { return entries.size(); }
        @Override public HistoryHolder onCreateViewHolder(ViewGroup parent,int type) {
            LinearLayout row=new LinearLayout(activity); row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(14),dp(12),dp(14),dp(12));
            GradientDrawable background=new GradientDrawable(); background.setColor(Color.rgb(27,33,29)); background.setCornerRadius(dp(12));
            row.setBackground(background);
            RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,-2); params.bottomMargin=dp(8); row.setLayoutParams(params);
            TextView kind=text("",12,MUTED), amount=text("",16,TEXT), date=text("",12,MUTED);
            amount.setTypeface(Typeface.DEFAULT,Typeface.BOLD); amount.setPadding(0,dp(4),0,dp(4));
            row.addView(kind); row.addView(amount); row.addView(date);
            return new HistoryHolder(row,kind,amount,date);
        }
        @Override public void onBindViewHolder(HistoryHolder holder,int position) {
            ProgressHistoryEntity change=entries.get(position);
            holder.kind.setText("reading".equals(change.kind) ? R.string.history_reading : "undo".equals(change.kind) ? R.string.history_undo : R.string.history_correction);
            holder.amount.setText(activity.getString(R.string.history_progress,change.unit,number(change.fromProgress),number(change.toProgress)));
            holder.date.setText(java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM,java.text.DateFormat.SHORT)
                    .format(new java.util.Date(change.recordedAt)));
        }
    }
    private static class HistoryHolder extends RecyclerView.ViewHolder {
        final TextView kind,amount,date;
        HistoryHolder(View root,TextView kind,TextView amount,TextView date) { super(root); this.kind=kind; this.amount=amount; this.date=date; }
    }
}
