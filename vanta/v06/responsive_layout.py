from pathlib import Path
root=Path(__file__).resolve().parents[1]/'personal'
main=root/'app/src/main/java/com/ronin/vanta/MainActivity.java'
s=main.read_text()
def replace(old,new):
 global s
 if s.count(old)!=1:raise SystemExit('Responsive review expected one exact match: '+old[:100])
 s=s.replace(old,new,1)
replace('    modelButton.setMaxLines(2);', '    modelButton.setMaxLines(2);\n    modelButton.setIncludeFontPadding(false);\n    modelButton.setPadding(dp(8), dp(6), dp(8), dp(6));\n    modelButton.setMinimumHeight(dp(60));')
replace('header.addView(modelButton, new LinearLayout.LayoutParams(0, dp(60), 1));','header.addView(modelButton, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1));')
replace('private LinearLayout composerHost, attachmentChips, emptyPanel, mediaPreview;', 'private LinearLayout composerHost, attachmentChips, mediaPreview;\n  private ScrollView emptyPanel;')
a=s.index('    emptyPanel = column();');b=s.index('    stage.addView(emptyPanel, new FrameLayout.LayoutParams(-1, -1));',a)
part=s[a:b].replace('emptyPanel', 'emptyContent')
s=s[:a]+'''    emptyPanel = new ScrollView(this);
    emptyPanel.setFillViewport(true);
    emptyPanel.setVerticalScrollBarEnabled(false);
    emptyPanel.setTag("conversation-empty-state");
    LinearLayout emptyContent;
'''+part+'''    emptyPanel.addView(emptyContent, new ScrollView.LayoutParams(-1, -2));
'''+s[b:]
main.write_text(s)
(root/'app/src/androidTest/java/com/ronin/vanta/ResponsiveLayoutTest.java').write_text('''package com.ronin.vanta;
import static org.junit.Assert.*;
import android.view.View;
import android.view.ViewGroup;
import android.widget.*;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;
@RunWith(AndroidJUnit4.class)
public class ResponsiveLayoutTest {
  @Test public void twoLineModelHeaderIsContentMeasuredAndNotClipped() throws Exception {
    try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
      scene.onActivity(activity->{
        activity.showMode("chat");
        Button model=activity.findViewById(android.R.id.content).findViewWithTag("model-selector");
        assertEquals(ViewGroup.LayoutParams.WRAP_CONTENT, model.getLayoutParams().height);
        model.setTextSize(23);
        model.setText("Vanta\\nAn unusually long model display name");
        model.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
        assertNotNull(model.getLayout());
        assertTrue("Both scaled lines fit inside measured content",model.getMeasuredHeight()-model.getCompoundPaddingTop()-model.getCompoundPaddingBottom()>=model.getLayout().getHeight());
      });
    }
  }
  @Test public void emptyStateCanScrollWhenLandscapeSpaceIsLimited()throws Exception {
    try(ActivityScenario<MainActivity> scene=ActivityScenario.launch(MainActivity.class)) {
      scene.onActivity(activity->{
        activity.showMode("chat");
        ScrollView state=activity.findViewById(android.R.id.content).findViewWithTag("conversation-empty-state");
        assertTrue(state.isFillViewport());
        state.setVisibility(View.VISIBLE);
        state.measure(View.MeasureSpec.makeMeasureSpec(600,View.MeasureSpec.EXACTLY),View.MeasureSpec.makeMeasureSpec(120,View.MeasureSpec.EXACTLY));
        state.layout(0,0,600,120);
        assertTrue("Content is not clipped to the viewport",state.getChildAt(0).getMeasuredHeight()>state.getHeight());
        state.setSmoothScrollingEnabled(false);
        state.fullScroll(View.FOCUS_DOWN);
        assertTrue("Lower empty-state actions remain reachable",state.getScrollY()>0);
      });
    }
  }
}
''')
print('Responsive review: content-measured model header and scrollable empty state, plus two device regressions.')
