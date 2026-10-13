package au.com.roningroup.patrollink;
import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.text.InputType;
import android.widget.*;
import android.view.*;
/** Guided one-time pairing. No SilverTrack credentials entered on patrol phones. */
final class MasterFeedUi {
 static void show(Activity a,PatrolEngine engine){
    boolean master=MasterFeedClient.master(a);
    boolean subscriber=MasterFeedClient.subscriber(a);
    if(!FleetRelay.configured(a)){
      new AlertDialog.Builder(a).setTitle("Set up device role first").setMessage("Choose Master / Supervisor or Patrol 1, 2 or 3 under Patrol duty / device role, then open Master feed pairing again.").setPositiveButton("OK",null).show();return;
    }
    MasterFeedSecrets secrets=new MasterFeedSecrets(a);
    LinearLayout body=new LinearLayout(a);body.setOrientation(LinearLayout.VERTICAL);
    int p=Math.round(16*a.getResources().getDisplayMetrics().density);body.setPadding(p,p,p,p);
    TextView heading=new TextView(a);heading.setText(master?"MASTER · one SilverTrack sign-in":FleetRelay.master(a)?"SUPERVISOR · read-only existing Master feed":"PATROL · no SilverTrack sign-in");heading.setTextSize(16);body.addView(heading);
    TextView explanation=new TextView(a);
    explanation.setText(master?
      "Sign into SilverTrack once on this Master phone. Keep Patrol Link running. After the Issue Monitor is read, only its authorised activity rows are relayed to the patrol phones. No password or session cookie is shared.":
      "Enter the Patrol Reader Key supplied by the supervisor once. This phone will read the Master feed over HTTPS and speak the updates. It must not sign into SilverTrack.");
    explanation.setPadding(0,p/2,0,p);body.addView(explanation);
    EditText publish=null;
    if(master){
      publish=new EditText(a);publish.setHint(secrets.ready(true)?"Master Publisher Key saved · leave blank to retain":"Master Publisher Key");publish.setSingleLine(true);
      publish.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
      publish.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);body.addView(publish);
    }
    EditText read=new EditText(a);read.setSingleLine(true);
    read.setHint(secrets.ready(false)?"Patrol Reader Key saved · leave blank to retain":"Patrol Reader Key");
    read.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
    read.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO);body.addView(read);
    TextView note=new TextView(a);note.setText(master?
      "For patrol setup, securely transfer the Reader Key only—not the Publisher Key and never SilverTrack credentials. The Master phone needs internet and a valid SilverTrack session. A reader key is also saved on the Master so it can verify the relay.":
      "After saving, wait for the Master to publish a valid Issue Monitor snapshot. If the Master is offline, the display warns that the feed is stale; it does not present old data as live.");
    note.setTextSize(12);note.setPadding(0,p,0,p);body.addView(note);
    Button test=new Button(a);test.setText("CHECK MASTER FEED NOW");test.setAllCaps(false);body.addView(test);
    TextView status=new TextView(a);status.setText("Source: "+MasterFeedClient.BASE+"\nPublisher/reader credentials are separate and encrypted locally.");
    body.addView(status);
    ScrollView scroll=new ScrollView(a);scroll.addView(body);
    AlertDialog dlg=new AlertDialog.Builder(a).setTitle("TRL Master SilverTrack feed").setView(scroll).setNegativeButton("Close",null).setPositiveButton("Save pairing",null).create();
    final EditText pub=publish;
    dlg.setOnShowListener(v->{
      dlg.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(x->{
        try{
          if(pub!=null&&!pub.getText().toString().trim().isEmpty())secrets.save(true,pub.getText().toString().trim());
          if(!read.getText().toString().trim().isEmpty())secrets.save(false,read.getText().toString().trim());
          if(master&&!secrets.ready(true)){status.setText("Enter a valid Master Publisher Key.");return;}
          if(subscriber&&!secrets.ready(false)){status.setText("Enter a valid Patrol Reader Key.");return;}
          engine.changeFeedMode();
          status.setText(master?"Master publishing enabled. Open SilverTrack and sign in if required.":"Patrol paired. Reading the Master feed without SilverTrack login.");
          dlg.dismiss();
        }catch(Exception err){status.setText("Pairing could not be saved: "+err.getMessage());}
      });
      test.setOnClickListener(x->{
         if(!secrets.ready(false)){status.setText("Reader Key required to test the feed. Save your pairing first.");return;}
         status.setText("Checking relay…");
         MasterFeedClient.poll(a,(response,error)->{
           if(!dlg.isShowing())return;
           if(error!=null){status.setText(error);return;}
           if(response==null||!response.optBoolean("masterOnline"))status.setText("Relay reachable, but Master is offline or not publishing yet.");
           else status.setText("Master online. Latest snapshot has "+(response.optJSONArray("rows")==null?0:response.optJSONArray("rows").length())+" activity rows.");
         });
      });
    });dlg.show();
 }
 private MasterFeedUi(){}
}