package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.text.Layout;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.widget.Button;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import io.websurf.app.download.DownloadStore;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Run unchanged at normal size or 320dp/font_scale=1.3; only synthetic records are created. */
@RunWith(AndroidJUnit4.class)
public final class ProductLayoutTest {
  private static final String BASE = "http://127.0.0.1:8765";
  private static final String LONG_TITLE = "这是一段用于核对窄屏与大字体的完整中文视频标题，包含清晰度、来源以及不能挤压操作按钮的详细内容";
  private static final String LONG_ERROR =
      "下载失败：服务器未能返回完整视频数据，请返回原网页确认播放状态后重试。"
          + "连接响应包含详细原因，长文本必须完整保留并能滚动查看，不能覆盖下方的重试与移除记录按钮。"
          + "请求地址：http://127.0.0.1:8765/诊断记录/这是一段不含空格的较长中文路径。"
          + "完整错误末尾：响应范围与请求范围不一致。";
  private Context context;
  private ActivityScenario<MainActivity> scenario;
  private SharedPreferences browser, appearance;
  private boolean hadAuto, oldAuto, hadDark, oldDark;
  private final List<String> ownIds = new ArrayList<>();

  @Before
  public void openSyntheticPage() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    browser = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    hadAuto = browser.contains("auto_play");
    oldAuto = browser.getBoolean("auto_play", true);
    hadDark = appearance.contains("dark");
    oldDark = appearance.getBoolean("dark", false);
    assertTrue(browser.edit().putBoolean("auto_play", false).commit());
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(BASE + "/layout.html?product-layout=" + UUID.randomUUID())));
    await(
        "Synthetic layout page did not settle",
        () ->
            ui(
                a ->
                    a.resumed
                        && a.current.web.getProgress() == 100
                        && a.current.web.getUrl() != null
                        && a.current.web.getUrl().startsWith(BASE + "/layout.html")));
  }

  @After
  public void removeOnlyOwnedRecordsAndRestorePreferences() {
    if (scenario != null) scenario.close();
    if (context != null) for (String id : ownIds) DownloadStore.remove(context, id);
    if (browser != null) {
      SharedPreferences.Editor edit = browser.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      assertTrue(edit.commit());
    }
    if (appearance != null) {
      SharedPreferences.Editor edit = appearance.edit();
      if (hadDark) edit.putBoolean("dark", oldDark);
      else edit.remove("dark");
      assertTrue(edit.commit());
      Ui.configure(context);
    }
  }

  @Test
  public void longChineseResourcesKeepFiltersAndRowsSeparatedInBothThemes() throws Exception {
    ui(
        a -> {
          a.current.resources.clear();
          for (int i = 0; i < 6; i++) {
            WebResource resource =
                new WebResource(BASE + "/public/sample-360.mp4?layout-resource=" + i);
            resource.title = LONG_TITLE + "（资源 " + (i + 1) + "）";
            resource.mime = "video/mp4";
            resource.classify();
            resource.width = 1920;
            resource.height = 1080;
            resource.durationMs = 125000;
            resource.availableQualities = "360p、480p、720p、1080p、1440p、2160p";
            Bitmap image = Bitmap.createBitmap(160, 90, Bitmap.Config.ARGB_8888);
            image.eraseColor(i % 2 == 0 ? Color.rgb(39, 85, 61) : Color.rgb(69, 91, 112));
            a.previewLoader.seed(resource, a.headersFor(resource), image, 1920, 1080);
            image.recycle();
            a.current.resources.put(resource.url, resource);
          }
          return null;
        });
    for (boolean dark : new boolean[] {false, true}) {
      showTheme(dark, true);
      try {
        ui(
            a -> {
              View root = a.activeSheet.getWindow().getDecorView();
              HorizontalScrollView filters = root.findViewWithTag("resource-filters");
              ViewGroup body = root.findViewWithTag("resource-list");
              assertNotNull(filters);
              assertTrue(body.getChildCount() >= 6);
              assertTrue(body.getChildAt(0) instanceof ResourceRow);
              Rect filterBounds = bounds(filters), first = bounds(body.getChildAt(0));
              assertTrue(
                  "Categories crowd the first result: " + filterBounds + " / " + first,
                  first.top - filterBounds.bottom >= Ui.dp(a, 12) - 1);
              assertButtonsSeparated((ViewGroup) filters.getChildAt(0), Ui.dp(a, 4));
              assertRows(body, ResourceRow.class, Ui.dp(a, 8));
              for (int i = 0; i < body.getChildCount(); i++) {
                View row = body.getChildAt(i);
                if (!(row instanceof ResourceRow)) continue;
                assertChildrenFit(row);
                assertTrue(row.getContentDescription().toString().contains(LONG_TITLE));
              }
              assertTrue(
                  "Long resource list must be scrollable",
                  parentScroll(body).canScrollVertically(1));
              return null;
            });
      } finally {
        UiCaptureTest.shot(context, "20-compact-resources-" + (dark ? "dark" : "light"));
      }
    }
  }

  @Test
  public void longDownloadTitlesAndFullErrorsKeepActionsReachableInBothThemes() throws Exception {
    Method put =
        DownloadStore.class.getDeclaredMethod("put", Context.class, DownloadStore.Job.class);
    put.setAccessible(true);
    List<DownloadStore.Job> jobs = new ArrayList<>();
    for (String state : new String[] {"completed", "cancelled", "failed"}) {
      DownloadStore.Job job = new DownloadStore.Job();
      job.id = "product-layout-" + UUID.randomUUID();
      ownIds.add(job.id);
      job.title = LONG_TITLE + "（" + state + "）";
      job.url = BASE + "/public/sample-360.mp4?layout-download=" + job.id;
      job.mimeType = "video/mp4";
      job.state = state;
      job.progress = "completed".equals(state) ? 100 : 37;
      job.bytes = 123456789;
      job.total = 345678901;
      job.completedParts = 37;
      job.totalParts = 100;
      job.error = "failed".equals(state) ? LONG_ERROR : "";
      job.createdAt = System.currentTimeMillis();
      put.invoke(null, context, job);
      jobs.add(job);
    }
    for (boolean dark : new boolean[] {false, true}) {
      showTheme(dark, false);
      try {
        ui(
            a -> {
              View root = a.activeSheet.getWindow().getDecorView();
              DownloadTaskRow row = findTask(root, jobs.get(2).title);
              assertNotNull("Owned failed record missing", row);
              assertRows((ViewGroup) row.getParent(), DownloadTaskRow.class, Ui.dp(a, 8));
              for (DownloadStore.Job job : jobs) {
                DownloadTaskRow task = findTask(root, job.title);
                assertNotNull(task);
                assertChildrenFit(task);
                TextView title = findText(task, job.title);
                assertEquals(job.title, title.getContentDescription().toString());
                assertEquals(job.title, title.getTooltipText().toString());
                ViewGroup actions = actionGroup(task);
                assertEquals(2, actions.getChildCount());
                assertButtonsSeparated(actions, Ui.dp(a, 8));
              }
              TextView error = findText(row, LONG_ERROR);
              assertNotNull(error);
              assertTrue("Full error must remain selectable", error.isTextSelectable());
              assertNull("Error must not be ellipsized", error.getEllipsize());
              Layout layout = error.getLayout();
              assertNotNull(layout);
              assertEquals(LONG_ERROR.length(), layout.getLineEnd(layout.getLineCount() - 1));
              assertTrue(
                  "Error text is vertically clipped",
                  layout.getHeight()
                      <= error.getHeight()
                          - error.getTotalPaddingTop()
                          - error.getTotalPaddingBottom());
              assertTrue(
                  "Long records must be scrollable", parentScroll(row).canScrollVertically(1));
              return null;
            });
      } finally {
        UiCaptureTest.shot(context, "21-compact-downloads-" + (dark ? "dark" : "light"));
      }
      for (DownloadStore.Job job : jobs) {
        String requestReport =
            ui(
                a -> {
                  DownloadTaskRow row =
                      findTask(a.activeSheet.getWindow().getDecorView(), job.title);
                  ViewGroup actions = actionGroup(row);
                  String before = actionDiagnostics(a, job.title);
                  boolean requested =
                      actions.requestRectangleOnScreen(
                          new Rect(0, 0, actions.getWidth(), actions.getHeight()), true);
                  return "before="
                      + before
                      + "\nrequestChanged="
                      + requested
                      + " after="
                      + actionDiagnostics(a, job.title);
                });
        System.out.println(
            "PRODUCT_LAYOUT_ACTION " + job.state + " dark=" + dark + " " + requestReport);
        StringBuilder trace = new StringBuilder(requestReport);
        String[] previous = {""};
        long started = SystemClock.elapsedRealtime();
        try {
          await(
              "Actions cannot be brought into the scroll viewport for " + job.state,
              () ->
                  ui(
                      a -> {
                        String sample = actionDiagnostics(a, job.title);
                        if (!sample.equals(previous[0]) && trace.length() < 16000) {
                          trace
                              .append("\n+")
                              .append(SystemClock.elapsedRealtime() - started)
                              .append("ms ")
                              .append(sample);
                          previous[0] = sample;
                        }
                        DownloadTaskRow row =
                            findTask(a.activeSheet.getWindow().getDecorView(), job.title);
                        ViewGroup actions = actionGroup(row);
                        Rect viewport = new Rect();
                        if (!parentScroll(row).getGlobalVisibleRect(viewport)) return false;
                        int[] windowOrigin = new int[2];
                        a.activeSheet.getWindow().getDecorView().getLocationOnScreen(windowOrigin);
                        viewport.offset(windowOrigin[0], windowOrigin[1]);
                        for (int i = 0; i < actions.getChildCount(); i++)
                          if (!viewport.contains(bounds(actions.getChildAt(i)))) return false;
                        return true;
                      }));
        } catch (AssertionError failure) {
          UiCaptureTest.shot(context, "22-action-unreachable-" + (dark ? "dark" : "light"));
          throw new AssertionError(failure.getMessage() + "\n" + trace, failure);
        }
      }
    }
  }

  private void showTheme(boolean dark, boolean resources) throws Exception {
    ui(
        a -> {
          if (a.activeSheet != null) a.activeSheet.dismiss();
          Ui.setDark(a, dark);
          a.applyChromeTheme();
          if (resources) a.showResources("视频");
          else a.showDownloads();
          return null;
        });
    await(
        "Sheet did not finish layout",
        () ->
            ui(
                a ->
                    a.activeSheet != null
                        && a.activeSheet.isShowing()
                        && a.activeSheet.getWindow().getDecorView().isLaidOut()
                        && !a.activeSheet.getWindow().getDecorView().isLayoutRequested()));
    SystemClock.sleep(250);
  }

  private static void assertRows(ViewGroup body, Class<?> rowType, int gap) {
    Rect previous = null, viewport = bounds(parentScroll(body));
    for (int i = 0; i < body.getChildCount(); i++) {
      View row = body.getChildAt(i);
      if (!rowType.isInstance(row)) continue;
      Rect next = bounds(row);
      assertTrue(
          "Row escapes viewport horizontally: " + next + " / " + viewport,
          next.left >= viewport.left && next.right <= viewport.right);
      if (previous != null)
        assertTrue(
            "Rows overlap or lack separation: " + previous + " / " + next,
            next.top - previous.bottom >= gap - 1);
      previous = next;
    }
  }

  private static void assertButtonsSeparated(ViewGroup group, int gap) {
    Rect previous = null;
    for (int i = 0; i < group.getChildCount(); i++) {
      View view = group.getChildAt(i);
      if (!(view instanceof Button) || view.getVisibility() != View.VISIBLE) continue;
      Rect next = bounds(view);
      assertTrue(
          "Touch target is shorter than 48dp",
          view.getHeight() >= Ui.dp(view.getContext(), 48) - 1);
      if (previous != null)
        assertTrue(
            "Action buttons overlap or lack a touch gap", next.left - previous.right >= gap - 1);
      previous = next;
    }
  }

  private static void assertChildrenFit(View view) {
    if (!(view instanceof ViewGroup group)) return;
    Rect parent = bounds(group);
    Rect previous = null;
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child.getVisibility() == View.GONE) continue;
      Rect box = bounds(child);
      assertTrue(
          "Child extends outside its row: "
              + child.getClass().getSimpleName()
              + " "
              + box
              + " / "
              + parent,
          box.left >= parent.left - 1
              && box.right <= parent.right + 1
              && box.top >= parent.top - 1
              && box.bottom <= parent.bottom + 1);
      if (group instanceof LinearLayout linear && previous != null) {
        assertTrue(
            "Adjacent row content overlaps",
            linear.getOrientation() == LinearLayout.VERTICAL
                ? box.top >= previous.bottom
                : box.left >= previous.right);
      }
      previous = box;
      assertChildrenFit(child);
    }
  }

  private static ViewGroup actionGroup(DownloadTaskRow row) {
    for (int i = 0; i < row.getChildCount(); i++)
      if (row.getChildAt(i) instanceof ViewGroup group
          && group.getChildCount() > 0
          && group.getChildAt(0) instanceof Button) return group;
    throw new AssertionError("Download actions missing");
  }

  private static DownloadTaskRow findTask(View view, String title) {
    if (view instanceof DownloadTaskRow row && findText(row, title) != null) return row;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        DownloadTaskRow found = findTask(group.getChildAt(i), title);
        if (found != null) return found;
      }
    return null;
  }

  private static TextView findText(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return label;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findText(group.getChildAt(i), text);
        if (found != null) return found;
      }
    return null;
  }

  private static ScrollView parentScroll(View view) {
    for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
      if (parent instanceof ScrollView scroll) return scroll;
    throw new AssertionError("A scrollable list ancestor is required");
  }

  private static String actionDiagnostics(MainActivity activity, String title) {
    View decor = activity.activeSheet.getWindow().getDecorView();
    DownloadTaskRow row = findTask(decor, title);
    if (row == null) return "row missing";
    ViewGroup actions = actionGroup(row);
    ScrollView scroll = parentScroll(row);
    View body = scroll.getChildAt(0);
    Rect global = new Rect();
    boolean visible = scroll.getGlobalVisibleRect(global);
    View focus = decor.findFocus();
    String focused =
        focus == null ? "none" : focus.getClass().getSimpleName() + "@" + bounds(focus);
    if (focus instanceof TextView text) {
      String label = text.getText().toString();
      focused += " text=" + label.substring(0, Math.min(100, label.length()));
    }
    StringBuilder buttons = new StringBuilder();
    for (int i = 0; i < actions.getChildCount(); i++) {
      View button = actions.getChildAt(i);
      buttons
          .append(' ')
          .append(button instanceof TextView text ? text.getText() : "button")
          .append('=')
          .append(bounds(button));
    }
    return "scrollY="
        + scroll.getScrollY()
        + " viewportVisible="
        + visible
        + " globalViewport="
        + global
        + " scrollScreen="
        + bounds(scroll)
        + " decorScreen="
        + bounds(decor)
        + " actionsScreen="
        + bounds(actions)
        + " bodyHeight="
        + body.getHeight()
        + " scrollHeight="
        + scroll.getHeight()
        + " bodyScreen="
        + bounds(body)
        + " requestedLayout="
        + scroll.isLayoutRequested()
        + " focus="
        + focused
        + " buttons="
        + buttons;
  }

  private static Rect bounds(View view) {
    int[] at = new int[2];
    view.getLocationOnScreen(at);
    return new Rect(at[0], at[1], at[0] + view.getWidth(), at[1] + view.getHeight());
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, Ready condition) throws Exception {
    long until = SystemClock.elapsedRealtime() + 7000;
    do {
      if (condition.value()) return;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < until);
    fail(message);
  }
}
