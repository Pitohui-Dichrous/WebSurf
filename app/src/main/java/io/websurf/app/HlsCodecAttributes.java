package io.websurf.app;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Media3 splits each variant's CODECS again when it assigns codecs to matching renditions. */
final class HlsCodecAttributes {
  private static final int MAX_CHARS = 256;
  private static final Pattern REFERENCE = Pattern.compile("\\{\\$([A-Za-z0-9_-]+)\\}");
  private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_-]+");
  private static final String TOKEN = "[A-Za-z0-9._+%/-]+";
  private static final Pattern LIST =
      Pattern.compile(
          "[ \\t]{0,4}" + TOKEN + "(?:[ \\t]{0,4},[ \\t]{0,4}" + TOKEN + ")*[ \\t]{0,4}");
  // A null value means this definition cannot be used as a bounded codec identifier. It may
  // still be used for a long signed URL or other attribute by the normal playlist parser.
  private final Map<String, String> variables = new HashMap<>();

  void check(String line, Map<String, String> imports) throws IOException {
    if (line.startsWith("#EXT-X-DEFINE")) {
      String name = quoted(line, "NAME=\""), imported = quoted(line, "IMPORT=\"");
      if (name != null && imported != null) throw invalid();
      if (name != null) {
        if (!NAME.matcher(name).matches()) throw invalid();
        variables.put(name, expand(quoted(line, "VALUE=\"")));
      } else if (imported != null) {
        if (!NAME.matcher(imported).matches()) throw invalid();
        String value = imports.get(imported);
        variables.put(imported, value != null && value.length() <= MAX_CHARS ? value : null);
      }
    } else if (line.startsWith("#EXT-X-STREAM-INF")
        || line.startsWith("#EXT-X-I-FRAME-STREAM-INF")) {
      // Match both CODECS and SUPPLEMENTAL-CODECS, including every occurrence the delegate
      // could find. Validate the substituted value, not just the short variable reference.
      String marker = "CODECS=\"";
      for (int at = 0; (at = line.indexOf(marker, at)) >= 0; ) {
        at += marker.length();
        String value = expand(valueAt(line, at));
        if (value == null || !LIST.matcher(value).matches()) throw invalid();
      }
    }
  }

  private String expand(String text) {
    if (text == null) return null;
    Matcher match = REFERENCE.matcher(text);
    if (!match.find()) return text.length() <= MAX_CHARS ? text : null;
    StringBuilder result = new StringBuilder();
    int from = 0;
    do {
      String replacement = variables.get(match.group(1));
      if (replacement == null
          || result.length() + match.start() - from + replacement.length() > MAX_CHARS) return null;
      result.append(text, from, match.start()).append(replacement);
      from = match.end();
    } while (match.find());
    return result.length() + text.length() - from <= MAX_CHARS
        ? result.append(text, from, text.length()).toString()
        : null;
  }

  private static String quoted(String line, String prefix) throws IOException {
    int start = line.indexOf(prefix);
    return start < 0 ? null : valueAt(line, start + prefix.length());
  }

  private static String valueAt(String line, int start) throws IOException {
    int end = line.indexOf('"', start);
    if (end <= start) throw invalid();
    return line.substring(start, end);
  }

  private static IOException invalid() {
    return new IOException("播放列表的编码或变量属性无效");
  }
}
