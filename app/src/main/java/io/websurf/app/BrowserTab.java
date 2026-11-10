package io.websurf.app;

import android.webkit.WebView;
import java.util.LinkedHashMap;

final class BrowserTab {
  WebView web;
  String title = "新标签页", url = "", framePlaying = "";
  volatile long epoch;
  long playGeneration;
  String pendingMedia = "";

  record NavigationRequest(long epoch, String url, java.util.Map<String, String> headers) {}

  static final class ReferrerProbe {
    static final String HEADER = "X-WebSurf-Referrer-Probe";
    final String challenge = java.util.UUID.randomUUID().toString(), marker, url;
    final long epoch;
    final java.util.concurrent.atomic.AtomicReference<String> referer =
        new java.util.concurrent.atomic.AtomicReference<>();

    ReferrerProbe(String url, long epoch, String frameKey) {
      this.url = url;
      this.epoch = epoch;
      marker = challenge + "." + frameKey;
    }

    void capture(String token, long currentEpoch, String requestedUrl, String observedReferer) {
      if (epoch == currentEpoch && marker.equals(token) && url.equals(requestedUrl))
        referer.compareAndSet(null, observedReferer);
    }
  }

  volatile ReferrerProbe referrerProbe;

  final java.util.concurrent.atomic.AtomicReference<NavigationRequest> navigationRequest =
      new java.util.concurrent.atomic.AtomicReference<>();
  final String startSeal = java.util.UUID.randomUUID().toString();
  final LinkedHashMap<String, Frame> documents = new LinkedHashMap<>();
  long inputSequence, inputAt, consumedInput;

  static final class Frame {
    final androidx.webkit.JavaScriptReplyProxy reply;
    final String origin;
    final boolean main, firstPartyTree;
    long inputSequence, inputTicket;
    String authorizedId = "", authorizedUrl = "", authorizedAction = "";
    String referrerKey = "";
    long authorizedVersion;

    Frame(
        androidx.webkit.JavaScriptReplyProxy reply,
        String origin,
        boolean main,
        boolean firstPartyTree) {
      this.reply = reply;
      this.origin = origin;
      this.main = main;
      this.firstPartyTree = firstPartyTree;
    }
  }

  boolean takeoverSuppressed;
  String selectedVideo = "";
  final LinkedHashMap<String, VideoTarget> videos = new LinkedHashMap<>();
  final LinkedHashMap<String, WebResource> resources = new LinkedHashMap<>();

  // Only a freshly constructed WebView with an app-selected starting URL has this record.
  String initialPage;
  // Only an app-owned navigation can authorize this exact LAN origin. WebView redirects,
  // popups and resource observations cannot create a grant.
  String localOrigin = "";
  private String pendingLocalAddress = "";
  private long localAddressAt;

  void selectNativeOrigin(String address) {
    localOrigin =
        NativeMediaHttp.isLocalOriginCandidate(address)
            ? ReferrerPolicy.forRequest(address, address, "origin")
            : "";
    pendingLocalAddress = localOrigin.isEmpty() ? "" : address.split("#", 2)[0];
    localAddressAt = android.os.SystemClock.elapsedRealtime();
  }

  void restrictPendingAddress(String nextUrl) {
    if (nextUrl == null || !pendingLocalAddress.equals(nextUrl.split("#", 2)[0]))
      pendingLocalAddress = "";
  }

  String consumeLocalAttachment(String requestedUrl) {
    String pending = pendingLocalAddress;
    pendingLocalAddress = "";
    return localDocument(requestedUrl)
            && pending.equals(requestedUrl.split("#", 2)[0])
            && android.os.SystemClock.elapsedRealtime() - localAddressAt < 60_000
        ? localOrigin
        : "";
  }

  boolean localDocument(String address) {
    return !localOrigin.isEmpty() && MediaRequestPolicy.sameOrigin(localOrigin, address);
  }

  boolean isInitialPage(String url) {
    return MediaRequestPolicy.sameOrigin(initialPage, url)
        && initialPage.split("#", 2)[0].equals(url.split("#", 2)[0]);
  }

  boolean initialRequest(String url, java.util.Map<String, String> headers) {
    if (!MediaRequestPolicy.sameOrigin(initialPage, url)) return false;
    for (var header : headers.entrySet())
      if (header.getKey().equalsIgnoreCase("Referer")) return isInitialPage(header.getValue());
    return false;
  }

  void resetResources(String page) {
    if (epoch == 1 && isInitialPage(page)) {
      resources
          .values()
          .removeIf(r -> !r.requestHeadersCaptured || !initialRequest(r.url, r.headers));
    } else {
      initialPage = null;
      resources.clear();
    }
  }

  void beginDocument(String page) {
    restrictPendingAddress(page);
    if (!localDocument(page)) localOrigin = "";
  }

  boolean makeDocumentRoom(String playingDocument) {
    if (documents.size() < 100) return true;
    var mediaDocuments = new java.util.HashSet<String>();
    for (VideoTarget video : videos.values()) mediaDocuments.add(video.documentId);
    String oldest = null;
    for (var entry : documents.entrySet()) {
      String document = entry.getKey();
      if (entry.getValue().main
          || document.equals(playingDocument)
          || selectedVideo.startsWith(document + ":")) continue;
      if (!mediaDocuments.contains(document)) {
        oldest = document;
        break;
      }
      if (oldest == null) oldest = document;
    }
    if (oldest == null) return false;
    forgetDocument(oldest);
    return true;
  }

  void forgetDocument(String document) {
    documents.remove(document);
    videos.values().removeIf(video -> video.documentId.equals(document));
    if (selectedVideo.startsWith(document + ":")) selectedVideo = "";
  }

  boolean retainVideo(String key, VideoTarget target, String playingKey) {
    final int characterBudget = 256 * 1024;
    int total = key.length() + target.retainedCharacters();
    int protectedSize = total;
    for (var entry : videos.entrySet()) {
      if (entry.getKey().equals(key)) continue;
      int size = entry.getKey().length() + entry.getValue().retainedCharacters();
      total += size;
      if (entry.getKey().equals(selectedVideo) || entry.getKey().equals(playingKey))
        protectedSize += size;
    }
    if (protectedSize > characterBudget) return false;
    int count = videos.size() + (videos.containsKey(key) ? 0 : 1);
    var entries = videos.entrySet().iterator();
    while (count > 100 || total > characterBudget) {
      var entry = entries.next();
      String candidate = entry.getKey();
      if (candidate.equals(key) || candidate.equals(selectedVideo) || candidate.equals(playingKey))
        continue;
      total -= candidate.length() + entry.getValue().retainedCharacters();
      entries.remove();
      count--;
    }
    videos.put(key, target);
    return true;
  }
}
