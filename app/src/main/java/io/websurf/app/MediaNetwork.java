package io.websurf.app;

import android.net.ConnectivityManager;
import android.net.IpPrefix;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import java.io.IOException;
import java.net.InetAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import okhttp3.OkHttpClient;

/** Resolution, translation prefixes, pooled connections and socket routing share a network. */
final class MediaNetwork {
  private final ConnectivityManager manager;
  private final Network network;
  private final LinkProperties properties;
  final OkHttpClient publicClient, localClient;
  private List<Nat64.Prefix> discovered;
  private volatile long discoveryExpires = Long.MAX_VALUE;

  MediaNetwork(ConnectivityManager manager, Network network, LinkProperties properties) {
    this.manager = manager;
    this.network = network;
    this.properties = properties;
    publicClient = NativeMediaHttp.client(false, this::resolve, this);
    localClient = NativeMediaHttp.client(true, this::resolve, this);
  }

  boolean matches(Network current, LinkProperties props) {
    return Objects.equals(network, current) && Objects.equals(properties, props);
  }

  boolean expired() {
    return System.nanoTime() >= discoveryExpires;
  }

  private boolean isCurrent() {
    if (manager == null) return true;
    Network active = manager.getActiveNetwork();
    return matches(active, active == null ? null : manager.getLinkProperties(active));
  }

  private List<InetAddress> resolve(String host) throws UnknownHostException {
    if (!isCurrent()) throw new UnknownHostException("网络已切换，请重试");
    if (network == null) return Arrays.asList(InetAddress.getAllByName(host));
    return Arrays.asList(network.getAllByName(host));
  }

  synchronized List<Nat64.Prefix> prefixes() {
    if (discovered != null && !expired()) return discovered;
    if (network == null) return List.of();
    if (Build.VERSION.SDK_INT >= 30 && properties != null) {
      IpPrefix prefix = properties.getNat64Prefix();
      if (prefix != null && Nat64.validLength(prefix.getPrefixLength())) {
        discovered = List.of(new Nat64.Prefix(prefix.getAddress(), prefix.getPrefixLength()));
        return discovered;
      }
    }
    try {
      discovered = isCurrent() ? Nat64.discover(network.getAllByName("ipv4only.arpa.")) : List.of();
    } catch (UnknownHostException unsupported) {
      discovered = List.of();
    }
    // A legacy network may update its translator without changing its Android Network handle.
    discoveryExpires = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(30);
    return discovered;
  }

  void bindCurrent(Socket socket) throws IOException {
    if (!isCurrent()) throw new IOException("网络已切换，请重试");
    if (network != null) network.bindSocket(socket);
  }
}
