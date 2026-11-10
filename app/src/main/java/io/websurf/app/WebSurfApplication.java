package io.websurf.app;

import android.app.Application;
import android.net.ConnectivityManager;

public final class WebSurfApplication extends Application {
  @Override
  public void onCreate() {
    super.onCreate();
    NativeMediaHttp.initialize(getSystemService(ConnectivityManager.class));
  }
}
