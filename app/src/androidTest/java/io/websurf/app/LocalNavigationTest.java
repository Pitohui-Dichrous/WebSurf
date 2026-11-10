package io.websurf.app;

import static org.junit.Assert.*;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.Test;

public class LocalNavigationTest {
  private static final String SITE = "http://127.0.0.1:8765";

  @Test
  public void onlyBrowserSelectedLanOriginSurvivesRestoration() {
    Context context = ApplicationProvider.getApplicationContext();
    String external = SITE + "/?entry=external";
    try (ActivityScenario<MainActivity> scene =
        ActivityScenario.launch(
            new Intent(Intent.ACTION_VIEW, Uri.parse(external), context, MainActivity.class))) {
      awaitVideo(scene, external, false);
      scene.recreate();
      awaitVideo(scene, external, false);
      String typed = SITE + "/?entry=address";
      scene.onActivity(a -> a.navigate(typed));
      awaitVideo(scene, typed, true);
      scene.recreate();
      awaitVideo(scene, typed, true);
      // Page navigation to a second loopback origin gets no grant, even after a recreation.
      String redirected = "http://localhost:8765/?entry=script";
      scene.onActivity(
          a -> a.current.web.evaluateJavascript("location.href='" + redirected + "'", null));
      awaitVideo(scene, redirected, false);
      scene.recreate();
      awaitVideo(scene, redirected, false);
    }
  }

  private static void awaitVideo(
      ActivityScenario<MainActivity> scene, String page, boolean trusted) {
    AtomicBoolean observed = new AtomicBoolean();
    long until = SystemClock.elapsedRealtime() + 12_000;
    while (!observed.get() && SystemClock.elapsedRealtime() < until) {
      scene.onActivity(
          a -> {
            BrowserTab tab = a.current;
            if (tab == null || !page.equals(tab.url)) return;
            for (VideoTarget video : tab.videos.values()) {
              WebResource row = tab.resources.get(video.url);
              if (row != null && tab.documents.containsKey(video.documentId)) {
                assertEquals(trusted, a.headersFor(row).containsKey(NativeMediaHttp.LOCAL_ORIGIN));
                observed.set(true);
                break;
              }
            }
          });
      if (!observed.get()) SystemClock.sleep(50);
    }
    assertTrue("Local fixture video was not observed at " + page, observed.get());
  }
}
