package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Title ownership uses real browser tabs; media URLs are identifiers and are never fetched. */
@RunWith(AndroidJUnit4.class)
public final class DownloadTitleTest {
  private static final String BASE = "http://127.0.0.1:8765";
  private static final String MEDIA = BASE + "/Remote_control.php?fixture=download-title";
  private ActivityScenario<MainActivity> scenario;
  private SharedPreferences preferences;
  private boolean hadAutoPlay, originalAutoPlay;

  @Before
  public void openEmptyLocalDocument() throws Exception {
    Context context = ApplicationProvider.getApplicationContext();
    preferences = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    hadAutoPlay = preferences.contains("auto_play");
    originalAutoPlay = preferences.getBoolean("auto_play", true);
    assertTrue(preferences.edit().putBoolean("auto_play", false).commit());
    String url = BASE + "/layout.html?download-title=" + System.nanoTime();
    scenario =
        ActivityScenario.launch(new Intent(context, MainActivity.class).setData(Uri.parse(url)));
    awaitPage(url);
  }

  @After
  public void closeAndRestorePreferences() {
    if (scenario != null) scenario.close();
    if (preferences != null) {
      SharedPreferences.Editor edit = preferences.edit();
      if (hadAutoPlay) edit.putBoolean("auto_play", originalAutoPlay);
      else edit.remove("auto_play");
      assertTrue(edit.commit());
    }
  }

  @Test
  public void endpointMediaUsesItsOwningPageEvenWhenAnotherTabWithSameUrlIsSelected()
      throws Exception {
    BrowserTab unrelated = ui(a -> a.current);
    String ownerUrl = BASE + "/layout.html?download-title-owner=" + System.nanoTime();
    BrowserTab owner = ui(a -> a.newTab(ownerUrl, true));
    assertNotNull(owner);
    awaitPage(ownerUrl);
    ui(
        a -> {
          WebResource resource = video("");
          unrelated.title = "另一标签页的无关标题";
          unrelated.resources.put(MEDIA, video(""));
          owner.title = "潮汐纪录 · 岩岸与海风";
          owner.resources.put(MEDIA, resource);
          long originalEpoch = owner.epoch;
          a.selectTab(unrelated);
          assertSame(unrelated, a.current);
          assertNotSame(unrelated.resources.get(MEDIA), resource);
          assertEquals("潮汐纪录 · 岩岸与海风", a.downloadTitle(resource));
          assertEquals(
              "Resolving a filename must not reload the owner", originalEpoch, owner.epoch);
          assertNull("Resolving a filename must not start playback", a.player);
          return null;
        });
  }

  @Test
  public void explicitMediaTitleWinsOverPageTitleAndGenericEndpoint() {
    ui(
        a -> {
          WebResource resource = video("Remote Control Robot Review · 实机演示");
          a.current.title = "播放器所在网页";
          a.current.resources.put(MEDIA, resource);
          assertEquals("Remote Control Robot Review · 实机演示", a.downloadTitle(resource));
          return null;
        });
  }

  @Test
  public void genericEmbeddedPlayerTitleDoesNotMaskOwningPageTitle() {
    ui(
        a -> {
          WebResource resource = video("Remote_control.php");
          resource.pageUrl = BASE + "/embedded/Remote_control.php";
          a.current.title = "一段真实的页面视频标题";
          a.current.resources.put(MEDIA, resource);
          assertEquals("一段真实的页面视频标题", a.downloadTitle(resource));
          return null;
        });
  }

  @Test
  public void imageDownloadKeepsImageFilenameInsteadOfBorrowingVideoPageTitle() {
    ui(
        a -> {
          WebResource image = new WebResource(BASE + "/images/moodboard-reference.jpg");
          image.mime = "image/jpeg";
          image.classify();
          a.current.title = "影片介绍与封面页面";
          a.current.resources.put(image.url, image);
          assertEquals("moodboard-reference.jpg", a.downloadTitle(image));
          return null;
        });
  }

  private static WebResource video(String title) {
    WebResource resource = new WebResource(MEDIA);
    resource.mime = "video/mp4";
    resource.title = title;
    resource.classify();
    return resource;
  }

  private void awaitPage(String url) throws Exception {
    long end = SystemClock.elapsedRealtime() + 8000;
    do {
      if (ui(
          a ->
              a.resumed
                  && a.current.web.getProgress() == 100
                  && url.equals(a.current.web.getUrl()))) return;
      SystemClock.sleep(60);
    } while (SystemClock.elapsedRealtime() < end);
    fail("Local document did not settle: " + url);
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }
}
