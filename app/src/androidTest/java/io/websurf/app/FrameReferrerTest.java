package io.websurf.app;

import static org.junit.Assert.*;

import android.os.SystemClock;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.webkit.WebViewCompat;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.json.JSONObject;
import org.junit.Test;

/** A real child frame and a preload-none top video report the same media URL. */
public final class FrameReferrerTest {
  private static final String TOP = "http://127.0.0.1:8765";
  private static final String MEDIA = "http://localhost:8765";

  @Test
  public void crossOriginFrameCannotLendItsLinkPolicyToTheTopVideo() throws Exception {
    checkFrame(MEDIA);
  }

  @Test
  public void sameOriginFrameWithAnotherPathCannotLendItsLinkPolicyToTheTopVideo() throws Exception {
    checkFrame(TOP);
  }

  private void checkFrame(String childOrigin) throws Exception {
    String token = Long.toString(System.nanoTime());
    String topPage = TOP + "/layout.html?private=" + token;
    String childPage = childOrigin + "/embedded/lesson?child=" + token;
    String media = MEDIA + "/public/sample-360.mp4?shared=" + token;
    String ordinary = media + "-explicit";
    try (ActivityScenario<MainActivity> scene =
        FixtureHttp.launchHomepage(ApplicationProvider.getApplicationContext(), topPage)) {
      await(scene, "top document", a -> "WebSurf layout fixture".equals(a.current.web.getTitle()));
      // Test content installed only in the child fixture. The production sniffer still uses
      // its own WebKit message listener, sender origin, document ID and epoch.
      scene.onActivity(a -> {
        WebViewCompat.addDocumentStartJavaScript(a.current.web,
            "if(window!==top){addEventListener('DOMContentLoaded',()=>{"
                + "history.replaceState(null,''," + JSONObject.quote(childPage) + ");"
                + "let l=document.createElement('a');l.href=" + JSONObject.quote(media)
                + ";l.referrerPolicy='unsafe-url';l.textContent='Export';document.body.append(l);"
                + "addEventListener('message',e=>{if(e.source!==parent)return;"
                + "if(e.data==='rescan')l.type='video/mp4';"
                + "if(e.data==='capture'){let v=document.createElement('video');v.preload='none';"
                + "v.src=l.href;document.body.append(v);"
                + "fetch(l.href,{mode:'no-cors',referrerPolicy:'unsafe-url'});}});});}",
            Set.of(childOrigin));
        a.current.web.evaluateJavascript(
            "let frame=document.createElement('iframe');frame.src="
                + JSONObject.quote(childOrigin + "/layout.html?frame=" + token)
                + ";document.body.append(frame)", null);
      });
      await(scene, "child's unrequested link", a -> {
        WebResource row = a.current.resources.get(media);
        return row != null && !row.requestHeadersCaptured
            && (childOrigin + "/").equals(referrer(a.headersFor(row)));
      });

      script(scene, "let v=document.createElement('video');v.preload='none';v.src="
          + JSONObject.quote(media) + ";document.body.append(v);"
          + "let link=document.createElement('a');link.href=" + JSONObject.quote(ordinary)
          + ";link.referrerPolicy='unsafe-url';document.body.append(link)");
      await(scene, "top video and explicit link", a ->
          target(a, media, true) != null && a.current.resources.containsKey(ordinary));
      assertEquals(topPage, ui(scene, a -> referrer(a.headersFor(a.current.resources.get(ordinary)))));
      assertNull(ui(scene, a -> referrer(a.headersFor(a.current.resources.get(media)))));
      script(scene, "frames[0].postMessage('rescan','*')");
      await(scene, "child's second observation", a ->
          (childOrigin + "/").equals(referrer(a.headersFor(a.current.resources.get(media))))
              && !a.current.resources.get(media).documentReferrer.main());

      Map<String, String> playback = ui(scene, a -> playback(a, media, true));
      assertNull(referrer(playback));
      // Grant only this test transport access to the local responder standing in for a CDN.
      // Exercise the native media GET with the selected video's actual playback headers.
      try (var response = NativeMediaHttp.get(media, media, MEDIA, playback, "bytes=0-0",
          null, 3000, 3000).execute()) {
        assertEquals(206, response.code());
        assertNull(response.networkResponse().request().header("Referer"));
      }

      script(scene, "frames[0].postMessage('capture','*')");
      await(scene, "browser request with child's exact path", a -> {
        WebResource row = a.current.resources.get(media);
        return target(a, media, false) != null && row.requestHeadersCaptured
            && childPage.equals(referrer(row.headers));
      });
      assertEquals(childPage, ui(scene, a -> referrer(playback(a, media, false))));
      assertNull(ui(scene, a -> referrer(playback(a, media, true))));
      scene.onActivity(a -> {
        WebResource request = new WebResource(ordinary);
        request.documentReferrer = target(a, media, false).documentReferrer;
        a.captureRequestHeaders(request, Map.of("Origin", "https://foreign.example"));
        assertFalse(a.headersFor(request).containsKey("Origin"));
        assertNull(referrer(a.headersFor(request)));
        a.captureRequestHeaders(request, Map.of("Origin", childOrigin));
        assertEquals(childOrigin, a.headersFor(request).get("Origin"));
        a.captureRequestHeaders(request, Map.of("Origin", "null"));
        assertEquals("null", a.headersFor(request).get("Origin"));
      });
    }
  }

  private static VideoTarget target(MainActivity a, String media, boolean main) {
    return a.current.videos.values().stream()
        .filter(v -> v.url.equals(media) && a.current.documents.get(v.documentId).main == main)
        .findFirst().orElse(null);
  }

  private static Map<String, String> playback(MainActivity a, String media, boolean main) {
    return MediaRequestPolicy.headers(media, media,
        a.playbackHeadersFor(a.current.resources.get(media), target(a, media, main)));
  }

  private static String referrer(Map<String, String> headers) {
    return headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("Referer"))
        .map(Map.Entry::getValue).findFirst().orElse(null);
  }

  private static void script(ActivityScenario<MainActivity> scene, String script) {
    scene.onActivity(a -> a.current.web.evaluateJavascript(script, null));
  }

  private static <T> T ui(ActivityScenario<MainActivity> scene, Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scene.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private static void await(ActivityScenario<MainActivity> scene, String message,
      Function<MainActivity, Boolean> ready) {
    long end = SystemClock.elapsedRealtime() + 12000;
    do {
      if (ui(scene, ready)) return;
      SystemClock.sleep(80);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }
}
