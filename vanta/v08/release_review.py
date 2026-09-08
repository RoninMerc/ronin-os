from pathlib import Path
import os
root=Path(os.environ.get('VANTA_PROJECT','vanta/personal'))
j=root/'app/src/main/java/com/ronin/vanta'
def edit(path,old,new):
 s=path.read_text();assert s.count(old)==1,(path.name,s.count(old),old[:90]);path.write_text(s.replace(old,new))
# Policy opt-in must not contaminate the all-catalogue discovery entry point.
edit(j/'ModelPicker.java','    uncensored.setChecked(prefs.getBoolean("only_unc_" + mode, false));','    uncensored.setChecked(!category.equals("all") && prefs.getBoolean("only_unc_" + mode, false));')
edit(j/'ModelPicker.java','          prefs.edit().putBoolean("only_unc_" + mode, on).apply();','          if (!category.equals("all")) prefs.edit().putBoolean("only_unc_" + mode, on).apply();')
# Credential presence is cheap metadata: do not decrypt every key whenever the picker opens.
edit(j/'MainActivity.java','  private Set<String> connectedProviders() {\n    return new HashSet<>(connectedCache);\n  }','''  private Set<String> connectedProviders() {
    Set<String> available=new HashSet<>();
    for(ProviderConfig p:providers)if(vault.containsSecret(p.id))available.add(p.id);
    return available;
  }''')
t=root/'app/src/androidTest/java/com/ronin/vanta/ExperienceDeviceTest.java'
edit(t,'count.set(list.getAdapter().getCount());','count.set(list.getAdapter().getCount() - list.getHeaderViewsCount() - list.getFooterViewsCount());')
# Latest contract makes low-refusal explicit, independent of Auto coding quality.
t=root/'app/src/test/java/com/ronin/vanta/MasterRebuildTest.java'
edit(t,'''        PromptStrategy.recommendForge(
            Arrays.asList(
                new VantaRouter.Candidate(provider("openai"), standard, 0),
                new VantaRouter.Candidate(provider("featherless"), shadow, 0)),
            "Android app that compiles and passes tests");''','''        ForgeRouting.select(
            Arrays.asList(
                new VantaRouter.Candidate(provider("openai"), standard, 0),
                new VantaRouter.Candidate(provider("featherless"), shadow, 0)),
            "Android app that compiles and passes tests", "Low-refusal", "");''')
for p in (root/'app/src').rglob('*.java'):
 raw=p.read_bytes()
 if b'\0' in raw:p.write_bytes(raw.replace(b'\0',b'\\0'))
# Score once per candidate; preserve ordering without O(n log n) repeated parsing.
p=j/'MainActivity.java';s=p.read_text();a=s.index('    out.sort(',s.index('private List<ModelInfo> eligible('));b=s.index('    return out;',a)
s=s[:a]+'''    Map<ModelInfo,Integer> scores=new IdentityHashMap<>();
    String cat=category();
    for(ModelInfo candidate:out)scores.put(candidate,prefs.getInt("priority_"+cat+"_"+p.id+"_"+candidate.id,ModelRanker.score(candidate,cat)));
    out.sort((a,b)->{int rank=Integer.compare(scores.get(b),scores.get(a));return rank==0?a.name.compareToIgnoreCase(b.name):rank;});
''' + s[b:];p.write_text(s)
edit(p,'    String mid = prefs.getString("model_" + mode, "");\n    List<ModelInfo> e = eligible(provider);','''    String mid = prefs.getString("model_" + mode, "");
    if(provider!=null&&!mid.isEmpty()) {
      boolean unc=prefs.getBoolean("only_unc_"+mode,false);
      String cat=category();
      for(ModelInfo saved:models.getOrDefault(provider.id,Collections.emptyList()))
        if(saved.id.equals(mid)&&saved.enabled&&ModelPolicy.supports(provider,saved,cat)&&(!unc||saved.isUncensored())){model=saved;return;}
    }
    List<ModelInfo> e = eligible(provider);''')
edit(j/'ForgeRouting.java','''    return (a, b) -> {
      int n = Integer.compare(score(b, request), score(a, request));
      if (n == 0) n = Long.compare(released(b.model), released(a.model));''','''    Map<VantaRouter.Candidate,Integer> scores=new IdentityHashMap<>();
    Map<ModelInfo,Long> dates=new IdentityHashMap<>();
    return (a, b) -> {
      int n = Integer.compare(scores.computeIfAbsent(b,c->score(c,request)),scores.computeIfAbsent(a,c->score(c,request)));
      if (n == 0) n = Long.compare(dates.computeIfAbsent(b.model,ForgeRouting::released),dates.computeIfAbsent(a.model,ForgeRouting::released));''')
# Superseded queries stop computing instead of delaying the latest search.
edit(j/'ModelPicker.java','  private int filterVersion = 0;','  private int filterVersion = 0;\n  private java.util.concurrent.Future<?> filterTask;')
edit(j/'ModelPicker.java','''    if (closed) return;
    filterExecutor.submit(
        () -> {
          List<VantaRouter.Candidate> result''','''    if (closed) return;
    if(filterTask!=null)filterTask.cancel(true);
    filterTask=filterExecutor.submit(
        () -> {
          List<VantaRouter.Candidate> result''')
# This test asserted removal of the old platform spinner; the new platform is two buttons.
t=root/'app/src/androidTest/java/com/ronin/vanta/DeviceTest.java'
edit(t,'            assertNull(root.findViewWithTag("forge-platform"));','''            assertNotNull(root.findViewWithTag("forge-platform"));
            assertFalse(root.findViewWithTag("forge-platform") instanceof android.widget.Spinner);
            assertNotNull(root.findViewWithTag("forge-platform-android"));
            assertNotNull(root.findViewWithTag("forge-platform-windows"));''')
edit(t,'            assertNotNull(root.findViewWithTag("forge-auto-coder"));','''            assertNotNull(root.findViewWithTag("forge-routing"));
            assertNotNull(root.findViewWithTag("forge-build"));''')
# Offscreen accessibility nodes can be cached on Android 10. Measure actual native coordinates.
# All motion is still driven by real touch events, never programmatic list scrolling.
t=root/'app/src/androidTest/java/com/ronin/vanta/MasterDeviceTest.java'
s=t.read_text();a=s.index('      android.app.UiAutomation ui =',s.index('public void largeModelPickerAcceptsRealVerticalSwipe'));b=s.index('\n    }\n  }\n}',a)
s=s[:a]+'''      AtomicReference<ListView> list=new AtomicReference<>();
      long ready=SystemClock.elapsedRealtime()+15000;
      AtomicInteger count=new AtomicInteger();
      while(SystemClock.elapsedRealtime()<ready){
        scenario.onActivity(host->{try{
          java.lang.reflect.Field field=MainActivity.class.getDeclaredField("currentSheet");field.setAccessible(true);
          Dialog dialog=(Dialog)field.get(host);
          if(dialog!=null){ListView view=dialog.findViewById(android.R.id.content).findViewWithTag("model-list");list.set(view);if(view!=null)count.set(view.getCount()-view.getHeaderViewsCount()-view.getFooterViewsCount());}
        }catch(Exception e){throw new AssertionError(e);}});
        if(count.get()>=120&&list.get()!=null)break;
        Thread.sleep(80);
      }
      assertNotNull("The real catalogue list must be rendered",list.get());
      assertTrue("Wait for model preparation before touching the list",count.get()>=120);
      Thread.sleep(300);
      int[] before=scrollPosition(scenario,list.get());
      shot("081-models-before-swipe");
      pointerSwipe(scenario,list.get());
      int[] first=scrollPosition(scenario,list.get());
      assertTrue("A physical swipe must advance native scroll coordinates; before="+Arrays.toString(before)+" after="+Arrays.toString(first),
          first[0]>before[0]||(first[0]==before[0]&&first[1]<before[1]-12));
      pointerSwipe(scenario,list.get());
      int[] second=scrollPosition(scenario,list.get());
      assertTrue("Two physical swipes must reach model rows beyond the first",second[0]>1);
      assertTrue("Catalogue rows remain recycled",second[2]<40);
      try(PrintWriter out=new PrintWriter(new File(ctx().getExternalFilesDir(null),"model-scroll-coordinates.txt"))){
        out.println("Actual pointer input; [firstVisiblePosition, firstChildTop, attachedChildren]");
        out.println("before="+Arrays.toString(before));out.println("first="+Arrays.toString(first));out.println("second="+Arrays.toString(second));
      }
      shot("081-models-after-swipe");''' + s[b:]
s=s.rstrip();assert s.endswith('}');s=s[:-1]+'''
  static int[] scrollPosition(ActivityScenario<MainActivity> scenario,ListView list){
    int[] result=new int[3];
    scenario.onActivity(host->{result[0]=list.getFirstVisiblePosition();result[1]=list.getChildCount()==0?0:list.getChildAt(0).getTop();result[2]=list.getChildCount();});return result;
  }
  static void pointerSwipe(ActivityScenario<MainActivity> scenario,ListView list)throws Exception{
    android.graphics.Rect bounds=new android.graphics.Rect();
    scenario.onActivity(host->{assertTrue(list.getGlobalVisibleRect(bounds));});
    assertTrue("Visible catalogue must have a touchable viewport",bounds.height()>100);
    int x=bounds.centerX(),from=bounds.bottom-bounds.height()/6,to=bounds.top+bounds.height()/6;
    android.app.UiAutomation ui=InstrumentationRegistry.getInstrumentation().getUiAutomation();
    long down=SystemClock.uptimeMillis();
    for(int step=0;step<=13;step++){
      int action=step==0?MotionEvent.ACTION_DOWN:step==13?MotionEvent.ACTION_UP:MotionEvent.ACTION_MOVE;
      float y=step==13?to:from+(to-from)*step/12f;
      MotionEvent event=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);
      try{event.setSource(android.view.InputDevice.SOURCE_TOUCHSCREEN);assertTrue("Android accepted real touch event",ui.injectInputEvent(event,true));}finally{event.recycle();}
      Thread.sleep(24);
    }
    Thread.sleep(500);
  }
}
''';t.write_text(s)
print('Reviewed routing, complete catalogue visibility, credential refresh, bounded sorting/search and real touch-scroll measurement.')
