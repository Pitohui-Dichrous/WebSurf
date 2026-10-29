package io.websurf.app;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;

/** Stable setting IDs and search URLs; declaration order is the user-facing choice order. */
enum SearchEngine {
  GOOGLE("google", "谷歌", "https://www.google.com/search?q=", ""),
  QUARK("quark", "夸克", "https://quark.sm.cn/s?q=", ""),
  BING_CN("bing_cn", "必应国内版", "https://cn.bing.com/search?q=", "&ensearch=0"),
  BING_INTL("bing_intl", "必应国际版", "https://www.bing.com/search?q=", "&ensearch=1"),
  BAIDU("baidu", "百度", "https://www.baidu.com/s?wd=", "");
  final String id, label;
  private final String prefix, suffix;

  SearchEngine(String id, String label, String prefix, String suffix) {
    this.id = id;
    this.label = label;
    this.prefix = prefix;
    this.suffix = suffix;
  }

  static SearchEngine fromId(String id) {
    for (SearchEngine engine : values()) if (engine.id.equals(id)) return engine;
    return BING_CN;
  }

  String search(String query) {
    try {
      return prefix + URLEncoder.encode(query, "UTF-8") + suffix;
    } catch (UnsupportedEncodingException impossible) {
      throw new IllegalStateException(impossible);
    }
  }
}
