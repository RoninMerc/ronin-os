package au.com.ronin.evie;

import android.accessibilityservice.AccessibilityService;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.*;

public class EvieAccessibilityService extends AccessibilityService {
    private static volatile EvieAccessibilityService self;
    private final Handler h=new Handler(Looper.getMainLooper());

    @Override public void onServiceConnected(){self=this;}
    @Override public void onAccessibilityEvent(AccessibilityEvent e){}
    @Override public void onInterrupt(){}
    @Override public void onDestroy(){if(self==this)self=null;super.onDestroy();}

    public static boolean isEnabled(Context c){
        String enabled=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        return enabled!=null && enabled.toLowerCase().contains(c.getPackageName().toLowerCase());
    }

    public static String visibleText(){
        EvieAccessibilityService s=self;if(s==null)return "";
        AccessibilityNodeInfo r=s.getRootInActiveWindow();if(r==null)return "";
        StringBuilder b=new StringBuilder();collect(r,b,0);
        String t=b.toString().trim();if(t.length()>12000)t=t.substring(t.length()-12000);return t;
    }

    private static void collect(AccessibilityNodeInfo n,StringBuilder b,int depth){
        if(n==null||depth>40)return;
        CharSequence t=n.getText(),d=n.getContentDescription();
        if(t!=null&&t.length()>0)b.append(t).append('\n');
        if(d!=null&&d.length()>0&& (t==null||!d.toString().equals(t.toString())))b.append(d).append('\n');
        for(int i=0;i<n.getChildCount();i++)collect(n.getChild(i),b,depth+1);
    }

    public static void sendToChatGPT(Context c,String conversation,String message){
        Intent launch=c.getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if(launch==null)return;launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(launch);
        EvieAccessibilityService s=self;if(s==null)return;
        s.h.postDelayed(()->s.prepareConversationAndSend(conversation,message,0),900);
    }

    public static void activateChatGptMic(Context c,String conversation){
        Intent launch=c.getPackageManager().getLaunchIntentForPackage("com.openai.chatgpt");
        if(launch==null)return;launch.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);c.startActivity(launch);
        EvieAccessibilityService s=self;if(s==null)return;
        s.h.postDelayed(()->{s.openConversation(conversation);s.h.postDelayed(()->s.clickAny(new String[]{"voice","microphone","mic","speak"}),700);},900);
    }

    private void prepareConversationAndSend(String conversation,String message,int attempt){
        if(attempt>8)return;
        AccessibilityNodeInfo root=getRootInActiveWindow();
        if(root==null){h.postDelayed(()->prepareConversationAndSend(conversation,message,attempt+1),500);return;}
        if(attempt==0)openConversation(conversation);
        AccessibilityNodeInfo edit=findEditable(root);
        if(edit!=null){
            Bundle args=new Bundle();args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,message);
            edit.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT,args);
            h.postDelayed(()->{if(!clickAny(new String[]{"send","send message","submit"})){prepareConversationAndSend(conversation,message,attempt+1);}},350);
            return;
        }
        h.postDelayed(()->prepareConversationAndSend(conversation,message,attempt+1),500);
    }

    private void openConversation(String title){
        if(title==null||title.trim().isEmpty())return;
        AccessibilityNodeInfo root=getRootInActiveWindow();if(root==null)return;
        AccessibilityNodeInfo direct=findByText(root,title.trim().toLowerCase());
        if(direct!=null){clickNode(direct);return;}
        clickAny(new String[]{"menu","open navigation drawer","sidebar","chats"});
        h.postDelayed(()->{AccessibilityNodeInfo r=getRootInActiveWindow();if(r==null)return;AccessibilityNodeInfo n=findByText(r,title.trim().toLowerCase());if(n!=null)clickNode(n);},500);
    }

    private boolean clickAny(String[] terms){
        AccessibilityNodeInfo r=getRootInActiveWindow();if(r==null)return false;
        AccessibilityNodeInfo n=findClickableByTerms(r,terms);if(n==null)return false;return clickNode(n);
    }

    private AccessibilityNodeInfo findEditable(AccessibilityNodeInfo n){
        if(n==null)return null;
        if(n.isEditable() || "android.widget.EditText".contentEquals(n.getClassName()))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo x=findEditable(n.getChild(i));if(x!=null)return x;}return null;
    }

    private AccessibilityNodeInfo findByText(AccessibilityNodeInfo n,String q){
        if(n==null)return null;
        String a=n.getText()==null?"":n.getText().toString().toLowerCase();
        String d=n.getContentDescription()==null?"":n.getContentDescription().toString().toLowerCase();
        if(a.contains(q)||d.contains(q))return n;
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo x=findByText(n.getChild(i),q);if(x!=null)return x;}return null;
    }

    private AccessibilityNodeInfo findClickableByTerms(AccessibilityNodeInfo n,String[] terms){
        if(n==null)return null;
        String a=(n.getText()==null?"":n.getText().toString()).toLowerCase();
        String d=(n.getContentDescription()==null?"":n.getContentDescription().toString()).toLowerCase();
        for(String t:terms)if(a.equals(t)||d.equals(t)||a.contains(t)||d.contains(t)){if(n.isClickable())return n;AccessibilityNodeInfo p=n.getParent();if(p!=null&&p.isClickable())return p;}
        for(int i=0;i<n.getChildCount();i++){AccessibilityNodeInfo x=findClickableByTerms(n.getChild(i),terms);if(x!=null)return x;}return null;
    }

    private boolean clickNode(AccessibilityNodeInfo n){
        if(n==null)return false;if(n.isClickable())return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        AccessibilityNodeInfo p=n.getParent();while(p!=null){if(p.isClickable())return p.performAction(AccessibilityNodeInfo.ACTION_CLICK);p=p.getParent();}return false;
    }
}
