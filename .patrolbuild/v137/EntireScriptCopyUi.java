package au.com.roningroup.patrollink;

import android.app.Activity;
import android.content.*;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.PersistableBundle;
import android.view.View;
import android.widget.*;

/** The same one-tap action on the dashboard, voice library and speech controls. */
public final class EntireScriptCopyUi extends LinearLayout {
    public static final String BUTTON_TAG="copy_entire_voice_script";
    public static final String RESULT_TAG="entire_voice_script_result";
    private static final int INK=0xff080d12,ACCENT=0xff70d7c4,MUTED=0xff9daebb,ERROR=0xffe4b771;

    public EntireScriptCopyUi(Activity activity,VoiceManager voices){
        super(activity);setOrientation(VERTICAL);
        Button copy=new Button(activity);
        copy.setTag(BUTTON_TAG);copy.setText("Copy entire voice script");copy.setAllCaps(false);
        copy.setTextSize(16);copy.setTypeface(Typeface.create("sans-serif-medium",Typeface.NORMAL));
        copy.setTextColor(INK);copy.setMinHeight(dp(56));copy.setPadding(dp(12),dp(10),dp(12),dp(10));
        GradientDrawable fill=new GradientDrawable();fill.setColor(ACCENT);fill.setCornerRadius(dp(16));
        copy.setBackground(fill);addView(copy,new LayoutParams(-1,-2));
        TextView result=new TextView(activity);result.setTag(RESULT_TAG);
        result.setTextSize(12);result.setTextColor(MUTED);result.setPadding(dp(2),dp(8),dp(2),dp(10));
        result.setText(voices.activeName()+" · original phrases + all imported additions");
        result.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);
        addView(result,new LayoutParams(-1,-2));

        copy.setOnClickListener(v->{
            copy.setEnabled(false);result.setTextColor(MUTED);result.setText("Preparing the entire script…");
            voices.exportEntireScript((export,error)->{
                if(activity.isFinishing()||activity.isDestroyed())return;
                copy.setEnabled(true);
                if(export==null){
                    result.setTextColor(ERROR);result.setText("Not copied: "+error);return;
                }
                try{
                    ClipboardManager clipboard=(ClipboardManager)activity.getSystemService(Context.CLIPBOARD_SERVICE);
                    if(clipboard==null)throw new IllegalStateException("Clipboard is unavailable.");
                    ClipData data=ClipData.newPlainText(export.source+" entire voice script",export.text);
                    // Do not expose patrol vocabulary in the system clipboard preview.
                    PersistableBundle extras=new PersistableBundle();
                    extras.putBoolean("android.content.extra.IS_SENSITIVE",true);
                    data.getDescription().setExtras(extras);
                    clipboard.setPrimaryClip(data);
                    result.setTextColor(ACCENT);
                    result.setText("Copied "+export.phrases+" phrases from "+export.source+
                            ". Entire script, including [pause 3] separators. Ready to paste.");
                }catch(RuntimeException e){
                    result.setTextColor(ERROR);
                    result.setText("Not copied: "+(e.getMessage()==null?"The clipboard could not accept the script.":e.getMessage()));
                }
            });
        });
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
}
