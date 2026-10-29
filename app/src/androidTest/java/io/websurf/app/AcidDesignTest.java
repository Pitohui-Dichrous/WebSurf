package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import io.websurf.app.download.DownloadStore;
import java.io.InputStream;
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

/** Real native views with local fixture content; run at normal and compact device sizes. */
@RunWith(AndroidJUnit4.class)
public final class AcidDesignTest {
  private static final String BASE = "http://127.0.0.1:8765";
  private static final String ERROR = "服务器返回 HTTP 403。请回到原网页确认视频仍可播放后重试。完整错误信息应保持可读，不能遮挡重试或移除记录。";
  private Context context;
  private ActivityScenario<MainActivity> scenario;
  private SharedPreferences browser, appearance;
  private boolean hadAuto, oldAuto, hadDark, oldDark;
  private boolean hadHomepage, hadThreads;
  private String oldHomepage;
  private int oldThreads;
  private WebView document;
  private long epoch;
  private final List<String> ownedJobs = new ArrayList<>();

  @Before
  public void openIsolatedDocument() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    browser = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    hadAuto = browser.contains("auto_play");
    oldAuto = browser.getBoolean("auto_play", true);
    hadDark = appearance.contains("dark");
    oldDark = appearance.getBoolean("dark", false);
    hadHomepage = browser.contains("homepage");
    oldHomepage = browser.getString("homepage", "https://www.baidu.com");
    hadThreads = browser.contains("download_threads");
    oldThreads = browser.getInt("download_threads", 8);
    assertTrue(browser.edit().putBoolean("auto_play", false).commit());
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(BASE + "/layout.html?acid=" + UUID.randomUUID())));
    await(
        "Local layout document did not settle",
        () ->
            ui(
                a ->
                    a.resumed
                        && a.current.web.getProgress() == 100
                        && a.current.web.getUrl() != null
                        && a.current.web.getUrl().startsWith(BASE + "/layout.html")));
    document = ui(a -> a.current.web);
    epoch = ui(a -> a.current.epoch);
  }

  @After
  public void restoreOnlyTestOwnedState() {
    if (scenario != null) scenario.close();
    if (context != null) for (String id : ownedJobs) DownloadStore.remove(context, id);
    if (browser != null) {
      SharedPreferences.Editor edit = browser.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      if (hadHomepage) edit.putString("homepage", oldHomepage);
      else edit.remove("homepage");
      if (hadThreads) edit.putInt("download_threads", oldThreads);
      else edit.remove("download_threads");
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
  public void shellAndUtilityPanelsKeepCurrentDocumentAcrossThemes() throws Exception {
    for (boolean dark : new boolean[] {false, true}) {
      applyTheme(dark);
      ui(
          a -> {
            assertDisjoint(a.topBar, a.webContainer);
            assertDisjoint(a.webContainer, a.resourceBar);
            assertDisjoint(a.resourceBar, a.bottomBar);
            assertControlsFit(a.topBar);
            assertControlsFit(a.resourceBar);
            assertControlsFit(a.bottomBar);
            assertTrue(a.address.isEnabled());
            assertTrue(a.downloadsButton.isEnabled());
            assertFalse(a.downloadsButton.getContentDescription().toString().isBlank());
            return null;
          });
      capture("01-browser", dark);
      ui(
          a -> {
            a.showMenu();
            return null;
          });
      awaitSheet();
      ui(
          a -> {
            assertNotNull(findText(sheetRoot(a), "//version. " + BuildConfig.VERSION_NAME));
            assertFalse(texts(sheetRoot(a)).contains("酸性设计"));
            return null;
          });
      capture("02-menu", dark);
      View settings = ui(a -> clickableText(sheetRoot(a), "设置"));
      assertNotNull("Menu must retain settings navigation", settings);
      reveal(settings);
      ui(
          a -> {
            assertTrue(settings.performClick());
            return null;
          });
      awaitSheet();
      ui(
          a -> {
            View root = sheetRoot(a);
            assertNotNull(findDescription(root, "主页地址"));
            assertNotNull(findText(root, "自动接管视频"));
            assertNotNull(findTextContaining(root, "下载并发"));
            assertNotNull(findTextContaining(root, "下载通知"));
            assertFalse(texts(root).contains("画质"));
            return null;
          });
      capture("03-settings", dark);
      View appearanceToggle = ui(a -> findText(sheetRoot(a), "深色外观"));
      assertNotNull(appearanceToggle);
      reveal(appearanceToggle);
      capture("04-settings-lower", dark);
      ui(
          a -> {
            a.activeSheet.dismiss();
            assertSame("Opening native panels must preserve the page", document, a.current.web);
            assertEquals("Theme or panel navigation reloaded the webpage", epoch, a.current.epoch);
            return null;
          });
    }
  }

  @Test
  public void resourceFiltersAndDownloadStatesStayOperableAcrossThemes() throws Exception {
    seedResources();
    DownloadStore.Job failed = persist("failed", "港湾日落 · 完整视频", 0, ERROR);
    DownloadStore.Job complete = persist("completed", "林间溪流 · 1080p", 100, "");
    DownloadStore.Job active = persist("downloading", "远岸航线 · 2160p", 63, "");
    for (boolean dark : new boolean[] {false, true}) {
      applyTheme(dark);
      ui(
          a -> {
            a.showResources("视频");
            return null;
          });
      awaitSheet();
      ui(
          a -> {
            View root = sheetRoot(a);
            HorizontalScrollView filters = root.findViewWithTag("resource-filters");
            ViewGroup results = root.findViewWithTag("resource-list");
            assertNotNull(filters);
            assertNotNull(results);
            assertEquals(3, countRows(results, ResourceRow.class));
            assertTrue(
                "Resource filters overlap thumbnails",
                bounds(results.getChildAt(0)).top >= bounds(filters).bottom);
            assertControlsFit(results.getChildAt(0));
            assertNotNull(findTextContaining(results, "2160"));
            return null;
          });
      capture("05-resources", dark);
      ui(
          a -> {
            View filters = sheetRoot(a).findViewWithTag("resource-filters");
            assertTrue(clickableText(filters, "音频").performClick());
            return null;
          });
      await(
          "Audio category did not replace video rows",
          () ->
              ui(
                  a -> {
                    ViewGroup results = sheetRoot(a).findViewWithTag("resource-list");
                    return countRows(results, ResourceRow.class) == 1
                        && findText(results, "沿岸风声") != null;
                  }));
      ui(
          a -> {
            a.showDownloads();
            return null;
          });
      awaitSheet();
      ui(
          a -> {
            View root = sheetRoot(a);
            DownloadTaskRow running = findTask(root, active.title);
            assertNotNull(running);
            assertNotNull(clickableText(running, "边下边播"));
            assertNotNull(clickableText(running, "取消"));
            assertTrue(texts(running).contains("63%"));
            assertTrue(texts(running).contains("8"));
            assertNotNull(clickableText(findTask(root, complete.title), "打开"));
            TextView error = findText(findTask(root, failed.title), ERROR);
            assertNotNull(error);
            assertTrue(error.isTextSelectable());
            assertNull("Failure details must not be ellipsized", error.getEllipsize());
            return null;
          });
      capture("06-downloads", dark);
      View retry = ui(a -> clickableText(findTask(sheetRoot(a), failed.title), "重试"));
      assertNotNull(retry);
      reveal(retry);
      ui(
          a -> {
            assertTrue(retry.isEnabled());
            assertTrue(retry.isClickable());
            assertControlsFit(findTask(sheetRoot(a), failed.title));
            return null;
          });
      capture("07-download-error", dark);
      ui(
          a -> {
            a.activeSheet.dismiss();
            assertSame(document, a.current.web);
            assertEquals(epoch, a.current.epoch);
            return null;
          });
    }
  }

  @Test
  public void homepageCanBeSavedWithKeyboardAndConcurrencyChoicePersists() throws Exception {
    for (boolean dark : new boolean[] {false, true}) {
      applyTheme(dark);
      ui(
          a -> {
            a.showSettings();
            return null;
          });
      awaitSheet();
      EditText home = ui(a -> (EditText) findDescription(sheetRoot(a), "主页地址"));
      assertNotNull(home);
      reveal(home);
      String requested = "https://example.org/acid-" + (dark ? "dark" : "light");
      ui(
          a -> {
            assertTrue(home.requestFocus());
            home.setText(requested);
            home.setSelection(requested.length());
            a.getSystemService(InputMethodManager.class)
                .showSoftInput(home, InputMethodManager.SHOW_IMPLICIT);
            return null;
          });
      await(
          "Software keyboard did not open for the homepage field",
          () ->
              ui(
                  a -> {
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(sheetRoot(a));
                    return insets != null && insets.isVisible(WindowInsetsCompat.Type.ime());
                  }));
      reveal(home);
      capture("08-settings-keyboard", dark);
      View save = ui(a -> clickableText(sheetRoot(a), "保存主页"));
      assertNotNull(save);
      reveal(save);
      ui(
          a -> {
            assertTrue(save.performClick());
            assertEquals(requested, a.homepage());
            a.getSystemService(InputMethodManager.class)
                .hideSoftInputFromWindow(home.getWindowToken(), 0);
            home.clearFocus();
            return null;
          });
      await(
          "Software keyboard did not dismiss",
          () ->
              ui(
                  a -> {
                    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(sheetRoot(a));
                    return insets != null && !insets.isVisible(WindowInsetsCompat.Type.ime());
                  }));
      View connections = ui(a -> clickableContaining(sheetRoot(a), "下载并发"));
      assertNotNull(connections);
      reveal(connections);
      ui(
          a -> {
            assertTrue(connections.performClick());
            return null;
          });
      awaitSheet();
      int selected = browser.getInt("download_threads", 8);
      int requestedThreads = selected == 16 ? 8 : 16;
      View choice = ui(a -> clickableText(sheetRoot(a), requestedThreads + " 路"));
      assertNotNull(choice);
      reveal(choice);
      capture("09-connections", dark);
      ui(
          a -> {
            assertTrue(choice.performClick());
            assertEquals(requestedThreads, a.prefs.getInt("download_threads", 8));
            return null;
          });
      awaitSheet();
      ui(
          a -> {
            assertNotNull(findText(sheetRoot(a), "下载并发 · " + requestedThreads + " 路"));
            assertSame("Changing settings replaced the browser", document, a.current.web);
            assertEquals("Changing settings refreshed the webpage", epoch, a.current.epoch);
            a.activeSheet.dismiss();
            return null;
          });
    }
  }

  @Test
  public void tabCounterFitsIconAndItsCenterOpensTheTabListAcrossThemes() throws Exception {
    String secondUrl = BASE + "/layout.html?acid-tabs=" + UUID.randomUUID();
    BrowserTab second = ui(a -> a.newTab(secondUrl, true));
    assertNotNull(second);
    await(
        "Second local tab did not load",
        () ->
            ui(
                a ->
                    a.current == second
                        && second.web.getProgress() == 100
                        && secondUrl.equals(second.web.getUrl())));
    for (boolean dark : new boolean[] {false, true}) {
      applyTheme(dark);
      for (String sample : new String[] {"1", "8", "12"}) {
        // 12 is only a typography sample; the browser still enforces its eight-tab limit.
        ui(
            a -> {
              a.tabCount.setText(sample);
              return null;
            });
        await(
            "Tab counter sample did not finish layout",
            () ->
                ui(
                    a ->
                        sample.contentEquals(a.tabCount.getText())
                            && a.tabCount.isLaidOut()
                            && !a.tabCount.isLayoutRequested()
                            && a.tabCount.getLayout() != null));
        ui(
            a -> {
              assertSame(a.tabGlyph.getParent(), a.tabCount.getParent());
              assertFalse(
                  "Counter must not intercept the navigation tap", a.tabCount.isClickable());
              assertFalse("Glyph must not be a separate button", a.tabGlyph.isClickable());
              assertTrue(a.tabButton.isClickable());
              assertTrue(a.tabButton.getHeight() >= a.dp(48) - 1);
              Rect glyph = bounds(a.tabGlyph), count = bounds(a.tabCount);
              // Lucide's front square occupies 8..22 within the 24-unit icon coordinate system.
              float left = glyph.left + glyph.width() * (8f / 24f);
              float top = glyph.top + glyph.height() * (8f / 24f);
              float right = glyph.left + glyph.width() * (22f / 24f);
              float bottom = glyph.top + glyph.height() * (22f / 24f);
              assertTrue(
                  "Counter escapes the icon's front square: " + count + " / " + glyph,
                  count.left >= left - 1
                      && count.top >= top - 1
                      && count.right <= right + 1
                      && count.bottom <= bottom + 1);
              assertEquals((left + right) / 2f, count.exactCenterX(), 1f);
              assertEquals((top + bottom) / 2f, count.exactCenterY(), 1f);
              Layout text = a.tabCount.getLayout();
              assertEquals("Counter must stay on a single line", 1, text.getLineCount());
              assertTrue(
                  "Counter digits are horizontally clipped",
                  text.getLineWidth(0)
                      <= a.tabCount.getWidth()
                          - a.tabCount.getCompoundPaddingLeft()
                          - a.tabCount.getCompoundPaddingRight()
                          + 1);
              assertTrue(
                  "Counter digits are vertically clipped",
                  text.getHeight()
                      <= a.tabCount.getHeight()
                          - a.tabCount.getCompoundPaddingTop()
                          - a.tabCount.getCompoundPaddingBottom()
                          + 1);
              return null;
            });
        capture("10-tab-count-" + sample, dark);
      }
      ui(
          a -> {
            a.updateNavigation();
            assertEquals("2", a.tabCount.getText().toString());
            assertEquals("标签页，2 个", a.tabButton.getContentDescription().toString());
            return null;
          });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      Rect count = ui(a -> bounds(a.tabCount));
      long time = SystemClock.uptimeMillis();
      MotionEvent down =
          MotionEvent.obtain(
              time, time, MotionEvent.ACTION_DOWN, count.exactCenterX(), count.exactCenterY(), 0);
      MotionEvent up =
          MotionEvent.obtain(
              time,
              time + 70,
              MotionEvent.ACTION_UP,
              count.exactCenterX(),
              count.exactCenterY(),
              0);
      try {
        InstrumentationRegistry.getInstrumentation().sendPointerSync(down);
        SystemClock.sleep(70);
        InstrumentationRegistry.getInstrumentation().sendPointerSync(up);
      } finally {
        down.recycle();
        up.recycle();
      }
      awaitSheet();
      ui(
          a -> {
            assertNotNull("Tapping the number must open tabs", findText(sheetRoot(a), "标签页"));
            assertNotNull(clickableText(sheetRoot(a), "新建标签页"));
            a.activeSheet.dismiss();
            return null;
          });
    }
    ui(
        a -> {
          a.closeTab(second);
          assertEquals("1", a.tabCount.getText().toString());
          return null;
        });
  }

  private void seedResources() throws Exception {
    Bitmap poster;
    try (InputStream input =
        InstrumentationRegistry.getInstrumentation()
            .getContext()
            .getAssets()
            .open("fixtures/media/cover.png")) {
      poster = BitmapFactory.decodeStream(input);
    }
    assertNotNull(poster);
    ui(
        a -> {
          a.current.resources.clear();
          String[] titles = {"港湾日落 · 长镜头", "林间溪流 · 完整片段", "远岸航线 · 航拍实录"};
          int[] heights = {720, 1080, 2160};
          for (int i = 0; i < titles.length; i++) {
            WebResource resource = new WebResource(BASE + "/public/sample-360.mp4?acid=" + i);
            resource.title = titles[i];
            resource.mime = "video/mp4";
            resource.classify();
            resource.width = heights[i] * 16 / 9;
            resource.height = heights[i];
            resource.durationMs = 125000 + i * 21000;
            a.previewLoader.seed(
                resource, a.headersFor(resource), poster, resource.width, resource.height);
            a.current.resources.put(resource.url, resource);
          }
          WebResource audio = new WebResource(BASE + "/public/audio-only.m4a");
          audio.title = "沿岸风声";
          audio.mime = "audio/mp4";
          audio.classify();
          a.current.resources.put(audio.url, audio);
          a.updateResourceBar();
          return null;
        });
    poster.recycle();
  }

  private DownloadStore.Job persist(String state, String title, int percent, String error)
      throws Exception {
    DownloadStore.Job job = new DownloadStore.Job();
    job.id = "acid-design-" + UUID.randomUUID();
    ownedJobs.add(job.id);
    job.title = title;
    job.url = BASE + "/public/sample-360.mp4?acid-task=" + job.id;
    job.mimeType = "video/mp4";
    job.state = state;
    job.progress = percent;
    job.bytes = percent * 1048576L;
    job.total = 100 * 1048576L;
    job.bytesPerSecond = "downloading".equals(state) ? 8547738 : 0;
    job.connections = "downloading".equals(state) ? 8 : 0;
    job.completedParts = percent;
    job.totalParts = 100;
    job.error = error;
    job.createdAt = System.currentTimeMillis();
    Method put =
        DownloadStore.class.getDeclaredMethod("put", Context.class, DownloadStore.Job.class);
    put.setAccessible(true);
    put.invoke(null, context, job);
    return job;
  }

  private void applyTheme(boolean dark) throws Exception {
    ui(
        a -> {
          if (a.activeSheet != null) a.activeSheet.dismiss();
          Ui.setDark(a, dark);
          Ui.configure(a);
          a.applyChromeTheme();
          return null;
        });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private void awaitSheet() throws Exception {
    await(
        "Native panel did not finish layout",
        () ->
            ui(
                a -> {
                  if (a.activeSheet == null || !a.activeSheet.isShowing()) return false;
                  View root = sheetRoot(a);
                  return root.isLaidOut() && !root.isLayoutRequested() && root.getHeight() > 0;
                }));
    SystemClock.sleep(250);
  }

  private void reveal(View target) throws Exception {
    ui(
        a -> {
          target.requestRectangleOnScreen(
              new Rect(0, 0, target.getWidth(), target.getHeight()), true);
          return null;
        });
    await(
        "Action cannot be brought fully into the viewport",
        () ->
            ui(
                a -> {
                  Rect visible = bounds(sheetRoot(a));
                  ScrollView scroll = ancestorScroll(target);
                  if (scroll != null && !visible.intersect(bounds(scroll))) return false;
                  return target.isShown() && visible.contains(bounds(target));
                }));
  }

  private void capture(String surface, boolean dark) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    // View geometry can settle before SurfaceFlinger presents the scrolled/IME frame.
    SystemClock.sleep(350);
    String prefix = InstrumentationRegistry.getArguments().getString("acid_capture_prefix", "acid");
    UiCaptureTest.shot(context, prefix + "-" + surface + "-" + (dark ? "dark" : "light"));
  }

  private static View sheetRoot(MainActivity activity) {
    return activity.activeSheet.getWindow().getDecorView();
  }

  private static void assertDisjoint(View first, View second) {
    Rect a = bounds(first), b = bounds(second);
    assertFalse("Browser surfaces overlap: " + a + " / " + b, Rect.intersects(a, b));
  }

  private static void assertControlsFit(View view) {
    if (!(view instanceof ViewGroup group)) return;
    Rect parent = bounds(group);
    for (int i = 0; i < group.getChildCount(); i++) {
      View child = group.getChildAt(i);
      if (child.getVisibility() != View.VISIBLE) continue;
      Rect next = bounds(child);
      assertTrue(
          "Control escapes its group: " + child.getClass().getSimpleName() + next + " / " + parent,
          next.left >= parent.left - 1
              && next.right <= parent.right + 1
              && next.top >= parent.top - 1
              && next.bottom <= parent.bottom + 1);
      assertControlsFit(child);
    }
  }

  private static int countRows(ViewGroup group, Class<?> type) {
    int count = 0;
    for (int i = 0; i < group.getChildCount(); i++)
      if (type.isInstance(group.getChildAt(i))) count++;
    return count;
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

  private static View clickableText(View view, String text) {
    View found = findText(view, text);
    while (found != null && !found.isClickable()) {
      found = found.getParent() instanceof View parent ? parent : null;
    }
    return found;
  }

  private static View clickableContaining(View view, String text) {
    View found = findTextContaining(view, text);
    while (found != null && !found.isClickable()) {
      found = found.getParent() instanceof View parent ? parent : null;
    }
    return found;
  }

  private static TextView findText(View view, String value) {
    if (view instanceof TextView text && value.contentEquals(text.getText())) return text;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findText(group.getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  private static TextView findTextContaining(View view, String value) {
    if (view instanceof TextView text && text.getText().toString().contains(value)) return text;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        TextView found = findTextContaining(group.getChildAt(i), value);
        if (found != null) return found;
      }
    return null;
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View found = findDescription(group.getChildAt(i), description);
        if (found != null) return found;
      }
    return null;
  }

  private static String texts(View view) {
    StringBuilder out = new StringBuilder();
    if (view instanceof TextView text) out.append(text.getText()).append(' ');
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) out.append(texts(group.getChildAt(i)));
    return out.toString();
  }

  private static ScrollView ancestorScroll(View view) {
    for (ViewParent parent = view.getParent(); parent != null; parent = parent.getParent())
      if (parent instanceof ScrollView scroll) return scroll;
    return null;
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
    long until = SystemClock.elapsedRealtime() + 8000;
    do {
      if (condition.value()) return;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < until);
    fail(message);
  }
}
