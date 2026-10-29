package io.websurf.app;

import static org.junit.Assert.*;

import java.net.URI;
import java.net.URLDecoder;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.Test;

public final class SearchEngineTest {
  @Test
  public void missingAndUnknownPreferencesUseDomesticBing() {
    assertEquals(SearchEngine.BING_CN, SearchEngine.fromId(null));
    assertEquals(SearchEngine.BING_CN, SearchEngine.fromId(""));
    assertEquals(SearchEngine.BING_CN, SearchEngine.fromId("unsupported"));
    for (SearchEngine engine : SearchEngine.values())
      assertEquals(engine, SearchEngine.fromId(engine.id));
  }

  @Test
  public void choicesHaveTheRequestedOrderAndNames() {
    assertArrayEquals(
        new String[] {"谷歌", "夸克", "必应国内版", "必应国际版", "百度"},
        java.util.Arrays.stream(SearchEngine.values()).map(e -> e.label).toArray(String[]::new));
  }

  @Test
  public void everyProviderReceivesOneEncodedQueryWithoutParameterInjection() throws Exception {
    String query = "中文 + emoji 🚀 &ensearch=9 #?/=50%";
    Map<SearchEngine, String> hosts =
        Map.of(
            SearchEngine.GOOGLE,
            "www.google.com",
            SearchEngine.QUARK,
            "quark.sm.cn",
            SearchEngine.BING_CN,
            "cn.bing.com",
            SearchEngine.BING_INTL,
            "www.bing.com",
            SearchEngine.BAIDU,
            "www.baidu.com");
    for (SearchEngine engine : SearchEngine.values()) {
      URI uri = URI.create(engine.search(query));
      assertEquals("https", uri.getScheme());
      assertEquals(hosts.get(engine), uri.getHost());
      assertNull(uri.getRawFragment());
      Map<String, String> parameters = new LinkedHashMap<>();
      for (String entry : uri.getRawQuery().split("&")) {
        String[] pair = entry.split("=", 2);
        assertNull(parameters.put(pair[0], URLDecoder.decode(pair[1], "UTF-8")));
      }
      assertEquals(query, parameters.get(engine == SearchEngine.BAIDU ? "wd" : "q"));
      if (engine == SearchEngine.BING_CN || engine == SearchEngine.BING_INTL) {
        assertEquals(2, parameters.size());
        assertEquals(engine == SearchEngine.BING_CN ? "0" : "1", parameters.get("ensearch"));
      } else assertEquals(1, parameters.size());
    }
  }

  @Test
  public void addressNavigationUsesSelectionOnlyForSearches() {
    for (SearchEngine engine : SearchEngine.values()) {
      assertEquals(
          "https://example.org/path?q=one+two#anchor",
          MainActivity.normalizeAddress(" https://example.org/path?q=one+two#anchor ", engine));
      assertEquals(
          "http://localhost:8765/page",
          MainActivity.normalizeAddress("http://localhost:8765/page", engine));
      assertEquals(
          "https://example.org/path", MainActivity.normalizeAddress("example.org/path", engine));
      assertEquals(
          engine.search("two words"), MainActivity.normalizeAddress("  two words  ", engine));
    }
    assertEquals(
        SearchEngine.BING_CN.search("hello world"),
        MainActivity.normalizeAddress("hello world", null));
  }
}
