package au.com.roningroup.patrollink;

import android.app.*;
import android.content.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Role, duty and supervisor fleet UI with PIN-locked supervisor elevation. */
public final class FleetUi {
    private final Activity a;
    private final Runnable changed;
    private boolean supervisorUnlocked;

    public FleetUi(Activity a,Runnable changed){this.a=a;this.changed=changed;}
    public boolean configured(){return FleetRelay.configured(a);}
    public boolean master(){return FleetRelay.master(a);}

    public String summary(){
        if(!configured())return "Choose this phone's Patrol Link role";
        if(master())return "Master / Supervisor · SilverTrack collector · no patrol assignment";
        return "Patrol "+FleetRelay.patrol(a)+(FleetRelay.onDuty(a)?" · ON DUTY":" · Off duty")+" · Master feed";
    }

    public void maybeSetup(){
        if(!configured())new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(this::setup,450);
    }

    public void open(){
        if(!configured())setup();
        else if(master())fleet();
        else patrolControls();
    }

    public void setup(){
        if(configured()&&!master()&&!supervisorUnlocked){unlockSupervisor();return;}
        setupUnlocked();
    }

    private void unlockSupervisor(){
        long locked=SupervisorPin.lockedSeconds(a);
        if(locked>0){toast("Supervisor unlock locked for "+locked+" seconds.");return;}
        if(!SupervisorPin.configured(a)){
            toast("No supervisor PIN exists on this device.");
            return;
        }
        EditText pin=new EditText(a);
        pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
        pin.setHint("Supervisor PIN");
        LinearLayout box=new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40,12,40,4);
        box.addView(pin);

        AlertDialog d=new AlertDialog.Builder(a)
            .setTitle("Supervisor unlock")
            .setView(box)
            .setPositiveButton("Unlock",null)
            .setNegativeButton("Cancel",null)
            .create();

        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            long wait=SupervisorPin.lockedSeconds(a);
            if(wait>0){toast("Locked for "+wait+" seconds.");d.dismiss();return;}
            if(!SupervisorPin.verify(a,pin.getText().toString())){
                toast("Incorrect supervisor PIN.");
                pin.setText("");
                return;
            }
            supervisorUnlocked=true;
            d.dismiss();
            setupUnlocked();
        }));
        d.show();
    }

    private void setupUnlocked(){
        LinearLayout box=new LinearLayout(a);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(40,18,40,4);

        Spinner mode=new Spinner(a);
        mode.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Master / Supervisor","User / Patrol phone"}));
        mode.setSelection(FleetRelay.master(a)?0:1);

        LinearLayout patrolBlock=new LinearLayout(a);
        patrolBlock.setOrientation(LinearLayout.VERTICAL);
        TextView patrolLabel=label("PATROL NUMBER");
        Spinner patrol=new Spinner(a);
        patrol.setAdapter(new ArrayAdapter<String>(a,android.R.layout.simple_spinner_dropdown_item,
            new String[]{"Patrol 1","Patrol 2","Patrol 3"}));
        patrol.setSelection(Math.max(0,FleetRelay.patrol(a)-1));
        patrolBlock.addView(patrolLabel);
        patrolBlock.addView(patrol);

        box.addView(label("DEVICE ROLE"));
        box.addView(mode);
        box.addView(patrolBlock);

        EditText newPin=null;
        if(!SupervisorPin.configured(a)){
            box.addView(label("CREATE SUPERVISOR PIN · 4–8 DIGITS"));
            newPin=new EditText(a);
            newPin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
            newPin.setHint("Required before saving");
            box.addView(newPin);
        }

        TextView note=label(
            "Master / Supervisor is not Patrol 1, 2 or 3. It only maintains the SilverTrack connection, checkpoint configuration and supervisor fleet view. " +
            "User devices are assigned Patrol 1, 2 or 3 and receive the Master SilverTrack feed without their own SilverTrack login."
        );
        box.addView(note);

        android.widget.AdapterView.OnItemSelectedListener roleListener=new android.widget.AdapterView.OnItemSelectedListener(){
            public void onNothingSelected(android.widget.AdapterView<?> parent){}
            public void onItemSelected(android.widget.AdapterView<?> parent,View view,int position,long id){
                patrolBlock.setVisibility(position==0?View.GONE:View.VISIBLE);
            }
        };
        mode.setOnItemSelectedListener(roleListener);
        patrolBlock.setVisibility(mode.getSelectedItemPosition()==0?View.GONE:View.VISIBLE);

        final EditText pinField=newPin;
        AlertDialog d=new AlertDialog.Builder(a)
            .setTitle("Patrol device setup")
            .setView(box)
            .setPositiveButton("Save",null)
            .setNegativeButton("Cancel",null)
            .create();

        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(pinField!=null&&!SupervisorPin.set(a,pinField.getText().toString())){
                toast("Create a 4–8 digit supervisor PIN.");
                return;
            }
            boolean isMaster=mode.getSelectedItemPosition()==0;
            int patrolNumber=isMaster?1:patrol.getSelectedItemPosition()+1; // ignored for Master
            FleetRelay.configure(a,isMaster,patrolNumber);
            supervisorUnlocked=isMaster;
            restartEngine();
            d.dismiss();
            changed.run();
            if(!isMaster)patrolControls();
        }));
        d.show();
    }

    private void restartEngine(){
        try{
            PatrolEngine e=((PatrolApp)a.getApplication()).engine();
            e.changeFeedMode();
        }catch(Exception ignored){}
    }

    private TextView label(String s){
        TextView t=new TextView(a);
        t.setText(s);
        t.setPadding(0,12,0,12);
        return t;
    }

    private void toast(String s){Toast.makeText(a,s,Toast.LENGTH_LONG).show();}

    public void patrolControls(){
        boolean duty=FleetRelay.onDuty(a);
        String msg="This device: Patrol "+FleetRelay.patrol(a)+"\n\n"+
            (duty?"This patrol is ON DUTY and visible to the Master device.":"Not transmitting as an active patrol.")+
            "\n\nSilverTrack activity and checkpoint configuration are received from the Master. This patrol phone does not need to sign into SilverTrack.";

        new AlertDialog.Builder(a)
            .setTitle("Patrol GPS + Master feed")
            .setMessage(msg)
            .setPositiveButton(duty?"Go off duty":"Go on duty",(d,w)->{
                if(!duty){
                    if(!LocalGpsService.hasPermission(a)){changed.run();return;}
                    LocalGpsService.start(a);
                }
                FleetRelay.setDuty(a,!duty);
                if(!duty)FleetRelay.publishLatest(a);
                changed.run();
            })
            .setNeutralButton("Supervisor unlock",(d,w)->unlockSupervisor())
            .setNegativeButton("Close",null)
            .show();
    }

    public void fleet(){loadFleet(null);}

    private void loadFleet(AlertDialog prior){
        if(prior!=null)prior.dismiss();
        final ProgressDialog p=ProgressDialog.show(a,"Supervisor Fleet GPS","Connecting to Patrol 1 / 2 / 3…",true,false);
        FleetRelay.load(a,(fleet,error)->{
            p.dismiss();
            StringBuilder s=new StringBuilder();
            if(error!=null)s.append(error).append("\n\n");
            s.append("Master / Supervisor · no patrol assignment\n");
            s.append("SilverTrack source: Master-only collector\n\n");
            for(int n=1;n<=3;n++){
                FleetRelay.Patrol x=fleet.get(n);
                s.append("PATROL ").append(n).append("\n");
                if(x==null)s.append("Waiting for relay\n\n");
                else{
                    s.append(x.onDuty?x.state:"Off duty").append(" · ");
                    if(x.time>0)s.append(age(x.age())).append(" ago");else s.append("time unknown");
                    s.append("\n");
                    if(x.lat!=0||x.lon!=0)s.append(String.format(java.util.Locale.US,"%.6f, %.6f · ±%.0fm",x.lat,x.lon,x.accuracy));
                    s.append("\n");
                    if(x.segments!=null&&x.segments.length()>0)s.append(x.segments.length()).append(" movement/history segments\n");
                    s.append("\n");
                }
            }
            AlertDialog d=new AlertDialog.Builder(a)
                .setTitle("Live fleet")
                .setMessage(s.toString())
                .setPositiveButton("Refresh",null)
                .setNeutralButton("Device setup",(q,w)->setup())
                .setNegativeButton("Close",null)
                .create();
            d.setOnShowListener(q->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->loadFleet(d)));
            d.show();
        });
    }

    private String age(long ms){
        long sec=ms/1000;if(sec<60)return sec+"s";
        long m=sec/60;if(m<60)return m+"m";
        return (m/60)+"h "+(m%60)+"m";
    }
}