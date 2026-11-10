package io.websurf.app;

import android.util.JsonReader;
import android.util.JsonToken;
import java.io.IOException;
import java.io.StringReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Read only bounded, standard JSON before a page message reaches authentication. */
final class BridgeMessage {
  private static final int MAX_CHARS = 32768;
  // sniffer.js emits at most eight nested levels inside its observation envelope.
  private static final int MAX_DEPTH = 16;
  private int remainingValues = 1024;

  static String prefix(String seal) {
    return "{\"seal\":" + JSONObject.quote(seal) + ",";
  }

  static JSONObject parse(String text, String requiredPrefix) throws IOException, JSONException {
    // Modern providers install this exact wire prefix before page scripts can run.
    // Reject foreign messages before constructing a reader or any JSON fields.
    if (text == null
        || text.length() > MAX_CHARS
        || (requiredPrefix != null && !text.startsWith(requiredPrefix))) return null;
    try (JsonReader reader = new JsonReader(new StringReader(text))) {
      reader.setLenient(false);
      if (reader.peek() != JsonToken.BEGIN_OBJECT) throw new IOException("无效的网页消息");
      JSONObject message = (JSONObject) new BridgeMessage().readValue(reader, 0);
      if (reader.peek() != JsonToken.END_DOCUMENT) throw new IOException("网页消息存在多余内容");
      return message;
    }
  }

  private Object readValue(JsonReader reader, int depth) throws IOException, JSONException {
    if (depth > MAX_DEPTH || --remainingValues < 0) throw new IOException("网页消息结构超过限制");
    switch (reader.peek()) {
      case BEGIN_OBJECT:
        JSONObject object = new JSONObject();
        reader.beginObject();
        while (reader.hasNext()) object.put(reader.nextName(), readValue(reader, depth + 1));
        reader.endObject();
        return object;
      case BEGIN_ARRAY:
        JSONArray array = new JSONArray();
        reader.beginArray();
        while (reader.hasNext()) array.put(readValue(reader, depth + 1));
        reader.endArray();
        return array;
      case STRING:
        return reader.nextString();
      case NUMBER:
        String number = reader.nextString();
        try {
          return Long.parseLong(number);
        } catch (NumberFormatException fractional) {
          double value = Double.parseDouble(number);
          if (!Double.isFinite(value)) throw new IOException("网页消息包含无效数值");
          return value;
        }
      case BOOLEAN:
        return reader.nextBoolean();
      case NULL:
        reader.nextNull();
        return JSONObject.NULL;
      default:
        throw new IOException("无效的网页消息字段");
    }
  }

  private BridgeMessage() {}
}
