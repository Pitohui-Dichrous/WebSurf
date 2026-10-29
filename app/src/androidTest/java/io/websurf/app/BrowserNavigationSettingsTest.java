package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Android back and search preferences follow actual browser navigation and native settings. */
@RunWith(AndroidJUnit4.class)
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class BrowserNavigationSettingsTest {
  private static final String BASE = "http://127.0.0.1:8765";
  private static final List<String> LABELS = List.of("谷歌", "夸克", "必应国内版", "必应国际版", "百度");
  private Context context;
  private ActivityScenario<MainActivity> scenario;
  private SharedPreferences prefs;
  private boolean hadAuto, oldAuto, hadSearch;
  private String previous, oldSearch;

  @Before
  public void openPreviousPage() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    prefs = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    hadAuto = prefs.contains("auto_play");
    oldAuto = prefs.getBoolean("auto_play", true);
    hadSearch = prefs.contains("search_engine");
    oldSearch = prefs.getString("search_engine", "");
    assertTrue(prefs.edit().putBoolean("auto_play", false).remove("search_engine").commit());
    previous = BASE + "/layout.html?previous=" + UUID.randomUUID();
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class).setData(Uri.parse(previous)));
    await(
        "Previous page did not load",
        10000,
        () ->
            ui(a -> previous.equals(a.current.web.getUrl()) && a.current.web.getProgress() == 100));
  }

  @After
  public void closeAndRestore() {
    if (scenario != null) scenario.close();
    if (prefs != null) {
      SharedPreferences.Editor edit = prefs.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      if (hadSearch) edit.putString("search_engine", oldSearch);
      else edit.remove("search_engine");
      assertTrue(edit.commit());
    }
  }

  @Test
  public void searchChoiceIsOrderedNearHomepagePersistsAndDrivesActualAddressSubmission()
      throws Exception {
    assertEquals("bing_cn", ui(a -> a.searchEngine().id));
    WebView original = ui(a -> a.current.web);
    AtomicReference<String> requested = new AtomicReference<>();
    ui(
        a -> {
          // Exercise real editor submission and WebView navigation while keeping provider requests
          // entirely local. This does not replace the address editor or its navigation callback.
          a.current.web.setWebViewClient(
              new WebViewClient() {
                @Override
                public WebResourceResponse shouldInterceptRequest(
                    WebView view, WebResourceRequest request) {
                  if (request.isForMainFrame()) requested.set(request.getUrl().toString());
                  return new WebResourceResponse(
                      "text/html",
                      "UTF-8",
                      new ByteArrayInputStream(
                          "<!doctype html><title>Search navigation fixture</title>"
                              .getBytes(StandardCharsets.UTF_8)));
                }
              });
          a.showSettings();
          return null;
        });
    await(
        "Search action is missing near the homepage settings",
        5000,
        () ->
            ui(
                a ->
                    findContaining(a.activeSheet.getWindow().getDecorView(), "搜索引擎 · 必应国内版") != null
                        && findContaining(a.activeSheet.getWindow().getDecorView(), "搜索引擎 · 必应国内版")
                                .getHeight()
                            > 0));
    ui(
        a -> {
          View root = a.activeSheet.getWindow().getDecorView();
          View home = findText(root, "保存主页");
          View search = findContaining(root, "搜索引擎 · 必应国内版");
          assertNotNull(home);
          assertTrue(
              "Search action belongs below the homepage controls without overlap",
              top(search) >= top(home) + home.getHeight());
          assertTrue(search.performClick());
          return null;
        });
    await(
        "Search picker did not open",
        5000,
        () ->
            ui(
                a ->
                    choiceLabels(a.activeSheet.getWindow().getDecorView()).size() == LABELS.size()
                        && a.activeSheet.getWindow().getDecorView().getHeight() > 0));
    ui(
        a -> {
          View root = a.activeSheet.getWindow().getDecorView();
          assertEquals(LABELS, choiceLabels(root));
          assertTrue("The default must be Bing China", findText(root, "必应国内版").isSelected());
          return null;
        });
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(350);
    UiCaptureTest.shot(
        context,
        "browser-search-engines-" + context.getResources().getConfiguration().screenWidthDp + "dp");

    String[] ids = {"google", "quark", "bing_cn", "bing_intl", "baidu"};
    String[] hosts = {
      "www.google.com", "quark.sm.cn", "cn.bing.com", "www.bing.com", "www.baidu.com"
    };
    String[] paths = {"/search", "/s", "/search", "/search", "/s"};
    String keyword = "海浪 视频 + 4K";
    for (int index = 0; index < LABELS.size(); index++) {
      final int selected = index;
      if (index > 0)
        ui(
            a -> {
              a.showSearchEngines();
              return null;
            });
      String before = ui(a -> a.current.web.getUrl());
      ui(
          a -> {
            View choice = findText(a.activeSheet.getWindow().getDecorView(), LABELS.get(selected));
            assertNotNull(choice);
            assertTrue(choice.performClick());
            assertEquals(ids[selected], a.prefs.getString("search_engine", ""));
            assertSame(
                "Changing search provider must retain the current WebView",
                original,
                a.current.web);
            assertEquals(
                "Changing provider must not reload or navigate the page",
                before,
                a.current.web.getUrl());
            a.activeSheet.dismiss();
            return null;
          });
      submit(keyword, requested);
      Uri destination = Uri.parse(requested.get());
      assertEquals("https", destination.getScheme());
      assertEquals(hosts[index], destination.getHost());
      assertEquals(paths[index], destination.getPath());
      assertEquals(keyword, destination.getQueryParameter(index == 4 ? "wd" : "q"));
      if (index == 2) assertEquals("0", destination.getQueryParameter("ensearch"));
      if (index == 3) assertEquals("1", destination.getQueryParameter("ensearch"));
    }
    for (String url :
        new String[] {
          "https://example.org/path?x=1&source=browser-test#view",
          "http://127.0.0.1:8765/layout.html?unchanged=1"
        }) {
      submit(url, requested);
      assertEquals(
          "An explicit URL must not be converted into a search",
          url,
          ui(a -> a.current.web.getUrl()));
    }
    scenario.recreate();
    await(
        "Recreated activity did not restore the selected provider",
        10000,
        () -> "baidu".equals(ui(a -> a.searchEngine().id)));
    ui(
        a -> {
          a.showSettings();
          assertNotNull(findContaining(a.activeSheet.getWindow().getDecorView(), "搜索引擎 · 百度"));
          a.showSearchEngines();
          assertTrue(findText(a.activeSheet.getWindow().getDecorView(), "百度").isSelected());
          return null;
        });
  }

  private void submit(String text, AtomicReference<String> requested) throws Exception {
    requested.set(null);
    ui(
        a -> {
          a.address.setText(text);
          a.address.onEditorAction(EditorInfo.IME_ACTION_GO);
          return null;
        });
    await(
        "Address editor did not issue its chosen navigation for " + text,
        7000,
        () -> requested.get() != null && ui(a -> a.current.web.getProgress() == 100));
  }

  private static int top(View view) {
    int[] point = new int[2];
    view.getLocationOnScreen(point);
    return point[1];
  }

  private static List<String> choiceLabels(View root) {
    List<String> labels = new ArrayList<>();
    collectChoiceLabels(root, labels);
    return labels;
  }

  private static void collectChoiceLabels(View view, List<String> labels) {
    if (view instanceof TextView text && LABELS.contains(text.getText().toString()))
      labels.add(text.getText().toString());
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        collectChoiceLabels(group.getChildAt(i), labels);
  }

  private static View findText(View view, String text) {
    if (view instanceof TextView label && text.contentEquals(label.getText())) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = findText(group.getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  private static View findContaining(View view, String text) {
    if (view instanceof TextView label && label.getText().toString().contains(text)) return view;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = findContaining(group.getChildAt(i), text);
        if (result != null) return result;
      }
    return null;
  }

  @Test
  public void oneAndroidBackLeavesTakeoverAndReturnsToPreviousPageInEveryPlayerMode()
      throws Exception {
    WebView original = ui(a -> a.current.web);
    int originalOrientation = ui(a -> a.getResources().getConfiguration().orientation);
    for (VideoChrome.Mode mode :
        new VideoChrome.Mode[] {
          VideoChrome.Mode.INLINE, VideoChrome.Mode.FULLSCREEN, VideoChrome.Mode.FLOATING
        }) {
      String videoPage = BASE + "/?back-mode=" + mode + "&run=" + UUID.randomUUID();
      ui(
          a -> {
            a.navigate(videoPage);
            return null;
          });
      await(
          "Video page did not expose its real DOM video",
          15000,
          () ->
              ui(
                  a ->
                      videoPage.equals(a.current.web.getUrl())
                          && a.current.web.getProgress() == 100
                          && a.current.videos.values().stream()
                              .anyMatch(t -> t.url.contains("sample-360.mp4") && t.height == 360)));
      scrollVideoIntoView();
      ui(
          a -> {
            assertTrue(
                "Fixture must have a real preceding web history entry", a.current.web.canGoBack());
            VideoTarget target =
                a.current.videos.values().stream()
                    .filter(t -> t.url.contains("sample-360.mp4") && t.height == 360)
                    .findFirst()
                    .orElseThrow();
            a.openTarget(a.current, target, true);
            a.player.setRepeatMode(Player.REPEAT_MODE_ONE);
            return null;
          });
      await(
          "Native takeover did not begin",
          15000,
          () ->
              ui(
                  a ->
                      a.player != null
                          && a.player.isPlaying()
                          && a.player.getPlayerError() == null
                          && a.activeTarget != null));
      ui(
          a -> {
            a.setPlayerMode(mode);
            a.hideKeyboard();
            return null;
          });
      if (mode == VideoChrome.Mode.INLINE) {
        // The back contract needs a real, attached visible takeover. Exact CSS-to-native
        // geometry is covered independently by ScrollPlaybackTest, not this navigation test.
        await(
            "Inline takeover was not attached and visible before Android back",
            7000,
            () ->
                ui(
                    a ->
                        a.player != null
                            && a.playerMode == VideoChrome.Mode.INLINE
                            && a.anchorClip != null
                            && a.videoChrome.getParent() == a.anchorClip
                            && a.anchorClip.getParent() == a.webContainer
                            && a.videoChrome.getWidth() > 0
                            && a.videoChrome.getHeight() > 0
                            && a.videoChrome.getGlobalVisibleRect(new Rect())
                            && a.hasWindowFocus()));
      } else VideoControlsTest.awaitPresentation(scenario, mode, originalOrientation);
      if (mode == VideoChrome.Mode.FULLSCREEN)
        ui(
            a -> {
              a.videoChrome.setLocked(true);
              return null;
            });
      InstrumentationRegistry.getInstrumentation().waitForIdleSync();
      InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
      await(
          "One Android back did not leave " + mode + " playback and navigate to the previous page",
          10000,
          () ->
              ui(
                  a ->
                      a.player == null
                          && previous.equals(a.current.web.getUrl())
                          && a.current.web.getProgress() == 100));
      ui(
          a -> {
            assertSame("Back must retain the browser tab's WebView", original, a.current.web);
            assertEquals(1, a.tabs.size());
            assertTrue("Web forward history must remain available", a.current.web.canGoForward());
            return null;
          });
      await(
          "Browser window did not recover after leaving playback",
          7000,
          () ->
              ui(
                  a ->
                      a.resumed
                          && a.hasWindowFocus()
                          && a.getResources().getConfiguration().orientation
                              == originalOrientation));
    }
  }

  private void scrollVideoIntoView() throws Exception {
    CountDownLatch scrolled = new CountDownLatch(1);
    ui(
        a -> {
          a.current.web.evaluateJavascript(
              "document.getElementById('video').scrollIntoView({block:'center',behavior:'instant'});"
                  + " true",
              ignored -> scrolled.countDown());
          return null;
        });
    assertTrue("The video scroll request did not execute", scrolled.await(5, TimeUnit.SECONDS));
    await(
        "The DOM video did not reach the visible viewport",
        7000,
        () ->
            ui(
                a ->
                    a.current.videos.values().stream()
                        .anyMatch(
                            t ->
                                t.url.contains("sample-360.mp4")
                                    && t.rect != null
                                    && t.rect.width() > 0
                                    && t.rect.height() > 0
                                    && t.viewportHeight > 0
                                    && t.rect.bottom > 0
                                    && t.rect.top < t.viewportHeight)));
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, long timeoutMs, Ready ready) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeoutMs;
    do {
      if (ready.value()) return;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }
}
