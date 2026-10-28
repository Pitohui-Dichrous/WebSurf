package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.webkit.WebView;
import android.widget.Button;
import androidx.core.graphics.ColorUtils;
import androidx.media3.common.Player;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Real Chromium layout and colors against fixture_server.py; requires port 8765 reverse. */
@RunWith(AndroidJUnit4.class)
public final class ThemeResourceUiTest {
  private static final String BASE = "http://127.0.0.1:8765";
  private static final String BUTTON = "document.querySelector('[data-websurf-control]')";
  private ActivityScenario<MainActivity> scenario;
  private Context context;
  private SharedPreferences browser, appearance;
  private boolean hadAuto, oldAuto, hadDark, oldDark;

  @Before
  public void openFixtureInLightMode() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    browser = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    hadAuto = browser.contains("auto_play");
    oldAuto = browser.getBoolean("auto_play", true);
    hadDark = appearance.contains("dark");
    oldDark = appearance.getBoolean("dark", false);
    assertTrue(browser.edit().putBoolean("auto_play", true).commit());
    assertTrue(appearance.edit().putBoolean("dark", false).commit());
    Ui.configure(context);
    HttpURLConnection health = (HttpURLConnection) new URL(BASE + "/health").openConnection();
    health.setConnectTimeout(3000);
    health.setReadTimeout(3000);
    try {
      assertEquals("Start fixture_server.py and reverse port 8765", 200, health.getResponseCode());
    } finally {
      health.disconnect();
    }
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(BASE + "/?theme-ui=" + System.nanoTime())));
    waitForPage();
  }

  @After
  public void restorePreferences() {
    if (scenario != null) scenario.close();
    if (browser != null) {
      SharedPreferences.Editor edit = browser.edit();
      if (hadAuto) edit.putBoolean("auto_play", oldAuto);
      else edit.remove("auto_play");
      edit.commit();
    }
    if (appearance != null) {
      SharedPreferences.Editor edit = appearance.edit();
      if (hadDark) edit.putBoolean("dark", oldDark);
      else edit.remove("dark");
      edit.commit();
      Ui.configure(context);
    }
  }

  @Test
  public void webpageActionIsBelowTheVideoAndStaysRightAlignedDuringScroll() throws Exception {
    revealWebpageAction();
    assertActionBelowVideo();
    double before = numberJs("window.scrollY").doubleValue();
    js("window.scrollBy(0,70); return true;");
    await(
        "Fixture did not scroll",
        3000,
        () -> numberJs("window.scrollY").doubleValue() > before + 20);
    assertActionBelowVideo();
    assertTrue(
        "The separate action row must not replace the site's next control row",
        boolJs(
            "document.querySelector('[data-websurf-ui]').nextElementSibling.classList.contains('actions')"));
  }

  @Test
  public void webpageActionIsOutsideAnAbsolutePlayersClippingWrapper() throws Exception {
    js(
        "var v=document.getElementById('video'),r=v.getBoundingClientRect(),w=document.createElement('div');"
            + "w.id='absolute-player';w.style.cssText='position:relative;overflow:hidden;width:100%;height:'+r.height+'px';"
            + "v.parentNode.insertBefore(w,v);w.appendChild(v);v.style.cssText='position:absolute;inset:0;width:100%;height:100%';return"
            + " true;");
    revealWebpageAction();
    assertActionBelowVideo();
    assertTrue(
        "The action must escape the clipped player without moving the video",
        boolJs(
            "document.getElementById('video').parentElement.id==='absolute-player' &&"
                + " document.querySelector('[data-websurf-ui]').previousElementSibling.id==='absolute-player'"
                + " && !document.getElementById('absolute-player').contains("
                + BUTTON
                + ")"));
  }

  @Test
  public void webpageAndNativeActionsChangeToReadableColorsWithoutReloading() throws Exception {
    revealWebpageAction();
    WebView web = ui(a -> a.current.web);
    String instance = String.valueOf(js("window.__fixture.instance"));
    int loads = numberJs("window.__fixture.loadCount").intValue();
    applyTheme(false);
    int[] lightWeb = webpageColors("light"), lightNative = nativeColors();
    assertReadable("Light webpage action", lightWeb);
    assertReadable("Light native action", lightNative);
    assertActionBelowVideo();
    awaitScreenshotFrame();
    UiCaptureTest.shot(context, "10-web-mode-light");

    applyTheme(true);
    int[] darkWeb = webpageColors("dark"), darkNative = nativeColors();
    assertReadable("Dark webpage action", darkWeb);
    assertReadable("Dark native action", darkNative);
    assertNotEquals("Webpage background stayed light", lightWeb[0], darkWeb[0]);
    assertNotEquals("Webpage label stayed light", lightWeb[1], darkWeb[1]);
    assertNotEquals("Bottom-bar action background stayed light", lightNative[0], darkNative[0]);
    assertNotEquals("Bottom-bar action label stayed light", lightNative[1], darkNative[1]);
    assertActionBelowVideo();
    awaitScreenshotFrame();
    UiCaptureTest.shot(context, "11-web-mode-dark");

    applyTheme(false);
    assertArrayEquals(
        "Returning to light must restore webpage colors", lightWeb, webpageColors("light"));
    assertArrayEquals("Returning to light must restore native colors", lightNative, nativeColors());
    assertSame("Changing appearance replaced the WebView", web, ui(a -> a.current.web));
    assertEquals(instance, String.valueOf(js("window.__fixture.instance")));
    assertEquals(
        "Changing appearance reloaded the page",
        loads,
        numberJs("window.__fixture.loadCount").intValue());
  }

  @Test
  public void newlyLoadedDocumentReceivesDarkThemeBeforeItsActionIsCreated() throws Exception {
    applyTheme(true);
    ui(
        a -> {
          a.current.web.loadUrl(BASE + "/?theme-ui-new-document=" + System.nanoTime());
          return null;
        });
    await(
        "New dark document did not finish navigation",
        15000,
        () -> String.valueOf(js("location.search")).contains("theme-ui-new-document="));
    waitForPage();
    revealWebpageAction();
    assertReadable("New document dark webpage action", webpageColors("dark"));
    assertReadable("New document dark native action", nativeColors());
    assertActionBelowVideo();
  }

  private void revealWebpageAction() throws Exception {
    tapElement("play");
    await(
        "Native player did not prepare the selected fixture video",
        20000,
        () ->
            ui(
                a ->
                    a.player != null
                        && a.player.getPlayerError() == null
                        && a.player.getPlaybackState() == Player.STATE_READY
                        && a.player.getVideoSize().height == 360));
    ui(
        a -> {
          a.closePlayer(true, true);
          return null;
        });
    await(
        "Closing takeover did not create a webpage reopen action",
        5000,
        () -> boolJs("Boolean(" + BUTTON + ")"));
    js(
        "document.getElementById('video').scrollIntoView({block:'center',behavior:'instant'});return"
            + " true;");
    await(
        "Webpage action was not laid out",
        5000,
        () -> boolJs(BUTTON + ".getBoundingClientRect().height>=44"));
  }

  private void assertActionBelowVideo() throws Exception {
    await(
        "Reopen action overlaps the video or is not aligned to its lower right",
        5000,
        () -> {
          JSONObject r =
              (JSONObject)
                  js(
                      "var v=document.getElementById('video').getBoundingClientRect(),"
                          + "b="
                          + BUTTON
                          + ",r=b.getBoundingClientRect(),rail=b.parentElement.getBoundingClientRect();return"
                          + " {videoBottom:v.bottom,videoRight:v.right,top:r.top,right:r.right,width:r.width,height:r.height,"
                          + "railTop:rail.top,railHeight:rail.height};");
          return r.getDouble("top") >= r.getDouble("videoBottom") - .5
              && r.getDouble("railTop") >= r.getDouble("videoBottom") - .5
              && Math.abs(r.getDouble("right") - r.getDouble("videoRight")) <= 2
              && r.getDouble("height") >= 44
              && r.getDouble("width") >= 44
              && r.getDouble("railHeight") >= 48;
        });
  }

  private void awaitScreenshotFrame() throws Exception {
    // Computed CSS and Drawable state change before RenderThread/WebView commit their surfaces.
    // Await two real display frames, then allow the compositor to present both native and web UI.
    CountDownLatch frames = new CountDownLatch(1);
    ui(
        a -> {
          android.view.View decor = a.getWindow().getDecorView();
          decor.invalidate();
          decor.postOnAnimation(() -> decor.postOnAnimation(frames::countDown));
          return null;
        });
    assertTrue(
        "Display frames did not advance before screenshot", frames.await(3, TimeUnit.SECONDS));
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(150);
  }

  private void applyTheme(boolean dark) {
    ui(
        a -> {
          Ui.setDark(a, dark);
          Ui.configure(a);
          a.applyChromeTheme();
          return null;
        });
  }

  private int[] webpageColors(String theme) throws Exception {
    await(
        "Webpage action did not receive the app's " + theme + " appearance",
        5000,
        () -> theme.equals(String.valueOf(js(BUTTON + ".getAttribute('data-websurf-theme')"))));
    JSONObject colors =
        (JSONObject)
            js(
                "var s=getComputedStyle("
                    + BUTTON
                    + ");return"
                    + " {background:s.backgroundColor,foreground:s.color,opacity:s.opacity};");
    assertEquals("Webpage label must remain opaque", 1.0, colors.getDouble("opacity"), .001);
    return new int[] {
      cssColor(colors.getString("background")), cssColor(colors.getString("foreground"))
    };
  }

  private int[] nativeColors() {
    return ui(
        a -> {
          Button button = a.takeoverButton;
          assertTrue("The bottom-bar takeover action must be enabled", button.isEnabled());
          assertEquals(1f, button.getAlpha(), .001f);
          Drawable background = button.getBackground();
          assertNotNull(background);
          int width = Math.max(32, button.getWidth()), height = Math.max(32, button.getHeight());
          Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
          Rect oldBounds = new Rect(background.getBounds());
          try {
            background.setBounds(0, 0, width, height);
            background.draw(new Canvas(bitmap));
            return new int[] {bitmap.getPixel(width / 2, height / 2), button.getCurrentTextColor()};
          } finally {
            background.setBounds(oldBounds);
            bitmap.recycle();
          }
        });
  }

  private static void assertReadable(String label, int[] colors) {
    assertEquals(label + " background must be opaque", 255, Color.alpha(colors[0]));
    assertEquals(label + " foreground must be opaque", 255, Color.alpha(colors[1]));
    double contrast = ColorUtils.calculateContrast(colors[1], colors[0]);
    assertTrue(label + " needs text contrast >= 4.5; got " + contrast, contrast >= 4.5);
  }

  private static int cssColor(String css) {
    Matcher values = Pattern.compile("[\\d.]+").matcher(css);
    float[] channels = new float[] {0, 0, 0, 1};
    int index = 0;
    while (values.find() && index < channels.length)
      channels[index++] = Float.parseFloat(values.group());
    assertTrue("Unexpected Chromium computed color: " + css, index >= 3);
    return Color.argb(
        Math.round(channels[3] * 255),
        Math.round(channels[0]),
        Math.round(channels[1]),
        Math.round(channels[2]));
  }

  private void waitForPage() throws Exception {
    await(
        "Fixture document and its real observer did not load",
        20000,
        () ->
            boolJs(
                "Boolean(window.__fixture&&window.__websurf&&document.getElementById('video')"
                    + "&&document.getElementById('video').readyState>=1)"));
  }

  private void tapElement(String id) throws Exception {
    String element = "document.getElementById(" + JSONObject.quote(id) + ")";
    js(element + ".scrollIntoView({block:'center',behavior:'instant'});return true;");
    SystemClock.sleep(180);
    JSONObject r =
        (JSONObject)
            js(
                "var r="
                    + element
                    + ".getBoundingClientRect();"
                    + "return {x:r.left+r.width/2,y:r.top+r.height/2,vw:window.innerWidth};");
    float[] at =
        ui(
            a -> {
              int[] position = new int[2];
              a.current.web.getLocationOnScreen(position);
              return new float[] {position[0], position[1], a.current.web.getWidth()};
            });
    float scale = at[2] / (float) r.getDouble("vw");
    float x = at[0] + (float) r.getDouble("x") * scale;
    float y = at[1] + (float) r.getDouble("y") * scale;
    long down = SystemClock.uptimeMillis();
    MotionEvent press = MotionEvent.obtain(down, down, MotionEvent.ACTION_DOWN, x, y, 0);
    MotionEvent release = MotionEvent.obtain(down, down + 70, MotionEvent.ACTION_UP, x, y, 0);
    try {
      InstrumentationRegistry.getInstrumentation().sendPointerSync(press);
      SystemClock.sleep(70);
      InstrumentationRegistry.getInstrumentation().sendPointerSync(release);
    } finally {
      press.recycle();
      release.recycle();
    }
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> value = new AtomicReference<>();
    scenario.onActivity(a -> value.set(action.apply(a)));
    return value.get();
  }

  private Object js(String script) throws Exception {
    assertNotEquals(
        "Do not await JavaScript on the UI thread",
        android.os.Looper.getMainLooper(),
        android.os.Looper.myLooper());
    CountDownLatch done = new CountDownLatch(1);
    AtomicReference<String> response = new AtomicReference<>();
    scenario.onActivity(
        a ->
            a.current.web.evaluateJavascript(
                "(function(){"
                    + (script.contains(";") ? script : "return (" + script + ");")
                    + "})()",
                value -> {
                  response.set(value);
                  done.countDown();
                }));
    assertTrue("JavaScript callback timed out: " + script, done.await(5, TimeUnit.SECONDS));
    String raw = response.get();
    return raw == null || raw.equals("null") ? JSONObject.NULL : new JSONTokener(raw).nextValue();
  }

  private Number numberJs(String script) throws Exception {
    Object value = js(script);
    assertTrue("Expected number for " + script + ", received " + value, value instanceof Number);
    return (Number) value;
  }

  private boolean boolJs(String script) throws Exception {
    return Boolean.TRUE.equals(js(script));
  }

  private interface Condition {
    boolean ready() throws Exception;
  }

  private void await(String message, long timeout, Condition condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + timeout;
    do {
      if (condition.ready()) return;
      SystemClock.sleep(80);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }
}
