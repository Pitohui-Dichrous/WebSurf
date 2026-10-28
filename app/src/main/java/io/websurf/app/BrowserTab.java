package io.websurf.app;

import android.webkit.WebView;
import java.util.LinkedHashMap;

final class BrowserTab {
  WebView web;
  String title = "新标签页", url = "", framePlaying = "";
  long epoch;
  long playGeneration;
  String pendingMedia = "";
  final java.util.Set<String> documents = new java.util.HashSet<>();
  boolean takeoverSuppressed;
  String selectedVideo = "";
  final LinkedHashMap<String, VideoTarget> videos = new LinkedHashMap<>();
  final LinkedHashMap<String, WebResource> resources = new LinkedHashMap<>();
}
