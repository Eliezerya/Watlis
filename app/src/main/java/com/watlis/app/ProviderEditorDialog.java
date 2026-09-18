package com.watlis.app;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.view.View;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import java.util.Arrays;
import java.util.concurrent.Executor;

/** Local provider form with retained drafts and no network activity on Save or Preview. */
final class ProviderEditorDialog {
    private final Activity activity;
    private final ImportProviderStore store;
    private final Executor executor;
    private final Runnable saved, closed;
    private final TextInputEditText[] fields=new TextInputEditText[8];
    private CheckBox enabled, uuid;
    private TextView error;
    private AlertDialog dialog;
    private String providerId, baseline;
    private boolean saving;

    ProviderEditorDialog(Activity activity,ImportProviderStore store,Executor executor,Runnable saved,Runnable closed) {
        this.activity=activity;this.store=store;this.executor=executor;this.saved=saved;this.closed=closed;
    }
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(Color.parseColor("#A6B0A8"));return v;}

    void show(ImportProvider existing,Bundle draft) {
        ImportProvider initial=existing==null ? new ImportProvider() : existing.copy();
        providerId=draft==null ? initial.id : draft.getString("id");
        LinearLayout form=new LinearLayout(activity);form.setOrientation(LinearLayout.VERTICAL);
        form.setFocusableInTouchMode(true);form.requestFocus();
        form.setPadding(dp(20),dp(12),dp(20),dp(16));
        TextView help=text("For compatible chapter/manga JSON APIs. Add only services you trust. Saving settings does not contact them.",14);
        form.addView(help);
        String[] labels={"Provider name","Website hosts","API base URL","Chapter link path","Chapter endpoint path","Manga endpoint path","Cover image hosts","Test chapter URL (optional)"};
        String[] values={initial.name,initial.hosts,initial.apiBase,initial.linkPath,initial.chapterEndpoint,initial.mangaEndpoint,initial.imageHosts,""};
        String[] restored=draft==null ? null : draft.getStringArray("fields");
        for(int i=0;i<fields.length;i++) {
            TextInputLayout box=new TextInputLayout(activity);box.setHint(labels[i]);
            box.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
            box.setBoxBackgroundColor(Color.parseColor("#28312B"));
            box.setBoxStrokeColor(Color.parseColor("#C7ED9A"));
            box.setDefaultHintTextColor(android.content.res.ColorStateList.valueOf(Color.parseColor("#A6B0A8")));
            TextInputEditText input=new TextInputEditText(box.getContext());input.setTextColor(Color.parseColor("#F3F6F1"));input.setTextSize(16);
            input.setBackground(null);input.setSingleLine(i!=1&&i!=6);input.setMinHeight(dp(56));
            input.setInputType(android.text.InputType.TYPE_CLASS_TEXT | android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
                    | ((i==1||i==6) ? android.text.InputType.TYPE_TEXT_FLAG_MULTI_LINE : 0));
            input.setText(restored==null ? values[i] : restored[i]);fields[i]=input;
            box.addView(input,new LinearLayout.LayoutParams(-1,-2));
            if(i==1)box.setHelperText("One host per line. Example: reader.example.com or *.example.com. A wildcard matches subdomains only.");
            if(i==2)box.setHelperText("HTTPS only; include the API version if needed, e.g. https://api.example.com/v1/");
            if(i==3)box.setHelperText("The website path, e.g. /chapter/{id}. Include {id} exactly once.");
            if(i==4||i==5)box.setHelperText("Relative to the API base; no leading slash. Use {id} for the chapter or manga ID.");
            if(i==6)box.setHelperText("Allowed cover domains. Leave empty to import without covers. No URLs; wildcards are supported.");
            LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(-1,-2);params.topMargin=dp(12);form.addView(box,params);
        }
        enabled=new CheckBox(activity);enabled.setText(R.string.provider_enabled);enabled.setTextColor(Color.parseColor("#F3F6F1"));enabled.setMinHeight(dp(48));
        enabled.setChecked(draft==null ? initial.enabled : draft.getBoolean("enabled"));form.addView(enabled);
        uuid=new CheckBox(activity);uuid.setText(R.string.provider_uuid);uuid.setTextColor(Color.parseColor("#F3F6F1"));uuid.setMinHeight(dp(48));
        uuid.setChecked(draft==null ? initial.uuidIds : draft.getBoolean("uuid"));form.addView(uuid);
        form.addView(text("Uncheck for numeric IDs or URL-safe IDs (letters, numbers, _ and -). The API must return data.chapter_id, data.manga_id and data.chapter_number; manga details must use the current title/taxonomy fields.",13));
        TextView preview=text("Preview requests",15);preview.setTextColor(Color.parseColor("#C7ED9A"));preview.setMinHeight(dp(48));preview.setGravity(android.view.Gravity.CENTER);
        preview.setOnClickListener(v->preview());form.addView(preview);
        error=text("",14);error.setTextColor(Color.parseColor("#FFAAA6"));error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);form.addView(error);
        // Reserve room for the title and a stacked native button bar at narrow widths / large text.
        ScrollView scroll=new ScrollView(activity) {
            @Override protected void onMeasure(int widthSpec,int heightSpec) {
                int limit=Math.min(dp(480),Math.round(getResources().getDisplayMetrics().heightPixels*.45f));
                if(MeasureSpec.getMode(heightSpec)!=MeasureSpec.UNSPECIFIED)limit=Math.min(limit,MeasureSpec.getSize(heightSpec));
                super.onMeasure(widthSpec,MeasureSpec.makeMeasureSpec(limit,MeasureSpec.AT_MOST));
            }
        };
        scroll.setFillViewport(true);scroll.addView(form);
        android.graphics.drawable.GradientDrawable surface=new android.graphics.drawable.GradientDrawable();
        surface.setColor(Color.parseColor("#1E2520"));surface.setCornerRadius(dp(20));
        dialog=new MaterialAlertDialogBuilder(activity).setTitle(existing==null&&draft==null ? "Add import provider" : "Edit import provider")
                .setBackground(surface)
                .setView(scroll).setNegativeButton("Cancel",null).setPositiveButton("Save provider",null).create();
        dialog.setCanceledOnTouchOutside(false);
        dialog.setOnDismissListener(d->closed.run());
        dialog.setOnKeyListener((d,key,event)->{
            if(key==android.view.KeyEvent.KEYCODE_BACK && event.getAction()==android.view.KeyEvent.ACTION_UP){exit();return true;}return false;
        });
        if(dialog.getWindow()!=null)dialog.getWindow().setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE
                | android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(v->exit());
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->save());
        baseline=draft==null ? key() : draft.getString("baseline",key());
    }

    Bundle capture() {
        if(dialog==null||!dialog.isShowing())return null;
        Bundle draft=new Bundle();String[] values=new String[fields.length];
        for(int i=0;i<fields.length;i++)values[i]=value(i);
        draft.putStringArray("fields",values);draft.putString("id",providerId);draft.putBoolean("enabled",enabled.isChecked());draft.putBoolean("uuid",uuid.isChecked());
        draft.putString("baseline",baseline);return draft;
    }
    private String value(int index){return fields[index]==null||fields[index].getText()==null ? "" : fields[index].getText().toString().trim();}
    private String key(){String[] values=new String[fields.length];for(int i=0;i<fields.length;i++)values[i]=value(i);return Arrays.toString(values)+enabled.isChecked()+uuid.isChecked();}
    private ImportProvider read() {
        ImportProvider p=new ImportProvider();p.id=providerId;p.name=value(0);p.hosts=value(1);p.apiBase=value(2);
        p.linkPath=value(3);p.chapterEndpoint=value(4);p.mangaEndpoint=value(5);p.imageHosts=value(6);p.enabled=enabled.isChecked();p.uuidIds=uuid.isChecked();p.validate();return p;
    }
    private void preview() {
        try {
            ImportProvider p=read();p.enabled=true;String id=p.chapterIdFor(value(7));
            if(id==null)throw new IllegalArgumentException("Enter a test chapter URL that matches these website hosts, link path and ID format.");
            new MaterialAlertDialogBuilder(activity).setTitle("Request preview")
                    .setMessage("Chapter ID: "+id+"\n\nGET "+p.endpoint(true,id)+"\n\nGET "+p.apiBase+p.mangaEndpoint.replace("{id}","[manga_id from chapter]")+"\n\nNo requests sent.")
                    .setPositiveButton("Close",null).show();
            error.setText("");
        } catch(IllegalArgumentException problem){showError(problem.getMessage());}
    }
    private void exit() {
        if(saving)return;
        if(key().equals(baseline)){dialog.dismiss();return;}
        new MaterialAlertDialogBuilder(activity).setTitle("Keep provider changes?").setMessage("Keep saves these provider settings. Discard removes only your unsaved edits.")
                .setNegativeButton("Discard",(d,w)->dialog.dismiss()).setPositiveButton("Keep",(d,w)->save()).show();
    }
    private void save() {
        if(saving)return;
        final ImportProvider provider;
        try {provider=read();}catch(IllegalArgumentException problem){showError(problem.getMessage());return;}
        saving=true;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(false);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(false);
        executor.execute(()->{
            try {
                store.save(provider);
                activity.runOnUiThread(()->{if(!activity.isDestroyed()&&!activity.isFinishing()){dialog.dismiss();saved.run();}});
            } catch(Exception problem) {
                activity.runOnUiThread(()->{if(!activity.isDestroyed()&&!activity.isFinishing()){
                    saving=false;dialog.getButton(AlertDialog.BUTTON_POSITIVE).setEnabled(true);dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setEnabled(true);
                    showError(problem.getMessage()==null ? "Could not save provider settings." : problem.getMessage());
                }});
            }
        });
    }

    private void showError(String message) {
        error.setText(message);
        error.post(()->error.requestRectangleOnScreen(new android.graphics.Rect(0,0,error.getWidth(),error.getHeight()),true));
    }
}
