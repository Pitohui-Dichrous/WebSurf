package io.websurf.app;

import static org.junit.Assert.*;

import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import org.junit.Test;
import org.junit.runner.RunWith;

/** The resource categories and first result remain separated in both application themes. */
@RunWith(AndroidJUnit4.class)
public final class ResourcePanelLayoutTest extends RecoveryFixture {
  @Test
  public void filtersDoNotOverlapResultsAndObsoleteFooterIsAbsent() throws Exception {
    boolean originalDark = Ui.isDark(context);
    try {
      for (boolean dark : new boolean[] {false, true}) {
        ui(
            a -> {
              if (a.activeSheet != null) a.activeSheet.dismiss();
              Ui.setDark(a, dark);
              Ui.configure(a);
              a.applyChromeTheme();
              a.showResources("视频");
              return null;
            });
        await(
            "Resource sheet did not finish layout",
            7000,
            () ->
                ui(
                    a -> {
                      if (a.activeSheet == null || !a.activeSheet.isShowing()) return false;
                      View root = a.activeSheet.getWindow().getDecorView();
                      View filters = root.findViewWithTag("resource-filters");
                      ViewGroup body = root.findViewWithTag("resource-list");
                      return filters != null
                          && filters.isLaidOut()
                          && !filters.isLayoutRequested()
                          && body != null
                          && body.getChildCount() > 0
                          && body.getChildAt(0).isLaidOut()
                          && !body.getChildAt(0).isLayoutRequested();
                    }));
        ui(
            a -> {
              View root = a.activeSheet.getWindow().getDecorView();
              View filters = root.findViewWithTag("resource-filters");
              ViewGroup body = root.findViewWithTag("resource-list");
              View first = body.getChildAt(0);
              assertTrue(
                  "Expected a real resource result, not only an empty-state label",
                  first instanceof ResourceRow);
              int[] filtersAt = new int[2], rowAt = new int[2];
              filters.getLocationOnScreen(filtersAt);
              first.getLocationOnScreen(rowAt);
              int gap = rowAt[1] - filtersAt[1] - filters.getHeight();
              assertTrue(
                  "Categories overlap or crowd the first resource: gap=" + gap,
                  gap >= Ui.dp(a, 12) - 1);
              assertFalse("Removed helper copy is still present", obsoleteFooter(root));
              return null;
            });
      }
    } finally {
      Ui.setDark(context, originalDark);
      Ui.configure(context);
    }
  }

  private static boolean obsoleteFooter(View view) {
    if (view instanceof TextView text) {
      String value = text.getText().toString().replace(" ", "");
      if (value.contains("切换画质后会继续嗅探") || value.contains("最多保留400个地址")) return true;
    }
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (obsoleteFooter(group.getChildAt(i))) return true;
    return false;
  }
}
