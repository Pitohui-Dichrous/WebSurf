package io.websurf.app.download;

import android.content.Context;
import android.util.AtomicFile;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** The app-private queue survives activity/service/process recreation. */
public final class DownloadStore {
  private DownloadStore() {}

  public static final class Job {
    public String id = "", title = "", url = "", state = "queued", error = "", outputUri = "";
    public String mimeType = "", fileName = "", detail = "等待下载";
    public int progress;
    public long bytes, total = -1, createdAt, bytesPerSecond;
    public int connections, completedParts, totalParts;
    public final Map<String, String> headers = new LinkedHashMap<>();

    public boolean active() {
      return "queued".equals(state) || "downloading".equals(state) || "converting".equals(state);
    }

    public String stateLabel() {
      switch (state) {
        case "queued":
          return "等待下载";
        case "downloading":
          return "下载中";
        case "converting":
          return "合并 MP4";
        case "completed":
          return "已完成";
        case "cancelled":
          return "已取消";
        default:
          return "下载失败";
      }
    }

    JSONObject json() {
      JSONObject o = new JSONObject();
      try {
        o.put("id", id)
            .put("title", title)
            .put("url", url)
            .put("state", state)
            .put("error", error)
            .put("outputUri", outputUri)
            .put("mimeType", mimeType)
            .put("fileName", fileName)
            .put("detail", detail)
            .put("progress", progress)
            .put("bytes", bytes)
            .put("total", total)
            .put("createdAt", createdAt)
            .put("bytesPerSecond", bytesPerSecond)
            .put("connections", connections)
            .put("completedParts", completedParts)
            .put("totalParts", totalParts)
            .put("headers", new JSONObject(headers));
      } catch (org.json.JSONException e) {
        throw new IllegalStateException(e);
      }
      return o;
    }

    static Job from(JSONObject o) {
      Job j = new Job();
      j.id = o.optString("id");
      j.title = o.optString("title");
      j.url = o.optString("url");
      j.state = o.optString("state", "failed");
      j.error = o.optString("error");
      j.outputUri = o.optString("outputUri");
      j.mimeType = o.optString("mimeType");
      j.fileName = o.optString("fileName");
      j.detail = o.optString("detail");
      j.progress = o.optInt("progress");
      j.bytes = o.optLong("bytes");
      j.total = o.optLong("total", -1);
      j.createdAt = o.optLong("createdAt");
      j.bytesPerSecond = o.optLong("bytesPerSecond");
      j.connections = o.optInt("connections");
      j.completedParts = o.optInt("completedParts");
      j.totalParts = o.optInt("totalParts");
      JSONObject h = o.optJSONObject("headers");
      if (h != null) {
        java.util.Iterator<String> keys = h.keys();
        while (keys.hasNext()) {
          String k = keys.next();
          j.headers.put(k, h.optString(k));
        }
      }
      return j;
    }
  }

  private static AtomicFile file(Context c) {
    return new AtomicFile(new File(c.getFilesDir(), "downloads.json"));
  }

  public static synchronized List<Job> list(Context c) {
    List<Job> jobs = new ArrayList<>();
    AtomicFile store = file(c);
    if (!store.getBaseFile().exists()) return jobs;
    try {
      JSONArray array = new JSONArray(new String(store.readFully(), StandardCharsets.UTF_8));
      for (int i = 0; i < array.length(); i++) jobs.add(Job.from(array.getJSONObject(i)));
    } catch (Exception e) {
      // Preserve damaged queue for diagnosis; never overwrite it with an empty list.
      throw new IllegalStateException("下载记录无法读取", e);
    }
    return jobs;
  }

  public static synchronized Job get(Context c, String id) {
    for (Job j : list(c)) if (j.id.equals(id)) return j;
    return null;
  }

  static synchronized void put(Context c, Job job) {
    List<Job> jobs = list(c);
    boolean found = false;
    for (int i = 0; i < jobs.size(); i++)
      if (jobs.get(i).id.equals(job.id)) {
        jobs.set(i, job);
        found = true;
        break;
      }
    if (!found) jobs.add(0, job);
    write(c, jobs);
  }

  static synchronized boolean updateProgress(Context c, Job job) {
    Job current = get(c, job.id);
    if (current == null
        || "cancelled".equals(current.state)
        || "failed".equals(current.state)
        || "completed".equals(current.state)) return false;
    put(c, job);
    return true;
  }

  static synchronized void changeState(Context c, String id, String state, String error) {
    Job j = get(c, id);
    if (j == null) return;
    j.state = state;
    j.error = error;
    j.detail = j.stateLabel();
    if (!j.active()) {
      j.bytesPerSecond = 0;
      j.connections = 0;
    }
    put(c, j);
  }

  public static synchronized void remove(Context c, String id) {
    List<Job> jobs = list(c);
    for (int i = jobs.size() - 1; i >= 0; i--) if (jobs.get(i).id.equals(id)) jobs.remove(i);
    write(c, jobs);
  }

  private static void write(Context c, List<Job> jobs) {
    JSONArray a = new JSONArray();
    for (Job j : jobs) a.put(j.json());
    AtomicFile store = file(c);
    FileOutputStream out = null;
    try {
      out = store.startWrite();
      out.write(a.toString().getBytes(StandardCharsets.UTF_8));
      store.finishWrite(out);
    } catch (IOException e) {
      if (out != null) store.failWrite(out);
      throw new IllegalStateException("下载记录无法保存", e);
    }
  }
}
