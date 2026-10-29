package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Rect;
import android.net.Uri;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.media3.common.Player;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONObject;
import org.json.JSONTokener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Real WebView + Media3 integration against scripts/fixture_server.py. Required: adb -s <emulator>
 * reverse tcp:8765 tcp:8765 before instrumentation. Playback is triggered by real pointer events,
 * never fabricated observer messages. JavaScript callbacks are awaited off the UI thread.
 */
@RunWith(AndroidJUnit4.class)
public final class BrowserFlowTest {
  private static final String BASE = "http://127.0.0.1:8765";
  private ActivityScenario<MainActivity> scenario;
  private Context context;
  private SharedPreferences preferences;
  private boolean hadHomepage, hadAutoPlay;
  private String originalHomepage;
  private boolean originalAutoPlay;

  @Before
  public void openLocalFixture() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    hadHomepage = preferences.contains("homepage");
    originalHomepage = preferences.getString("homepage", "https://www.baidu.com");
    hadAutoPlay = preferences.contains("auto_play");
    originalAutoPlay = preferences.getBoolean("auto_play", true);
    assertTrue(
        "Could not enable the test's automatic takeover setting",
        preferences.edit().putBoolean("auto_play", true).commit());
    HttpURLConnection health = (HttpURLConnection) new URL(BASE + "/health").openConnection();
    health.setConnectTimeout(3000);
    health.setReadTimeout(3000);
    try {
      assertEquals(
          "Start fixture_server.py and configure adb reverse tcp:8765 tcp:8765",
          200,
          health.getResponseCode());
    } finally {
      health.disconnect();
    }
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class)
                .setData(Uri.parse(BASE + "/?browser-flow=" + System.nanoTime())));
    waitForPage();
  }

  @After
  public void closeAndRestorePreferences() {
    if (scenario != null) scenario.close();
    if (preferences != null) {
      SharedPreferences.Editor edit = preferences.edit();
      if (hadHomepage) edit.putString("homepage", originalHomepage);
      else edit.remove("homepage");
      if (hadAutoPlay) edit.putBoolean("auto_play", originalAutoPlay);
      else edit.remove("auto_play");
      edit.commit();
    }
  }

  @Test
  public void nativeTakeoverClosesWithoutReloadAndCanImmediatelyReopenAt720p() throws Exception {
    WebView originalWeb = ui(activity -> activity.current.web);
    String instance = stringJs("window.__fixture.instance");
    int loadCount = numberJs("window.__fixture.loadCount").intValue();
    String marker = "Keep this text: WebSurf 720p";
    js("document.getElementById('note').value=" + JSONObject.quote(marker) + "; true");
    tapElement("play");
    awaitNativeHeight(360);
    assertEquals(VideoChrome.Mode.INLINE, ui(activity -> activity.playerMode));
    assertSame(
        ui(activity -> activity.anchorClip), ui(activity -> activity.videoChrome.getParent()));
    assertSame(
        ui(activity -> activity.webContainer), ui(activity -> activity.anchorClip.getParent()));
    assertTrue(
        ui(
            activity ->
                activity.playerView.getVideoSurfaceView() instanceof android.view.TextureView));
    await(
        "The page did not receive native playback state",
        5000,
        () ->
            boolJs(
                "!document.getElementById('video').paused &&"
                    + " document.getElementById('video').currentTime>0.1"));
    double scroll = numberJs("window.scrollY").doubleValue();
    long nativePosition = ui(activity -> activity.player.getCurrentPosition());
    int playRequests = numberJs("window.__fixture.playRequests").intValue();
    ui(
        activity -> {
          activity.closePlayer(true, true);
          return null;
        });
    await(
        "The existing web video did not resume",
        5000,
        () -> boolJs("!document.getElementById('video').paused"));
    assertNull(ui(activity -> activity.videoChrome));
    assertSame(originalWeb, ui(activity -> activity.current.web));
    assertEquals(instance, stringJs("window.__fixture.instance"));
    assertEquals(loadCount, numberJs("window.__fixture.loadCount").intValue());
    assertEquals(marker, stringJs("document.getElementById('note').value"));
    assertEquals(scroll, numberJs("window.scrollY").doubleValue(), 2.0);
    assertTrue(
        numberJs("document.getElementById('video').currentTime").doubleValue()
            >= Math.max(0, nativePosition / 1000.0 - .5));
    assertNoTakeoverFor(400);

    // Re-enable using the persistent switch, with no new HTML play gesture or page reload.
    ui(
        activity -> {
          assertTrue(activity.takeoverButton.performClick());
          return null;
        });
    awaitNativeHeight(360);
    assertEquals(playRequests, numberJs("window.__fixture.playRequests").intValue());
    ui(
        activity -> {
          activity.closePlayer(true, true);
          return null;
        });
    js(
        "document.getElementById('quality').value='mp4-720';document.getElementById('quality').dispatchEvent(new"
            + " Event('change',{bubbles:true})); true");
    await(
        "The original page's 720p selector did not load",
        10000,
        () ->
            boolJs(
                "document.getElementById('video').readyState>=2 &&"
                    + " document.getElementById('video').videoHeight===720"));
    assertNoTakeoverFor(400);
    ui(
        activity -> {
          assertTrue(activity.takeoverButton.performClick());
          return null;
        });
    awaitNativeHeight(720);
    assertTrue(ui(activity -> activity.activeTarget.url.contains("sample-720.mp4?quality=720")));
    assertEquals(playRequests, numberJs("window.__fixture.playRequests").intValue());
    assertSame(originalWeb, ui(activity -> activity.current.web));
    assertEquals(instance, stringJs("window.__fixture.instance"));
    assertEquals(marker, stringJs("document.getElementById('note').value"));
  }

  @Test
  public void automaticAdsNeverStealTheExplicitlyChosenVideo() throws Exception {
    loadMultiFixture();
    await(
        "Independent autoplay ad must really be playing",
        10000,
        () ->
            boolJs(
                "!document.getElementById('ad').paused &&"
                    + " document.getElementById('ad').currentTime>0.1"));
    assertNoNativeFor(600);
    tapElement("play-main");
    awaitNativeHeight(360);
    assertEquals(1, numberJs("window.__fixture.trustedClicks").intValue());
    assertTrue(ui(activity -> activity.activeTarget.url.contains("slot=main&")));
    String mainKey = ui(activity -> activity.activeTarget.key());
    assertEquals(VideoChrome.Mode.INLINE, ui(activity -> activity.playerMode));
    js("window.__fixture.startLateAd(); true");
    await(
        "Later autoplay ad did not start",
        5000,
        () ->
            boolJs(
                "!document.getElementById('late-ad').paused &&"
                    + " document.getElementById('late-ad').currentTime>0.1"));
    SystemClock.sleep(600);
    assertEquals(
        "Later automatic media replaced the user's video",
        mainKey,
        ui(activity -> activity.activeTarget.key()));
    assertTrue(
        "Native takeover globally paused unrelated media",
        boolJs("!document.getElementById('ad').paused"));
    tapElement("play-other", true);
    awaitNativeHeight(720);
    assertEquals(2, numberJs("window.__fixture.trustedClicks").intValue());
    assertTrue(ui(activity -> activity.activeTarget.url.contains("slot=other")));
    assertNotEquals(mainKey, ui(activity -> activity.activeTarget.key()));
    assertEquals(VideoChrome.Mode.INLINE, ui(activity -> activity.playerMode));
    assertTrue(boolJs("!document.getElementById('ad').paused"));
  }

  @Test
  public void prerollEndedSwitchesTheSameElementToMainWithoutAnotherGesture() throws Exception {
    loadMultiFixture();
    tapElement("play-preroll");
    awaitNativeHeight(360);
    String key = ui(activity -> activity.activeTarget.key());
    long sourceVersion = ui(activity -> activity.activeTarget.sourceVersion);
    assertTrue(ui(activity -> activity.activeTarget.url.contains("slot=preroll")));
    ui(
        activity -> {
          activity.player.seekTo(Math.max(0, activity.player.getDuration() - 650));
          return null;
        });
    await(
        "Native ended did not reach the page's preroll handler",
        10000,
        () -> numberJs("window.__fixture.prerollEnded").intValue() == 1);
    awaitNativeHeight(720);
    assertEquals(key, ui(activity -> activity.activeTarget.key()));
    assertTrue(ui(activity -> activity.activeTarget.sourceVersion > sourceVersion));
    assertTrue(ui(activity -> activity.activeTarget.url.contains("slot=main-after-preroll")));
    assertEquals(1, numberJs("window.__fixture.trustedClicks").intValue());
    assertEquals(1, numberJs("window.__fixture.prerollRequests").intValue());
    assertEquals(VideoChrome.Mode.INLINE, ui(activity -> activity.playerMode));
    assertTrue(boolJs("!document.getElementById('preroll').paused"));
  }

  @Test
  public void presentationModesKeepOnePlayerAndInlineFollowsDocumentGeometry() throws Exception {
    loadMultiFixture();
    tapElement("play-main");
    awaitNativeHeight(360);
    androidx.media3.exoplayer.ExoPlayer session = ui(activity -> activity.player);
    VideoChrome chrome = ui(activity -> activity.videoChrome);
    View surface = ui(activity -> activity.playerView.getVideoSurfaceView());
    ui(
        activity -> {
          activity.player.pause();
          return null;
        });
    await(
        "Pause did not synchronize to the page",
        5000,
        () -> boolJs("document.getElementById('video').paused"));
    long position = ui(activity -> activity.player.getCurrentPosition());
    int originalOrientation =
        ui(activity -> activity.getResources().getConfiguration().orientation);
    int originalRequestedOrientation = ui(MainActivity::getRequestedOrientation);
    for (VideoChrome.Mode mode :
        new VideoChrome.Mode[] {
          VideoChrome.Mode.FULLSCREEN, VideoChrome.Mode.FLOATING, VideoChrome.Mode.INLINE
        }) {
      ui(
          activity -> {
            activity.setPlayerMode(mode);
            return null;
          });
      VideoControlsTest.awaitPresentation(scenario, mode, originalOrientation);
      assertSame(
          "Switching presentation replaced the player", session, ui(activity -> activity.player));
      assertSame(chrome, ui(activity -> activity.videoChrome));
      assertSame(surface, ui(activity -> activity.playerView.getVideoSurfaceView()));
      assertEquals(position, ui(activity -> activity.player.getCurrentPosition()), 150);
      assertFalse(ui(activity -> activity.player.getPlayWhenReady()));
      if (mode == VideoChrome.Mode.FULLSCREEN) {
        ui(
            activity -> {
              activity.videoChrome.setLocked(true);
              return null;
            });
        assertTrue(ui(activity -> activity.videoChrome.isLocked()));
        ui(
            activity -> {
              activity.videoChrome.setLocked(false);
              return null;
            });
      }
    }
    assertEquals(
        originalRequestedOrientation, ui(MainActivity::getRequestedOrientation).intValue());
    assertSame(
        ui(activity -> activity.webContainer), ui(activity -> activity.anchorClip.getParent()));
    js(
        "document.getElementById('video').scrollIntoView({block:'center',behavior:'instant'});"
            + " true");
    awaitInlineRect();
    int oldTop =
        ui(
            activity -> {
              int[] at = new int[2];
              activity.videoChrome.getLocationOnScreen(at);
              return at[1];
            });
    double oldScroll = numberJs("window.scrollY").doubleValue();
    js("window.scrollBy(0,60); true");
    awaitInlineRect();
    double moved = numberJs("window.scrollY").doubleValue() - oldScroll;
    assertTrue("Fixture must actually scroll", moved > 20);
    float scale =
        ui(activity -> activity.current.web.getWidth())
            / numberJs("window.innerWidth").floatValue();
    int newTop =
        ui(
            activity -> {
              int[] at = new int[2];
              activity.videoChrome.getLocationOnScreen(at);
              return at[1];
            });
    assertEquals(oldTop - moved * scale, newTop, 4);
    assertSame(session, ui(activity -> activity.player));
  }

  @Test
  public void mutedTakeoverKeepsLogicalVolumeAndRestoresItWhenPageUnmutes() throws Exception {
    js(
        "document.getElementById('video').volume=0.7;document.getElementById('video').muted=true;"
            + " true");
    js(
        "document.getElementById('video').scrollIntoView({block:'center',behavior:'instant'});"
            + " true");
    await(
        "The muted video did not become visible before manual takeover",
        5000,
        () ->
            boolJs(
                    "return (function(){var"
                        + " r=document.getElementById('video').getBoundingClientRect();return"
                        + " r.width>0&&r.top>=0&&r.bottom<=window.innerHeight;})()")
                && ui(
                    activity ->
                        activity.current.videos.values().stream()
                            .anyMatch(
                                target ->
                                    target.url.contains("sample-360.mp4")
                                        && target.rect.height() > 0
                                        && target.clip.height() >= target.rect.height() * .9f)));
    await(
        "Muted page volume was not observed before manual takeover",
        5000,
        () ->
            ui(
                activity ->
                    activity.current.videos.values().stream()
                        .anyMatch(
                            target ->
                                target.url.contains("sample-360.mp4")
                                    && target.muted
                                    && Math.abs(target.volume - .7) < .001)));
    ui(
        activity -> {
          assertTrue(activity.takeoverButton.performClick());
          return null;
        });
    awaitNativeHeight(360);
    assertEquals(0f, ui(activity -> activity.player.getVolume()), .001f);
    assertEquals(.7, numberJs("document.getElementById('video').volume").doubleValue(), .001);
    assertTrue(boolJs("document.getElementById('video').muted"));
    js("document.getElementById('video').muted=false; true");
    await(
        "Unmuting the page did not restore its previous gain",
        5000,
        () ->
            ui(
                activity ->
                    activity.player != null
                        && Math.abs(activity.player.getVolume() - .7f) < .001f));
    assertFalse(boolJs("document.getElementById('video').muted"));
    assertEquals(.7, numberJs("document.getElementById('video').volume").doubleValue(), .001);
  }

  @Test
  public void changingThePausedPageSourceKeepsNativePlaybackPaused() throws Exception {
    tapElement("play");
    awaitNativeHeight(360);
    String key = ui(activity -> activity.activeTarget.key());
    long sourceVersion = ui(activity -> activity.activeTarget.sourceVersion);
    js("document.getElementById('video').pause(); true");
    await(
        "Page pause did not reach native playback",
        5000,
        () -> ui(activity -> activity.player != null && !activity.player.getPlayWhenReady()));
    assertTrue(boolJs("document.getElementById('video').paused"));
    js(
        "document.getElementById('quality').value='mp4-720';document.getElementById('quality').dispatchEvent(new"
            + " Event('change',{bubbles:true})); true");
    await(
        "Paused 720p source was not prepared natively",
        15000,
        () ->
            ui(
                activity ->
                    activity.player != null
                        && activity.player.getPlayerError() == null
                        && activity.player.getPlaybackState() == Player.STATE_READY
                        && activity.player.getVideoSize().height == 720
                        && activity.activeTarget != null
                        && activity.activeTarget.sourceVersion > sourceVersion));
    assertEquals(key, ui(activity -> activity.activeTarget.key()));
    assertTrue(ui(activity -> activity.activeTarget.url.contains("sample-720.mp4")));
    long position = ui(activity -> activity.player.getCurrentPosition());
    long end = SystemClock.elapsedRealtime() + 700;
    do {
      assertFalse(
          "Changing source resumed a paused native session",
          ui(activity -> activity.player.getPlayWhenReady()));
      assertTrue(
          "Changing source resumed the page's logical player",
          boolJs("document.getElementById('video').paused"));
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < end);
    assertEquals(position, ui(activity -> activity.player.getCurrentPosition()), 150);
  }

  @Test
  public void removingDynamicIframeReleasesItsPlayerAndAReplacementCanTakeOver() throws Exception {
    createDynamicFrame();
    tapFrameElement("dynamic-frame", "frame-play");
    awaitNativeHeight(360);
    assertTrue(ui(activity -> activity.activeTarget.url.contains("slot=embedded")));
    assertEquals(VideoChrome.Mode.INLINE, ui(activity -> activity.playerMode));
    String oldKey = ui(activity -> activity.activeTarget.key());
    String oldDocument = ui(activity -> activity.activeTarget.documentId);
    String firstInstance =
        stringJs("document.getElementById('dynamic-frame').contentWindow.__frameFixture.instance");
    assertEquals(
        1,
        numberJs(
                "document.getElementById('dynamic-frame').contentWindow.__frameFixture.trustedClicks")
            .intValue());
    js("document.getElementById('dynamic-frame').remove(); true");
    await(
        "Removing an iframe left a stale native player or media target",
        6000,
        () ->
            ui(
                activity ->
                    activity.player == null
                        && activity.videoChrome == null
                        && !activity.current.videos.containsKey(oldKey)
                        && activity.current.videos.values().stream()
                            .noneMatch(target -> oldDocument.equals(target.documentId))));
    assertTrue(
        "Removing the frame also removed the top-level video",
        ui(
            activity ->
                activity.current.videos.values().stream()
                    .anyMatch(target -> target.url.contains("quality=360"))));
    createDynamicFrame();
    assertNotEquals(
        firstInstance,
        stringJs("document.getElementById('dynamic-frame').contentWindow.__frameFixture.instance"));
    tapFrameElement("dynamic-frame", "frame-play");
    awaitNativeHeight(360);
    assertTrue(ui(activity -> activity.activeTarget.url.contains("slot=embedded")));
    assertNotEquals(oldDocument, ui(activity -> activity.activeTarget.documentId));
    assertNotEquals(oldKey, ui(activity -> activity.activeTarget.key()));
    assertEquals(
        1,
        numberJs(
                "document.getElementById('dynamic-frame').contentWindow.__frameFixture.trustedClicks")
            .intValue());
    assertEquals(VideoChrome.Mode.INLINE, ui(activity -> activity.playerMode));
  }

  @Test
  public void actualPageResourcesAreClassifiedAndFilterable() throws Exception {
    await(
        "Expected video, audio and image requests were not observed",
        10000,
        () ->
            ui(
                activity -> {
                  boolean video = false, audio = false, image = false;
                  for (WebResource resource : activity.current.resources.values()) {
                    if (resource.url.contains("sample-360.mp4") && "视频".equals(resource.kind))
                      video = true;
                    if (resource.url.contains("audio-only.m4a") && "音频".equals(resource.kind))
                      audio = true;
                    if (resource.url.contains("cover.png") && "图片".equals(resource.kind))
                      image = true;
                  }
                  return video && audio && image;
                }));
    List<WebResource> resources =
        ui(activity -> new ArrayList<>(activity.current.resources.values()));
    for (WebResource resource : resources) {
      if (resource.url.contains("audio-only.m4a") || resource.url.contains("cover.png"))
        assertFalse(
            "Image/audio was incorrectly presented as video: " + resource.url, resource.isVideo());
    }
    ui(
        activity -> {
          activity.showResources("视频");
          return null;
        });
    assertTrue(sheetContains("sample-360.mp4"));
    assertFalse(sheetContains("audio-only.m4a"));
    assertFalse(sheetContains("cover.png"));

    clickSheetButton("音频");
    assertTrue(sheetContains("audio-only.m4a"));
    assertFalse(sheetContains("sample-360.mp4"));
    assertFalse(sheetContains("cover.png"));
    clickSheetButton("图片");
    assertTrue(sheetContains("cover.png"));
    assertFalse(sheetContains("sample-360.mp4"));
    assertFalse(sheetContains("audio-only.m4a"));
    clickSheetButton("全部");
    assertTrue(sheetContains("sample-360.mp4"));
    assertTrue(sheetContains("audio-only.m4a"));
    assertTrue(sheetContains("cover.png"));
  }

  @Test
  public void savingHomepagePreservesCurrentDocumentAndSurvivesRelaunch() throws Exception {
    WebView originalWeb = ui(activity -> activity.current.web);
    String instance = stringJs("window.__fixture.instance");
    String currentUrl = ui(activity -> activity.current.url);
    js("document.getElementById('note').value='settings preserve this'; true");
    String savedHome = BASE + "/?saved-home=browser-flow";
    ui(
        activity -> {
          activity.showSettings();
          View home = findDescription(activity.activeSheet.getWindow().getDecorView(), "主页地址");
          assertTrue("Settings must expose a homepage address input", home instanceof EditText);
          ((EditText) home).setText(savedHome);
          Button save = findButton(activity.activeSheet.getWindow().getDecorView(), "保存主页");
          assertNotNull("Settings must expose Save homepage", save);
          assertTrue(save.performClick());
          return null;
        });
    await(
        "Homepage setting was not saved", 5000, () -> savedHome.equals(ui(MainActivity::homepage)));
    assertEquals(savedHome, preferences.getString("homepage", ""));
    ui(
        activity -> {
          activity.activeSheet.dismiss();
          return null;
        });
    assertSame(originalWeb, ui(activity -> activity.current.web));
    assertEquals(currentUrl, ui(activity -> activity.current.url));
    assertEquals(instance, stringJs("window.__fixture.instance"));
    assertEquals("settings preserve this", stringJs("document.getElementById('note').value"));

    scenario.close();
    scenario = ActivityScenario.launch(new Intent(context, MainActivity.class));
    waitForPage();
    assertEquals(
        "A new launch must use the persisted homepage",
        savedHome,
        ui(activity -> activity.current.web.getUrl()));
  }

  @Test
  public void switchingAndClosingTabsPreservesBothExistingWebViews() throws Exception {
    BrowserTab first = ui(activity -> activity.current);
    WebView firstWeb = first.web;
    String firstInstance = stringJs("window.__fixture.instance");
    js("document.getElementById('note').value='first tab state'; window.scrollTo(0,180); true");
    double firstScroll = numberJs("window.scrollY").doubleValue();
    BrowserTab second = ui(activity -> activity.newTab(BASE + "/?second-tab=browser-flow", true));
    assertNotNull(second);
    waitForPage();
    WebView secondWeb = second.web;
    assertNotSame(firstWeb, secondWeb);
    String secondInstance = stringJs("window.__fixture.instance");
    js("document.getElementById('note').value='second tab state'; true");

    ui(
        activity -> {
          activity.selectTab(first);
          return null;
        });
    assertSame(firstWeb, ui(activity -> activity.current.web));
    assertEquals(firstInstance, stringJs("window.__fixture.instance"));
    assertEquals("first tab state", stringJs("document.getElementById('note').value"));
    assertEquals(firstScroll, numberJs("window.scrollY").doubleValue(), 2.0);
    ui(
        activity -> {
          activity.selectTab(second);
          return null;
        });
    assertSame(secondWeb, ui(activity -> activity.current.web));
    assertEquals(secondInstance, stringJs("window.__fixture.instance"));
    assertEquals("second tab state", stringJs("document.getElementById('note').value"));
    ui(
        activity -> {
          activity.closeTab(second);
          return null;
        });
    assertSame(firstWeb, ui(activity -> activity.current.web));
    assertEquals(1, ui(activity -> activity.tabs.size()).intValue());
    assertEquals(firstInstance, stringJs("window.__fixture.instance"));
    assertEquals("first tab state", stringJs("document.getElementById('note').value"));
  }

  private void createDynamicFrame() throws Exception {
    js(
        "var f=document.createElement('iframe');f.id='dynamic-frame';f.title='动态测试视频';"
            + "f.src='/frame.html?instance='+Date.now();f.allow='autoplay; fullscreen';"
            + "f.style.cssText='display:block;width:100%;height:300px;border:0;margin:12px 0';"
            + "document.body.insertBefore(f,document.body.firstChild); true");
    await(
        "Dynamic iframe did not load its video and observer",
        15000,
        () ->
            boolJs(
                "Boolean(document.getElementById('dynamic-frame') &&"
                    + " document.getElementById('dynamic-frame').contentWindow.__frameFixture &&"
                    + " document.getElementById('dynamic-frame').contentWindow.__websurf &&"
                    + " document.getElementById('dynamic-frame').contentDocument.getElementById('frame-video').readyState>=1)"));
  }

  private void tapFrameElement(String frameId, String elementId) throws Exception {
    String frame = "document.getElementById(" + JSONObject.quote(frameId) + ")";
    js(frame + ".scrollIntoView({block:'center',behavior:'instant'}); true");
    SystemClock.sleep(180);
    JSONObject rect =
        (JSONObject)
            js(
                "return (function(){var f="
                    + frame
                    + ",a=f.getBoundingClientRect(),"
                    + "b=f.contentDocument.getElementById("
                    + JSONObject.quote(elementId)
                    + ").getBoundingClientRect();return"
                    + " {x:a.left+f.clientLeft+b.left+b.width/2,y:a.top+f.clientTop+b.top+b.height/2,vw:window.innerWidth};})()");
    tapViewportPoint(rect);
  }

  private void loadMultiFixture() throws Exception {
    ui(
        activity -> {
          activity.current.web.loadUrl(BASE + "/multi.html?test=" + System.nanoTime());
          return null;
        });
    await(
        "Multi-video document not ready",
        20000,
        () ->
            boolJs(
                "Boolean(window.__fixture && window.__websurf && document.getElementById('other')"
                    + " && document.getElementById('video').readyState>=1 &&"
                    + " document.getElementById('preroll').readyState>=1)"));
  }

  private void awaitNativeHeight(int height) throws Exception {
    await(
        "Native " + height + "p video did not play",
        20000,
        () ->
            ui(
                activity ->
                    activity.player != null
                        && activity.player.getPlayerError() == null
                        && activity.player.getPlaybackState() == Player.STATE_READY
                        && activity.player.isPlaying()
                        && activity.player.getVideoSize().height == height
                        && activity.player.getCurrentPosition() >= 100));
  }

  private void assertNoNativeFor(long milliseconds) {
    long end = SystemClock.elapsedRealtime() + milliseconds;
    do {
      assertNull(
          "Automatic media opened the native player without a user gesture",
          ui(activity -> activity.player));
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < end);
  }

  private void tapElement(String id) throws Exception {
    tapElement(id, false);
  }

  private void tapElement(String id, boolean avoidPinnedPlayer) throws Exception {
    String element = "document.getElementById(" + JSONObject.quote(id) + ")";
    js(element + ".scrollIntoView({block:'center',behavior:'instant'}); true");
    SystemClock.sleep(180);
    JSONObject rect = elementCenter(element);
    if (avoidPinnedPlayer) {
      // A pinned 16:9 player can extend below the viewport's midpoint in short browser windows.
      // Reveal the actual HTML button below it instead of sending a tap through the native player.
      if (nativePlayerCovers(id, "center", rect)) {
        js(element + ".scrollIntoView({block:'end',behavior:'instant'}); true");
        SystemClock.sleep(180);
        rect = elementCenter(element);
      }
      assertFalse(
          "The pinned native player still obscures the real HTML button " + id,
          nativePlayerCovers(id, "final", rect));
    }
    tapViewportPoint(rect);
  }

  private JSONObject elementCenter(String element) throws Exception {
    return (JSONObject)
        js(
            "return JSON.parse(JSON.stringify((function(){var r="
                + element
                + ".getBoundingClientRect();return"
                + " {x:r.left+r.width/2,y:r.top+r.height/2,vw:window.innerWidth,vh:window.innerHeight};})()))");
  }

  private boolean nativePlayerCovers(String id, String alignment, JSONObject center) {
    return ui(
        activity -> {
          WebView web = activity.current.web;
          int[] at = new int[2];
          web.getLocationOnScreen(at);
          float scale = web.getWidth() / (float) center.optDouble("vw");
          int x = Math.round(at[0] + (float) center.optDouble("x") * scale);
          int y = Math.round(at[1] + (float) center.optDouble("y") * scale);
          Rect clip = new Rect(), chrome = new Rect();
          boolean clipVisible =
              activity.anchorClip != null
                  && activity.anchorClip.isShown()
                  && activity.anchorClip.getGlobalVisibleRect(clip);
          boolean chromeVisible =
              activity.videoChrome != null
                  && activity.videoChrome.isShown()
                  && activity.videoChrome.getGlobalVisibleRect(chrome);
          boolean covered =
              clipVisible && chromeVisible && clip.contains(x, y) && chrome.contains(x, y);
          String diagnostic =
              "HTML tap "
                  + id
                  + " ("
                  + alignment
                  + "): web="
                  + web.getWidth()
                  + "x"
                  + web.getHeight()
                  + ", CSS="
                  + center
                  + ", screen=("
                  + x
                  + ","
                  + y
                  + "), anchorVisible="
                  + clipVisible
                  + ":"
                  + clip
                  + ", playerVisible="
                  + chromeVisible
                  + ":"
                  + chrome
                  + ", pinned="
                  + activity.inlinePinned
                  + ", covered="
                  + covered;
          System.out.println(diagnostic);
          android.util.Log.i("WebSurfTest", diagnostic);
          return covered;
        });
  }

  private void tapViewportPoint(JSONObject rect) throws Exception {
    float[] location =
        ui(
            activity -> {
              int[] at = new int[2];
              activity.current.web.getLocationOnScreen(at);
              return new float[] {at[0], at[1], activity.current.web.getWidth()};
            });
    float scale = location[2] / (float) rect.getDouble("vw");
    float x = location[0] + (float) rect.getDouble("x") * scale;
    float y = location[1] + (float) rect.getDouble("y") * scale;
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

  private void awaitInlineRect() throws Exception {
    await(
        "Native video did not follow the page's actual bounding rectangle",
        5000,
        () -> {
          JSONObject rect =
              (JSONObject)
                  js(
                      "return JSON.parse(JSON.stringify((function(){var"
                          + " r=document.getElementById('video').getBoundingClientRect();return"
                          + " {x:r.left,y:r.top,w:r.width,h:r.height,vw:window.innerWidth};})()))");
          return ui(
              activity -> {
                int[] at = new int[2], web = new int[2];
                activity.videoChrome.getLocationOnScreen(at);
                activity.current.web.getLocationOnScreen(web);
                float scale = activity.current.web.getWidth() / (float) rect.optDouble("vw");
                return Math.abs(at[0] - web[0] - rect.optDouble("x") * scale) < 4
                    && Math.abs(at[1] - web[1] - rect.optDouble("y") * scale) < 4
                    && Math.abs(activity.videoChrome.getWidth() - rect.optDouble("w") * scale) < 4
                    && Math.abs(activity.videoChrome.getHeight() - rect.optDouble("h") * scale) < 4;
              });
        });
  }

  private void waitForPage() throws Exception {
    await(
        "Local fixture document/observer did not become ready; check server and adb reverse",
        20000,
        () ->
            boolJs(
                "Boolean(window.__fixture && window.__websurf && document.getElementById('video')"
                    + " && document.getElementById('video').readyState>=1)"));
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> value = new AtomicReference<>();
    scenario.onActivity(activity -> value.set(action.apply(activity)));
    return value.get();
  }

  private Object js(String script) throws Exception {
    assertNotEquals(
        "Never await a JavaScript callback on the UI thread",
        android.os.Looper.getMainLooper(),
        android.os.Looper.myLooper());
    AtomicReference<String> value = new AtomicReference<>();
    CountDownLatch result = new CountDownLatch(1);
    scenario.onActivity(
        activity ->
            activity.current.web.evaluateJavascript(
                "(function(){"
                    + (script.contains(";") ? script : "return (" + script + ");")
                    + "})()",
                response -> {
                  value.set(response);
                  result.countDown();
                }));
    assertTrue(
        "Timed out waiting for WebView JavaScript callback: " + script,
        result.await(5, TimeUnit.SECONDS));
    String raw = value.get();
    if (raw == null || "null".equals(raw)) return JSONObject.NULL;
    return new JSONTokener(raw).nextValue();
  }

  private String stringJs(String expression) throws Exception {
    return String.valueOf(js(expression));
  }

  private Number numberJs(String expression) throws Exception {
    Object result = js(expression);
    assertTrue(
        "Expected JavaScript number for " + expression + ", got " + result,
        result instanceof Number);
    return (Number) result;
  }

  private boolean boolJs(String expression) throws Exception {
    return Boolean.TRUE.equals(js(expression));
  }

  private interface Condition {
    boolean ready() throws Exception;
  }

  private void await(String message, long milliseconds, Condition condition) throws Exception {
    long end = SystemClock.elapsedRealtime() + milliseconds;
    do {
      if (condition.ready()) return;
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < end);
    String state =
        ui(
            activity ->
                "url="
                    + (activity.current == null ? "null" : activity.current.url)
                    + ", resources="
                    + (activity.current == null ? 0 : activity.current.resources.size())
                    + ", player="
                    + (activity.player == null ? "null" : activity.player.getPlaybackState())
                    + ", nativeWidth="
                    + (activity.player == null ? 0 : activity.player.getVideoSize().width)
                    + ", nativeHeight="
                    + (activity.player == null ? 0 : activity.player.getVideoSize().height)
                    + ", isPlaying="
                    + (activity.player != null && activity.player.isPlaying())
                    + ", playWhenReady="
                    + (activity.player != null && activity.player.getPlayWhenReady())
                    + ", suppressionReason="
                    + (activity.player == null
                        ? "none"
                        : activity.player.getPlaybackSuppressionReason())
                    + ", anchorVisibility="
                    + (activity.anchorClip == null ? "none" : activity.anchorClip.getVisibility())
                    + ", anchorShown="
                    + (activity.anchorClip != null && activity.anchorClip.isShown())
                    + ", error="
                    + (activity.player == null ? "none" : activity.player.getPlayerError()));
    fail(message + " [" + state + "]");
  }

  private void assertNoTakeoverFor(long milliseconds) {
    long end = SystemClock.elapsedRealtime() + milliseconds;
    do {
      assertNull(
          "Suppressed page unexpectedly reopened the native player",
          ui(activity -> activity.player));
      assertTrue(
          "Suppression must persist for the current document",
          ui(activity -> activity.current.takeoverSuppressed));
      SystemClock.sleep(100);
    } while (SystemClock.elapsedRealtime() < end);
  }

  private boolean sheetContains(String text) {
    return ui(activity -> containsText(activity.activeSheet.getWindow().getDecorView(), text));
  }

  private void clickSheetButton(String text) {
    ui(
        activity -> {
          Button button = findButton(activity.activeSheet.getWindow().getDecorView(), text);
          assertNotNull("Sheet button missing: " + text, button);
          assertTrue(button.performClick());
          return null;
        });
  }

  private static Button findButton(View view, String text) {
    if (view instanceof Button && text.contentEquals(((Button) view).getText()))
      return (Button) view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        Button result = findButton(group.getChildAt(i), text);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static View findDescription(View view, String description) {
    if (description.contentEquals(
        view.getContentDescription() == null ? "" : view.getContentDescription())) return view;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++) {
        View result = findDescription(group.getChildAt(i), description);
        if (result != null) return result;
      }
    }
    return null;
  }

  private static boolean containsText(View view, String text) {
    if (view instanceof TextView && ((TextView) view).getText().toString().contains(text))
      return true;
    if (view instanceof ViewGroup) {
      ViewGroup group = (ViewGroup) view;
      for (int i = 0; i < group.getChildCount(); i++)
        if (containsText(group.getChildAt(i), text)) return true;
    }
    return false;
  }
}
