package io.websurf.app;

import static org.junit.Assert.*;

import android.animation.ValueAnimator;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Animatable;
import android.graphics.drawable.Drawable;
import android.net.Uri;
import android.os.SystemClock;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Supplied resource artwork and the real asynchronous preview lifecycle in native views. */
@RunWith(AndroidJUnit4.class)
public final class ResourceArtworkTest {
  private Context context;
  private ActivityScenario<MainActivity> scenario;
  private Fixture server;
  private SharedPreferences browser, appearance;
  private boolean hadAuto, oldAuto, hadDark, oldDark;

  @Before
  public void openIsolatedPage() throws Exception {
    context = ApplicationProvider.getApplicationContext();
    browser = context.getSharedPreferences("browser", Context.MODE_PRIVATE);
    appearance = context.getSharedPreferences("appearance", Context.MODE_PRIVATE);
    hadAuto = browser.contains("auto_play");
    oldAuto = browser.getBoolean("auto_play", true);
    hadDark = appearance.contains("dark");
    oldDark = appearance.getBoolean("dark", true);
    assertTrue(browser.edit().putBoolean("auto_play", false).commit());
    server = new Fixture();
    scenario =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class).setData(Uri.parse(server.base() + "/page")));
    await("Isolated page did not load", () -> ui(a -> a.current.web.getProgress() == 100));
  }

  @After
  public void restoreTestState() throws Exception {
    if (server != null) server.release.countDown();
    if (scenario != null) scenario.close();
    if (server != null) server.close();
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
  public void audioAndDocumentArtworkKeepRowActionsWithoutPreviewRequests() throws Exception {
    AtomicInteger actions = new AtomicInteger();
    for (boolean dark : new boolean[] {false, true}) {
      ResourceRow[] rows =
          ui(
              a -> {
                Ui.setDark(a, dark);
                a.applyChromeTheme();
                LinearLayout body = a.scrollBody(a.sheet("网页资源"));
                WebResource audio = new WebResource(server.base() + "/recording.m4a");
                audio.title = "音频文件";
                WebResource document = new WebResource(server.base() + "/document.pdf");
                document.title = "文档文件";
                ResourceRow first =
                    new ResourceRow(a, audio, a.previewLoader, Map.of(), actions::incrementAndGet);
                ResourceRow second =
                    new ResourceRow(
                        a, document, a.previewLoader, Map.of(), actions::incrementAndGet);
                body.addView(first);
                body.addView(second);
                return new ResourceRow[] {first, second};
              });
      await(
          "Static artwork did not settle",
          () ->
              ui(
                  a ->
                      artwork(rows[0]) != null
                          && artwork(rows[0]).isLaidOut()
                          && artwork(rows[1]) != null
                          && artwork(rows[1]).isLaidOut()));
      ui(
          a -> {
            assertEquals(ResourceArtworkView.Kind.AUDIO, artwork(rows[0]).getKind());
            assertEquals(ResourceArtworkView.Kind.DOCUMENT, artwork(rows[1]).getKind());
            ResourceArtworkView duplicate = new ResourceArtworkView(a);
            duplicate.show(ResourceArtworkView.Kind.AUDIO);
            assertSame(
                "Identical artwork must share its decoded bitmap across resource rows",
                ((android.graphics.drawable.BitmapDrawable) artwork(rows[0]).getDrawable())
                    .getBitmap(),
                ((android.graphics.drawable.BitmapDrawable) duplicate.getDrawable()).getBitmap());
            assertFalse(artwork(rows[0]).isAnimating());
            assertFalse(artwork(rows[1]).isAnimating());
            assertTrue(rows[0].performClick());
            assertTrue(rows[1].performClick());
            return null;
          });
      shot("types-" + (dark ? "dark" : "light"));
    }
    assertEquals(4, actions.get());
    assertEquals(
        "Audio and documents must not start preview transfers", 0, server.mediaRequests.get());
  }

  @Test
  public void loadingMovesOnlyWhileVisibleAndStopsWhenReplaced() throws Exception {
    FrameLayout host =
        ui(
            a -> {
              LinearLayout body = a.scrollBody(a.sheet("网页资源"));
              FrameLayout container = new FrameLayout(a);
              body.addView(container, new LinearLayout.LayoutParams(-1, a.dp(100)));
              ResourceArtworkView art = new ResourceArtworkView(a);
              art.show(ResourceArtworkView.Kind.LOADING);
              container.addView(art, new FrameLayout.LayoutParams(a.dp(84), a.dp(84)));
              return container;
            });
    ResourceArtworkView art = ui(a -> (ResourceArtworkView) host.getChildAt(0));
    await(
        "Loading artwork did not become visible", () -> ui(a -> art.isLaidOut() && art.isShown()));
    if (ValueAnimator.areAnimatorsEnabled()) {
      await("Loading animation did not start", () -> ui(a -> art.isAnimating()));
      assertFramesChange(art);
      shot("loading");
    } else {
      assertFalse("System reduced-motion preference must be honored", ui(a -> art.isAnimating()));
    }
    ui(
        a -> {
          host.setVisibility(View.GONE);
          return null;
        });
    await("Hidden artwork keeps animating", () -> !ui(a -> art.isAnimating()));
    ui(
        a -> {
          host.setVisibility(View.VISIBLE);
          return null;
        });
    if (ValueAnimator.areAnimatorsEnabled())
      await("Visible artwork did not resume", () -> ui(a -> art.isAnimating()));
    Drawable previous = ui(a -> art.getDrawable());
    ui(
        a -> {
          host.removeView(art);
          return null;
        });
    await("Detached artwork keeps animating", () -> !ui(a -> art.isAnimating()));
    ui(
        a -> {
          host.addView(art, new FrameLayout.LayoutParams(a.dp(84), a.dp(84)));
          return null;
        });
    if (ValueAnimator.areAnimatorsEnabled())
      await("Reattached artwork did not resume", () -> ui(a -> art.isAnimating()));
    ui(
        a -> {
          art.show(ResourceArtworkView.Kind.UNKNOWN);
          return null;
        });
    await(
        "Replacing loading with a static icon did not stop animation",
        () -> !ui(a -> art.isAnimating()));
    ui(
        a -> {
          assertEquals(ResourceArtworkView.Kind.UNKNOWN, art.getKind());
          if (previous instanceof Animatable animation) assertFalse(animation.isRunning());
          return null;
        });
    shot("unknown-static");
  }

  @Test
  public void failedPreviewOffersRetryAndRealImageStopsLoadingArtwork() throws Exception {
    AtomicInteger rowActions = new AtomicInteger();
    for (boolean dark : new boolean[] {false, true}) {
      server.resetPreview();
      String theme = dark ? "dark" : "light";
      ResourceRow row =
          ui(
              a -> {
                Ui.setDark(a, dark);
                a.applyChromeTheme();
                LinearLayout body = a.scrollBody(a.sheet("网页资源"));
                WebResource image = new WebResource(server.base() + "/preview.png?theme=" + theme);
                image.title = "异步预览";
                ResourceRow result =
                    new ResourceRow(
                        a, image, a.previewLoader, Map.of(), rowActions::incrementAndGet);
                body.addView(result);
                return result;
              });
      assertTrue("Preview was not requested", server.requested.await(5, TimeUnit.SECONDS));
      ResourceArtworkView art = ui(a -> artwork(row));
      assertNotNull(art);
      assertEquals(ResourceArtworkView.Kind.LOADING, ui(a -> art.getKind()));
      if (ValueAnimator.areAnimatorsEnabled()) {
        await("Real pending request did not animate", () -> ui(a -> art.isAnimating()));
        assertFramesChange(art);
      }
      ui(
          a -> {
            assertPreviewBorder(row, null);
            return null;
          });
      shot("request-loading-" + theme);
      server.release.countDown();
      await(
          "Failed preview did not show unknown artwork",
          () ->
              ui(
                  a ->
                      art.getKind() == ResourceArtworkView.Kind.UNKNOWN
                          && containsText(row, "重试")));
      assertFalse(ui(a -> art.isAnimating()));
      assertTrue(ui(a -> containsText(row, "暂无预览")));
      ui(
          a -> {
            assertPreviewBorder(row, null);
            return null;
          });
      shot("request-failed-" + theme);
      ui(
          a -> {
            View retry = clickableAncestor(art, row);
            assertNotNull("Preview artwork must retain a retry action", retry);
            assertTrue(retry.performClick());
            return null;
          });
      await(
          "Retry did not replace loading artwork with the real image",
          () -> ui(a -> art.getVisibility() == View.GONE && hasRealImage(row)));
      assertFalse(ui(a -> art.isAnimating()));
      assertEquals(2, server.previewRequests.get());
      assertEquals(
          "Retrying the preview must not invoke the row download action", 0, rowActions.get());
      ui(
          a -> {
            assertPreviewBorder(row, 0xffc0fe03);
            return null;
          });
      shot("request-complete-" + theme);
    }
  }

  @Test
  public void opaqueWideSquareAndPortraitPreviewsKeepAllBordersInBothThemes() throws Exception {
    String[] names = {"wide", "square", "portrait"};
    int[] colors = {Color.RED, Color.GREEN, Color.BLUE};
    for (boolean dark : new boolean[] {false, true})
      for (int index = 0; index < names.length; index++) {
        final String name = names[index], theme = dark ? "dark" : "light";
        final int color = colors[index];
        ResourceRow row =
            ui(
                a -> {
                  Ui.setDark(a, dark);
                  a.applyChromeTheme();
                  LinearLayout body = a.scrollBody(a.sheet("网页资源"));
                  WebResource image =
                      new WebResource(server.base() + "/" + name + ".png?theme=" + theme);
                  image.title = name + " 不透明缩略图";
                  ResourceRow result =
                      new ResourceRow(a, image, a.previewLoader, Map.of(), () -> {});
                  body.addView(result);
                  return result;
                });
        await(
            "Opaque " + name + " thumbnail did not load",
            () -> ui(a -> artwork(row).getVisibility() == View.GONE && hasRealImage(row)));
        ui(
            a -> {
              assertPreviewBorder(row, color);
              return null;
            });
        shot("border-" + name + "-" + theme);
      }
  }

  private static void assertPreviewBorder(ResourceRow row, Integer centerColor) {
    View picture = (View) artwork(row).getParent();
    Bitmap bitmap = render(picture);
    try {
      int inset = Math.max(0, Ui.dp(row.getContext(), 1) / 2);
      int right = bitmap.getWidth() - 1 - inset, bottom = bitmap.getHeight() - 1 - inset;
      for (int fraction = 1; fraction <= 3; fraction++) {
        int x = bitmap.getWidth() * fraction / 4, y = bitmap.getHeight() * fraction / 4;
        assertPixel("left preview border", Ui.BORDER, bitmap.getPixel(inset, y), 2);
        assertPixel("right preview border", Ui.BORDER, bitmap.getPixel(right, y), 2);
        assertPixel("top preview border", Ui.BORDER, bitmap.getPixel(x, inset), 2);
        assertPixel("bottom preview border", Ui.BORDER, bitmap.getPixel(x, bottom), 2);
      }
      if (centerColor != null)
        assertPixel(
            "Real thumbnail center remains visible",
            centerColor,
            bitmap.getPixel(bitmap.getWidth() / 2, bitmap.getHeight() / 2),
            12);
    } finally {
      bitmap.recycle();
    }
  }

  private static void assertPixel(String message, int expected, int actual, int tolerance) {
    assertTrue(
        message
            + ": expected "
            + Integer.toHexString(expected)
            + " but was "
            + Integer.toHexString(actual),
        Math.abs(Color.red(expected) - Color.red(actual)) <= tolerance
            && Math.abs(Color.green(expected) - Color.green(actual)) <= tolerance
            && Math.abs(Color.blue(expected) - Color.blue(actual)) <= tolerance
            && Color.alpha(actual) == 255);
  }

  private void assertFramesChange(ResourceArtworkView art) throws Exception {
    Bitmap first = ui(a -> render(art));
    boolean changed = false;
    try {
      // Sample across multiple encoded frames, without assuming the supplied GIF's frame rate.
      for (int sample = 0; sample < 6 && !changed; sample++) {
        SystemClock.sleep(140);
        Bitmap next = ui(a -> render(art));
        changed = !first.sameAs(next);
        next.recycle();
      }
      assertTrue("Loading must display changing GIF frames, not just an animated flag", changed);
    } finally {
      first.recycle();
    }
  }

  private static Bitmap render(View view) {
    Bitmap bitmap = Bitmap.createBitmap(view.getWidth(), view.getHeight(), Bitmap.Config.ARGB_8888);
    view.draw(new Canvas(bitmap));
    return bitmap;
  }

  private void shot(String name) throws Exception {
    InstrumentationRegistry.getInstrumentation().waitForIdleSync();
    SystemClock.sleep(200);
    UiCaptureTest.shot(
        context,
        "resource-art-"
            + name
            + "-"
            + context.getResources().getConfiguration().screenWidthDp
            + "dp");
  }

  private static ResourceArtworkView artwork(View view) {
    if (view instanceof ResourceArtworkView art) return art;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++) {
        ResourceArtworkView result = artwork(group.getChildAt(i));
        if (result != null) return result;
      }
    return null;
  }

  private static boolean hasRealImage(View view) {
    if (view instanceof ImageView image
        && !(view instanceof ResourceArtworkView)
        && image.getVisibility() == View.VISIBLE
        && image.getDrawable() != null) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (hasRealImage(group.getChildAt(i))) return true;
    return false;
  }

  private static boolean containsText(View view, String text) {
    if (view instanceof TextView label && label.getText().toString().contains(text)) return true;
    if (view instanceof ViewGroup group)
      for (int i = 0; i < group.getChildCount(); i++)
        if (containsText(group.getChildAt(i), text)) return true;
    return false;
  }

  private static View clickableAncestor(View view, View stop) {
    while (view != null && view != stop) {
      if (view.isClickable()) return view;
      view = view.getParent() instanceof View parent ? parent : null;
    }
    return null;
  }

  private <T> T ui(Function<MainActivity, T> action) {
    AtomicReference<T> result = new AtomicReference<>();
    scenario.onActivity(a -> result.set(action.apply(a)));
    return result.get();
  }

  private interface Ready {
    boolean value() throws Exception;
  }

  private void await(String message, Ready ready) throws Exception {
    long end = SystemClock.elapsedRealtime() + 7000;
    do {
      if (ready.value()) return;
      SystemClock.sleep(50);
    } while (SystemClock.elapsedRealtime() < end);
    fail(message);
  }

  private static final class Fixture implements AutoCloseable {
    final ServerSocket socket = new ServerSocket(0);
    final ExecutorService pool = Executors.newCachedThreadPool();
    volatile CountDownLatch requested = new CountDownLatch(1), release = new CountDownLatch(1);
    final AtomicInteger mediaRequests = new AtomicInteger(), previewRequests = new AtomicInteger();
    final byte[] png, wide, square, portrait;
    volatile boolean closed;

    Fixture() throws IOException {
      png = png(32, 18, 0xffc0fe03);
      wide = png(320, 180, Color.RED);
      square = png(180, 180, Color.GREEN);
      portrait = png(90, 180, Color.BLUE);
      pool.execute(
          () -> {
            while (!closed) {
              try {
                Socket client = socket.accept();
                pool.execute(() -> serve(client));
              } catch (IOException failure) {
                if (!closed) throw new RuntimeException(failure);
              }
            }
          });
    }

    String base() {
      return "http://127.0.0.1:" + socket.getLocalPort();
    }

    void resetPreview() {
      requested = new CountDownLatch(1);
      release = new CountDownLatch(1);
      previewRequests.set(0);
    }

    private static byte[] png(int width, int height, int color) throws IOException {
      Bitmap bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
      bitmap.eraseColor(color);
      try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
        bitmap.compress(Bitmap.CompressFormat.PNG, 100, output);
        return output.toByteArray();
      } finally {
        bitmap.recycle();
      }
    }

    void serve(Socket client) {
      try (Socket connection = client) {
        connection.setSoTimeout(5000);
        BufferedReader reader =
            new BufferedReader(
                new InputStreamReader(connection.getInputStream(), StandardCharsets.US_ASCII));
        String first = reader.readLine();
        if (first == null) return;
        String path = first.split(" ")[1].split("\\?", 2)[0];
        while (true) {
          String line = reader.readLine();
          if (line == null || line.isEmpty()) break;
        }
        byte[] body =
            "<!doctype html><title>Artwork fixture</title>".getBytes(StandardCharsets.UTF_8);
        String status = "200 OK", mime = "text/html";
        if (path.equals("/preview.png")) {
          int request = previewRequests.incrementAndGet();
          requested.countDown();
          if (request == 1) {
            if (!release.await(10, TimeUnit.SECONDS)) return;
            status = "404 Not Found";
            body = new byte[0];
          } else {
            body = png;
            mime = "image/png";
          }
        } else if (path.equals("/wide.png")
            || path.equals("/square.png")
            || path.equals("/portrait.png")) {
          body = path.equals("/wide.png") ? wide : path.equals("/square.png") ? square : portrait;
          mime = "image/png";
        } else if (path.equals("/recording.m4a") || path.equals("/document.pdf")) {
          mediaRequests.incrementAndGet();
          status = "404 Not Found";
          body = new byte[0];
        }
        OutputStream output = connection.getOutputStream();
        output.write(
            ("HTTP/1.1 "
                    + status
                    + "\r\nContent-Type: "
                    + mime
                    + "\r\nContent-Length: "
                    + body.length
                    + "\r\nConnection: close\r\n\r\n")
                .getBytes(StandardCharsets.US_ASCII));
        output.write(body);
        output.flush();
      } catch (IOException ignored) {
      } catch (InterruptedException interrupted) {
        Thread.currentThread().interrupt();
      }
    }

    @Override
    public void close() throws IOException {
      closed = true;
      release.countDown();
      socket.close();
      pool.shutdownNow();
    }
  }
}
