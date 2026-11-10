package io.websurf.app;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.ClipData;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.Uri;
import android.os.Process;
import android.webkit.WebChromeClient.FileChooserParams;
import java.util.ArrayList;
import java.util.List;

final class UploadDocuments {
  private UploadDocuments() {}

  static Intent picker(Activity context, FileChooserParams params) {
    Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE);
    String[] types = params.getAcceptTypes();
    intent.setType("*/*");
    if (types != null && types.length == 1 && !types[0].isBlank()) intent.setType(types[0]);
    else if (types != null && types.length > 1) intent.putExtra(Intent.EXTRA_MIME_TYPES, types);
    intent.putExtra(
        Intent.EXTRA_ALLOW_MULTIPLE, params.getMode() == FileChooserParams.MODE_OPEN_MULTIPLE);
    List<ResolveInfo> system =
        context
            .getPackageManager()
            .queryIntentActivities(
                intent, PackageManager.MATCH_DEFAULT_ONLY | PackageManager.MATCH_SYSTEM_ONLY);
    for (ResolveInfo match : system) {
      if (match.activityInfo.exported)
        return intent.setClassName(match.activityInfo.packageName, match.activityInfo.name);
    }
    throw new ActivityNotFoundException("没有可用的系统文件选择器");
  }

  static Uri[] selection(Context context, Intent data) {
    if (data == null || (data.getFlags() & Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return null;
    ClipData clip = data.getClipData();
    ArrayList<Uri> uris = new ArrayList<>();
    if (clip != null) {
      for (int i = 0; i < clip.getItemCount(); i++) uris.add(clip.getItemAt(i).getUri());
    } else uris.add(data.getData());
    for (Uri uri : uris) {
      if (uri == null || !"content".equals(uri.getScheme()) || uri.getAuthority() == null)
        return null;
      String authority = uri.getAuthority();
      authority = authority.substring(authority.lastIndexOf('@') + 1);
      if (authority.equalsIgnoreCase(context.getPackageName() + ".files")
          || context.checkUriPermission(
                  uri, Process.myPid(), Process.myUid(), Intent.FLAG_GRANT_READ_URI_PERMISSION)
              != PackageManager.PERMISSION_GRANTED) return null;
    }
    return uris.isEmpty() ? null : uris.toArray(new Uri[0]);
  }
}
