package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Layout;
import android.view.MotionEvent;
import android.view.PixelCopy;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.inputmethod.InputMethodManager;
import android.webkit.WebView;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.core.graphics.ColorUtils;
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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

  @Test
  public void persistentBarColorsReduceLargeFillsAndKeepDarkControlsLegible() throws Exception {
    applyTheme(false);
    ui(
        a -> {
          int bars = backgroundSamples(a.topBar)[0];
          assertEquals(bars, backgroundSamples(a.bottomBar)[0]);
          assertTrue(Color.luminance(bars) < Color.luminance(0xfff2f2f2));
          int green = backgroundSamples(a.resourceBar)[0];
          assertTrue(Color.luminance(green) < Color.luminance(Ui.ACCENT));
          assertTrue(Color.green(green) > Color.red(green) && Color.red(green) > Color.blue(green));
          assertTrue(
              ColorUtils.calculateContrast(a.resourceText.getCurrentTextColor(), green) >= 4.5);
          return null;
        });
    applyTheme(true);
    ui(
        a -> {
          int bottom = backgroundSamples(a.bottomBar)[0];
          assertEquals(Color.BLACK, bottom);
          int mark = a.shellMark.getCurrentTextColor();
          assertTrue(Color.luminance(mark) < Color.luminance(Ui.ACCENT));
          assertTrue(ColorUtils.calculateContrast(mark, backgroundSamples(a.topBar)[0]) >= 4.5);
          int badge = backgroundSamples(a.downloadBadge)[0];
          assertTrue(Color.luminance(badge) < Color.luminance(Ui.ACCENT));
          assertTrue(
              ColorUtils.calculateContrast(a.downloadBadge.getCurrentTextColor(), badge) >= 4.5);
          int tab = a.tabGlyph.getImageTintList().getDefaultColor();
          assertEquals(tab, a.tabCount.getCurrentTextColor());
          assertTrue(Color.luminance(tab) < Color.luminance(0xfff5f5f5));
          assertTrue(ColorUtils.calculateContrast(tab, bottom) >= 4.5);
          for (int i = 0; i < a.bottomBar.getChildCount(); i++) {
            if (a.bottomBar.getChildAt(i) instanceof ImageButton icon) {
              int color = icon.getImageTintList().getDefaultColor();
              assertTrue(Color.luminance(color) < Color.luminance(0xfff5f5f5));
              assertTrue(ColorUtils.calculateContrast(color, bottom) >= 3);
            }
          }
          a.takeoverButton.setEnabled(true);
          int[] button = backgroundSamples(a.takeoverButton);
          assertTrue(
              "The large button fill should be near black", Color.luminance(button[0]) < .05);
          assertTrue(
              "The outline should remain visible",
              ColorUtils.calculateContrast(button[1], button[0]) >= 3);
          assertTrue(
              ColorUtils.calculateContrast(a.takeoverButton.getCurrentTextColor(), button[0])
                  >= 4.5);
          a.takeoverButton.setPressed(true);
          try {
            int pressed = backgroundSamples(a.takeoverButton)[0];
            assertTrue(Color.luminance(pressed) > Color.luminance(button[0]));
            assertTrue(
                ColorUtils.calculateContrast(a.takeoverButton.getCurrentTextColor(), pressed)
                    >= 4.5);
            assertEquals(bottom, backgroundSamples(a.bottomBar)[0]);
          } finally {
            a.takeoverButton.setPressed(false);
          }
          return null;
        });
  }

  private static int[] backgroundSamples(View view) {
    Drawable background = view.getBackground();
    Rect original = new Rect(background.getBounds());
    int width = Math.max(32, view.getWidth()), height = Math.max(24, view.getHeight());
    Bitmap sample = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    try {
      background.setBounds(0, 0, width, height);
      background.draw(new Canvas(sample));
      return new int[] {sample.getPixel(width / 2, height / 2), sample.getPixel(width / 2, 0)};
    } finally {
      background.setBounds(original);
      sample.recycle();
    }
  }

  @Test
  public void pixelShiftMovesInkTogetherAndKeepsOriginalTouchTargets() throws Exception {
    ui(
        a -> {
          a.downloadBadge.setText("1");
          a.downloadBadge.setVisibility(View.VISIBLE);
          return null;
        });
    await(
        "Visible browser controls did not finish layout",
        () -> ui(a -> a.hasWindowFocus() && a.downloadBadge.getWidth() > 0));

    Rect tabTarget =
        ui(
            a -> {
              ChromePixelShift shift = a.chromePixelShift;
              shift.update(0);
              View tabMark = (View) a.tabGlyph.getParent();
              Rect tab = bounds(a.tabButton),
                  glyph = bounds(a.tabGlyph),
                  count = bounds(a.tabCount);
              Rect ws = bounds(a.shellMark),
                  back = bounds(a.back),
                  download = bounds(a.downloadsButton);
              assertSame(tabMark, a.tabCount.getParent());
              boolean sawMovement = false;
              // Advance the real timer callback through a full range without waiting on the device.
              for (int minute = 1; minute <= 16; minute++) {
                a.updateNavigation();
                a.updateResourceBar();
                shift.update(minute * ChromePixelShift.INTERVAL_MS);
                int x = Math.round(tabMark.getTranslationX());
                int y = Math.round(tabMark.getTranslationY());
                assertEquals((float) x, tabMark.getTranslationX(), 0f);
                assertEquals((float) y, tabMark.getTranslationY(), 0f);
                assertTrue(Math.abs(x) <= 2 && Math.abs(y) <= 2);
                sawMovement |= x != 0 || y != 0;
                assertEquals(x, bounds(a.tabGlyph).left - glyph.left);
                assertEquals(y, bounds(a.tabGlyph).top - glyph.top);
                assertEquals(x, bounds(a.tabCount).left - count.left);
                assertEquals(y, bounds(a.tabCount).top - count.top);
                assertEquals(x, -a.shellMark.getScrollX());
                assertEquals(y, -a.shellMark.getScrollY());
                assertEquals(x, -a.back.getScrollX());
                assertEquals(y, -a.back.getScrollY());
                assertEquals((float) x, a.downloadBadge.getTranslationX(), 0f);
                assertEquals((float) y, a.downloadBadge.getTranslationY(), 0f);
                assertTrue(download.contains(bounds(a.downloadBadge)));
                assertEquals(tab, bounds(a.tabButton));
                assertEquals(ws, bounds(a.shellMark));
                assertEquals(back, bounds(a.back));
                assertEquals(download, bounds(a.downloadsButton));
              }
              assertTrue("Marks stayed on the same pixels for the whole cycle", sawMovement);
              assertTrue(tab.height() >= a.dp(48));
              shift.update(0);
              View menu = a.bottomBar.getChildAt(a.bottomBar.getChildCount() - 1);
              Bitmap wsBefore = renderInParent(a.shellMark), menuBefore = renderInParent(menu);
              a.tabButton.setPressed(true);
              try {
                shift.update(ChromePixelShift.INTERVAL_MS);
                assertEquals("A held target moved", glyph, bounds(a.tabGlyph));
                assertRenderedMarkChanged(a.shellMark, wsBefore);
                assertRenderedMarkChanged(menu, menuBefore);
              } finally {
                a.tabButton.setPressed(false);
                wsBefore.recycle();
                menuBefore.recycle();
              }
              shift.update(ChromePixelShift.INTERVAL_MS);
              return tab;
            });

    // Tap just inside the original cell, well outside the smaller, shifted glyph.
    long downTime = SystemClock.uptimeMillis();
    for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
      MotionEvent event =
          MotionEvent.obtain(
              downTime,
              SystemClock.uptimeMillis(),
              action,
              tabTarget.left + 1f,
              tabTarget.exactCenterY(),
              0);
      try {
        InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
      } finally {
        event.recycle();
      }
    }
    awaitSheet();
    ui(
        a -> {
          assertNotNull(findText(sheetRoot(a), "标签页"));
          return null;
        });
  }

  private static Bitmap renderInParent(View view) {
    // Drawing the parent reproduces ViewGroup's child scroll transform within the fixed hit box.
    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    Canvas canvas = new Canvas(bitmap);
    canvas.translate(-view.getLeft(), -view.getTop());
    ((ViewGroup) view.getParent()).draw(canvas);
    return bitmap;
  }

  @Test
  public void continuousPageTapsStillShiftRenderedBrowserMarks() throws Exception {
    await("Isolated browser window has no focus", () -> ui(a -> a.hasWindowFocus()));
    View ws = ui(a -> a.shellMark);
    View menu = ui(a -> a.bottomBar.getChildAt(a.bottomBar.getChildCount() - 1));
    Rect wsTarget = ui(a -> bounds(ws)), menuTarget = ui(a -> bounds(menu));
    // Keep the two starting crops on the same side of a minute boundary.
    waitForStableCapture();
    int initialX = ui(a -> menu.getScrollX()), initialY = ui(a -> menu.getScrollY());
    boolean[] originalWs = windowInk(ws), originalMenu = windowInk(menu);
    long started = SystemClock.uptimeMillis();
    while (SystemClock.uptimeMillis() - started < ChromePixelShift.INTERVAL_MS + 1500) {
      Rect surface =
          ui(
              a -> {
                assertTrue(a.resumed && a.hasWindowFocus());
                assertSame(document, a.current.web);
                assertEquals(epoch, a.current.epoch);
                assertEquals("WebSurf layout fixture", document.getTitle());
                assertTrue(document.getUrl().startsWith(BASE + "/layout.html?acid="));
                assertEquals(wsTarget, bounds(ws));
                assertEquals(menuTarget, bounds(menu));
                return bounds(document);
              });
      long down = SystemClock.uptimeMillis();
      for (int action : new int[] {MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP}) {
        MotionEvent event =
            MotionEvent.obtain(
                down,
                SystemClock.uptimeMillis(),
                action,
                surface.exactCenterX(),
                surface.exactCenterY(),
                0);
        try {
          InstrumentationRegistry.getInstrumentation().sendPointerSync(event);
        } finally {
          event.recycle();
        }
      }
      SystemClock.sleep(1600);
    }
    waitForStableCapture();
    int dx = initialX - ui(a -> menu.getScrollX()), dy = initialY - ui(a -> menu.getScrollY());
    assertTrue("No real timer transition during continuous page input", dx != 0 || dy != 0);
    assertInkMoved(originalWs, windowInk(ws), wsTarget.width(), dx, dy);
    assertInkMoved(originalMenu, windowInk(menu), menuTarget.width(), dx, dy);
    assertEquals(wsTarget, ui(a -> bounds(ws)));
    assertEquals(menuTarget, ui(a -> bounds(menu)));
  }

  private static void waitForStableCapture() {
    long phaseTime = SystemClock.uptimeMillis() % ChromePixelShift.INTERVAL_MS;
    long untilTick = ChromePixelShift.INTERVAL_MS - phaseTime;
    if (untilTick < 500) SystemClock.sleep(untilTick + 120);
    else if (phaseTime < 120) SystemClock.sleep(120 - phaseTime);
  }

  /** Copy only the control's final, composited pixels from this Activity window. */
  private boolean[] windowInk(View control) throws Exception {
    int width = control.getWidth(), height = control.getHeight();
    Bitmap crop = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
    CountDownLatch copied = new CountDownLatch(1);
    AtomicInteger status = new AtomicInteger(-1);
    try {
      ui(
          a -> {
            int[] at = new int[2];
            control.getLocationInWindow(at);
            Rect area = new Rect(at[0], at[1], at[0] + width, at[1] + height);
            PixelCopy.request(
                a.getWindow(),
                area,
                crop,
                result -> {
                  status.set(result);
                  copied.countDown();
                },
                new Handler(Looper.getMainLooper()));
            return null;
          });
      assertTrue("Window pixel copy timed out", copied.await(2, TimeUnit.SECONDS));
      assertEquals(PixelCopy.SUCCESS, status.get());
      int[] colors = new int[width * height];
      crop.getPixels(colors, 0, width, 0, 0, width, height);
      int background = colors[2 * width + 2];
      boolean[] ink = new boolean[colors.length];
      for (int i = 0; i < colors.length; i++) {
        int c = colors[i];
        ink[i] =
            Math.abs(Color.red(c) - Color.red(background)) >= 48
                || Math.abs(Color.green(c) - Color.green(background)) >= 48
                || Math.abs(Color.blue(c) - Color.blue(background)) >= 48;
      }
      return ink;
    } finally {
      crop.recycle();
    }
  }

  private static void assertInkMoved(boolean[] before, boolean[] after, int width, int dx, int dy) {
    int ink = 0, matched = 0, stationary = 0;
    for (int i = 0; i < before.length; i++) {
      if (!before[i]) continue;
      ink++;
      if (after[i]) stationary++;
      int x = i % width + dx, y = i / width + dy;
      if (x >= 0 && x < width && y >= 0 && y < after.length / width && after[y * width + x])
        matched++;
    }
    assertTrue("Could not isolate the symbol in the hardware crop", ink >= 12);
    assertTrue(
        "Symbol pixels did not follow the timer offset",
        matched > stationary && matched >= ink * .9);
  }

  private static void assertRenderedMarkChanged(View control, Bitmap before) {
    Bitmap after = renderInParent(control);
    try {
      assertFalse(
          "Ink stayed on identical pixels: " + control.getContentDescription(),
          before.sameAs(after));
    } finally {
      after.recycle();
    }
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
