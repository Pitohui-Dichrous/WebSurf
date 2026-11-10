package io.websurf.app;

import static org.junit.Assert.*;

import android.content.ClipData;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.os.Parcel;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.view.accessibility.AccessibilityNodeInfo;
import android.webkit.WebChromeClient.FileChooserParams;
import androidx.test.core.app.ActivityScenario;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class BrowserSecurityTest {
  @Test
  public void missedFrameUnloadsCanReclaimAnIdleSlotAtCapacity() throws Exception {
    BrowserTab tab = new BrowserTab();
    for (int i = 0; i < 100; i++) {
      String document = "d" + i;
      tab.documents.put(document, new BrowserTab.Frame(null, "https://example.test", i == 0, true));
      if (i <= 3) {
        VideoTarget target =
            VideoTarget.read(
                new JSONObject().put("documentId", document).put("mediaId", "1"), null);
        tab.videos.put(target.key(), target);
      }
    }
    tab.selectedVideo = "d1:1";
    assertTrue(tab.makeDocumentRoom("d2"));
    assertEquals(99, tab.documents.size());
    assertFalse(tab.documents.containsKey("d4"));
    for (int i = 0; i <= 3; i++) assertTrue(tab.documents.containsKey("d" + i));
  }

  @Test
  public void earliestSelectedAndActiveTargetsCannotPinTheWholeCache() throws Exception {
    BrowserTab tab = new BrowserTab();
    String title = "x".repeat(3000);
    tab.selectedVideo = "d:0";
    for (int i = 0; i < 2000; i++) {
      VideoTarget video =
          VideoTarget.read(
              new JSONObject()
                  .put("documentId", "d")
                  .put("mediaId", Integer.toString(i))
                  .put("title", i < 120 ? "" : title),
              null);
      assertTrue(tab.retainVideo(video.key(), video, "d:1"));
      assertTrue(tab.videos.size() <= 100);
    }
    assertTrue(tab.videos.containsKey("d:0"));
    assertTrue(tab.videos.containsKey("d:1"));
    assertTrue(tab.videos.containsKey("d:1999"));
    assertTrue(
        tab.videos.values().stream().mapToInt(VideoTarget::retainedCharacters).sum() <= 256 * 1024);
  }

  @Test
  public void savedTabSlotsHaveBoundedUrlsAndRestoreInOrder() {
    Context context = ApplicationProvider.getApplicationContext();
    String normal = "http://127.0.0.1:8765/layout.html";
    try (ActivityScenario<MainActivity> scene =
        ActivityScenario.launch(
            new Intent(context, MainActivity.class).setData(Uri.parse(normal)))) {
      scene.onActivity(
          a -> {
            BrowserTab selected = a.newTab(null, true);
            selected.url = normal + "#" + "a".repeat(700000);
            a.tabs.get(0).url = normal;
            Bundle b = new Bundle();
            a.onSaveInstanceState(b);
            assertEquals(List.of(normal, "about:blank"), b.getStringArrayList("tabs"));
            assertEquals(1, b.getInt("active"));
            Parcel parcel = Parcel.obtain();
            try {
              parcel.writeBundle(b);
              assertTrue(parcel.dataSize() < 256 * 1024);
            } finally {
              parcel.recycle();
            }
          });
      scene.recreate();
      scene.onActivity(
          a -> {
            assertEquals(2, a.tabs.size());
            assertSame(a.tabs.get(1), a.current);
            assertEquals("about:blank", a.current.web.getUrl());
          });
    }
  }

  @Test
  public void forgedOrMixedPickerResultCannotUploadPrivateContent() {
    Context c = ApplicationProvider.getApplicationContext();
    Uri own = Uri.parse("content://" + c.getPackageName() + ".files/downloads/private.mp4");
    for (Uri u :
        List.of(
            own,
            Uri.parse("content://0@" + own.getAuthority() + "/downloads/private.mp4"),
            Uri.parse("file:///data/data/private"),
            Uri.parse("content://test.ungranted/document/secret"))) {
      assertNull(
          UploadDocuments.selection(
              c, new Intent().setData(u).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)));
    }
    Intent mixed = new Intent().addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
    ClipData clip = ClipData.newRawUri("file", own);
    clip.addItem(new ClipData.Item(Uri.parse("file:///sdcard/Download/private.mp4")));
    mixed.setClipData(clip);
    assertNull(UploadDocuments.selection(c, mixed));
  }

  @Test
  @SdkSuppress(minSdkVersion = 29)
  public void documentChosenInSystemPickerIsDeliveredToTheWebView() throws Exception {
    Context c = ApplicationProvider.getApplicationContext();
    String name = "websurf-upload-" + SystemClock.uptimeMillis() + ".txt";
    ContentValues values = new ContentValues();
    values.put(MediaStore.Downloads.DISPLAY_NAME, name);
    values.put(MediaStore.Downloads.MIME_TYPE, "text/plain");
    Uri testFile = c.getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
    assertNotNull(testFile);
    try (ActivityScenario<MainActivity> scene =
        ActivityScenario.launch(
            new Intent(c, MainActivity.class)
                .setData(Uri.parse("http://127.0.0.1:8765/layout.html")))) {
      try (var out = c.getContentResolver().openOutputStream(testFile)) {
        out.write(83);
      }
      AtomicReference<Uri[]> selected = new AtomicReference<>();
      CountDownLatch returned = new CountDownLatch(1);
      // Wait for the originating document to commit before opening the file input.
      long deadline = SystemClock.uptimeMillis() + 8000;
      boolean[] loaded = {false};
      while (!loaded[0] && SystemClock.uptimeMillis() < deadline) {
        scene.onActivity(
            a -> loaded[0] = "WebSurf layout fixture".equals(a.current.web.getTitle()));
        if (!loaded[0]) SystemClock.sleep(100);
      }
      assertTrue(loaded[0]);
      scene.onActivity(
          a -> {
            var web = a.current.web;
            assertTrue(
                web.getWebChromeClient()
                    .onShowFileChooser(
                        web,
                        uris -> {
                          selected.set(uris);
                          returned.countDown();
                        },
                        new FileChooserParams() {
                          public int getMode() {
                            return MODE_OPEN;
                          }

                          public String[] getAcceptTypes() {
                            return new String[] {"text/plain"};
                          }

                          public boolean isCaptureEnabled() {
                            return false;
                          }

                          public CharSequence getTitle() {
                            return null;
                          }

                          public String getFilenameHint() {
                            return null;
                          }

                          public Intent createIntent() {
                            throw new AssertionError("System picker required");
                          }
                        }));
          });
      if (!clickPickerText(name, 2500)) {
        assertTrue("Open system picker locations", clickPickerText("Show roots", 3000));
        assertTrue("Open Downloads", clickPickerText("Downloads", 3000));
        assertTrue("Select test upload", clickPickerText(name, 5000));
      }
      if (!returned.await(1, TimeUnit.SECONDS)) clickPickerText("Open", 2500);
      assertTrue(returned.await(5, TimeUnit.SECONDS));
      assertNotNull(selected.get());
      assertEquals(1, selected.get().length);
      try (var in = c.getContentResolver().openInputStream(selected.get()[0])) {
        assertEquals(83, in.read());
      }
    } finally {
      c.getContentResolver().delete(testFile, null, null);
    }
  }

  private static boolean clickPickerText(String text, long timeout) {
    var automation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
    long end = SystemClock.uptimeMillis() + timeout;
    do {
      AccessibilityNodeInfo root = automation.getRootInActiveWindow();
      if (root != null && String.valueOf(root.getPackageName()).endsWith(".documentsui"))
        for (var match : root.findAccessibilityNodeInfosByText(text)) {
          if (!text.equalsIgnoreCase(String.valueOf(match.getText()))
              && !text.equalsIgnoreCase(String.valueOf(match.getContentDescription()))) continue;
          for (var node = match; node != null; node = node.getParent()) {
            if (node.isClickable() && node.performAction(AccessibilityNodeInfo.ACTION_CLICK))
              return true;
          }
        }
      SystemClock.sleep(100);
    } while (SystemClock.uptimeMillis() < end);
    return false;
  }
}
