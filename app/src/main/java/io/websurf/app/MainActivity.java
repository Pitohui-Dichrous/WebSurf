package io.websurf.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
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
import io.websurf.app.download.DownloadNames;
import io.websurf.app.download.DownloadService;
import io.websurf.app.download.DownloadStore;
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
  private ScrollControls scrollControls;
  ChromePixelShift chromePixelShift;
  EditText address;
  boolean addressDark;
  ProgressBar progress;
  TextView shellMark;
  ImageView tabGlyph;
  FrameLayout tabButton;
  TextView resourceText, tabCount;
  Button takeoverButton;
  VideoChrome videoChrome;
  FrameLayout anchorClip;
  boolean inlinePinned;
  float inlineExpansion;
  final ResourcePreviewLoader previewLoader = new ResourcePreviewLoader();
  VideoTarget activeTarget;
  private String activeReferrer = "";
  private long activeReferrerGeneration;
  VideoChrome.Mode playerMode = VideoChrome.Mode.INLINE;
  float floatX = Float.NaN, floatY = Float.NaN;
  float logicalVolume = 1, appliedGain = 1;
  boolean logicalMuted;
  boolean immersivePlayer;
  int previousOrientation;
  ImageButton back, forward, refresh, downloadsButton;
  TextView downloadBadge;
  LinearLayout downloadFeedback;
  final Map<String, String> downloadStates = new HashMap<>();
  boolean downloadReceiverRegistered;
  DownloadStore.Job pendingDownloadFeedback;
  final BroadcastReceiver downloadReceiver =
      new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
          refreshDownloadFeedback();
        }
      };
  ExoPlayer player;
  private ExoPlayer previewCapturedSession;
  private int previewCapturedWidth, previewCapturedHeight;
  private long previewReadyAfter;
  PlayerView playerView;
  WebResource playing;
  BrowserTab playerTab;
  boolean inPip, resumed;
  long playerStartPosition;
  boolean playerRenderedFrame;
  boolean nativeAttachmentReady, requestedPlayIntent;
  String pendingAttachment = "";
  ValueCallback<Uri[]> fileCallback;
  private BrowserTab uploadTab;
  private long uploadEpoch;
  private int uploadRequestCode = 40;
  WebChromeClient.CustomViewCallback customViewCallback;
  String snifferScript;
  Dialog activeSheet;
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
    for (DownloadStore.Job j : DownloadStore.list(this)) downloadStates.put(j.id, j.state);
    androidx.core.content.ContextCompat.registerReceiver(
        this,
        downloadReceiver,
        new IntentFilter(DownloadService.ACTION_CHANGED),
        androidx.core.content.ContextCompat.RECEIVER_NOT_EXPORTED);
    downloadReceiverRegistered = true;
    refreshDownloadBadge();
    String initial = getIntent().getDataString();
    if (state != null) {
      uploadRequestCode = state.getInt("uploadRequestCode", 40);
      ArrayList<String> urls = state.getStringArrayList("tabs");
      if (urls != null) {
        boolean[] local = state.getBooleanArray("localMediaTabs");
        for (int i = 0; i < Math.min(8, urls.size()); i++) {
          String url = urls.get(i);
          newTab(
              WebResource.isHttp(url) ? url : "about:blank",
              false,
              local != null && i < local.length && local[i]);
        }
      }
      if (!tabs.isEmpty())
        selectTab(tabs.get(Math.max(0, Math.min(state.getInt("active"), tabs.size() - 1))));
    }
    if (tabs.isEmpty()) {
      if (WebResource.isHttp(initial)) newTab(initial, true, false);
      else newTab(homepage(), true);
    }
    if (getIntent().getBooleanExtra("show_downloads", false)) handler.post(this::showDownloads);
  }

  void createShell() {
    root = new FrameLayout(this);
    root.setBackgroundColor(Ui.BG);
    shell = new LinearLayout(this);
    shell.setOrientation(LinearLayout.VERTICAL);
    root.addView(shell, new FrameLayout.LayoutParams(-1, -1));
    scrollControls = new ScrollControls(this);
    topBar = new LinearLayout(this);
    topBar.setGravity(Gravity.CENTER_VERTICAL);
    topBar.setPadding(dp(8), dp(6), dp(8), dp(6));
    shellMark = Ui.display(this, "WS", 26, Ui.CONTROL);
    shellMark.setGravity(Gravity.CENTER);
    shellMark.setContentDescription("WebSurf 菜单");
    shellMark.setTooltipText("WebSurf 菜单");
    Ui.ripple(shellMark, Color.TRANSPARENT, 0);
    shellMark.setOnClickListener(v -> showMenu());
    topBar.addView(shellMark, lp(48, 48));
    address = createAddress();
    topBar.addView(address, new LinearLayout.LayoutParams(0, dp(48), 1));
    refresh = Ui.icon(this, "refresh", "刷新网页");
    topBar.addView(refresh, lp(48, 48));
    refresh.setOnClickListener(
        v -> {
          if (current != null) {
            closePlayer(false, false);
            current.web.reload();
          }
        });
    FrameLayout downloadControl = new FrameLayout(this);
    downloadsButton = Ui.icon(this, "download", "下载任务");
    downloadControl.addView(downloadsButton, new FrameLayout.LayoutParams(dp(48), dp(48)));
    downloadBadge = Ui.mono(this, "", 10, Color.BLACK);
    downloadBadge.setGravity(Gravity.CENTER);
    downloadBadge.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    FrameLayout.LayoutParams badgePosition =
        new FrameLayout.LayoutParams(dp(18), dp(16), Gravity.TOP | Gravity.END);
    badgePosition.topMargin = ChromePixelShift.MAX_OFFSET_PX;
    badgePosition.setMarginEnd(ChromePixelShift.MAX_OFFSET_PX);
    downloadControl.addView(downloadBadge, badgePosition);
    downloadBadge.setVisibility(View.GONE);
    topBar.addView(downloadControl, lp(48, 48));
    downloadsButton.setOnClickListener(v -> showDownloads());
    shell.addView(scrollControls.header, new LinearLayout.LayoutParams(-1, -2));
    scrollControls.header.addView(topBar, new LinearLayout.LayoutParams(-1, -2));
    progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
    progress.setMax(100);
    progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Ui.ACCENT));
    scrollControls.header.addView(progress, new LinearLayout.LayoutParams(-1, dp(2)));
    webContainer = new FrameLayout(this);
    shell.addView(webContainer, new LinearLayout.LayoutParams(-1, 0, 1));
    resourceBar = new LinearLayout(this);
    resourceBar.setGravity(Gravity.CENTER_VERTICAL);
    resourceBar.setPadding(dp(12), dp(6), dp(8), dp(6));
    resourceText = Ui.text(this, "网页资源", 12, Ui.PLANE_INK);
    resourceText.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    resourceText.setMaxLines(2);
    resourceBar.addView(resourceText, new LinearLayout.LayoutParams(0, dp(48), 1));
    takeoverButton = Ui.button(this, "接管播放", true);
    takeoverButton.setTextSize(13);
    takeoverButton.setAllCaps(false);
    takeoverButton.setPadding(dp(8), 0, dp(8), 0);
    resourceBar.addView(takeoverButton, lp(96, 48));
    takeoverButton.setOnClickListener(v -> toggleTakeover());
    ImageButton resBtn = Ui.icon(this, "video", "查看网页资源");
    resourceBar.addView(resBtn, lp(48, 48));
    resBtn.setOnClickListener(v -> showResources("视频"));
    resourceBar.setOnClickListener(v -> showResources("视频"));
    shell.addView(scrollControls.footer, new LinearLayout.LayoutParams(-1, -2));
    scrollControls.footer.addView(resourceBar, new LinearLayout.LayoutParams(-1, -2));
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
    tabButton = new FrameLayout(this);
    Ui.ripple(tabButton, Color.TRANSPARENT, 0);
    tabButton.setContentDescription("标签页，1 个");
    tabButton.setTooltipText("标签页");
    FrameLayout tabMark = new FrameLayout(this);
    tabMark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS);
    tabButton.addView(tabMark, new FrameLayout.LayoutParams(dp(24), dp(24), Gravity.CENTER));
    tabGlyph = new ImageView(this);
    tabGlyph.setImageDrawable(new Ui.Icon("tabs", Ui.TEXT));
    tabGlyph.setScaleType(ImageView.ScaleType.FIT_XY);
    tabMark.addView(tabGlyph, new FrameLayout.LayoutParams(-1, -1));
    tabCount = Ui.text(this, "1", 9, Ui.TEXT);
    tabCount.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    tabCount.setGravity(Gravity.CENTER);
    tabCount.setSingleLine(true);
    tabCount.setAutoSizeTextTypeUniformWithConfiguration(
        7, 10, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    tabCount.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    // The unmodified Lucide icon's front window is (8,8)–(22,22) in its 24×24 viewBox.
    // Both layers use this one coordinate space, independent of the navigation cell width.
    FrameLayout.LayoutParams countLp = new FrameLayout.LayoutParams(dp(14), dp(14));
    countLp.leftMargin = dp(8);
    countLp.topMargin = dp(8);
    tabMark.addView(tabCount, countLp);
    tabButton.setOnClickListener(v -> showTabs());
    bottomBar.addView(tabButton, new LinearLayout.LayoutParams(0, dp(48), 1));
    addNav("more", "菜单", () -> showMenu());
    chromePixelShift =
        new ChromePixelShift(
            shellMark, bottomBar, tabButton, tabMark, downloadsButton, downloadBadge);
    scrollControls.footer.addView(bottomBar, new LinearLayout.LayoutParams(-1, dp(54)));
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
          if (insets.isVisible(WindowInsetsCompat.Type.ime())) scrollControls.reveal();
          return insets;
        });
    applyChromeTheme();
  }

  EditText createAddress() {
    EditText field = new EditText(chromeContext());
    addressDark = Ui.isDark(this);
    field.setSingleLine(true);
    field.setSelectAllOnFocus(true);
    field.setTextSize(13);
    field.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    field.setTextColor(Ui.TEXT);
    field.setHintTextColor(Ui.MUTED);
    field.setHint("搜索或输入网址");
    field.setContentDescription("搜索或输入网址");
    field.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_URI);
    field.setImeOptions(EditorInfo.IME_ACTION_GO);
    field.setPadding(dp(14), 0, dp(10), 0);
    field.setBackground(Ui.outline(this, Ui.SURFACE, Ui.BORDER, 2));
    field.setOnEditorActionListener(
        (v, action, event) -> {
          if (action == EditorInfo.IME_ACTION_GO
              || (event != null
                  && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                  && event.getAction() == KeyEvent.ACTION_UP)) {
            navigate(v.getText().toString());
            hideKeyboard();
            return true;
          }
          return false;
        });
    return field;
  }

  /** API 26–28 have no public cursor/handle tint API; replace only the themed text field. */
  void refreshLegacyAddressTheme() {
    if (Build.VERSION.SDK_INT >= 29 || addressDark == Ui.isDark(this)) return;
    EditText previous = address;
    String value = previous.getText().toString();
    int start = previous.getSelectionStart(), end = previous.getSelectionEnd();
    int scrollX = previous.getScrollX(), scrollY = previous.getScrollY();
    boolean focused = previous.hasFocus();
    WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(previous);
    boolean keyboardVisible =
        focused
            && previous.hasWindowFocus()
            && insets != null
            && insets.isVisible(WindowInsetsCompat.Type.ime());
    int position = topBar.indexOfChild(previous);
    ViewGroup.LayoutParams layout = previous.getLayoutParams();
    EditText replacement = createAddress();
    replacement.setSelectAllOnFocus(false);
    replacement.setText(value);
    topBar.removeView(previous);
    address = replacement;
    topBar.addView(replacement, position, layout);
    if (focused) replacement.requestFocus();
    if (start >= 0 && end >= 0)
      replacement.setSelection(Math.min(start, value.length()), Math.min(end, value.length()));
    replacement.setSelectAllOnFocus(true);
    replacement.post(
        () -> {
          if (address != replacement || !replacement.isAttachedToWindow()) return;
          replacement.scrollTo(scrollX, scrollY);
          if (keyboardVisible && replacement.hasFocus() && replacement.hasWindowFocus())
            getSystemService(InputMethodManager.class)
                .showSoftInput(replacement, InputMethodManager.SHOW_IMPLICIT);
        });
  }

  void updateTaskBranding() {
    if (Build.VERSION.SDK_INT >= 28) {
      setTaskDescription(new ActivityManager.TaskDescription("WebSurf", R.drawable.ws_logo, Ui.BG));
    } else {
      android.graphics.BitmapFactory.Options options = new android.graphics.BitmapFactory.Options();
      options.inSampleSize = 8;
      setTaskDescription(
          new ActivityManager.TaskDescription(
              "WebSurf",
              android.graphics.BitmapFactory.decodeResource(
                  getResources(), R.drawable.ws_logo, options),
              Ui.BG));
    }
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

  Context chromeContext() {
    return new ContextThemeWrapper(this, Ui.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme);
  }

  AlertDialog.Builder alert() {
    return new AlertDialog.Builder(chromeContext());
  }

  void tintTextInput(EditText field) {
    field.setHighlightColor(Ui.isDark(this) ? 0x66c0fe03 : 0x33000000);
    if (Build.VERSION.SDK_INT >= 29) {
      android.graphics.drawable.Drawable cursor = field.getTextCursorDrawable();
      if (cursor != null) {
        cursor = cursor.mutate();
        cursor.setTint(Ui.CONTROL);
        field.setTextCursorDrawable(cursor);
      }
      android.graphics.drawable.Drawable left = field.getTextSelectHandleLeft();
      android.graphics.drawable.Drawable right = field.getTextSelectHandleRight();
      android.graphics.drawable.Drawable middle = field.getTextSelectHandle();
      if (left != null) {
        left = left.mutate();
        left.setTint(Ui.CONTROL);
        field.setTextSelectHandleLeft(left);
      }
      if (right != null) {
        right = right.mutate();
        right.setTint(Ui.CONTROL);
        field.setTextSelectHandleRight(right);
      }
      if (middle != null) {
        middle = middle.mutate();
        middle.setTint(Ui.CONTROL);
        field.setTextSelectHandle(middle);
      }
    }
  }

  LinearLayout.LayoutParams lp(int w, int h) {
    return new LinearLayout.LayoutParams(dp(w), dp(h));
  }

  String homepage() {
    return prefs.getString("homepage", "https://www.baidu.com");
  }

  SearchEngine searchEngine() {
    return SearchEngine.fromId(prefs.getString("search_engine", "bing_cn"));
  }

  void hideKeyboard() {
    address.clearFocus();
    ((InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
        .hideSoftInputFromWindow(address.getWindowToken(), 0);
  }

  static String normalizeAddress(String input) {
    return normalizeAddress(input, SearchEngine.BING_CN);
  }

  static String normalizeAddress(String input, SearchEngine engine) {
    String s = input.trim();
    if (WebResource.isHttp(s)) return s;
    if (!s.contains(" ")
        && (s.contains(".") || s.startsWith("localhost"))
        && WebResource.isHttp("https://" + s)) return "https://" + s;
    return (engine == null ? SearchEngine.BING_CN : engine).search(s);
  }

  void navigate(String url) {
    navigate(url, true);
  }

  private void navigate(String url, boolean fromBrowserControls) {
    if (current == null) return;
    closePlayer(false, false);
    String address = normalizeAddress(url, searchEngine());
    current.selectNativeOrigin(fromBrowserControls ? address : "");
    current.web.loadUrl(address);
  }

  private void observePageGesture(WebView web, MotionEvent event) {
    if (current == null || web != current.web || fullscreenWeb != null || immersivePlayer || inPip)
      return;
    if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
      WindowInsetsCompat insets = ViewCompat.getRootWindowInsets(web);
      android.view.accessibility.AccessibilityManager accessibility =
          getSystemService(android.view.accessibility.AccessibilityManager.class);
      if ((insets != null && insets.isVisible(WindowInsetsCompat.Type.ime()))
          || accessibility.isTouchExplorationEnabled()) {
        scrollControls.reveal();
        return;
      }
    }
    scrollControls.observe(event);
  }

  BrowserTab newTab(String url, boolean select) {
    return newTab(url, select, true);
  }

  @SuppressWarnings("SetJavaScriptEnabled")
  private BrowserTab newTab(String url, boolean select, boolean fromBrowserControls) {
    if (tabs.size() >= 8) {
      toast("最多打开 8 个标签页");
      return null;
    }
    BrowserTab tab = new BrowserTab();
    tab.initialPage = url;
    if (url != null && fromBrowserControls) tab.selectNativeOrigin(url);
    WebView w =
        new WebView(this) {
          @Override
          public boolean dispatchTouchEvent(MotionEvent event) {
            if (event.getActionMasked() == MotionEvent.ACTION_DOWN) {
              tab.inputSequence++;
              tab.inputAt = SystemClock.uptimeMillis();
            }
            observePageGesture(this, event);
            return super.dispatchTouchEvent(event);
          }

          @Override
          public boolean dispatchKeyEvent(KeyEvent event) {
            if (event.getAction() == KeyEvent.ACTION_DOWN
                && event.getRepeatCount() == 0
                && (event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    || event.getKeyCode() == KeyEvent.KEYCODE_SPACE
                    || event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE)) {
              tab.inputSequence++;
              tab.inputAt = SystemClock.uptimeMillis();
            }
            return super.dispatchKeyEvent(event);
          }
        };
    tab.web = w;
    w.setOnScrollChangeListener(
        (view, x, y, oldX, oldY) -> {
          if (tab == playerTab && playerMode == VideoChrome.Mode.INLINE && !inPip)
            layoutNativeInline();
        });
    w.addOnLayoutChangeListener(
        (view, l, t, r, b, oldL, oldT, oldR, oldB) -> {
          if (tab == playerTab && (b - t != oldB - oldT || r - l != oldR - oldL))
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
    if (supportsCookieIntercept())
      androidx.webkit.WebSettingsCompat.setCookiesIncludedInShouldInterceptRequest(settings, true);
    CookieManager.getInstance().setAcceptCookie(true);
    CookieManager.getInstance().setAcceptThirdPartyCookies(w, true);
    WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG);
    if (WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER)) {
      final String requiredPrefix =
          WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
              ? BridgeMessage.prefix(tab.startSeal)
              : null;
      WebViewCompat.addWebMessageListener(
          w,
          "WebSurfObserver",
          Collections.singleton("*"),
          (view, message, origin, isMain, reply) -> {
            if (!tabs.contains(tab)
                || !WebResource.isHttp(origin.toString())
                || message.getType() != androidx.webkit.WebMessageCompat.TYPE_STRING) return;
            try {
              JSONObject packet = BridgeMessage.parse(message.getData(), requiredPrefix);
              if (packet == null) return;
              JSONObject observation = packet.optJSONObject("observation");
              JSONObject event = observation == null ? packet : observation;
              String document = event.optString("documentId");
              if (!document.matches("[a-zA-Z0-9_-]{1,96}")) return;
              String type = event.optString("type");
              if (!"hello".equals(type)
                  && !"input".equals(type)
                  && (observation == null
                      || (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                          && !tab.startSeal.equals(packet.optString("seal"))))) return;
              BrowserTab.Frame frame = tab.documents.get(document);
              if ("hello".equals(type)) {
                if (!matchesFramePage(origin.toString(), event.optString("page"), isMain)) return;
                if (isMain
                    && !event.optString("page").split("#", 2)[0].equals(tab.url.split("#", 2)[0]))
                  return;
                if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                    && !tab.startSeal.equals(event.optString("seal"))) return;
                if (frame == null) {
                  for (BrowserTab.Frame registered : tab.documents.values())
                    if (registered.reply == reply) return;
                  String playingDocument =
                      tab == playerTab && activeTarget != null ? activeTarget.documentId : "";
                  if (!tab.makeDocumentRoom(playingDocument)) return;
                  tab.documents.put(
                      document,
                      new BrowserTab.Frame(
                          reply,
                          origin.toString(),
                          isMain,
                          WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                              && tab.startSeal.equals(event.optString("seal"))
                              && event.optBoolean("firstPartyTree")));
                } else if (frame.reply != reply
                    || frame.main != isMain
                    || !frame.origin.equals(origin.toString())) return;
                frame = tab.documents.get(document);
                String key = event.optString("referrerKey");
                if (frame.firstPartyTree && frame.referrerKey.isEmpty()
                    && key.matches("[0-9]{1,10}(-[0-9]{1,10}){3}")) frame.referrerKey = key;
                reply.postMessage(
                    new JSONObject()
                        .put("documentId", document)
                        .put("epoch", tab.epoch)
                        .put("dark", Ui.isDark(this))
                        .toString());
                return;
              }
              if (frame == null
                  || frame.reply != reply
                  || frame.main != isMain
                  || !frame.origin.equals(origin.toString())) return;
              // Before JS receives its first ack it can attest a gesture on this exact frame.
              // The epoch check still applies to every command. Navigation clears the frame and
              // consumes the physical input before acknowledging its replacement document.
              boolean awaitingFirstAck =
                  "input".equals(type) && event.has("epoch") && event.isNull("epoch");
              if (!awaitingFirstAck && event.optLong("epoch", -1) != tab.epoch) return;
              if ("goodbye".equals(type)) {
                if (!isMain
                    && observation != null
                    && tab.startSeal.equals(packet.optString("seal"))) {
                  if (tab == playerTab
                      && activeTarget != null
                      && document.equals(activeTarget.documentId)) closePlayer(false, false);
                  tab.forgetDocument(document);
                  if (tab == current) updateResourceBar();
                }
                return;
              }
              if ("input".equals(type)) {
                if (tab.startSeal.equals(event.optString("seal"))
                    && tab == current
                    && resumed
                    && tab.inputSequence != tab.consumedInput
                    && event.optLong("inputTicket", 0) > 0
                    && SystemClock.uptimeMillis() - tab.inputAt < 1800) {
                  frame.inputSequence = tab.inputSequence;
                  frame.inputTicket = event.optLong("inputTicket");
                  frame.authorizedId = event.optString("mediaId");
                  frame.authorizedVersion = event.optLong("sourceVersion", -1);
                  frame.authorizedUrl = event.optString("url");
                  frame.authorizedAction = event.optString("action");
                }
                return;
              }
              if ("project".equals(type) || "unproject".equals(type)) {
                if (isMain) applyFrameProjection(tab, event);
                return;
              }
              // The callback supplies the sending origin; a subframe cannot name the top document.
              String framePage = event.optString("page");
              if (!matchesFramePage(frame.origin, framePage, isMain)) return;
              if (!isMain) {
                event.put("page", frame.origin + "/");
                for (String field : VideoTarget.LAYOUT_FIELDS) event.remove(field);
              }
              if ("takeover".equals(type)
                  || ("play".equals(type) && event.optBoolean("userInitiated"))) {
                if (!resumed
                    || tab != current
                    || tab.inputSequence == tab.consumedInput
                    || frame.inputSequence != tab.inputSequence
                    || frame.inputTicket != event.optLong("inputTicket", -1)
                    || !type.equals(frame.authorizedAction)
                    || !frame.authorizedId.equals(event.optString("mediaId"))
                    || frame.authorizedVersion != event.optLong("sourceVersion", -1)
                    || !frame.authorizedUrl.equals(event.optString("url"))
                    || SystemClock.uptimeMillis() - tab.inputAt >= 1800) return;
                tab.consumedInput = tab.inputSequence;
              }
              onObservation(
                  tab,
                  event,
                  new WebResource.DocumentReferrer(
                      document,
                      frame.origin,
                      requiredPrefix != null ? framePage : event.optString("page"),
                      isMain,
                      event.optString("referrerPolicy")));
            } catch (Exception ignored) {
            }
          });
    }
    if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
      WebViewCompat.addDocumentStartJavaScript(
          w,
          snifferScript.replace("__WEBSURF_DOCUMENT_START_SEAL__", tab.startSeal),
          Collections.singleton("*"));
    w.setWebViewClient(
        new WebViewClient() {
          @Override
          public void onPageStarted(WebView view, String url, android.graphics.Bitmap favicon) {
            tab.epoch++;
            tab.beginDocument(url);
            tab.navigationRequest.set(null);
            tab.playGeneration++;
            tab.documents.clear();
            tab.consumedInput = tab.inputSequence;
            tab.url = url;
            tab.resetResources(url);
            tab.videos.clear();
            tab.selectedVideo = "";
            tab.takeoverSuppressed = false;
            tab.framePlaying = "";
            if (tab == playerTab) closePlayer(false, false);
            if (tab == current) {
              scrollControls.reveal();
              address.setText(url);
              updateResourceBar();
              progress.setVisibility(View.VISIBLE);
              updateNavigation();
            }
          }

          @Override
          public void onPageFinished(WebView view, String url) {
            if (!WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT))
              view.evaluateJavascript(
                  snifferScript.replace("__WEBSURF_DOCUMENT_START_SEAL__", ""), null);
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
            if (request.isForMainFrame()) tab.restrictPendingAddress(url);
            if (request.isForMainFrame() && !tab.localDocument(url)) tab.localOrigin = "";
            if (WebResource.isHttp(url)) return false;
            if (request.hasGesture() && request.isForMainFrame()) {
              alert()
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
            if ("HEAD".equals(request.getMethod())) {
              String token = null, referer = "";
              for (var header : request.getRequestHeaders().entrySet()) {
                if (header.getKey().equalsIgnoreCase(BrowserTab.ReferrerProbe.HEADER)) token = header.getValue();
                if (header.getKey().equalsIgnoreCase("Referer")) referer = header.getValue();
              }
              if (token != null) {
                var probe = tab.referrerProbe;
                if (probe != null && !request.isForMainFrame())
                  probe.capture(token, tab.epoch, request.getUrl().toString(), referer);
                // Also absorb expired probes. These requests never enter the resource list.
                return new WebResourceResponse("text/plain", "UTF-8", 204, "No Content",
                    Map.of("Cache-Control", "no-store"), null);
              }
            }
            if ("GET".equals(request.getMethod())) {
              String url = request.getUrl().toString();
              Map<String, String> headers = new LinkedHashMap<>(request.getRequestHeaders());
              long epoch = tab.epoch;
              boolean navigation = request.isForMainFrame();
              if (navigation)
                tab.navigationRequest.set(
                    new BrowserTab.NavigationRequest(epoch, url, Map.copyOf(headers)));
              handler.post(
                  () -> {
                    if (!tabs.contains(tab)) return;
                    if (epoch == 0) {
                      // A new WebView may fetch initial media before onPageStarted reaches the
                      // UI thread. Only its exact, browser-supplied first-document referrer can
                      // bind this early GET. An opener, redirect or later navigation cannot.
                      if (tab.epoch > 1
                          || !tab.initialRequest(url, headers)
                          || (tab.epoch == 1 && !tab.isInitialPage(tab.url))) return;
                    } else if (epoch != tab.epoch) return;
                    WebResource resource =
                        observe(
                            tab,
                            url,
                            "",
                            "网络请求",
                            0,
                            0,
                            epoch == 0 ? tab.initialPage : tab.url,
                            null);
                    if (resource != null) captureRequestHeaders(resource, headers, navigation);
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
            if (fileCallback != null || tab != current) {
              callback.onReceiveValue(null);
              return true;
            }
            try {
              Intent picker = UploadDocuments.picker(MainActivity.this, params);
              uploadRequestCode = uploadRequestCode >= 65535 ? 41 : uploadRequestCode + 1;
              startActivityForResult(picker, uploadRequestCode);
              fileCallback = callback;
              uploadTab = tab;
              uploadEpoch = tab.epoch;
            } catch (ActivityNotFoundException | SecurityException e) {
              callback.onReceiveValue(null);
            }
            return true;
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
              observe(tab, uri, mime, "下载链接", 0, 0, tab.url, null);
          if (resource != null) {
            // WebView does not expose the final GET of an attachment redirect. It must not
            // inherit an earlier URL's Cookie or acquire a previously absent Referer.
            BrowserTab.NavigationRequest request = tab.navigationRequest.getAndSet(null);
            resource.documentReferrer = null;
            resource.pageUrl = tab.url;
            if (request != null
                && request.epoch() == tab.epoch
                && tab.localDocument(uri)
                && tab.localDocument(request.url())
                && tab.localDocument(tab.web.getUrl())) {
              WebResource initiatingLink = tab.resources.get(request.url());
              if (initiatingLink != null && initiatingLink.fromLocalDocument)
                resource.fromLocalDocument = true;
            }
            captureRequestHeaders(
                resource,
                request != null && request.epoch() == tab.epoch && uri.equals(request.url())
                    ? request.headers()
                    : Map.of(),
                true);
            if (ua != null && !ua.isBlank()) resource.headers.put("User-Agent", ua);
            String directOrigin = tab.consumeLocalAttachment(uri);
            if (tab == current && !directOrigin.isEmpty()) {
              // An attachment does not commit a document. Keep this explicit action separate
              // from the previous page's sniffed rows, including rows for the same URL.
              WebResource attachment = new WebResource(uri);
              attachment.mime = resource.mime;
              attachment.classify();
              attachment.headers.putAll(headersFor(resource));
              attachment.headers.put(NativeMediaHttp.LOCAL_ORIGIN, directOrigin);
              attachment.frozenDownloadHeaders = true;
              resource = attachment;
            }
            if (resource.isVideo() || "音频".equals(resource.kind)) resourceActions(resource);
            else confirmDownload(resource);
          }
        });
    tabs.add(tab);
    if (select) selectTab(tab);
    else w.onPause();
    if (url != null) w.loadUrl(url);
    return tab;
  }

  private static boolean matchesFramePage(String callbackOrigin, String page, boolean main) {
    // WebKit reports the inherited principal separately from about:blank / about:srcdoc.
    // The bridge has already rejected opaque and non-HTTP(S) callback origins.
    return ScopedHttpDataSource.sameOrigin(callbackOrigin, page)
        || (!main && ("about:blank".equals(page) || "about:srcdoc".equals(page)));
  }

  // A parent can project its child's position but cannot report a new source or control playback.
  private void applyFrameProjection(BrowserTab tab, JSONObject event) throws JSONException {
    String owner = event.optString("owner");
    BrowserTab.Frame frame = tab.documents.get(owner);
    if (frame == null || frame.main) return;
    String key = owner + ":" + event.optString("mediaId");
    VideoTarget previous = tab.videos.get(key);
    if (previous == null || previous.sourceVersion != event.optLong("sourceVersion", -1)) return;
    if ("unproject".equals(event.optString("type"))) {
      if (previous.checkingRemoval) return;
      previous.checkingRemoval = true;
      command(tab, previous, "probe", new JSONObject());
      handler.postDelayed(
          () -> {
            if (tab.videos.get(key) != previous) return;
            tab.videos.remove(key);
            if (playerTab == tab && activeTarget != null && key.equals(activeTarget.key()))
              closePlayer(false, false);
            updateResourceBar();
          },
          1000);
    } else {
      JSONObject layout =
          new JSONObject()
              .put("documentId", owner)
              .put("mediaId", previous.mediaId)
              .put("sourceVersion", previous.sourceVersion);
      for (String field : VideoTarget.LAYOUT_FIELDS)
        if (event.has(field)) layout.put(field, event.get(field));
      VideoTarget projected = VideoTarget.read(layout, previous);
      tab.videos.put(key, projected);
      if (playerTab == tab && activeTarget != null && key.equals(activeTarget.key()))
        layoutNativeInline();
    }
  }

  private void onObservation(
      BrowserTab tab, JSONObject event, WebResource.DocumentReferrer referrer) {
    String type = event.optString("type"),
        url = event.optString("url"),
        page = event.optString("page");
    boolean local = tab.localDocument(url) && tab.localDocument(tab.url);
    if (local) {
      BrowserTab.Frame sender = tab.documents.get(referrer.documentId());
      // A foreign embed cannot substitute metadata (such as artwork) for an authorized LAN item.
      if (sender == null
          || !(sender.main || sender.firstPartyTree)
          || !tab.localDocument(sender.origin)) return;
    }
    if ("resource".equals(type)) {
      WebResource found =
          observe(
              tab,
              url,
              event.optString("mime"),
              event.optString("source", "网页媒体"),
              event.optInt("width"),
              event.optInt("height"),
              page,
              referrer);
      if (found != null && local) found.fromLocalDocument = true;
      if (found != null && found.previewIdentity.isEmpty() && event.has("poster"))
        updatePoster(found, event.optString("poster"));
      return;
    }
    if (!event.optString("mediaId").matches("[0-9]{1,16}") || !event.optBoolean("video", true))
      return;
    String key = event.optString("documentId") + ":" + event.optString("mediaId");
    VideoTarget previous = tab.videos.get(key);
    long incomingVersion = event.optLong("sourceVersion", -1);
    if (incomingVersion < 0
        || (previous != null
            && (incomingVersion < previous.sourceVersion
                || (incomingVersion == previous.sourceVersion && !url.equals(previous.url)))))
      return;
    boolean active = playerTab == tab && activeTarget != null && activeTarget.key().equals(key);
    if ("removed".equals(type)) {
      tab.videos.remove(key);
      if (active) closePlayer(false, false);
      updateResourceBar();
      return;
    }
    VideoTarget target = VideoTarget.read(event, previous);
    target.documentReferrer = referrer;
    if (!target.sameSourceAs(previous) && firstPartySource(tab, target)) {
      // HTML media uses first-party credentials for this source. Record its maximum cookie
      // scope once at registration. A later anonymous fetch to this URL cannot widen it.
      String cookie = CookieManager.getInstance().getCookie(target.url);
      if (cookie != null && cookie.length() <= 8192) target.sourceCookies = cookie;
    }
    if (!tab.retainVideo(
        key, target, tab == playerTab && activeTarget != null ? activeTarget.key() : "")) return;
    WebResource resource =
        observe(
            tab, url, "video/*", "网页视频", target.width, target.height, page, referrer);
    if (resource != null && local) resource.fromLocalDocument = true;
    bindVideoResource(tab, target, resource, "takeover".equals(type));
    if (active
        && "attached".equals(type)
        && player != null
        && target.sourceVersion == activeTarget.sourceVersion
        && target.url.equals(activeTarget.url)
        && !pendingAttachment.isEmpty()
        && pendingAttachment.equals(event.optString("attachment"))
        && !nativeAttachmentReady) {
      nativeAttachmentReady = true;
      pendingAttachment = "";
      player.setPlayWhenReady(requestedPlayIntent && (resumed || inPip));
      player.prepare();
      syncNativeState();
      return;
    }
    if (active
        && "request".equals(type)
        && player != null
        && activeTarget.sourceVersion == target.sourceVersion
        && activeTarget.url.equals(target.url)) {
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
      if (WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
          && WebResource.isHttp(target.url)) openTarget(tab, target, false);
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
                && WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)
                && previous != null
                && previous.nativeFailed
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
                || !latest.url.equals(url)
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

  private WebResource observe(
      BrowserTab tab,
      String url,
      String mime,
      String source,
      int width,
      int height,
      String page,
      WebResource.DocumentReferrer referrer) {
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
    if (referrer != null) r.documentReferrer = referrer;
    if (referrer != null || r.documentReferrer == null) r.pageUrl = page;
    r.notePreviewContext(tab.url);
    // For bridge observations the callback already validated this page against the WebKit
    // sender's origin, including the inherited origin of about:srcdoc.
    r.notePreviewContext(page);
    if (!"播放画面".equals(r.qualitySource)) {
      if (width > 0) r.width = width;
      if (height > 0) r.height = height;
    }
    if (r == playing && player != null && player.getVideoSize().width > 0) {
      r.width = player.getVideoSize().width;
      r.height = player.getVideoSize().height;
      r.qualitySource = "播放画面";
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
        || resource == null
        || resource != playing
        || !playerRenderedFrame
        || session.getPlaybackState() != Player.STATE_READY
        || SystemClock.uptimeMillis() < previewReadyAfter
        || playerView == null
        || !WebResource.isHttp(resource.url)
        || !(playerView.getVideoSurfaceView() instanceof TextureView texture)
        || !texture.isAvailable()) return;
    VideoSize size = session.getVideoSize();
    if (size.width <= 0 || size.height <= 0) return;
    if (previewCapturedSession == session
        && previewCapturedWidth == size.width
        && previewCapturedHeight == size.height) return;
    // Do not let a just-rendered black intro overwrite the decoded three-second preview.
    long duration = session.getDuration();
    if ((duration <= 0 || duration > 3000) && session.getCurrentPosition() < 3000) return;
    float factor = Math.min(1, Math.min(320f / size.width, 480f / size.height));
    int width = Math.max(1, Math.round(size.width * factor));
    int height = Math.max(1, Math.round(size.height * factor));
    // Capture only the current session's rendered surface; the loader owns its resized copy.
    android.graphics.Bitmap bitmap = texture.getBitmap(width, height);
    if (bitmap != null) {
      previewLoader.seed(resource, headersFor(resource), bitmap, size.width, size.height);
      previewCapturedSession = session;
      previewCapturedWidth = size.width;
      previewCapturedHeight = size.height;
      bitmap.recycle();
    }
  }

  // AndroidX WebKit 1.15 exposes COOKIE_INTERCEPT but omits it from its feature StringDef.
  @android.annotation.SuppressLint("WrongConstant")
  static boolean supportsCookieIntercept() {
    return WebViewFeature.isFeatureSupported(WebViewFeature.COOKIE_INTERCEPT);
  }

  void captureRequestHeaders(WebResource resource, Map<String, String> headers) {
    captureRequestHeaders(resource, headers, false);
  }

  private void captureRequestHeaders(
      WebResource resource, Map<String, String> headers, boolean navigation) {
    resource.requestHeadersCaptured = true;
    resource.navigationRequest = navigation;
    resource.requestDestination = navigation ? "document" : "";
    resource.capturedDocument = resource.documentReferrer;
    // Absence is meaningful: no-referrer/same-origin must not acquire an invented Referer.
    resource
        .headers
        .keySet()
        .removeIf(
            name ->
                name.equalsIgnoreCase("Cookie")
                    || name.equalsIgnoreCase("Referer")
                    || name.equalsIgnoreCase("Origin")
                    || name.equalsIgnoreCase("User-Agent")
                    || name.equalsIgnoreCase("Authorization"));
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      String name = entry.getKey();
      if (resource.capturedDocument != null
          && !resource.capturedDocument.matchesHeader(name, entry.getValue()))
        resource.capturedDocument = null;
      if (!navigation && name.equalsIgnoreCase("Sec-Fetch-Dest"))
        resource.requestDestination = entry.getValue();
      if (name.equalsIgnoreCase("Cookie")
          || name.equalsIgnoreCase("Referer")
          || name.equalsIgnoreCase("Origin")
          || name.equalsIgnoreCase("User-Agent")
          || name.equalsIgnoreCase("Authorization")) resource.headers.put(name, entry.getValue());
    }
    if (resource.onRequestCaptured != null) {
      Runnable ready = resource.onRequestCaptured;
      resource.onRequestCaptured = null;
      handler.removeCallbacks(ready);
      ready.run();
    }
  }

  Map<String, String> headersFor(WebResource r) {
    return headersFor(r, r.documentReferrer);
  }

  private static void removeDocumentHeaders(Map<String, String> headers) {
    headers.keySet().removeIf(name -> name.equalsIgnoreCase("Referer")
        || name.equalsIgnoreCase("Origin") || name.equalsIgnoreCase("Cookie")
        || name.equalsIgnoreCase("Authorization"));
  }

  private Map<String, String> headersFor(WebResource r, WebResource.DocumentReferrer document) {
    Map<String, String> h = new LinkedHashMap<>(r.headers);
    if (r.frozenDownloadHeaders) return h;
    if (r.fromLocalDocument
        && current != null
        && current.resources.get(r.url) == r
        && current.localDocument(current.web.getUrl())
        && current.localDocument(r.url)) h.put(NativeMediaHttp.LOCAL_ORIGIN, current.localOrigin);
    if (current != null
        && h.keySet().stream().noneMatch(name -> name.equalsIgnoreCase("User-Agent")))
      h.put("User-Agent", current.web.getSettings().getUserAgentString());
    if (document != null && r.requestHeadersCaptured) {
      // WebView supplies the URL and headers but no initiating frame ID. Reuse its captured
      // headers only for a document that could have sent that Referer and Origin.
      boolean differentDocument =
          (r.capturedDocument != null && !document.sameDocument(r.capturedDocument))
              || h.entrySet().stream()
                  .anyMatch(e -> !document.matchesHeader(e.getKey(), e.getValue()));
      if (differentDocument) removeDocumentHeaders(h);
    } else if (document != null) {
      String referrer = document.forRequest(r.url);
      if (!referrer.isBlank()) h.put("Referer", referrer);
    }
    boolean firstParty =
        current != null
            && current.resources.get(r.url) == r
            && MediaRequestPolicy.sameOrigin(current.web.getUrl(), r.url)
            && MediaRequestPolicy.sameOrigin(document == null ? r.pageUrl : document.origin(), r.url);
    // WebView can attach a Cookie to the intercept callback even for fetch(credentials:'omit').
    // An unknown fetch destination is insufficient evidence, including for a known video URL.
    if (!firstParty || !r.requestHeadersCaptured) {
      h.keySet().removeIf(k -> k.equalsIgnoreCase("Cookie") || k.equalsIgnoreCase("Authorization"));
    } else if (!r.navigationRequest
        && !("video".equalsIgnoreCase(r.requestDestination)
            && current.videos.values().stream()
                .anyMatch(v -> v.url.equals(r.url) && firstPartySource(current, v)))) {
      h.keySet().removeIf("Cookie"::equalsIgnoreCase);
    }
    return h;
  }

  private boolean firstPartySource(BrowserTab tab, VideoTarget target) {
    BrowserTab.Frame owner = tab.documents.get(target.documentId);
    return owner != null
        && owner.firstPartyTree
        && MediaRequestPolicy.sameOrigin(owner.origin, target.url)
        && MediaRequestPolicy.sameOrigin(tab.web.getUrl(), target.url);
  }

  Map<String, String> playbackHeadersFor(WebResource r, VideoTarget target) {
    VideoTarget live = current != null && target != null ? current.videos.get(target.key()) : null;
    boolean sameSource =
        live != null && live.sameSourceAs(target) && live.url.equals(r.url)
            && current.resources.get(r.url) == r && current.documents.containsKey(live.documentId);
    Map<String, String> headers = headersFor(r, sameSource ? live.documentReferrer : r.documentReferrer);
    headers.putIfAbsent(MediaTransferCache.SCOPE_HEADER, r.transferScope);
    if (target == null || r.frozenDownloadHeaders) return headers;
    if (!sameSource) {
      removeDocumentHeaders(headers);
      return headers;
    }
    // The selected HTML source is a separate operation from previewing a sniffed request.
    // A live document, element and source generation must all match the original grant.
    headers.keySet().removeIf("Cookie"::equalsIgnoreCase);
    if (firstPartySource(current, live)) {
      if (!live.sourceCookies.isEmpty()) {
        String cookie = live.remainingSourceCookies(CookieManager.getInstance().getCookie(r.url));
        if (!cookie.isEmpty()) headers.put("Cookie", cookie);
      }
      // The active player's download uses the same transfer context as its decoder.
      if (!r.requestHeadersCaptured && !activeReferrer.isEmpty() && playing == r
          && playerTab == current && target == activeTarget
          && activeReferrerGeneration == current.playGeneration && live.documentReferrer != null
          && live.documentReferrer.sameDocument(target.documentReferrer)
          && live.documentReferrer.matchesHeader("Referer", activeReferrer))
        headers.put("Referer", activeReferrer);
    }
    return headers;
  }

  Map<String, String> mediaHeadersFor(WebResource r) {
    return playbackHeadersFor(r, null);
  }

  void selectTab(BrowserTab tab) {
    if (current == tab) return;
    scrollControls.reveal();
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
    tabButton.setContentDescription("标签页，" + tabs.size() + " 个");
  }

  void updateResourceBar() {
    if (current == null) return;
    long count = current.resources.values().stream().filter(WebResource::isVideo).count();
    resourceText.setText(
        count > 0
            ? "视频 " + count + " · 资源 " + current.resources.size()
            : "网页资源 · " + current.resources.size());
    if (takeoverButton != null) {
      takeoverButton.setText(
          player != null && playerTab == current
              ? "网页播放"
              : current.videos.isEmpty() && count > 0 ? "播放视频" : "接管播放");
      takeoverButton.setEnabled(player != null || !current.videos.isEmpty() || count > 0);
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
    openTarget(tab, target, explicit, true, "");
  }

  private void openTarget(
      BrowserTab tab, VideoTarget target, boolean explicit, boolean awaitRequest, String referer) {
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
            target.width,
            target.height,
            target.page,
            target.documentReferrer);
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
    long generation = ++tab.playGeneration;
    if (awaitRequest
        && !resource.requestHeadersCaptured
        && MediaRequestPolicy.sameOrigin(tab.web.getUrl(), resource.url)) {
      // A site's src mutation can reach us before WebView dispatches the matching GET. Retain
      // the browser's decoder briefly so source swaps can capture their own request context.
      if (resource.onRequestCaptured != null) handler.removeCallbacks(resource.onRequestCaptured);
      BrowserTab.Frame frame = tab.documents.get(target.documentId);
      BrowserTab.ReferrerProbe probe = firstPartySource(tab, target) && !frame.referrerKey.isEmpty()
          ? new BrowserTab.ReferrerProbe(resource.url, tab.epoch, frame.referrerKey) : null;
      tab.referrerProbe = probe;
      resource.onRequestCaptured =
          () -> {
            resource.onRequestCaptured = null;
            if (tab.referrerProbe == probe) tab.referrerProbe = null;
            VideoTarget latest = tab.videos.get(target.key());
            if (tab != current
                || tab.playGeneration != generation
                || !resumed
                || latest == null
                || !latest.sameSourceAs(target)) return;
            String observed = probe == null ? null : probe.referer.get();
            boolean usable = observed != null && !resource.requestHeadersCaptured
                && probe.epoch == tab.epoch && firstPartySource(tab, latest)
                && latest.documentReferrer != null && target.documentReferrer != null
                && latest.documentReferrer.sameDocument(target.documentReferrer)
                && latest.documentReferrer.matchesHeader("Referer", observed);
            openTarget(tab, latest, explicit, false, usable ? observed : "");
          };
      handler.postDelayed(resource.onRequestCaptured, 750);
      if (probe != null) {
        JSONObject data = new JSONObject();
        try {
          data.put("url", resource.url).put("token", probe.challenge);
          command(tab, target, "referrer", data);
        } catch (JSONException ignored) {}
      }
      return;
    }
    target.suppressed = false;
    target.nativeFailed = false;
    tab.selectedVideo = target.key();
    VideoChrome.Mode next =
        activeTarget != null && activeTarget.key().equals(target.key())
            ? playerMode
            : VideoChrome.Mode.INLINE;
    boolean intent =
        activeTarget != null && activeTarget.key().equals(target.key()) && player != null
            ? player.getPlayWhenReady()
            : explicit || !target.paused;
    startPlayer(resource, Math.max(0, (long) (target.time * 1000)), target, next, intent, false, referer);
  }

  void startPlayer(
      WebResource resource,
      long position,
      VideoTarget target,
      VideoChrome.Mode mode,
      boolean playIntent) {
    startPlayer(resource, position, target, mode, playIntent, false, "");
  }

  private void startPlayer(
      WebResource resource,
      long position,
      VideoTarget target,
      VideoChrome.Mode mode,
      boolean playIntent,
      boolean downloadedFile,
      String observedReferrer) {
    closePlayer(false, false);
    playerStartPosition = position;
    playerRenderedFrame = false;
    nativeAttachmentReady = target == null;
    pendingAttachment = target == null ? "" : UUID.randomUUID().toString();
    requestedPlayIntent = playIntent;
    playing = resource;
    playerTab = current;
    activeTarget = target;
    activeReferrer = observedReferrer;
    activeReferrerGeneration = current == null ? 0 : current.playGeneration;
    if (target != null && playerTab != null) playerTab.selectedVideo = target.key();
    logicalVolume = target == null ? 1 : (float) Math.max(0, Math.min(1, target.volume));
    logicalMuted = target != null && target.muted;
    appliedGain = logicalMuted ? 0 : logicalVolume;
    playerMode = mode;
    androidx.media3.exoplayer.source.MediaSource.Factory sources;
    if (downloadedFile) {
      // Completed outputs are standalone files published by DownloadEngine.
      sources =
          new androidx.media3.exoplayer.source.ProgressiveMediaSource.Factory(
              () -> new androidx.media3.datasource.ContentDataSource(this));
    } else {
      Map<String, String> headers = playbackHeadersFor(resource, target);
      boolean hls =
          resource.isHls()
              || androidx.media3.common.util.Util.inferContentType(Uri.parse(resource.url))
                  == C.CONTENT_TYPE_HLS;
      var network = MediaTransferCache.remotePlayback(this, resource.url, headers, hls);
      sources =
          hls
              ? new androidx.media3.exoplayer.hls.HlsMediaSource.Factory(
                      HlsManifestInput.sources(network))
                  .setPlaylistParserFactory(new NetworkHlsParserFactory())
              : new DefaultMediaSourceFactory(network);
    }
    player = new ExoPlayer.Builder(this).setMediaSourceFactory(sources).build();
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
                if (playing != null) confirmDownload(playing, activeTarget);
              }

              @Override
              public void onSelectSource() {
                showVideoTargets();
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
              if (events.contains(Player.EVENT_POSITION_DISCONTINUITY)
                  || events.contains(Player.EVENT_PLAYBACK_STATE_CHANGED))
                previewReadyAfter = SystemClock.uptimeMillis() + 160;
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
          public void onVideoSizeChanged(VideoSize size) {
            if (session != player || resource != playing || size.width <= 0 || size.height <= 0)
              return;
            previewReadyAfter = SystemClock.uptimeMillis() + 160;
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
          capturePlayingPreview(player, playing);
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
      if ("attach".equals(action)) data.put("attachment", pendingAttachment);
      BrowserTab.Frame frame = tab.documents.get(target.documentId);
      if (frame != null && WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER))
        frame.reply.postMessage(
            new JSONObject()
                .put("documentId", target.documentId)
                .put("epoch", tab.epoch)
                .put("command", data)
                .toString());
    } catch (JSONException | IllegalStateException ignored) {
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
    if (!Float.isFinite(scale) || !(scale > 0) || target.rect.isEmpty() || target.contentClip.isEmpty()) {
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
    if (!Float.isFinite(rect.left) || !Float.isFinite(rect.top)
        || !InlineVideoGeometry.fitsViewport(rect.width(), rect.height(), web.getWidth(), web.getHeight())) {
      anchorClip.setVisibility(View.INVISIBLE);
      inlinePinned = false;
      inlineExpansion = 0;
      return;
    }
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
    if (!InlineVideoGeometry.fitsViewport(rect.width(), rect.height(), web.getWidth(), web.getHeight())
        || !visible.intersect(rect) || !visible.intersect(0, 0, web.getWidth(), web.getHeight())) {
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
    if (current != null) current.playGeneration++;
    BrowserTab owner = playerTab;
    VideoTarget target = activeTarget;
    long position = player == null ? 0 : player.getCurrentPosition();
    if (failed && !playerRenderedFrame) position = Math.max(position, playerStartPosition);
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
    previewCapturedSession = null;
    previewReadyAfter = 0;
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
    activeReferrer = "";
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
    if (current != null && current.videos.isEmpty()) {
      List<WebResource> videos = new ArrayList<>();
      for (WebResource resource : current.resources.values())
        if (resource.isVideo()) videos.add(resource);
      if (videos.size() == 1) {
        openPlayer(videos.get(0), 0);
        return;
      }
      if (videos.size() > 1) {
        showResources("视频");
        return;
      }
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
    // Retire the previous updater before dismiss() queues that dialog's listener.
    stopSheetRefresh();
    if (activeSheet != null) activeSheet.dismiss();
    Dialog dialog =
        new Dialog(
            new ContextThemeWrapper(
                this, Ui.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme));
    dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
    activeSheet = dialog;
    LinearLayout box = new LinearLayout(this);
    box.setOrientation(LinearLayout.VERTICAL);
    box.setClipToPadding(false);
    box.setPadding(dp(16), 0, dp(16), dp(16));
    box.setBackgroundColor(Ui.BG);
    LinearLayout header = new LinearLayout(this);
    header.setOrientation(LinearLayout.VERTICAL);
    header.setPadding(dp(16), dp(6), dp(8), dp(12));
    header.setBackgroundColor(Ui.PLANE);
    LinearLayout topline = new LinearLayout(this);
    topline.setGravity(Gravity.CENTER_VERTICAL);
    TextView heading =
        Ui.text(
            this,
            title.equals("WebSurf") ? "//version. " + BuildConfig.VERSION_NAME : title,
            14,
            Ui.PLANE_INK);
    heading.setTypeface(Typeface.create("sans-serif", Typeface.BOLD));
    heading.setMaxLines(2);
    heading.setEllipsize(TextUtils.TruncateAt.END);
    topline.addView(heading, new LinearLayout.LayoutParams(0, dp(48), 1));
    ImageButton close = Ui.icon(this, "close", "关闭" + title);
    close.setImageTintList(android.content.res.ColorStateList.valueOf(Ui.PLANE_INK));
    topline.addView(close, lp(48, 48));
    close.setOnClickListener(v -> dialog.dismiss());
    header.addView(topline, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout displayLine = new LinearLayout(this);
    displayLine.setGravity(Gravity.CENTER_VERTICAL);
    TextView display =
        Ui.display(this, displayTitle(title), title.equals("WebSurf") ? 64 : 48, Ui.PLANE_INK);
    display.setSingleLine(true);
    display.setAutoSizeTextTypeUniformWithConfiguration(
        26, title.equals("WebSurf") ? 64 : 48, 1, android.util.TypedValue.COMPLEX_UNIT_SP);
    display.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    displayLine.addView(
        display, new LinearLayout.LayoutParams(0, dp(title.equals("WebSurf") ? 72 : 54), 1));
    if (title.equals("WebSurf")) {
      ImageView logo = new ImageView(this);
      logo.setImageResource(R.drawable.ws_logo);
      logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
      logo.setContentDescription("WebSurf");
      LinearLayout.LayoutParams logoParams = lp(72, 72);
      logoParams.setMarginStart(dp(12));
      logoParams.setMarginEnd(dp(8));
      displayLine.addView(logo, logoParams);
    }
    header.addView(displayLine, new LinearLayout.LayoutParams(-1, -2));
    LinearLayout.LayoutParams headerParams = new LinearLayout.LayoutParams(-1, -2);
    headerParams.leftMargin = -dp(16);
    headerParams.rightMargin = -dp(16);
    headerParams.bottomMargin = dp(16);
    box.addView(header, headerParams);
    box.addView(Ui.rule(this, Ui.BORDER), new LinearLayout.LayoutParams(-1, dp(1)));
    dialog.setContentView(box);
    Window window = dialog.getWindow();
    if (window != null) {
      window.setBackgroundDrawableResource(android.R.color.transparent);
      window.setGravity(Gravity.BOTTOM);
      window.addFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
      WindowManager.LayoutParams attr = window.getAttributes();
      attr.dimAmount = .52f;
      attr.windowAnimations = 0;
      window.setAttributes(attr);
      window.setNavigationBarColor(Ui.BG);
      window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);
    }
    dialog.setOnDismissListener(
        d -> {
          // A delayed close from an older panel must not cancel its successor's updater.
          if (activeSheet == dialog) {
            stopSheetRefresh();
            activeSheet = null;
          }
        });
    dialog.show();
    if (window != null) window.setLayout(-1, -2);
    Ui.enter(box);
    return box;
  }

  private void stopSheetRefresh() {
    if (sheetRefresh != null) handler.removeCallbacks(sheetRefresh);
    sheetRefresh = null;
  }

  String displayTitle(String title) {
    return switch (title) {
      case "WebSurf" -> "WEBSURF";
      case "网页资源" -> "RESOURCES";
      case "下载" -> "DOWNLOADS";
      case "设置" -> "SETTINGS";
      case "标签页" -> "TABS";
      case "书签与历史" -> "LIBRARY";
      case "选择网页视频" -> "VIDEO SOURCE";
      case "下载并发" -> "CONNECTIONS";
      case "搜索引擎" -> "SEARCH ENGINE";
      default -> "WEBSURF";
    };
  }

  LinearLayout scrollBody(LinearLayout box) {
    ScrollView scroll =
        new ScrollView(this) {
          @Override
          protected void onMeasure(int widthSpec, int heightSpec) {
            int limit =
                Math.min(
                    dp(470),
                    Math.max(
                        dp(96),
                        root.getHeight()
                            - root.getPaddingTop()
                            - root.getPaddingBottom()
                            - dp(240)));
            if (View.MeasureSpec.getMode(heightSpec) != View.MeasureSpec.UNSPECIFIED)
              limit = Math.min(limit, View.MeasureSpec.getSize(heightSpec));
            super.onMeasure(
                widthSpec, View.MeasureSpec.makeMeasureSpec(limit, View.MeasureSpec.AT_MOST));
          }
        };
    scroll.setFillViewport(false);
    LinearLayout body = new LinearLayout(this);
    body.setOrientation(LinearLayout.VERTICAL);
    scroll.addView(body);
    box.addView(scroll, new LinearLayout.LayoutParams(-1, -2));
    return body;
  }

  TextView note(LinearLayout box, String text) {
    TextView label = Ui.text(this, text, 13, Ui.MUTED);
    label.setPadding(0, dp(8), 0, dp(8));
    box.addView(label, new LinearLayout.LayoutParams(-1, -2));
    return label;
  }

  Button action(LinearLayout box, String title, Runnable callback) {
    Button b = Ui.button(this, title, false);
    b.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    b.setCompoundDrawablePadding(dp(12));
    Ui.Icon glyph = new Ui.Icon(actionIcon(title), Ui.TEXT);
    glyph.setBounds(0, 0, dp(20), dp(20));
    b.setCompoundDrawablesRelative(glyph, null, null, null);
    LinearLayout.LayoutParams actionSpace = new LinearLayout.LayoutParams(-1, -2);
    actionSpace.topMargin = dp(8);
    box.addView(b, actionSpace);
    b.setOnClickListener(v -> callback.run());
    return b;
  }

  String actionIcon(String title) {
    if (title.startsWith("搜索引擎")) return "search";
    if (title.contains("通知")) return "bell";
    if (title.contains("下载") || title.contains("并发")) return "download";
    if (title.contains("书签") || title.contains("历史")) return "bookmark";
    if (title.contains("复制") || title.contains("地址")) return "copy";
    if (title.contains("设置")) return "settings";
    if (title.contains("主页")) return "home";
    if (title.contains("标签")) return "tabs";
    if (title.contains("小窗")) return "float";
    return "video";
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
    filterLayout.topMargin = dp(12);
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
          if (shown == 0) note(body, "暂无" + ("全部".equals(filter[0]) ? "资源" : filter[0]));
        };
    for (String name : new String[] {"视频", "音频", "图片", "其他", "全部"}) {
      Button b = Ui.button(this, name, name.equals(initialFilter));
      b.setSelected(name.equals(initialFilter));
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
              child.setSelected(selected);
              child.setTextColor(selected ? Ui.ACTION_INK : Ui.TEXT);
              child.setBackgroundTintList(null);
              Ui.ripple(child, selected ? Ui.ACTION : Ui.SURFACE, 2);
            }
            render.run();
          });
    }
    render.run();
    Dialog panel = activeSheet;
    sheetRefresh =
        new Runnable() {
          public void run() {
            if (activeSheet != panel || sheetRefresh != this) return;
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
    alert()
        .setTitle(r.label())
        .setItems(
            options,
            (d, index) -> {
              if (index == 0) {
                if (activeSheet != null) activeSheet.dismiss();
                if (r.isVideo() || "音频".equals(r.kind)) openPlayer(r, 0);
                else navigate(r.url, false);
              } else if (index == 1) confirmDownload(r);
              else if (index == 2) copy(r.url);
              else
                alert()
                    .setTitle("资源地址")
                    .setMessage(r.url)
                    .setPositiveButton("复制", (a, b) -> copy(r.url))
                    .setNegativeButton("关闭", null)
                    .show();
            })
        .show();
  }

  void confirmDownload(WebResource r) {
    confirmDownload(r, null);
  }

  private void confirmDownload(WebResource r, VideoTarget target) {
    for (DownloadStore.Job existing : DownloadStore.list(this))
      if (existing.active() && existing.url.equals(r.url)) {
        showDownloadFeedback("正在下载", existing);
        return;
      }
    String downloadTitle = downloadTitle(r);
    new AlertDialog.Builder(
            new ContextThemeWrapper(
                this, Ui.isDark(this) ? R.style.AppThemeDark : R.style.AppTheme))
        .setTitle("下载")
        .setMessage(downloadTitle)
        .setNegativeButton("取消", null)
        .setPositiveButton(
            "下载",
            (d, w) -> {
              if (Build.VERSION.SDK_INT >= 33
                  && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                      != PackageManager.PERMISSION_GRANTED
                  && !prefs.getBoolean("notification_requested", false)) {
                prefs.edit().putBoolean("notification_requested", true).apply();
                requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 42);
              }
              try {
                String id =
                    DownloadService.enqueue(
                        this, r.url, downloadTitle, playbackHeadersFor(r, target), r.mime);
                DownloadStore.Job job = DownloadStore.get(this, id);
                if (job != null) {
                  downloadStates.put(id, job.state);
                  showDownloadFeedback(
                      job.active() ? "已加入下载" : "completed".equals(job.state) ? "下载完成" : "下载失败",
                      job);
                }
                refreshDownloadBadge();
              } catch (Exception error) {
                showError("无法开始下载", error);
              }
            })
        .show();
  }

  String downloadTitle(WebResource resource) {
    String pageTitle = "";
    if (resource.isVideo() || "音频".equals(resource.kind)) {
      for (BrowserTab tab : tabs) {
        if (tab.resources.get(resource.url) == resource) {
          pageTitle = tab.title;
          break;
        }
      }
      if (pageTitle.isBlank() && resource == playing && playerTab != null)
        pageTitle = playerTab.title;
    }
    String name = DownloadNames.meaningfulTitle(resource.title, pageTitle, resource.label());
    return name.isBlank() ? (resource.isVideo() ? "WebSurf 视频" : "WebSurf 下载") : name;
  }

  void refreshDownloadBadge() {
    if (downloadBadge == null) return;
    long active = DownloadStore.list(this).stream().filter(DownloadStore.Job::active).count();
    downloadBadge.setText(active > 9 ? "9+" : String.valueOf(active));
    downloadBadge.setVisibility(active > 0 ? View.VISIBLE : View.GONE);
    downloadsButton.setContentDescription(active > 0 ? "下载任务，" + active + "项进行中" : "下载任务");
  }

  void refreshDownloadFeedback() {
    for (DownloadStore.Job job : DownloadStore.list(this)) {
      String before = downloadStates.put(job.id, job.state);
      if (Objects.equals(before, job.state)) continue;
      String message =
          "completed".equals(job.state)
              ? "下载完成"
              : "failed".equals(job.state)
                  ? "下载失败"
                  : "downloading".equals(job.state) && "queued".equals(before) ? "开始下载" : null;
      if (message != null) {
        if (resumed) showDownloadFeedback(message, job);
        else if (!job.active()) pendingDownloadFeedback = job;
      }
    }
    refreshDownloadBadge();
  }

  void showDownloadFeedback(String message, DownloadStore.Job job) {
    if (downloadFeedback != null) root.removeView(downloadFeedback);
    LinearLayout bar = new LinearLayout(this);
    downloadFeedback = bar;
    bar.setTag("download-feedback");
    bar.setGravity(Gravity.CENTER_VERTICAL);
    bar.setPadding(dp(12), dp(4), dp(4), dp(4));
    bar.setBackground(Ui.shape(this, Ui.SOFT_ACCENT, 6));
    bar.setElevation(dp(6));
    TextView label = Ui.text(this, message + " · " + job.title, 13, Ui.TEXT);
    label.setMaxLines(2);
    label.setEllipsize(TextUtils.TruncateAt.END);
    bar.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
    Button view = Ui.button(this, "查看", false);
    bar.addView(view, lp(64, 48));
    view.setOnClickListener(
        v -> {
          root.removeView(bar);
          showDownloads();
        });
    ImageButton close = Ui.icon(this, "close", "关闭提示");
    bar.addView(close, lp(48, 48));
    close.setOnClickListener(v -> root.removeView(bar));
    FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM);
    at.setMargins(dp(12), 0, dp(12), dp(112));
    root.addView(bar, at);
    bar.announceForAccessibility(message + "，" + job.title);
    handler.postDelayed(
        () -> {
          if (bar.getParent() == root) root.removeView(bar);
        },
        6000);
  }

  void showDownloads() {
    LinearLayout body = scrollBody(sheet("下载"));
    String requestedTask = getIntent().getStringExtra("download_id");
    getIntent().removeExtra("download_id");
    Map<String, DownloadTaskRow> rows = new LinkedHashMap<>();
    DownloadTaskRow.Actions callbacks =
        new DownloadTaskRow.Actions() {
          public void open(DownloadStore.Job job) {
            openDownloaded(job);
          }

          public void preview(DownloadStore.Job job) {
            openDownloadPreview(job);
          }

          public void retry(DownloadStore.Job job) {
            DownloadService.retry(MainActivity.this, job.id);
            showDownloadFeedback("已加入下载", job);
          }

          public void cancel(DownloadStore.Job job) {
            DownloadService.cancel(MainActivity.this, job.id);
          }

          public void remove(DownloadStore.Job job) {
            alert()
                .setTitle("移除记录？")
                .setMessage("已下载的文件会保留")
                .setNegativeButton("取消", null)
                .setPositiveButton(
                    "移除",
                    (d, w) -> {
                      DownloadStore.remove(MainActivity.this, job.id);
                      View row = rows.remove(job.id);
                      if (row != null) body.removeView(row);
                    })
                .show();
          }
        };
    Runnable render =
        () -> {
          List<DownloadStore.Job> jobs = DownloadStore.list(this);
          if (jobs.isEmpty()) {
            if (rows.isEmpty() && body.getChildCount() == 0) note(body, "暂无下载");
            return;
          }
          if (rows.isEmpty()) body.removeAllViews();
          for (int i = 0; i < jobs.size(); i++) {
            DownloadStore.Job job = jobs.get(i);
            DownloadTaskRow row = rows.get(job.id);
            if (row == null) {
              row = new DownloadTaskRow(this, callbacks);
              rows.put(job.id, row);
              LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(-1, -2);
              params.bottomMargin = dp(8);
              body.addView(row, Math.min(i, body.getChildCount()), params);
            }
            row.bind(job);
          }
        };
    render.run();
    if (requestedTask != null && rows.containsKey(requestedTask)) {
      DownloadTaskRow selected = rows.get(requestedTask);
      body.postDelayed(
          () -> {
            if (selected.getParent() == body) {
              ((ScrollView) body.getParent()).smoothScrollTo(0, selected.getTop());
              selected.announceForAccessibility("下载任务");
            }
          },
          220);
    }
    Dialog owner = activeSheet;
    sheetRefresh =
        new Runnable() {
          public void run() {
            if (activeSheet != owner || sheetRefresh != this) return;
            render.run();
            handler.postDelayed(this, 750);
          }
        };
    handler.postDelayed(sheetRefresh, 750);
  }

  void openDownloadPreview(DownloadStore.Job job) {
    if (activeSheet != null) activeSheet.dismiss();
    long position = 0;
    if (player != null
        && playing != null
        && playing.url.equals(job.url)
        && Objects.equals(
            mediaHeadersFor(playing).get(MediaTransferCache.SCOPE_HEADER),
            job.headers.get(MediaTransferCache.SCOPE_HEADER)))
      position = player.getCurrentPosition();
    WebResource resource = new WebResource(job.url);
    resource.title = job.title;
    resource.mime = job.mimeType;
    resource.classify();
    resource.headers.putAll(job.headers);
    resource.frozenDownloadHeaders = true;
    startPlayer(resource, position, null, VideoChrome.Mode.FLOATING, true);
    if (player != null && resource.isHls())
      player.setTrackSelectionParameters(
          player
              .getTrackSelectionParameters()
              .buildUpon()
              .setForceHighestSupportedBitrate(true)
              .build());
  }

  void openDownloaded(DownloadStore.Job job) {
    if (job.mimeType != null
        && (job.mimeType.startsWith("video/") || job.mimeType.startsWith("audio/"))) {
      WebResource resource = new WebResource(job.outputUri);
      resource.mime = job.mimeType;
      resource.title = job.title;
      resource.classify();
      if (activeSheet != null) activeSheet.dismiss();
      startPlayer(resource, 0, null, VideoChrome.Mode.FLOATING, true, true, "");
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
    int tabIndex = 0;
    for (BrowserTab tab : new ArrayList<>(tabs)) {
      LinearLayout row = new LinearLayout(this);
      row.setGravity(Gravity.CENTER_VERTICAL);
      row.setPadding(dp(8), 0, 0, 0);
      row.setBackground(Ui.outline(this, Ui.SURFACE, tab == current ? Ui.CONTROL : Ui.BORDER, 0));
      row.setSelected(tab == current);
      TextView index =
          Ui.display(
              this,
              String.format(Locale.ROOT, "%02d", ++tabIndex),
              30,
              tab == current ? Ui.CONTROL : Ui.MUTED);
      index.setGravity(Gravity.CENTER);
      index.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
      row.addView(index, lp(48, 64));
      TextView name = Ui.text(this, tab.title, 14, Ui.TEXT);
      name.setTypeface(tab == current ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
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
    LinearLayout body = scrollBody(box);
    LinearLayout portals = new LinearLayout(this);
    int resources = current == null ? 0 : current.resources.size();
    List<DownloadStore.Job> jobs = DownloadStore.list(this);
    long active = jobs.stream().filter(DownloadStore.Job::active).count();
    portals.addView(
        menuTile("网页资源", "video", resources, () -> showResources("全部")),
        new LinearLayout.LayoutParams(0, dp(132), 1));
    LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(0, dp(132), 1);
    second.leftMargin = dp(8);
    portals.addView(menuTile("下载", "download", jobs.size(), () -> showDownloads()), second);
    LinearLayout.LayoutParams portalParams = new LinearLayout.LayoutParams(-1, -2);
    portalParams.topMargin = dp(12);
    portalParams.bottomMargin = dp(4);
    body.addView(portals, portalParams);
    if (active > 0) note(body, active + " 个任务正在下载");
    Button takeover = Ui.button(this, player != null ? "返回网页播放" : "接管网页视频", true);
    takeover.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
    Ui.Icon playIcon = new Ui.Icon("play", Ui.ACTION_INK);
    playIcon.setBounds(0, 0, dp(22), dp(22));
    takeover.setCompoundDrawablesRelative(playIcon, null, null, null);
    takeover.setCompoundDrawablePadding(dp(12));
    LinearLayout.LayoutParams takeoverParams = new LinearLayout.LayoutParams(-1, -2);
    takeoverParams.topMargin = dp(8);
    body.addView(takeover, takeoverParams);
    takeover.setOnClickListener(
        v -> {
          activeSheet.dismiss();
          toggleTakeover();
        });
    action(
        body,
        "添加书签",
        () -> {
          saveBookmark();
          activeSheet.dismiss();
        });
    action(body, "书签与历史", () -> showSaved());
    if (playing != null)
      action(
          body,
          "系统小窗",
          () -> {
            activeSheet.dismiss();
            enterPictureInPictureMode(
                new PictureInPictureParams.Builder().setAspectRatio(new Rational(16, 9)).build());
          });
    action(
        body,
        "复制网页链接",
        () -> {
          copy(current.url);
          activeSheet.dismiss();
        });
    action(body, "设置", () -> showSettings());
  }

  View menuTile(String label, String icon, int count, Runnable callback) {
    LinearLayout tile = new LinearLayout(this);
    tile.setOrientation(LinearLayout.VERTICAL);
    tile.setPadding(dp(14), dp(12), dp(14), dp(12));
    Ui.ripple(tile, Ui.SURFACE, 2);
    tile.setContentDescription(label + "，" + count);
    tile.setOnClickListener(v -> callback.run());
    LinearLayout top = new LinearLayout(this);
    top.setGravity(Gravity.CENTER_VERTICAL);
    TextView name = Ui.text(this, label, 15, Ui.TEXT);
    name.setTypeface(Typeface.DEFAULT_BOLD);
    name.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
    ImageView glyph = new ImageView(this);
    glyph.setImageDrawable(new Ui.Icon(icon, Ui.CONTROL));
    glyph.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    top.addView(glyph, lp(24, 24));
    tile.addView(top);
    TextView value = Ui.display(this, String.format(Locale.ROOT, "%02d", count), 56, Ui.CONTROL);
    value.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
    LinearLayout.LayoutParams countParams = new LinearLayout.LayoutParams(-1, 0, 1);
    value.setGravity(Gravity.BOTTOM);
    tile.addView(value, countParams);
    tile.addView(Ui.rule(this, Ui.BORDER), new LinearLayout.LayoutParams(-1, dp(1)));
    return tile;
  }

  void showSettings() {
    LinearLayout box = sheet("设置");
    LinearLayout body = scrollBody(box);
    sectionLabel(body, "浏览");
    note(body, "主页");
    EditText home = new EditText(chromeContext());
    home.setSingleLine(true);
    home.setTextSize(14);
    home.setTextColor(Ui.TEXT);
    home.setHintTextColor(Ui.MUTED);
    home.setBackground(Ui.outline(this, Ui.SURFACE, Ui.BORDER, 2));
    home.setPadding(dp(12), 0, dp(12), 0);
    tintTextInput(home);
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
    action(body, "搜索引擎 · " + searchEngine().label, this::showSearchEngines);
    addSwitch(
        body,
        "自动接管视频",
        prefs.getBoolean("auto_play", true),
        checked -> {
          prefs.edit().putBoolean("auto_play", checked).apply();
          if (!checked) closePlayer(true, true);
        });
    sectionLabel(body, "下载");
    boolean notifications = getSystemService(NotificationManager.class).areNotificationsEnabled();
    action(
        body,
        "下载通知 · " + (notifications ? "已开启" : "未开启"),
        () -> {
          try {
            startActivity(
                new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, getPackageName()));
          } catch (RuntimeException error) {
            showError("无法打开通知设置", error);
          }
        });
    int threads = Math.max(1, Math.min(16, prefs.getInt("download_threads", 8)));
    action(body, "下载并发 · " + threads + " 路", this::showConnections);
    sectionLabel(body, "外观与网页");
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
    sectionLabel(body, "关于");
    note(
        body,
        "Chromium WebView\n"
            + (provider == null ? "系统提供" : provider.packageName + " · " + provider.versionName));
    note(body, "WebSurf " + BuildConfig.VERSION_NAME);
  }

  void sectionLabel(LinearLayout body, String title) {
    TextView label = Ui.text(this, title, 12, Ui.MUTED);
    label.setTypeface(Typeface.DEFAULT_BOLD);
    label.setPadding(0, dp(18), 0, dp(8));
    body.addView(label, new LinearLayout.LayoutParams(-1, -2));
    body.addView(Ui.rule(this, Ui.BORDER), new LinearLayout.LayoutParams(-1, dp(1)));
  }

  void showSearchEngines() {
    LinearLayout body = scrollBody(sheet("搜索引擎"));
    SearchEngine selected = searchEngine();
    for (SearchEngine engine : SearchEngine.values()) {
      Button choice =
          action(
              body,
              engine.label,
              () -> {
                prefs.edit().putString("search_engine", engine.id).apply();
                showSettings();
              });
      choice.setSelected(engine == selected);
      if (engine == selected) {
        choice.setTextColor(Ui.ACTION_INK);
        Ui.ripple(choice, Ui.ACTION, 2);
        Ui.Icon check = new Ui.Icon("check", Ui.ACTION_INK);
        check.setBounds(0, 0, dp(20), dp(20));
        choice.setCompoundDrawablesRelative(check, null, null, null);
      } else choice.setCompoundDrawablesRelative(null, null, null, null);
    }
  }

  void showConnections() {
    LinearLayout body = scrollBody(sheet("下载并发"));
    int selected = prefs.getInt("download_threads", 8);
    for (int count : new int[] {1, 2, 4, 8, 16}) {
      Button choice =
          action(
              body,
              count + " 路",
              () -> {
                prefs.edit().putInt("download_threads", count).apply();
                showSettings();
              });
      choice.setSelected(count == selected);
      if (count == selected) {
        choice.setTextColor(Ui.ACTION_INK);
        Ui.ripple(choice, Ui.ACTION, 2);
        Ui.Icon check = new Ui.Icon("check", Ui.ACTION_INK);
        check.setBounds(0, 0, dp(20), dp(20));
        choice.setCompoundDrawablesRelative(check, null, null, null);
      } else choice.setCompoundDrawablesRelative(null, null, null, null);
    }
  }

  interface Checked {
    void run(boolean checked);
  }

  void addSwitch(LinearLayout box, String title, boolean initial, Checked callback) {
    Switch control = new Switch(this);
    control.setText(title);
    control.setTextSize(14);
    control.setTextColor(Ui.TEXT);
    control.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
    control.setPadding(dp(12), dp(8), dp(12), dp(8));
    control.setSwitchPadding(dp(16));
    control.setMinHeight(dp(60));
    control.setThumbTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {Ui.ACTION, Ui.MUTED}));
    control.setTrackTintList(
        new android.content.res.ColorStateList(
            new int[][] {new int[] {android.R.attr.state_checked}, new int[] {}},
            new int[] {Ui.isDark(this) ? 0xff596b21 : 0xffa0a0a0, Ui.BORDER}));
    control.setChecked(initial);
    box.addView(control, new LinearLayout.LayoutParams(-1, -2));
    control.setOnCheckedChangeListener((b, checked) -> callback.run(checked));
    box.addView(Ui.rule(this, Ui.BORDER), new LinearLayout.LayoutParams(-1, dp(1)));
  }

  void applyChromeTheme() {
    refreshLegacyAddressTheme();
    boolean dark = Ui.isDark(this);
    // These colors belong to the persistent browser controls. Panels and page themes use Ui's
    // original palette, including its full accent for transient actions.
    int bar = dark ? Color.BLACK : 0xffe4e4e4;
    int accent = dark ? 0xff9ccb18 : 0xffabe300;
    int ink = dark ? 0xffbdbdbd : Color.BLACK;
    int resourceInk = dark ? accent : Color.BLACK;
    int border = dark ? 0xff333333 : 0xffadadad;
    root.setBackgroundColor(Ui.BG);
    topBar.setBackgroundColor(bar);
    bottomBar.setBackgroundColor(bar);
    resourceBar.setBackground(Ui.outline(this, dark ? Color.BLACK : accent, border, 0));
    resourceText.setTextColor(resourceInk);
    shellMark.setTextColor(resourceInk);
    Ui.styleTakeoverButton(takeoverButton, accent);
    address.setTextColor(ink);
    address.setHintTextColor(Ui.MUTED);
    address.setBackground(Ui.outline(this, dark ? Ui.SURFACE : 0xffeeeeee, border, 2));
    tintTextInput(address);
    tabCount.setTextColor(ink);
    tabGlyph.setImageTintList(ColorStateList.valueOf(ink));
    ColorStateList navColors =
        new ColorStateList(
            new int[][] {new int[] {-android.R.attr.state_enabled}, new int[] {}},
            new int[] {dark ? 0xff606060 : 0xff7e7e7e, ink});
    tintIcons(topBar, navColors);
    tintIcons(bottomBar, navColors);
    tintIcons(resourceBar, ColorStateList.valueOf(resourceInk));
    downloadBadge.setTextColor(Color.BLACK);
    downloadBadge.setBackground(Ui.shape(this, accent, 0));
    getWindow().setStatusBarColor(Ui.BG);
    getWindow().setNavigationBarColor(Ui.BG);
    getWindow()
        .getDecorView()
        .setSystemUiVisibility(
            dark
                ? 0
                : View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR | View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);
    for (BrowserTab tab : tabs) sendPageTheme(tab);
    updateTaskBranding();
  }

  void sendPageTheme(BrowserTab tab) {
    tab.web.evaluateJavascript(
        "window.__websurf&&window.__websurf.command({action:'theme',dark:" + Ui.isDark(this) + "})",
        null);
  }

  private void tintIcons(ViewGroup group, ColorStateList colors) {
    for (int i = 0; i < group.getChildCount(); i++) {
      View v = group.getChildAt(i);
      if (v instanceof ImageButton b) b.setImageTintList(colors);
      else if (v instanceof ViewGroup g) tintIcons(g, colors);
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
      if (player == null) return;
    }
    if (player != null) {
      boolean takeover = activeTarget != null;
      closePlayer(false, takeover);
      if (!takeover) return;
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
    if (WebResource.isHttp(intent.getDataString())) navigate(intent.getDataString(), false);
    if (intent.getBooleanExtra("show_downloads", false)) showDownloads();
  }

  @Override
  public void onConfigurationChanged(android.content.res.Configuration config) {
    super.onConfigurationChanged(config);
    scrollControls.reveal();
    root.post(this::layoutPlayer);
  }

  @Override
  public void onPictureInPictureModeChanged(
      boolean enabled, android.content.res.Configuration config) {
    super.onPictureInPictureModeChanged(enabled, config);
    inPip = enabled;
    shell.setVisibility(enabled ? View.INVISIBLE : View.VISIBLE);
    if (enabled) chromePixelShift.stop();
    else if (resumed) chromePixelShift.start();
    layoutPlayer();
  }

  @Override
  protected void onResume() {
    super.onResume();
    updateTaskBranding();
    resumed = true;
    if (!inPip) chromePixelShift.start();
    if (pendingDownloadFeedback != null) {
      DownloadStore.Job job = pendingDownloadFeedback;
      pendingDownloadFeedback = null;
      showDownloadFeedback("completed".equals(job.state) ? "下载完成" : "下载失败", job);
    }
    if (current != null) current.web.onResume();
  }

  @Override
  protected void onPause() {
    super.onPause();
    chromePixelShift.stop();
    scrollControls.reveal();
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
    // Eight 8-Ki-character URLs take at most 128 KiB of UTF-16 parcel data.
    // Preserve the tab slot rather than navigating to a truncated URL.
    for (BrowserTab t : tabs)
      urls.add(
          t.url != null && t.url.length() <= 8192 && WebResource.isHttp(t.url)
              ? t.url
              : "about:blank");
    state.putStringArrayList("tabs", urls);
    boolean[] local = new boolean[urls.size()];
    for (int i = 0; i < local.length; i++) local[i] = tabs.get(i).localDocument(urls.get(i));
    state.putBooleanArray("localMediaTabs", local);
    state.putInt("active", Math.max(0, tabs.indexOf(current)));
    state.putInt("uploadRequestCode", uploadRequestCode);
  }

  @Override
  protected void onDestroy() {
    if (activeSheet != null) activeSheet.dismiss();
    closePlayer(false, false);
    previewLoader.close();
    handler.removeCallbacksAndMessages(null);
    for (BrowserTab tab : tabs) tab.web.destroy();
    tabs.clear();
    if (downloadReceiverRegistered) {
      unregisterReceiver(downloadReceiver);
      downloadReceiverRegistered = false;
    }
    super.onDestroy();
  }

  @Override
  protected void onActivityResult(int request, int result, Intent data) {
    super.onActivityResult(request, result, data);
    if (request == uploadRequestCode && fileCallback != null) {
      ValueCallback<Uri[]> callback = fileCallback;
      fileCallback = null;
      boolean currentDocument =
          current != null && uploadTab == current && uploadEpoch == current.epoch;
      uploadTab = null;
      callback.onReceiveValue(
          currentDocument && result == RESULT_OK ? UploadDocuments.selection(this, data) : null);
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
    alert()
        .setTitle(title)
        .setMessage(e.getMessage() == null ? e.toString() : e.getMessage())
        .setPositiveButton("关闭", null)
        .show();
  }
}
