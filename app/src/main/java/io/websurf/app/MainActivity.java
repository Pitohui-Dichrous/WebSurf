package io.websurf.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.InputType;
import android.text.TextUtils;
import android.util.Rational;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.*;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import androidx.webkit.WebViewCompat;
import androidx.webkit.WebViewFeature;
import io.websurf.app.download.DownloadService;
import io.websurf.app.download.DownloadStore;
import java.net.URLEncoder;
import java.util.*;
import org.json.*;

/** Browser shell. Each tab owns its WebView for the entire tab lifetime. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public class MainActivity extends Activity {
  final ArrayList<BrowserTab> tabs = new ArrayList<>();
  BrowserTab current;
  final Handler handler = new Handler(Looper.getMainLooper());
  SharedPreferences prefs;
  FrameLayout root, webContainer, fullscreenWeb;
  LinearLayout shell, topBar, bottomBar, resourceBar;
  EditText address;
  ProgressBar progress;
  TextView resourceText, tabCount;
  Button takeoverButton;
  VideoChrome videoChrome;
  FrameLayout anchorClip;
  boolean inlinePinned;
  float inlineExpansion;
  final ResourcePreviewLoader previewLoader = new ResourcePreviewLoader();
  VideoTarget activeTarget;
  VideoChrome.Mode playerMode = VideoChrome.Mode.INLINE;
  float floatX = Float.NaN, floatY = Float.NaN;
  float logicalVolume = 1, appliedGain = 1;
  boolean logicalMuted;
  boolean immersivePlayer;
  int previousOrientation;
  ImageButton back, forward, refresh;
  ExoPlayer player;
  PlayerView playerView;
  WebResource playing;
  BrowserTab playerTab;
  boolean inPip, resumed;
  long playerStartPosition;
  boolean playerRenderedFrame;
  boolean nativeAttachmentReady, requestedPlayIntent;
  PendingQuality pendingQuality;

  record PendingQuality(
      BrowserTab tab, long epoch, String key, long sourceVersion, String url, boolean playIntent) {}

  ValueCallback<Uri[]> fileCallback;
  WebChromeClient.CustomViewCallback customViewCallback;
  String snifferScript;
  Dialog activeSheet;
  AlertDialog qualityDialog;
  Runnable sheetRefresh;
  final Map<String, android.webkit.WebView> popupViews = new HashMap<>();

  @Override
  public void onCreate(Bundle state) {
    prefs = getSharedPreferences("browser", MODE_PRIVATE);
    setTheme(Ui.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
    super.onCreate(state);
    Ui.configure(this);
    try (java.io.InputStream in = getAssets().open("sniffer.js")) {
      java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
      byte[] buffer = new byte[8192];
      int count;
      while ((count = in.read(buffer)) != -1) bytes.write(buffer, 0, count);
      snifferScript = bytes.toString("UTF-8");
    } catch (Exception e) {
      throw new IllegalStateException("Missing browser observer", e);
    }
    createShell();
    DownloadService.recoverInterrupted(this);
    String initial = getIntent().getDataString();
    if (state != null && initial == null) {
      ArrayList<String> urls = state.getStringArrayList("tabs");
      if (urls != null) for (String url : urls) newTab(url, false);
      if (!tabs.isEmpty()) selectTab(tabs.get(Math.min(state.getInt("active"), tabs.size() - 1)));
    }
    if (tabs.isEmpty()) newTab(WebResource.isHttp(initial) ? initial : homepage(), true);
    if (getIntent().getBooleanExtra("show_downloads", false)) handler.post(this::showDownloads);
  }

  void createShell() {
    root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
    address = new EditText(this);
    address.setSingleLine(true);
    address.setSelectAllOnFocus(true);
    address.setTextSize(14);
    address.setTextColor(Ui.TEXT);
    address.setHintTextColor(Ui.MUTED);
    address.setHint("搜索或输入网址");
    address.setContentDescription("搜索或输入网址");
    address.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    address.setImeOptions(EditorInfo.IME_ACTION_GO);
    address.setPadding(dp(14), 0, dp(10), 0);
    address.setBackground(Ui.shape(this, Ui.SURFACE, 8));
    topBar.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1));
    address.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_GO
              || (event != null
                  && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                  && event.getAction() == KeyEvent.ACTION_UP)) {
            navigate(address.getText().toString());
            hideKeyboard();
            return true;
          }
          return false;
        });
    refresh = Ui.icon(this, "refresh", "刷新网页");
    topBar.addView(refresh, lp(48, 48));
    refresh.setOnClickListener(
        v -> {
          if (current != null) {
            closePlayer(false, false);
            current.web.reload();
          }
        });
    ImageButton downloads = Ui.icon(this, "download", "下载任务");
    topBar.addView(downloads, lp(48, 48));
    downloads.setOnClickListener(v -> showDownloads());
    shell.addView(topBar, new LinearLayout.LayoutParams(-1, -2));
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    shell.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
    webContainer = new FrameLayout(this);
    shell.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));
    resourceBar = new LinearLayout(this);
    resourceBar.setGravity(Gravity.CENTER_VERTICAL);
    resourceBar.setPadding(dp(16), 0, dp(8), 0);
    resourceBar.setBackgroundColor(Ui.SOFT_ACCENT);
    resourceText = Ui.text(this, "网页资源", 12, Ui.CONTROL);
    resourceText.setMaxLines(2);
    resourceBar.addView(resourceText, new LinearLayout.LayoutParams(0, dp(48), 1));
    takeoverButton = Ui.button(this, "接管播放", false);
    takeoverButton.setTextSize(12);
    takeoverButton.setAllCaps(false);
    takeoverButton.setBackground(Ui.shape(this, Ui.SURFACE, 8));
    resourceBar.addView(takeoverButton, lp(96, 44));
    takeoverButton.setOnClickListener(v -> toggleTakeover());
    ImageButton resBtn = Ui.icon(this, "video", "查看网页资源");
    resourceBar.addView(resBtn, lp(48, 48));
    resBtn.setOnClickListener(v -> showResources("视频"));
    resourceBar.setOnClickListener(v -> showResources("视频"));
    shell.addView(resourceBar, new LinearLayout.LayoutParams(-1, -2));
    bottomBar = new LinearLayout(this);
    bottomBar.setGravity(Gravity.CENTER);
    back = addNav("back", "后退", () -> goBack());
    forward =
        addNav(
            "forward",
            "前进",
            () -> {
              if (current.web.canGoForward()) {
                closePlayer(false, false);
                current.web.goForward();
              }
            });
    addNav("home", "主页", () -> navigate(homepage()));
    FrameLayout tabButton = new FrameLayout(this);
    ImageButton tabIcon = Ui.icon(this, "tabs", "标签页");
    tabButton.addView(tabIcon, new FrameLayout.LayoutParams(-1, dp(48)));
    tabCount = Ui.text(this, "1", 10, Ui.TEXT);
    tabCount.setGravity(Gravity.CENTER);
    tabCount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(dp(20), dp(18), Gravity.CENTER);
    tabButton.addView(tabCount, countLp);
    tabButton.setOnClickListener(v -> showTabs());
    tabIcon.setOnClickListener(v -> showTabs());
    bottomBar.addView(tabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
    addNav("more", "菜单", () -> showMenu());
    shell.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(54)));
    setContentView(root);
    ViewCompat.setOnApplyWindowInsetsListener(
        root,
        (v, insets) -> {
          androidx.core.graphics.Insets i =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars()
                      | WindowInsetsCompat.Type.ime()
                      | WindowInsetsCompat.Type.displayCutout());
          v.setPadding(i.left, i.top, i.right, i.bottom);
          return insets;
        });
    applyChromeTheme();
  }

  ImageButton addNav(String icon, String label, Runnable action) {
    ImageButton b = Ui.icon(this, icon, label);
    bottomBar.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
    b.setOnClickListener(v -> action.run());
    return b;
  }

  int dp(float value) {
    return Ui.dp(this, value);
  }

  LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(dp(w), dp(h));
  }

  String homepage() {
    return prefs.getString("homepage", "https://www.baidu.com");
  }

  void hideKeyboard() {
    address.clearFocus();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(address.getWindowToken(), 0);
  }

  static String normalizeAddress(String input) {
    String s = input.trim();
    if (WebResource.isHttp(s)) return s;
    if (!s.contains(" ")
        && (s.contains(".") || s.startsWith("localhost"))
        && WebResource.isHttp("https://" + s)) return "https://" + s;
    try {
      return "https://www.baidu.com/s?wd=" + URLEncoder.encode(s, "UTF-8");
    } catch (java.io.UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  void navigate(String url) {
    if (current == null) return;
    closePlayer(false, false);
    current.web.loadUrl(normalizeAddress(url));
  }

  @SuppressWarnings("SetJavaScriptEnabled")
  BrowserTab newTab(String url, boolean select) {
    if (tabs.size() >= 8) {
      toast("最多打开 8 个标签页");
      return null;
    }
    BrowserTab tab = new BrowserTab();
    WebView w = new WebView(this);
    tab.web = w;
    w.setOnScrollChangeListener(
        (view, x, y, oldX, oldY) -> {
          if (tab == playerTab && playerMode == VideoChrome.Mode.INLINE && !inPip)
            layoutNativeInline();
        });
    w.setBackgroundColor(Ui.BG);
    w.setFocusable(true);
    w.setFocusableInTouchMode(true);
    WebSettings settings = w.getSettings();
    settings.setJavaScriptEnabled(true);
    settings.setDomStorageEnabled(true);
    settings.setMediaPlaybackRequiresUserGesture(false);
    settings.setBuiltInZoomControls(true);
    settings.setDisplayZoomControls(false);
    settings.setUseWideViewPort(true);
    settings.setLoadWithOverviewMode(true);
    settings.setAllowFileAccess(false);
    settings.setAllowContentAccess(false);
    settings.setSupportMultipleWindows(true);
    settings.setJavaScriptCanOpenWindowsAutomatically(false);
    settings.setMixedContentMode(WebSettings.MIXED_CONTENT_NEVER_ALLOW);
    settings.setSafeBrowsingEnabled(true);
    if (prefs.getBoolean("desktop", false))
      settings.setUserAgentString(
          settings
              .getUserAgentString()
              .replace("; wv", "")
              .replace("Mobile", "")
              .replace("Android", "Linux"));
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      WebViewCompat.addWebMessageListener(
          w,
          "WebSurfObserver",
          Collections.singleton("*"),
          (view, message, origin, isMain, reply) -> {
            if (!tabs.contains(tab)
                || message.getData() == null
                || message.getData().length() > 32768) return;
            try {
              JSONObject event = new JSONObject(message.getData());
              String document = event.optString("documentId");
              if (document.isEmpty()) return;
              if ("hello".equals(event.optString("type"))) {
                if (isMain
                    && !event.optString("page").split("#", 2)[0].equals(tab.url.split("#", 2)[0]))
                  return;
                if (tab.documents.size() > 100) return;
                tab.documents.add(document);
                reply.postMessage(
                    new JSONObject()
                        .put("documentId", document)
                        .put("epoch", tab.epoch)
                        .put("dark", Ui.isDark(this))
                        .toString());
                return;
              }
              if (event.optLong("epoch", -1) != tab.epoch || !tab.documents.contains(document))
                return;
              onObservation(tab, event);
            } catch (Exception ignored) {
            }
          });
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
      WebViewCompat.addDocumentStartJavaScript(w, snifferScript, Collections.singleton("*"));
    w.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            if (pendingQuality != null && pendingQuality.tab() == tab) pendingQuality = null;
            tab.epoch++;
            tab.playGeneration++;
            tab.documents.clear();
            tab.url = url;
            tab.resources.clear();
            tab.videos.clear();
            tab.selectedVideo = "";
            tab.takeoverSuppressed = false;
            tab.framePlaying = "";
            if (tab == playerTab) closePlayer(false, false);
            if (tab == current) {
              address.setText(url);
              updateResourceBar();
              progress.setVisibility(View.VISIBLE);
              updateNavigation();
            }
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            view.evaluateJavascript(snifferScript, null);
            sendPageTheme(tab);
            if (tab == current) {
              progress.setVisibility(View.INVISIBLE);
              updateNavigation();
            }
            rememberHistory(tab);
          }

          @Override
          public void doUpdateVisitedHistory(WebView view, String url, boolean reload) {
            tab.url = url;
            if (tab == current && !address.hasFocus()) address.setText(url);
          }

          @Override
          public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
            String url = request.getUrl().toString();
            if (WebResource.isHttp(url)) return false;
            if (request.hasGesture() && request.isForMainFrame()) {
              new AlertDialog.Builder(MainActivity.this)
                  .setTitle("打开其他应用")
                  .setMessage(url)
                  .setNegativeButton("取消", null)
                  .setPositiveButton(
                      "打开",
                      (d, which) -> {
                        try {
                          Intent intent = Intent.parseUri(url, Intent.URI_INTENT_SCHEME);
                          intent.addCategory(Intent.CATEGORY_BROWSABLE);
                          intent.setComponent(null);
                          intent.setSelector(null);
                          startActivity(intent);
                        } catch (Exception e) {
                          toast("没有可打开此链接的应用");
                        }
                      })
                  .show();
            }
            return true;
          }

          @Override
          public WebResourceResponse shouldInterceptRequest(
              WebView view, WebResourceRequest request) {
            if (!request.isForMainFrame() && "GET".equals(request.getMethod())) {
              String url = request.getUrl().toString();
              Map<String, String> headers = new LinkedHashMap<>(request.getRequestHeaders());
              long epoch = tab.epoch;
              handler.post(
                  () -> {
                    if (tabs.contains(tab) && epoch == tab.epoch)
                      observe(tab, url, "", "网络请求", headers, 0, 0, tab.url);
                  });
            }
            return null;
          }

          @Override
          public void onReceivedError(
              WebView view, WebResourceRequest request, WebResourceError error) {
            if (request.isForMainFrame() && tab == current)
              toast("网页加载失败：" + error.getDescription());
          }

          @Override
          public boolean onRenderProcessGone(WebView view, RenderProcessGoneDetail detail) {
            closeTab(tab);
            toast("网页进程已退出，请重新打开页面");
            return true;
          }
        });
    w.setWebChromeClient(
        new WebChromeClient() {
          @Override
          public void onProgressChanged(WebView view, int value) {
            if (tab == current) {
              progress.setProgress(value);
              progress.setVisibility(value >= 100 ? View.INVISIBLE : View.VISIBLE);
            }
          }

          @Override
          public void onReceivedTitle(WebView view, String title) {
            tab.title = title == null ? "网页" : title;
          }

          @Override
          public void onShowCustomView(View view, CustomViewCallback callback) {
            if (fullscreenWeb != null) {
              callback.onCustomViewHidden();
              return;
            }
            fullscreenWeb = new FrameLayout(MainActivity.this);
            fullscreenWeb.setBackgroundColor(Color.BLACK);
            fullscreenWeb.addView(view, new FrameLayout.LayoutParams(-1, -1));
            ImageButton exit = Ui.icon(MainActivity.this, "close", "退出网页全屏");
            exit.setImageTintList(android.content.res.ColorStateList.valueOf(Color.WHITE));
            FrameLayout.LayoutParams p =
                new FrameLayout.LayoutParams(dp(48), dp(48), Gravity.TOP | Gravity.END);
            fullscreenWeb.addView(exit, p);
            exit.setOnClickListener(v -> exitWebFullscreen());
            customViewCallback = callback;
            root.addView(fullscreenWeb, new FrameLayout.LayoutParams(-1, -1));
          }

          @Override
          public void onHideCustomView() {
            exitWebFullscreen();
          }

          @Override
          public boolean onShowFileChooser(
              WebView view, ValueCallback<Uri[]> callback, FileChooserParams params) {
            if (fileCallback != null) fileCallback.onReceiveValue(null);
            fileCallback = callback;
            try {
              startActivityForResult(params.createIntent(), 41);
              return true;
            } catch (Exception e) {
              fileCallback = null;
              return false;
            }
          }

          @Override
          public boolean onCreateWindow(
              WebView view, boolean dialog, boolean userGesture, Message resultMsg) {
            if (!userGesture) return false;
            BrowserTab popup = newTab(null, true);
            if (popup == null) return false;
            ((WebView.WebViewTransport) resultMsg.obj).setWebView(popup.web);
            resultMsg.sendToTarget();
            return true;
          }

          @Override
          public void onCloseWindow(WebView window) {
            for (BrowserTab t : new ArrayList<>(tabs)) if (t.web == window) closeTab(t);
          }
        });
    w.setDownloadListener(
        (uri, ua, disposition, mime, length) -> {
          WebResource resource =
              observe(tab, uri, mime, "下载链接", Collections.emptyMap(), 0, 0, tab.url);
          if (resource != null) confirmDownload(resource);
        });
    tabs.add(tab);
    if (select) selectTab(tab);
    else w.onPause();
    if (url != null) w.loadUrl(url);
    return tab;
  }

  void onObservation(BrowserTab tab, JSONObject event) {
    String type = event.optString("type"),
        url = event.optString("url"),
        page = event.optString("page", tab.url);
    if ("resource".equals(type)) {
      WebResource found =
          observe(
              tab,
              url,
              event.optString("mime"),
              event.optString("source", "网页媒体"),
              Collections.emptyMap(),
              event.optInt("width"),
              event.optInt("height"),
              page);
      if (found != null && found.previewIdentity.isEmpty() && event.has("poster"))
        updatePoster(found, event.optString("poster"));
      return;
    }
    if (event.optString("mediaId").isBlank() || !event.optBoolean("video", true)) return;
    String key = event.optString("documentId") + ":" + event.optString("mediaId");
    VideoTarget previous = tab.videos.get(key);
    if (previous != null && event.optLong("sourceVersion") < previous.sourceVersion) return;
    boolean active = playerTab == tab && activeTarget != null && activeTarget.key().equals(key);
    if ("removed".equals(type)) {
      tab.videos.remove(key);
      if (active) closePlayer(false, false);
      updateResourceBar();
      return;
    }
    VideoTarget target = VideoTarget.read(event, previous);
    tab.videos.put(key, target);
    if (tab.videos.size() > 100) {
      String evict = tab.videos.keySet().iterator().next();
      if (!evict.equals(tab.selectedVideo) && (!active || !evict.equals(key)))
        tab.videos.remove(evict);
    }
    WebResource resource =
        observe(
            tab, url, "video/*", "网页视频", Collections.emptyMap(), target.width, target.height, page);
    bindVideoResource(tab, target, resource, "takeover".equals(type));
    PendingQuality quality = pendingQuality;
    if ("source".equals(type)
        && event.optBoolean("qualitySwitch")
        && quality != null
        && quality.tab() == tab
        && quality.epoch() == tab.epoch
        && quality.key().equals(key)
        && quality.sourceVersion() < target.sourceVersion
        && quality.url().equals(target.url)) {
      pendingQuality = null;
      target.paused = !quality.playIntent();
      openTarget(tab, target, quality.playIntent());
      return;
    }
    if (active
        && "attached".equals(type)
        && player != null
        && target.sourceVersion == activeTarget.sourceVersion
        && !nativeAttachmentReady) {
      nativeAttachmentReady = true;
      player.setPlayWhenReady(requestedPlayIntent && (resumed || inPip));
      player.prepare();
      syncNativeState();
      return;
    }
    if (active && "request".equals(type) && player != null) {
      if (BuildConfig.DEBUG)
        android.util.Log.d(
            "WebSurfPlayback",
            "DOM request=" + event.optString("action") + " version=" + target.sourceVersion);
      switch (event.optString("action")) {
        case "play":
          requestedPlayIntent = true;
          player.play();
          break;
        case "pause":
          requestedPlayIntent = false;
          player.pause();
          break;
        case "seek":
          player.seekTo(Math.max(0, (long) (target.time * 1000)));
          break;
        case "rate":
          player.setPlaybackSpeed((float) Math.max(.25, Math.min(4, target.rate)));
          break;
        case "volume":
          logicalVolume = (float) Math.max(0, Math.min(1, target.volume));
          logicalMuted = target.muted;
          appliedGain = logicalMuted ? 0 : logicalVolume;
          if (videoChrome != null) videoChrome.setUnmutedVolume(logicalVolume);
          player.setVolume(appliedGain);
          break;
      }
      return;
    }
    if (active && "source".equals(type)) {
      if (WebResource.isHttp(target.url)) openTarget(tab, target, false);
      else closePlayer(true, false);
      return;
    }
    if (active && "error".equals(type)) {
      closePlayer(true, true);
      return;
    }
    if (active) layoutPlayer();
    if ("takeover".equals(type)) {
      openTarget(tab, target, true);
    } else if ((("play".equals(type) && target.userInitiated)
            || ("source".equals(type)
                && event.optBoolean("previousNativeFailed")
                && key.equals(tab.selectedVideo)))
        && !target.nativeFailed
        && !target.suppressed
        && prefs.getBoolean("auto_play", true)
        && resumed
        && tab == current) {
      // A deliberate play always supersedes an older video; arrival order never selects an ad.
      tab.selectedVideo = key;
      long epoch = tab.epoch, version = target.sourceVersion;
      handler.postDelayed(
          () -> {
            VideoTarget latest = tab.videos.get(key);
            if (tab != current
                || tab.epoch != epoch
                || !key.equals(tab.selectedVideo)
                || latest == null
                || latest.sourceVersion != version
                || latest.suppressed
                || latest.nativeFailed
                || !resumed
                || !prefs.getBoolean("auto_play", true)) return;
            openTarget(tab, latest, false);
          },
          180);
    }
    if (tab == current) updateResourceBar();
  }

  WebResource observe(
      BrowserTab tab,
      String url,
      String mime,
      String source,
      Map<String, String> headers,
      int width,
      int height,
      String page) {
    if (!WebResource.isHttp(url)) return null;
    WebResource r = tab.resources.get(url);
    if (r == null) {
      r = new WebResource(url);
      tab.resources.put(url, r);
      if (tab.resources.size() > 400) {
        String evict = tab.resources.keySet().iterator().next();
        for (WebResource item : tab.resources.values())
          if (!item.isVideo() && !"音频".equals(item.kind)) {
            evict = item.url;
            break;
          }
        tab.resources.remove(evict);
      }
    }
    if (mime != null && !mime.isBlank() && (!mime.endsWith("/*") || r.mime.isBlank()))
      r.mime = mime.split(";", 2)[0];
    r.classify();
    r.source = source;
    r.seenAt = System.currentTimeMillis();
    r.pageUrl = page;
    if (!"播放画面".equals(r.qualitySource)) {
      if (width > 0) r.width = width;
      if (height > 0) r.height = height;
    }
    if (r == playing && player != null && player.getVideoSize().width > 0) {
      r.width = player.getVideoSize().width;
      r.height = player.getVideoSize().height;
      r.qualitySource = "播放画面";
    }
    for (Map.Entry<String, String> e : headers.entrySet()) {
      String key = e.getKey();
      if (key.equalsIgnoreCase("User-Agent")
          || key.equalsIgnoreCase("Referer")
          || key.equalsIgnoreCase("Origin")
          || key.equalsIgnoreCase("Authorization")) r.headers.put(key, e.getValue());
    }
    if (tab == current) updateResourceBar();
    return r;
  }

  void updatePoster(WebResource resource, String poster) {
    if (resource != null
        && (poster.isEmpty() || WebResource.isHttp(poster))
        && !poster.equals(resource.posterUrl)) {
      resource.posterUrl = poster;
      resource.previewRevision++;
    }
  }

  void bindVideoResource(
      BrowserTab tab, VideoTarget target, WebResource resource, boolean explicit) {
    if (resource == null) return;
    String prefix = target.key() + ":", identity = prefix + target.sourceVersion;
    // URLs are deduplicated in the list. Keep the chosen video's identity when elements share a
    // URL.
    if (!explicit
        && !resource.previewIdentity.isEmpty()
        && !resource.previewIdentity.startsWith(prefix)
        && !target.key().equals(tab.selectedVideo)) return;
    if (!identity.equals(resource.previewIdentity)) {
      resource.previewIdentity = identity;
      resource.posterUrl = "";
      resource.width = target.width;
      resource.height = target.height;
      resource.qualitySource = "";
      resource.maxVariantWidth = resource.maxVariantHeight = 0;
      resource.availableQualities = "";
      resource.durationMs = 0;
      resource.previewRevision++;
    }
    resource.title = target.title;
    updatePoster(resource, target.poster);
  }

  void capturePlayingPreview(ExoPlayer session, WebResource resource) {
    if (session != player
        || resource != playing
        || playerView == null
        || !WebResource.isHttp(resource.url)
        || !(playerView.getVideoSurfaceView() instanceof TextureView texture)
        || !texture.isAvailable()) return;
    VideoSize size = session.getVideoSize();
    if (size.width <= 0 || size.height <= 0) return;
    float factor = Math.min(1, Math.min(320f / size.width, 480f / size.height));
    int width = Math.max(1, Math.round(size.width * factor));
    int height = Math.max(1, Math.round(size.height * factor));
    // Capture only the current session's rendered surface; the loader owns its resized copy.
    android.graphics.Bitmap bitmap = texture.getBitmap(width, height);
    if (bitmap != null) {
      previewLoader.seed(resource, headersFor(resource), bitmap, size.width, size.height);
      bitmap.recycle();
    }
  }

  Map<String, String> headersFor(WebResource r) {
    Map<String, String> h = new LinkedHashMap<>(r.headers);
    if (current != null) h.put("User-Agent", current.web.getSettings().getUserAgentString());
    if (!r.pageUrl.isBlank() && !h.containsKey("Referer")) h.put("Referer", r.pageUrl);
    String cookie = CookieManager.getInstance().getCookie(r.url);
    if (cookie != null && !cookie.isBlank()) h.put("Cookie", cookie);
    return h;
  }

  void selectTab(BrowserTab tab) {
    if (current == tab) return;
    pendingQuality = null;
    closePlayer(false, false);
    if (current != null) current.web.onPause();
    current = tab;
    webContainer.removeAllViews();
    if (tab.web.getParent() != null)
      ((android.view.ViewGroup) tab.web.getParent()).removeView(tab.web);
    webContainer.addView(tab.web, new FrameLayout.LayoutParams(-1, -1));
    tab.web.onResume();
    sendPageTheme(tab);
    address.setText(tab.url);
    hideKeyboard();
    updateNavigation();
    updateResourceBar();
  }

  void closeTab(BrowserTab tab) {
    if (tab == playerTab) closePlayer(false, false);
    int index = tabs.indexOf(tab);
    tabs.remove(tab);
    if (tab == current) {
      webContainer.removeAllViews();
      current = null;
      if (!tabs.isEmpty()) selectTab(tabs.get(Math.min(index, tabs.size() - 1)));
    }
    tab.web.stopLoading();
    tab.web.destroy();
    if (tabs.isEmpty()) newTab(homepage(), true);
    updateNavigation();
  }

  void updateNavigation() {
    if (current == null) return;
    back.setEnabled(current.web.canGoBack());
    forward.setEnabled(current.web.canGoForward());
    tabCount.setText(String.valueOf(tabs.size()));
  }

  void updateResourceBar() {
    if (current == null) return;
    long count = current.resources.values().stream().filter(WebResource::isVideo).count();
    resourceText.setText(
        count > 0
            ? "视频 " + count + " · 资源 " + current.resources.size()
            : "网页资源 · " + current.resources.size());
    if (takeoverButton != null) {
      takeoverButton.setText(player != null && playerTab == current ? "网页播放" : "接管播放");
      takeoverButton.setTextColor(Ui.CONTROL);
      takeoverButton.setEnabled(player != null || !current.videos.isEmpty());
    }
  }

  void openPlayer(WebResource resource, long position) {
    if (current != null) {
      for (VideoTarget target : current.videos.values()) {
        if (resource.url.equals(target.url)) {
          openTarget(current, target, true);
          return;
        }
      }
    }
    startPlayer(resource, position, null, VideoChrome.Mode.FLOATING, true);
  }

  void openTarget(BrowserTab tab, VideoTarget target, boolean explicit) {
    if (tab != current || !WebResource.isHttp(target.url)) {
      if (explicit) toast("此视频使用网页专用媒体源，请继续在网页播放");
      return;
    }
    if (activeTarget != null
        && playerTab == tab
        && activeTarget.key().equals(target.key())
        && activeTarget.sourceVersion == target.sourceVersion
        && player != null) {
      if (explicit) {
        requestedPlayIntent = true;
        player.play();
      }
      return;
    }
    WebResource resource =
        observe(
            tab,
            target.url,
            "video/*",
            "网页视频",
            Collections.emptyMap(),
            target.width,
            target.height,
            target.page);
    if (resource == null) return;
    bindVideoResource(tab, target, resource, true);
    if (player != null
        && playerTab == tab
        && activeTarget != null
        && activeTarget.key().equals(target.key())
        && activeTarget.sourceVersion == target.sourceVersion
        && activeTarget.url.equals(target.url)) {
      if (explicit) player.play();
      return;
    }
    target.suppressed = false;
    target.nativeFailed = false;
    tab.selectedVideo = target.key();
    int preferred = prefs.getInt("preferred_quality", 0);
    VideoTarget.QualitySource desired = preferredSource(target, preferred);
    if (desired != null && !desired.url().equals(target.url)) {
      requestPageQuality(tab, target, desired, explicit || !target.paused);
      return;
    }
    VideoChrome.Mode next =
        activeTarget != null && activeTarget.key().equals(target.key())
            ? playerMode
            : VideoChrome.Mode.INLINE;
    boolean intent =
        activeTarget != null && activeTarget.key().equals(target.key()) && player != null
            ? player.getPlayWhenReady()
            : explicit || !target.paused;
    startPlayer(resource, Math.max(0, (long) (target.time * 1000)), target, next, intent);
  }

  void startPlayer(
      WebResource resource,
      long position,
      VideoTarget target,
      VideoChrome.Mode mode,
      boolean playIntent) {
    closePlayer(false, false);
    playerStartPosition = position;
    playerRenderedFrame = false;
    nativeAttachmentReady = target == null;
    requestedPlayIntent = playIntent;
    playing = resource;
    playerTab = current;
    activeTarget = target;
    if (target != null && playerTab != null) playerTab.selectedVideo = target.key();
    logicalVolume = target == null ? 1 : (float) Math.max(0, Math.min(1, target.volume));
    logicalMuted = target != null && target.muted;
    appliedGain = logicalMuted ? 0 : logicalVolume;
    playerMode = mode;
    ScopedHttpDataSource.Factory http =
        new ScopedHttpDataSource.Factory(resource.url, headersFor(resource));
    player =
        new ExoPlayer.Builder(this)
            .setMediaSourceFactory(
                new DefaultMediaSourceFactory(this)
                    .setDataSourceFactory(
                        new androidx.media3.datasource.DefaultDataSource.Factory(this, http)))
            .build();
    player.setAudioAttributes(
        new AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
            .build(),
        true);
    videoChrome =
        new VideoChrome(
            this,
            new VideoChrome.Callback() {
              @Override
              public void onClose() {
                closePlayer(true, true);
              }

              @Override
              public void onModeRequested(VideoChrome.Mode mode) {
                setPlayerMode(mode);
              }

              @Override
              public void onDownload() {
                if (playing != null) confirmDownload(playing);
              }

              @Override
              public void onSelectSource() {
                showVideoTargets();
              }

              @Override
              public void onSelectQuality() {
                showQualityOptions();
              }

              @Override
              public void onDrag(float dx, float dy) {
                if (playerMode != VideoChrome.Mode.FLOATING || videoChrome == null) return;
                floatX += dx;
                floatY += dy;
                layoutPlayer();
              }

              @Override
              public void onRetry() {
                if (player != null) {
                  player.prepare();
                  player.play();
                }
              }
            });
    videoChrome.bind(player);
    videoChrome.setUnmutedVolume(logicalVolume);
    videoChrome.setTitle(resource.title.isBlank() ? "网页视频" : resource.title);
    videoChrome.setDownloadVisible(WebResource.isHttp(resource.url));
    playerView = videoChrome.getPlayerView();
    anchorClip = new InlineVideoHost(this, playerTab.web);
    anchorClip.setClipChildren(true);
    anchorClip.setClipToPadding(true);
    final ExoPlayer session = player;
    if (BuildConfig.DEBUG)
      android.util.Log.d(
          "WebSurfPlayback",
          "start intent="
              + playIntent
              + " position="
              + position
              + " session="
              + System.identityHashCode(session));
    player.addListener(
        new Player.Listener() {
          @Override
          public void onPlayWhenReadyChanged(boolean ready, int reason) {
            if (session == player
                && !nativeAttachmentReady
                && reason == Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST)
              requestedPlayIntent = ready;
            if (BuildConfig.DEBUG)
              android.util.Log.d(
                  "WebSurfPlayback",
                  "intent="
                      + ready
                      + " reason="
                      + reason
                      + " session="
                      + System.identityHashCode(session));
          }

          @Override
          public void onEvents(Player p, Player.Events events) {
            if (session == player) {
              if (Math.abs(player.getVolume() - appliedGain) > .0001f) {
                appliedGain = player.getVolume();
                logicalMuted = appliedGain == 0;
                if (!logicalMuted) logicalVolume = appliedGain;
              }
              syncNativeState();
              if (events.contains(Player.EVENT_IS_PLAYING_CHANGED)) layoutPlayer();
            }
          }

          @Override
          public void onPlayerError(PlaybackException error) {
            handler.post(() -> recoverNativeFailure(session));
          }

          @Override
          public void onRenderedFirstFrame() {
            if (session == player) playerRenderedFrame = true;
            handler.postDelayed(() -> capturePlayingPreview(session, resource), 120);
          }

          @Override
          public void onTracksChanged(Tracks tracks) {
            if (session == player)
              QualityOptions.applyPreferredHeight(session, prefs.getInt("preferred_quality", 0));
          }

          @Override
          public void onVideoSizeChanged(VideoSize size) {
            if (session != player || resource != playing || size.width <= 0 || size.height <= 0)
              return;
            if (resource.width != size.width || resource.height != size.height)
              resource.previewRevision++;
            resource.width = size.width;
            resource.height = size.height;
            resource.qualitySource = "播放画面";
            handler.postDelayed(() -> capturePlayingPreview(session, resource), 160);
          }
        });
    MediaItem.Builder item = new MediaItem.Builder().setUri(resource.url);
    if (resource.isHls()) item.setMimeType(MimeTypes.APPLICATION_M3U8);
    player.setMediaItem(item.build(), position);
    if (target != null) {
      // IDLE records intent without acquiring audio focus. Controls can cancel pending playback;
      // only the matching webpage attachment acknowledgement may prepare the native decoder.
      player.setPlayWhenReady(playIntent);
      player.setPlaybackSpeed((float) Math.max(.25, Math.min(4, target.rate)));
      player.setVolume(appliedGain);
      command(playerTab, target, "attach", new JSONObject());
      handler.postDelayed(
          () -> {
            if (session == player && !nativeAttachmentReady) recoverNativeFailure(session);
          },
          2000);
    } else {
      player.prepare();
      player.setPlayWhenReady(playIntent);
    }
    layoutPlayer();
    handler.removeCallbacks(playerTicker);
    handler.post(playerTicker);
    updateResourceBar();
  }

  final Runnable playerTicker =
      new Runnable() {
        @Override
        public void run() {
          if (player == null) return;
          syncNativeState();
          handler.postDelayed(this, 250);
        }
      };

  void syncNativeState() {
    if (player == null || activeTarget == null || playerTab == null || !nativeAttachmentReady)
      return;
    // A native decoding/network error is not a DOM MediaError. Restore the web decoder instead.
    if (player.getPlayerError() != null) return;
    JSONObject state = new JSONObject();
    try {
      state.put("time", player.getCurrentPosition() / 1000.0);
      if (player.getDuration() != C.TIME_UNSET)
        state.put("duration", player.getDuration() / 1000.0);
      state.put(
          "paused", !player.getPlayWhenReady() || player.getPlaybackState() == Player.STATE_ENDED);
      state.put("ended", player.getPlaybackState() == Player.STATE_ENDED);
      state.put("rate", player.getPlaybackParameters().speed);
      state.put("volume", logicalVolume);
      state.put("muted", logicalMuted);
      state.put("buffering", player.getPlaybackState() == Player.STATE_BUFFERING);
      state.put("ready", player.getPlaybackState() == Player.STATE_READY);
      int phase = player.getPlaybackState();
      state.put(
          "readyState",
          phase == Player.STATE_READY || phase == Player.STATE_ENDED
              ? 4
              : phase == Player.STATE_BUFFERING && player.getDuration() != C.TIME_UNSET ? 2 : 0);
      state.put("error", JSONObject.NULL);
      command(playerTab, activeTarget, "sync", state);
    } catch (JSONException ignored) {
    }
  }

  void command(BrowserTab tab, VideoTarget target, String action, JSONObject data) {
    if (tab == null || target == null || !tabs.contains(tab)) return;
    try {
      data.put("action", action)
          .put("documentId", target.documentId)
          .put("mediaId", target.mediaId)
          .put("sourceVersion", target.sourceVersion);
      tab.web.evaluateJavascript("window.__websurf&&window.__websurf.command(" + data + ")", null);
    } catch (JSONException ignored) {
    }
  }

  void setPlayerMode(VideoChrome.Mode mode) {
    if (mode == VideoChrome.Mode.INLINE && activeTarget == null) mode = VideoChrome.Mode.FLOATING;
    playerMode = mode;
    layoutPlayer();
  }

  void updateImmersivePlayer() {
    boolean full = player != null && playerMode == VideoChrome.Mode.FULLSCREEN && !inPip;
    if (full == immersivePlayer) return;
    androidx.core.view.WindowInsetsControllerCompat controller =
        new androidx.core.view.WindowInsetsControllerCompat(getWindow(), root);
    if (full) {
      previousOrientation = getRequestedOrientation();
      immersivePlayer = true;
      VideoSize size = player.getVideoSize();
      int width = size.width > 0 ? size.width : playing == null ? 16 : playing.width;
      int height = size.height > 0 ? size.height : playing == null ? 9 : playing.height;
      setRequestedOrientation(
          width >= height
              ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
              : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT);
      controller.setSystemBarsBehavior(
          androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
      controller.hide(WindowInsetsCompat.Type.systemBars());
    } else {
      immersivePlayer = false;
      controller.show(WindowInsetsCompat.Type.systemBars());
      setRequestedOrientation(previousOrientation);
    }
  }

  void layoutPlayer() {
    if (videoChrome == null) return;
    updateImmersivePlayer();
    boolean inline = playerMode == VideoChrome.Mode.INLINE && !inPip && activeTarget != null;
    videoChrome.setMode(inPip ? VideoChrome.Mode.FULLSCREEN : playerMode);
    if (inline) {
      if (videoChrome.getParent() != anchorClip) {
        removeFromParent(videoChrome);
        anchorClip.addView(videoChrome);
      }
      if (anchorClip.getParent() != webContainer) {
        removeFromParent(anchorClip);
        webContainer.addView(anchorClip);
      }
      layoutNativeInline();
    } else {
      removeFromParent(anchorClip);
      videoChrome.setTranslationX(0);
      videoChrome.setTranslationY(0);
      if (videoChrome.getParent() != root) {
        removeFromParent(videoChrome);
        root.addView(videoChrome);
      }
      if (playerMode == VideoChrome.Mode.FULLSCREEN || inPip) {
        videoChrome.setLayoutParams(new FrameLayout.LayoutParams(-1, -1));
      } else {
        int availableW =
            Math.max(dp(200), root.getWidth() - root.getPaddingLeft() - root.getPaddingRight());
        int availableH =
            Math.max(dp(160), root.getHeight() - root.getPaddingTop() - root.getPaddingBottom());
        int width = Math.min(dp(352), availableW - dp(24)), height = Math.round(width * 9f / 16f);
        if (Float.isNaN(floatX)) {
          floatX = availableW - width - dp(12);
          floatY = availableH - height - dp(118);
        }
        floatX = Math.max(0, Math.min(floatX, availableW - width));
        floatY = Math.max(0, Math.min(floatY, availableH - height));
        FrameLayout.LayoutParams params = new FrameLayout.LayoutParams(width, height);
        params.leftMargin = Math.round(floatX);
        params.topMargin = Math.round(floatY);
        videoChrome.setLayoutParams(params);
      }
    }
    root.setKeepScreenOn(player != null && player.getPlayWhenReady());
  }

  static void removeFromParent(View view) {
    if (view != null && view.getParent() instanceof ViewGroup)
      ((ViewGroup) view.getParent()).removeView(view);
  }

  /** Normal document scrolling follows WebView's native position in the same UI callback. */
  void layoutNativeInline() {
    if (videoChrome == null
        || anchorClip == null
        || playerTab == null
        || activeTarget == null
        || playerMode != VideoChrome.Mode.INLINE
        || inPip) return;
    VideoTarget target = playerTab.videos.get(activeTarget.key());
    if (target == null) target = activeTarget;
    WebView web = playerTab.web;
    float scale = target.viewportWidth > 0 ? web.getWidth() / target.viewportWidth : 0;
    if (!(scale > 0) || target.rect.isEmpty() || target.contentClip.isEmpty()) {
      anchorClip.setVisibility(View.INVISIBLE);
      inlinePinned = false;
      inlineExpansion = 0;
      return;
    }
    float dx =
        target.nativeScrollFollow
            ? (target.topScrollX + target.visualOffsetX) * scale - web.getScrollX()
            : 0;
    float dy =
        target.nativeScrollFollow
            ? (target.topScrollY + target.visualOffsetY) * scale - web.getScrollY()
            : 0;
    android.graphics.RectF rect = scaledRect(target.rect, scale, dx, dy);
    android.graphics.RectF visible = scaledRect(target.contentClip, scale, dx, dy);
    InlineVideoGeometry.Placement placement =
        InlineVideoGeometry.place(
            rect.left,
            rect.top,
            rect.width(),
            rect.height(),
            web.getWidth(),
            target.nativeScrollFollow,
            player != null && player.isPlaying(),
            inlinePinned);
    inlinePinned = placement.pinned();
    inlineExpansion = placement.expansion();
    rect.set(
        placement.left(),
        placement.top(),
        placement.left() + placement.width(),
        placement.top()
            + (inlinePinned ? Math.min(placement.height(), web.getHeight()) : placement.height()));
    if (inlinePinned) visible.set(0, 0, web.getWidth(), web.getHeight());
    if (!visible.intersect(rect) || !visible.intersect(0, 0, web.getWidth(), web.getHeight())) {
      anchorClip.setVisibility(View.INVISIBLE);
      return;
    }
    anchorClip.setVisibility(View.VISIBLE);
    placeFrame(anchorClip, visible.width(), visible.height(), visible.left, visible.top);
    placeFrame(
        videoChrome, rect.width(), rect.height(), rect.left - visible.left, rect.top - visible.top);
  }

  static android.graphics.RectF scaledRect(
      android.graphics.RectF value, float scale, float dx, float dy) {
    return new android.graphics.RectF(
        value.left * scale + dx,
        value.top * scale + dy,
        value.right * scale + dx,
        value.bottom * scale + dy);
  }

  static void placeFrame(View view, float width, float height, float x, float y) {
    int w = Math.max(1, Math.round(width)), h = Math.max(1, Math.round(height));
    if (!(view.getLayoutParams() instanceof FrameLayout.LayoutParams p)
        || p.width != w
        || p.height != h
        || p.leftMargin != 0
        || p.topMargin != 0) {
      view.setLayoutParams(new FrameLayout.LayoutParams(w, h));
    }
    // Translation changes are immediate; plain page scrolls do not queue a fresh layout pass.
    view.setTranslationX(x);
    view.setTranslationY(y);
  }

  void closePlayer(boolean returnToWeb, boolean suppress) {
    closePlayer(returnToWeb, suppress, false);
  }

  void recoverNativeFailure(ExoPlayer session) {
    if (session != player || activeTarget == null || playerTab == null) return;
    VideoTarget latest = playerTab.videos.get(activeTarget.key());
    if (latest == null || latest.sourceVersion != activeTarget.sourceVersion) {
      closePlayer(false, false);
      return;
    }
    closePlayer(true, false, true);
  }

  void closePlayer(boolean returnToWeb, boolean suppress, boolean failed) {
    if (qualityDialog != null) {
      qualityDialog.dismiss();
      qualityDialog = null;
    }
    BrowserTab owner = playerTab;
    VideoTarget target = activeTarget;
    long position = player == null ? 0 : player.getCurrentPosition();
    if (failed && !playerRenderedFrame) position = Math.max(position, playerStartPosition);
    if (returnToWeb && !failed) pendingQuality = null;
    boolean resume =
        returnToWeb
            && player != null
            && (nativeAttachmentReady ? player.getPlayWhenReady() : requestedPlayIntent)
            && player.getPlaybackState() != Player.STATE_ENDED;
    if (owner != null && target != null) {
      VideoTarget latest = owner.videos.get(target.key());
      if (latest != null && suppress) latest.suppressed = true;
      if (latest != null && failed) latest.nativeFailed = true;
      owner.selectedVideo = target.key();
      owner.takeoverSuppressed = suppress;
    }
    handler.removeCallbacks(playerTicker);
    // Remove listeners and surface before release; native callbacks cannot mutate a successor
    // session.
    ExoPlayer old = player;
    player = null;
    if (videoChrome != null) videoChrome.release();
    if (old != null) old.release();
    removeFromParent(videoChrome);
    removeFromParent(anchorClip);
    videoChrome = null;
    anchorClip = null;
    playerView = null;
    playing = null;
    playerTab = null;
    activeTarget = null;
    inlinePinned = false;
    inlineExpansion = 0;
    root.setKeepScreenOn(false);
    updateImmersivePlayer();
    if (owner != null && target != null) {
      try {
        command(
            owner,
            target,
            "detach",
            new JSONObject()
                .put("time", Math.max(0, position) / 1000.0)
                .put("resume", resume)
                .put("failed", failed)
                .put("reason", failed ? "native-failure" : "user")
                .put("failedSourceVersion", target.sourceVersion));
      } catch (JSONException ignored) {
      }
    }
    if (resourceText != null) updateResourceBar();
  }

  void toggleTakeover() {
    if (player != null && playerTab == current) {
      closePlayer(true, true);
      return;
    }
    if (current == null || current.videos.isEmpty()) {
      toast("尚未发现可接管的视频");
      return;
    }
    VideoTarget selected = current.videos.get(current.selectedVideo);
    if (selected != null) {
      openTarget(current, selected, true);
      return;
    }
    if (current.videos.size() == 1) {
      openTarget(current, current.videos.values().iterator().next(), true);
      return;
    }
    showVideoTargets();
  }

  static VideoTarget.QualitySource preferredSource(VideoTarget target, int preferred) {
    if (preferred <= 0) return null;
    VideoTarget.QualitySource below = null, lowest = null;
    for (VideoTarget.QualitySource source : target.qualitySources) {
      int resolution = source.resolution();
      if (resolution <= 0) continue;
      if (lowest == null || resolution < lowest.resolution()) lowest = source;
      if (resolution <= preferred && (below == null || resolution > below.resolution()))
        below = source;
    }
    return below == null ? lowest : below;
  }

  void requestPageQuality(
      BrowserTab tab, VideoTarget target, VideoTarget.QualitySource choice, boolean playIntent) {
    if (choice.url().equals(target.url)) return;
    PendingQuality request =
        new PendingQuality(
            tab, tab.epoch, target.key(), target.sourceVersion, choice.url(), playIntent);
    pendingQuality = request;
    double time =
        player != null
                && playerTab == tab
                && activeTarget != null
                && activeTarget.key().equals(target.key())
            ? player.getCurrentPosition() / 1000.0
            : target.time;
    try {
      command(
          tab,
          target,
          "quality",
          new JSONObject()
              .put("url", choice.url())
              .put("time", time)
              .put("playIntent", playIntent));
    } catch (JSONException ignored) {
      pendingQuality = null;
    }
    handler.postDelayed(
        () -> {
          if (pendingQuality != request) return;
          pendingQuality = null;
          if (tab == current && tab.epoch == request.epoch()) toast("网页未切换画质，可使用网页画质入口");
        },
        4000);
  }

  void showQualityOptions() {
    if (player == null) return;
    if (qualityDialog != null) qualityDialog.dismiss();
    ExoPlayer session = player;
    BrowserTab owner = playerTab;
    VideoTarget target =
        activeTarget == null || owner == null ? null : owner.videos.get(activeTarget.key());
    QualityOptions.Snapshot nativeOptions = QualityOptions.snapshot(session);
    ArrayList<String> labels = new ArrayList<>();
    ArrayList<Runnable> actions = new ArrayList<>();
    int selected = -1;
    if (target != null
        && target.qualitySources.stream().anyMatch(q -> !q.url().equals(target.url))
        && !nativeOptions.hasMultipleQualities()) {
      if (target.qualitySources.stream().noneMatch(q -> q.url().equals(target.url))) {
        labels.add("当前 · " + nativeOptions.currentLabel());
        actions.add(() -> {});
        selected = 0;
      }
      for (VideoTarget.QualitySource choice : target.qualitySources) {
        if (choice.url().equals(target.url)) selected = labels.size();
        labels.add(choice.label() + " · 网页");
        actions.add(
            () -> {
              VideoTarget latest = owner.videos.get(target.key());
              if (player != session
                  || latest == null
                  || latest.sourceVersion != target.sourceVersion) return;
              prefs.edit().putInt("preferred_quality", Math.max(0, choice.resolution())).apply();
              requestPageQuality(owner, latest, choice, session.getPlayWhenReady());
            });
      }
    } else {
      selected = nativeOptions.selectedIndex();
      for (QualityOptions.Choice choice : nativeOptions.choices()) {
        labels.add(choice.label());
        actions.add(
            () -> {
              if (player != session) return;
              prefs
                  .edit()
                  .putInt("preferred_quality", choice.automatic() ? 0 : choice.resolution())
                  .apply();
              QualityOptions.apply(session, choice);
            });
      }
    }
    AlertDialog.Builder dialog =
        new AlertDialog.Builder(
                new android.view.ContextThemeWrapper(
                    this, Ui.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme))
            .setTitle("画质")
            .setNegativeButton("关闭", null);
    if (labels.isEmpty()) dialog.setMessage("当前视频未提供可选清晰度");
    else
      dialog.setSingleChoiceItems(
          labels.toArray(new String[0]),
          selected,
          (view, index) -> {
            view.dismiss();
            actions.get(index).run();
          });
    if (target != null)
      dialog.setNeutralButton(
          "网页画质",
          (view, which) -> {
            if (player == session) closePlayer(true, true);
          });
    qualityDialog = dialog.show();
  }

  void showVideoTargets() {
    LinearLayout body = scrollBody(sheet("选择网页视频"));
    if (current == null || current.videos.isEmpty()) {
      note(body, "尚未发现网页视频");
      return;
    }
    int n = 0;
    for (VideoTarget t : new ArrayList<>(current.videos.values())) {
      String label =
          "视频 "
              + (++n)
              + (t.height > 0 ? " · " + t.height + "p" : "")
              + (t.duration > 0 ? " · " + (int) t.duration + " 秒" : "")
              + (activeTarget != null && activeTarget.key().equals(t.key()) ? " · 当前播放" : "");
      action(
          body,
          label,
          () -> {
            activeSheet.dismiss();
            openTarget(current, t, true);
          });
    }
  }

  LinearLayout sheet(String title) {
    if (activeSheet != null) activeSheet.dismiss();
    Dialog dialog = new Dialog(this);
    activeSheet = dialog;
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setPadding(dp(16), dp(8), dp(16), dp(16));
    box.setBackground(Ui.shape(this, Ui.BG, 12));
    LinearLayout header = new LinearLayout(this);
    header.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading = Ui.text(this, title, 20, Ui.TEXT);
    heading.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    header.addView(heading, new LinearLayout.LayoutParams(0, dp(56), 1));
    ImageButton close = Ui.icon(this, "close", "关闭" + title);
    header.addView(close, lp(48, 48));
    close.setOnClickListener(v -> dialog.dismiss());
    box.addView(header);
    dialog.setContentView(box);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setGravity(Gravity.BOTTOM);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams attr = window.getAttributes();
      attr.dimAmount = .28f;
      window.setAttributes(attr);
    }
    dialog.setOnDismissListener(
        d -> {
          if (sheetRefresh != null) {
            handler.removeCallbacks(sheetRefresh);
            sheetRefresh = null;
          }
          if (activeSheet == dialog) activeSheet = null;
        });
    dialog.show();
    if (window != null) window.setLayout(-1, -2);
    Ui.enter(box);
    return box;
  }

  LinearLayout scrollBody(LinearLayout box) {
    ScrollView scroll = new ScrollView(this);
    scroll.setFillViewport(false);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(body);
    box.addView(
        scroll,
        new LinearLayout.LayoutParams(
            -1,
            Math.min(
                dp(470),
                Math.max(
                    dp(96),
                    root.getHeight() - root.getPaddingTop() - root.getPaddingBottom() - dp(200)))));
    return body;
  }

  TextView note(LinearLayout box, String text) {
    TextView label = Ui.text(this, text, 12, Ui.MUTED);
    label.setPadding(0, dp(8), 0, dp(8));
    box.addView(label, new LinearLayout.LayoutParams(-1, -2));
    return label;
  }

  Button action(LinearLayout box, String title, Runnable callback) {
    Button b = Ui.button(this, title, false);
    box.addView(b, new LinearLayout.LayoutParams(-1, dp(48)));
    b.setOnClickListener(v -> callback.run());
    return b;
  }

  void showResources(String initialFilter) {
    if (current == null) return;
    BrowserTab owner = current;
    owner.web.evaluateJavascript("window.__websurf&&window.__websurf.scan()", null);
    LinearLayout box = sheet("网页资源");
    HorizontalScrollView filters = new HorizontalScrollView(this);
    filters.setTag("resource-filters");
    filters.setHorizontalScrollBarEnabled(false);
    LinearLayout choices = new LinearLayout(this);
    filters.addView(choices);
    LinearLayout.LayoutParams filterLayout = new LinearLayout.LayoutParams(-1, -2);
    filterLayout.bottomMargin = dp(16);
    box.addView(filters, filterLayout);
    LinearLayout body = scrollBody(box);
    body.setTag("resource-list");
    String[] filter = {initialFilter};
    String[] fingerprint = {""};
    Runnable render =
        () -> {
          StringBuilder key = new StringBuilder(filter[0]);
          for (WebResource r : owner.resources.values())
            key.append(r.url)
                .append(r.mime)
                .append(r.width)
                .append(r.height)
                .append(r.posterUrl)
                .append(r.previewRevision);
          if (key.toString().equals(fingerprint[0])) return;
          fingerprint[0] = key.toString();
          body.removeAllViews();
          List<WebResource> list = new ArrayList<>(owner.resources.values());
          Collections.reverse(list);
          int shown = 0;
          for (WebResource r : list) {
            if (!"全部".equals(filter[0]) && !filter[0].equals(r.kind)) continue;
            shown++;
            ResourceRow row =
                new ResourceRow(this, r, previewLoader, headersFor(r), () -> resourceActions(r));
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(8);
            body.addView(row, p);
          }
          if (shown == 0)
            note(body, "尚未发现" + ("全部".equals(filter[0]) ? "资源" : filter[0]) + "，播放或浏览网页后会自动更新。");
        };
    for (String name : new String[] {"视频", "音频", "图片", "其他", "全部"}) {
      Button b = Ui.button(this, name, name.equals(initialFilter));
      b.setSingleLine(true);
      b.setPadding(dp(8), 0, dp(8), 0);
      LinearLayout.LayoutParams p = lp(68, 48);
      p.rightMargin = dp(4);
      choices.addView(b, p);
      b.setOnClickListener(
          v -> {
            filter[0] = name;
            for (int i = 0; i < choices.getChildCount(); i++) {
              Button child = (Button) choices.getChildAt(i);
              boolean selected = child == b;
              child.setTextColor(selected ? Color.WHITE : Ui.TEXT);
              child.setBackgroundTintList(null);
              Ui.ripple(child, selected ? Ui.ACCENT : Ui.SURFACE, 8);
            }
            render.run();
          });
    }
    render.run();
    sheetRefresh =
        new Runnable() {
          public void run() {
            if (activeSheet == null) return;
            render.run();
            handler.postDelayed(this, 700);
          }
        };
    handler.postDelayed(sheetRefresh, 700);
  }

  void resourceActions(WebResource r) {
    String[] options =
        r.isVideo() || "音频".equals(r.kind)
            ? new String[] {"播放视频", "下载", "复制链接", "查看地址"}
            : new String[] {"打开预览", "下载", "复制链接", "查看地址"};
    new AlertDialog.Builder(this)
        .setTitle(r.label())
        .setItems(
            options,
            (d, index) -> {
              if (index == 0) {
                if (activeSheet != null) activeSheet.dismiss();
                if (r.isVideo() || "音频".equals(r.kind)) openPlayer(r, 0);
                else navigate(r.url);
              } else if (index == 1) confirmDownload(r);
              else if (index == 2) copy(r.url);
              else
                new AlertDialog.Builder(this)
                    .setTitle("资源地址")
                    .setMessage(r.url)
                    .setPositiveButton("复制", (a, b) -> copy(r.url))
                    .setNegativeButton("关闭", null)
                    .show();
            })
        .show();
  }

  void confirmDownload(WebResource r) {
    String explanation = r.isHls() ? "将下载可用的最高画质并合并为 MP4。若地址是单一画质，则保留该画质。" : "保存到 Download/WebSurf";
    new AlertDialog.Builder(this)
        .setTitle("下载" + r.kind)
        .setMessage(r.label() + "\n\n" + explanation)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "下载",
            (d, w) -> {
              if (Build.VERSION.SDK_INT >= 33
                  && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                      != PackageManager.PERMISSION_GRANTED)
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
              try {
                DownloadService.enqueue(
                    this, r.url, r.title.isBlank() ? r.label() : r.title, headersFor(r), r.mime);
                toast("已加入下载");
              } catch (Exception e) {
                showError("无法开始下载", e);
              }
            })
        .show();
  }

  void showDownloads() {
    LinearLayout box = sheet("下载");
    LinearLayout body = scrollBody(box);
    String[] fingerprint = {""};
    Runnable render =
        () -> {
          List<DownloadStore.Job> jobs = DownloadStore.list(this);
          StringBuilder key = new StringBuilder();
          for (DownloadStore.Job j : jobs)
            key.append(j.id).append(j.state).append(j.progress).append(j.error);
          if (key.toString().equals(fingerprint[0]) && body.getChildCount() > 0) return;
          fingerprint[0] = key.toString();
          body.removeAllViews();
          if (jobs.isEmpty()) {
            note(body, "还没有下载任务");
            return;
          }
          for (DownloadStore.Job j : jobs) {
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding(dp(12), dp(12), dp(12), dp(12));
            row.setBackground(Ui.shape(this, Ui.SURFACE, 8));
            TextView title = Ui.text(this, j.title, 14, Ui.TEXT);
            title.setMaxLines(2);
            row.addView(title);
            String status =
                switch (j.state) {
                  case "queued" -> "等待下载";
                  case "downloading" -> "下载中";
                  case "converting" -> "正在合并 MP4";
                  case "completed" -> "已完成";
                  case "cancelled" -> "已取消";
                  default -> "下载失败";
                };
            note(
                row,
                status
                    + (("downloading".equals(j.state) || "converting".equals(j.state))
                        ? " · " + j.progress + "%"
                        : "")
                    + (j.bytes > 0 ? " · " + formatBytes(j.bytes) : ""));
            if (!"completed".equals(j.state)
                && !"failed".equals(j.state)
                && !"cancelled".equals(j.state)) {
              ProgressBar bar =
                  new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
              bar.setProgress(j.progress);
              bar.setIndeterminate(j.progress < 0);
              bar.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
              row.addView(bar, new LinearLayout.LayoutParams(-1, dp(4)));
            }
            if (j.error != null && !j.error.isBlank()) {
              TextView error = note(row, j.error);
              error.setTextIsSelectable(true);
            }
            if ("completed".equals(j.state)) action(row, "打开文件", () -> openDownloaded(j));
            else if ("failed".equals(j.state) || "cancelled".equals(j.state))
              action(
                  row,
                  "重试",
                  () -> {
                    DownloadService.retry(this, j.id);
                    fingerprint[0] = "";
                  });
            else
              action(
                  row,
                  "取消",
                  () -> {
                    DownloadService.cancel(this, j.id);
                    fingerprint[0] = "";
                  });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
            p.bottomMargin = dp(8);
            body.addView(row, p);
          }
        };
    render.run();
    sheetRefresh =
        new Runnable() {
          public void run() {
            if (activeSheet == null) return;
            render.run();
            handler.postDelayed(this, 750);
          }
        };
    handler.postDelayed(sheetRefresh, 750);
  }

  void openDownloaded(DownloadStore.Job job) {
    if (job.mimeType != null
        && (job.mimeType.startsWith("video/") || job.mimeType.startsWith("audio/"))) {
      WebResource resource = new WebResource(job.outputUri);
      resource.mime = job.mimeType;
      resource.title = job.title;
      resource.classify();
      if (activeSheet != null) activeSheet.dismiss();
      openPlayer(resource, 0);
      return;
    }
    try {
      Intent open =
          new Intent(Intent.ACTION_VIEW)
              .setDataAndType(Uri.parse(job.outputUri), job.mimeType == null ? "*/*" : job.mimeType)
              .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
      startActivity(open);
    } catch (Exception e) {
      showError("无法打开文件", e);
    }
  }

  void showTabs() {
    LinearLayout box = sheet("标签页");
    LinearLayout body = scrollBody(box);
    for (BrowserTab tab : new ArrayList<>(tabs)) {
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(8), 0, 0, 0);
      row.setBackground(Ui.shape(this, tab == current ? Ui.SOFT_ACCENT : Ui.SURFACE, 8));
      TextView name = Ui.text(this, tab.title, 14, Ui.TEXT);
      name.setMaxLines(2);
      row.addView(name, new LinearLayout.LayoutParams(0, dp(64), 1));
      name.setOnClickListener(
          v -> {
            activeSheet.dismiss();
            selectTab(tab);
          });
      ImageButton close = Ui.icon(this, "close", "关闭标签页 " + tab.title);
      row.addView(close, lp(48, 48));
      close.setOnClickListener(
          v -> {
            closeTab(tab);
            showTabs();
          });
      LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2);
      p.bottomMargin = dp(8);
      body.addView(row, p);
    }
    action(
        box,
        "新建标签页",
        () -> {
          activeSheet.dismiss();
          newTab(homepage(), true);
        });
  }

  void showMenu() {
    LinearLayout box = sheet("WebSurf");
    action(box, "网页资源", () -> showResources("全部"));
    action(box, "下载", () -> showDownloads());
    action(
        box,
        "添加书签",
        () -> {
          saveBookmark();
          activeSheet.dismiss();
        });
    action(box, "书签与历史", () -> showSaved());
    if (playing != null)
      action(
          box,
          "系统小窗",
          () -> {
            activeSheet.dismiss();
            enterPictureInPictureMode(
                new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
          });
    action(
        box,
        player != null ? "返回网页播放" : "接管网页视频",
        () -> {
          activeSheet.dismiss();
          toggleTakeover();
        });
    action(
        box,
        "复制网页链接",
        () -> {
          copy(current.url);
          activeSheet.dismiss();
        });
    action(box, "设置", () -> showSettings());
  }

  void showSettings() {
    LinearLayout box = sheet("设置");
    LinearLayout body = scrollBody(box);
    note(body, "主页");
    EditText home = new EditText(this);
    home.setSingleLine(true);
    home.setTextSize(14);
    home.setTextColor(Ui.TEXT);
    home.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    home.setText(homepage());
    home.setContentDescription("主页地址");
    body.addView(home, new LinearLayout.LayoutParams(-1, dp(56)));
    action(
        body,
        "保存主页",
        () -> {
          String value = home.getText().toString().trim();
          if (!value.matches("(?i)^https?://.*")) value = "https://" + value;
          if (!WebResource.isHttp(value)) {
            home.setError("请输入有效的 http 或 https 网址");
            return;
          }
          prefs.edit().putString("homepage", value).apply();
          toast("主页已保存");
        });
    addSwitch(
        body,
        "自动接管视频",
        prefs.getBoolean("auto_play", true),
        checked -> {
          prefs.edit().putBoolean("auto_play", checked).apply();
          if (!checked) closePlayer(true, true);
        });
    note(body, "点击网页视频后原位接管。关闭后可选画质，再点接管播放。");
    int preferred = prefs.getInt("preferred_quality", 0);
    action(
        body,
        "首选画质 · " + (preferred <= 0 ? "自动" : preferred + "p"),
        () -> {
          String[] labels = {"自动 / 跟随网页", "360p", "480p", "720p", "1080p", "1440p", "2160p"};
          int[] values = {0, 360, 480, 720, 1080, 1440, 2160};
          int checked = 0;
          for (int i = 0; i < values.length; i++) if (values[i] == preferred) checked = i;
          new AlertDialog.Builder(
                  new android.view.ContextThemeWrapper(
                      this, Ui.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme))
              .setTitle("首选画质")
              .setSingleChoiceItems(
                  labels,
                  checked,
                  (dialog, index) -> {
                    prefs.edit().putInt("preferred_quality", values[index]).apply();
                    dialog.dismiss();
                    if (player != null) QualityOptions.applyPreferredHeight(player, values[index]);
                    showSettings();
                  })
              .setNegativeButton("取消", null)
              .show();
        });
    addSwitch(
        body,
        "深色外观",
        Ui.isDark(this),
        checked -> {
          Ui.setDark(this, checked);
          Ui.configure(this);
          applyChromeTheme();
          showSettings();
        });
    addSwitch(
        body,
        "桌面版网页",
        prefs.getBoolean("desktop", false),
        checked -> {
          prefs.edit().putBoolean("desktop", checked).apply();
          for (BrowserTab t : tabs) {
            String ua = WebSettings.getDefaultUserAgent(this);
            t.web
                .getSettings()
                .setUserAgentString(
                    checked
                        ? ua.replace("; wv", "").replace("Mobile", "").replace("Android", "Linux")
                        : ua);
          }
          toast("刷新网页后生效");
        });
    android.content.pm.PackageInfo provider = WebView.getCurrentWebViewPackage();
    note(
        body,
        "Chromium WebView\n"
            + (provider == null ? "系统提供" : provider.packageName + " · " + provider.versionName));
    note(body, "WebSurf " + BuildConfig.VERSION_NAME);
  }

  interface Checked {
    void run(boolean checked);
  }

  void addSwitch(LinearLayout box, String title, boolean initial, Checked callback) {
    Switch control = new Switch(this);
    control.setText(title);
    control.setTextSize(14);
    control.setTextColor(Ui.TEXT);
    control.setMinHeight(dp(56));
    control.setChecked(initial);
    box.addView(control, new LinearLayout.LayoutParams(-1, -2));
    control.setOnCheckedChangeListener((b, checked) -> callback.run(checked));
  }

  void applyChromeTheme() {
    root.setBackgroundColor(Ui.BG);
    topBar.setBackgroundColor(Ui.BG);
    bottomBar.setBackgroundColor(Ui.BG);
    resourceBar.setBackgroundColor(Ui.SOFT_ACCENT);
    resourceText.setTextColor(Ui.CONTROL);
    takeoverButton.setBackgroundTintList(null);
    Ui.ripple(takeoverButton, Ui.SURFACE, 8);
    takeoverButton.setTextColor(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {Ui.MUTED, Ui.CONTROL}));
    address.setTextColor(Ui.TEXT);
    address.setHintTextColor(Ui.MUTED);
    address.setBackground(Ui.shape(this, Ui.SURFACE, 8));
    tabCount.setTextColor(Ui.TEXT);
    tintIcons(topBar);
    tintIcons(bottomBar);
    tintIcons(resourceBar);
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            Ui.isDark(this)
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    for (BrowserTab tab : tabs) sendPageTheme(tab);
  }

  void sendPageTheme(BrowserTab tab) {
    tab.web.evaluateJavascript(
        "window.__websurf&&window.__websurf.command({action:'theme',dark:" + Ui.isDark(this) + "})",
        null);
  }

  void tintIcons(ViewGroup group) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View v = group.getChildAt(i);
      if (v instanceof ImageButton b)
        b.setImageTintList(
            new android.content.res.ColorStateList(
                new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
                new int[] {(Ui.TEXT & 0x00ffffff) | 0x66000000, Ui.TEXT}));
      else if (v instanceof ViewGroup g) tintIcons(g);
    }
  }

  void rememberHistory(BrowserTab tab) {
    if (!WebResource.isHttp(tab.url)) return;
    try {
      JSONArray old = new JSONArray(prefs.getString("history", "[]"));
      JSONArray next = new JSONArray();
      next.put(new JSONObject().put("url", tab.url).put("title", tab.title));
      for (int i = 0; i < old.length() && next.length() < 100; i++)
        if (!tab.url.equals(old.getJSONObject(i).optString("url"))) next.put(old.getJSONObject(i));
      prefs.edit().putString("history", next.toString()).apply();
    } catch (Exception ignored) {
    }
  }

  void saveBookmark() {
    try {
      JSONArray list = new JSONArray(prefs.getString("bookmarks", "[]"));
      for (int i = 0; i < list.length(); i++)
        if (list.getJSONObject(i).optString("url").equals(current.url)) {
          toast("已在书签中");
          return;
        }
      list.put(new JSONObject().put("url", current.url).put("title", current.title));
      prefs.edit().putString("bookmarks", list.toString()).apply();
      toast("已添加书签");
    } catch (Exception e) {
      showError("无法保存书签", e);
    }
  }

  void showSaved() {
    LinearLayout box = sheet("书签与历史");
    LinearLayout body = scrollBody(box);
    for (String key : new String[] {"bookmarks", "history"}) {
      note(body, key.equals("bookmarks") ? "书签" : "历史记录");
      try {
        JSONArray list = new JSONArray(prefs.getString(key, "[]"));
        if (list.length() == 0) note(body, "暂无记录");
        for (int i = 0; i < list.length(); i++) {
          JSONObject entry = list.getJSONObject(i);
          String url = entry.optString("url");
          Button b =
              action(
                  body,
                  entry.optString("title", url),
                  () -> {
                    activeSheet.dismiss();
                    navigate(url);
                  });
          b.setMaxLines(1);
          b.setEllipsize(TextUtils.TruncateAt.END);
        }
      } catch (Exception ignored) {
      }
    }
  }

  void exitWebFullscreen() {
    if (fullscreenWeb == null) return;
    root.removeView(fullscreenWeb);
    fullscreenWeb = null;
    if (customViewCallback != null) {
      WebChromeClient.CustomViewCallback cb = customViewCallback;
      customViewCallback = null;
      cb.onCustomViewHidden();
    }
  }

  void goBack() {
    if (fullscreenWeb != null) {
      exitWebFullscreen();
      return;
    }
    if (player != null) {
      if (videoChrome != null && videoChrome.isLocked()) {
        videoChrome.setLocked(false);
        return;
      }
      if (playerMode != VideoChrome.Mode.INLINE && activeTarget != null) {
        setPlayerMode(VideoChrome.Mode.INLINE);
        return;
      }
      closePlayer(true, true);
      return;
    }
    if (current != null && current.web.canGoBack()) {
      current.web.goBack();
      return;
    }
    if (tabs.size() > 1) {
      closeTab(current);
      return;
    }
    moveTaskToBack(true);
  }

  @Override
  public void onBackPressed() {
    goBack();
  }

  @Override
  public void onNewIntent(Intent intent) {
    super.onNewIntent(intent);
    setIntent(intent);
    if (WebResource.isHttp(intent.getDataString())) navigate(intent.getDataString());
    if (intent.getBooleanExtra("show_downloads", false)) showDownloads();
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration config) {
    super.onConfigurationChanged(config);
    root.post(this::layoutPlayer);
  }

  @Override
  public void onPictureInPictureModeChanged(
      boolean enabled, android.content.res.Configuration config) {
    super.onPictureInPictureModeChanged(enabled, config);
    inPip = enabled;
    shell.setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
    layoutPlayer();
  }

  @Override
  protected void onResume() {
    super.onResume();
    resumed = true;
    if (current != null) current.web.onResume();
  }

  @Override
  protected void onPause() {
    super.onPause();
    resumed = false;
    CookieManager.getInstance().flush();
  }

  @Override
  protected void onStop() {
    super.onStop();
    if (!isInPictureInPictureMode()) {
      if (player != null) player.pause();
      if (current != null) current.web.onPause();
    }
  }

  @Override
  protected void onSaveInstanceState(Bundle state) {
    super.onSaveInstanceState(state);
    ArrayList<String> urls = new ArrayList<>();
    for (BrowserTab t : tabs) urls.add(t.url);
    state.putStringArrayList("tabs", urls);
    state.putInt("active", Math.max(0, tabs.indexOf(current)));
  }

  @Override
  protected void onDestroy() {
    if (activeSheet != null) activeSheet.dismiss();
    closePlayer(false, false);
    previewLoader.close();
    handler.removeCallbacksAndMessages(null);
    for (BrowserTab tab : tabs) tab.web.destroy();
    tabs.clear();
    super.onDestroy();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == 41 && fileCallback != null) {
      fileCallback.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result, data));
      fileCallback = null;
    }
  }

  void copy(String text) {
    ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE))
        .setPrimaryClip(ClipData.newPlainText("WebSurf", text));
    toast("已复制链接");
  }

  String formatBytes(long bytes) {
    return bytes >= 1024L * 1024 * 1024
        ? String.format(Locale.ROOT, "%.2f GB", bytes / (1024d * 1024 * 1024))
        : bytes >= 1024 * 1024
            ? String.format(Locale.ROOT, "%.1f MB", bytes / (1024d * 1024))
            : String.format(Locale.ROOT, "%.1f KB", bytes / 1024d);
  }

  void toast(String message) {
    Toast.makeText(this, message, Toast.LENGTH_SHORT).show();
  }

  void showError(String title, Exception e) {
    new AlertDialog.Builder(this)
        .setTitle(title)
        .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
        .setPositiveButton("关闭", null)
        .show();
  }
}
