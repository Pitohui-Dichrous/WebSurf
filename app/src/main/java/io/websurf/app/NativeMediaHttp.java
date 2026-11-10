package io.websurf.app;

import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import com.google.common.net.InetAddresses;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.Socket;
import java.net.SocketAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import javax.net.SocketFactory;
import okhttp3.Call;
import okhttp3.Dns;
import okhttp3.Headers;
import okhttp3.OkHttpClient;
import okhttp3.Request;

/** Address-space boundary for native media GETs; WebView handles normal page navigation itself. */
public final class NativeMediaHttp {
  /** App-issued authority, never copied to an HTTP request. */
  public static final String LOCAL_ORIGIN = "X-WebSurf-Local-Origin";

  private NativeMediaHttp() {}

  private static ConnectivityManager connectivity;
  private static MediaNetwork currentNetwork;

  static synchronized void initialize(ConnectivityManager manager) {
    connectivity = manager;
    currentNetwork = null;
  }

  private static synchronized MediaNetwork routes() {
    Network network = connectivity == null ? null : connectivity.getActiveNetwork();
    LinkProperties properties = network == null ? null : connectivity.getLinkProperties(network);
    if (currentNetwork == null
        || currentNetwork.expired()
        || !currentNetwork.matches(network, properties)) {
      if (currentNetwork != null) {
        currentNetwork.publicClient.connectionPool().evictAll();
        currentNetwork.localClient.connectionPool().evictAll();
      }
      currentNetwork = new MediaNetwork(connectivity, network, properties);
    }
    return currentNetwork;
  }

  static OkHttpClient client(boolean local, Dns resolver) {
    return client(local, resolver, null);
  }

  static OkHttpClient client(boolean local, Dns resolver, MediaNetwork network) {
    // Separate pools prevent an already-connected LAN socket from qualifying for a public call.
    return new OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .dns(host -> checkedAnswers(resolver.lookup(host), local, network))
        .socketFactory(new AddressSocketFactory(local, network))
        .build();
  }

  public static boolean isLocalOriginCandidate(String url) {
    if (!WebResource.isHttp(url)) return false;
    String host = URI.create(url).getHost();
    if (host.startsWith("[") && host.endsWith("]")) host = host.substring(1, host.length() - 1);
    if (host.contains("%")) return false;
    if (InetAddresses.isInetAddress(host)) return isLan(InetAddresses.forString(host).getAddress());
    String name = host.toLowerCase(java.util.Locale.ROOT);
    if (name.endsWith(".")) name = name.substring(0, name.length() - 1);
    return !name.contains(".")
        || name.endsWith(".local")
        || name.endsWith(".localhost")
        || name.equals("home.arpa")
        || name.endsWith(".home.arpa");
  }

  public static Call get(
      String root,
      String destination,
      String localOrigin,
      Map<String, String> scopedHeaders,
      String range,
      String ifRange,
      int connectMillis,
      int readMillis)
      throws IOException {
    MediaRequestPolicy.requireNetwork(root, destination);
    URI uri = URI.create(destination);
    // An HTTP or SOCKS proxy can independently resolve the remote name. Never bypass a
    // required system proxy, or delegate the checked destination to its resolver.
    ProxySelector selector = ProxySelector.getDefault();
    if (selector != null) {
      List<Proxy> routes = selector.select(uri);
      if (routes != null && !routes.isEmpty() && routes.get(0).type() != Proxy.Type.DIRECT)
        throw new IOException("当前代理无法验证媒体目标地址，请使用网页播放");
    }
    boolean local = isLocalOriginCandidate(destination);
    if (local
        && (!MediaRequestPolicy.sameOrigin(root, localOrigin)
            || !MediaRequestPolicy.sameOrigin(destination, localOrigin)))
      throw new IOException("未授权访问此局域网媒体来源");
    MediaNetwork routes = routes();
    OkHttpClient client = local ? routes.localClient : routes.publicClient;
    Headers.Builder headers = new Headers.Builder();
    for (Map.Entry<String, String> e : scopedHeaders.entrySet()) {
      // Only MediaRequestPolicy's HTTP fields can leave the application.
      String name = e.getKey();
      if (name.equalsIgnoreCase("User-Agent")
          || name.equalsIgnoreCase("Cookie")
          || name.equalsIgnoreCase("Authorization")
          || name.equalsIgnoreCase("Origin")
          || name.equalsIgnoreCase("Referer")) headers.addUnsafeNonAscii(name, e.getValue());
    }
    headers.set("Accept-Encoding", "identity");
    if (range != null) headers.set("Range", range);
    if (ifRange != null) headers.set("If-Range", ifRange);
    Request request = new Request.Builder().url(destination).headers(headers.build()).build();
    return client
        .newBuilder()
        .connectTimeout(connectMillis, TimeUnit.MILLISECONDS)
        .readTimeout(readMillis, TimeUnit.MILLISECONDS)
        .build()
        .newCall(request);
  }

  static List<InetAddress> checkedAnswers(
      List<InetAddress> answers, boolean local, MediaNetwork network) throws UnknownHostException {
    List<InetAddress> checked = new java.util.ArrayList<>();
    for (InetAddress address : answers) {
      Nat64.Verdict verdict = verdict(address, local, network);
      if (verdict == Nat64.Verdict.BLOCK) throw new UnknownHostException("拒绝访问此网络地址");
      if (verdict == Nat64.Verdict.ALLOW) checked.add(address);
    }
    if (checked.isEmpty()) throw new UnknownHostException("无法验证媒体服务器的目的地址");
    return List.copyOf(checked);
  }

  private static Nat64.Verdict verdict(InetAddress address, boolean local, MediaNetwork network) {
    List<Nat64.Prefix> prefixes =
        address instanceof java.net.Inet6Address && !address.isLoopbackAddress() && network != null
            ? network.prefixes()
            : List.of();
    return Nat64.verdict(address, local, prefixes);
  }

  static boolean permitted(InetAddress address, boolean local) {
    return permittedBytes(address.getAddress(), local);
  }

  static boolean permittedBytes(byte[] bytes, boolean local) {
    if (local) return isLan(bytes);
    int a = bytes[0] & 255, b = bytes[1] & 255, c = bytes[2] & 255;
    if (bytes.length == 4) {
      // Special-purpose and non-unicast ranges include CGNAT, benchmarking and documentation.
      return a != 0
          && a != 10
          && a != 127
          && a < 224
          && !(a == 100 && b >= 64 && b <= 127)
          && !(a == 169 && b == 254)
          && !(a == 172 && b >= 16 && b <= 31)
          && !(a == 192 && (b == 168 || (b == 0 && (c == 0 || c == 2)) || (b == 88 && c == 99)))
          && !(a == 198 && (b == 18 || b == 19 || (b == 51 && c == 100)))
          && !(a == 203 && b == 0 && c == 113);
    }
    // Global unicast only. Exclude special protocol assignments, documentation and tunnels
    // embedding IPv4 addresses (including 6to4, Teredo and well-known NAT64 prefixes).
    return bytes.length == 16
        && (a & 0xe0) == 0x20
        && !(a == 0x20 && b == 0x01 && c < 2)
        && !(a == 0x20 && b == 0x01 && c == 0x0d && (bytes[3] & 255) == 0xb8)
        && !(a == 0x20 && b == 0x02)
        && !(a == 0x3f && b == 0xff && (c & 0xf0) == 0);
  }

  private static boolean isLan(byte[] ip) {
    int a = ip[0] & 255, b = ip[1] & 255;
    if (ip.length == 4)
      return a == 10 || a == 127 || (a == 172 && b >= 16 && b <= 31) || (a == 192 && b == 168);
    if (ip.length != 16) return false;
    if ((a & 0xfe) == 0xfc) return true;
    for (int i = 0; i < 15; i++) if (ip[i] != 0) return false;
    return ip[15] == 1;
  }

  /** OkHttp uses createSocket(), then connect with its resolved route (also for IP literals). */
  private static final class AddressSocketFactory extends SocketFactory {
    final boolean local;

    final MediaNetwork network;

    AddressSocketFactory(boolean local, MediaNetwork network) {
      this.local = local;
      this.network = network;
    }

    @Override
    public Socket createSocket() {
      return new Socket(Proxy.NO_PROXY) {
        @Override
        public void connect(SocketAddress endpoint, int timeout) throws IOException {
          if (!(endpoint instanceof InetSocketAddress ip)
              || ip.isUnresolved()
              || verdict(ip.getAddress(), local, network) != Nat64.Verdict.ALLOW)
            throw new IOException("拒绝访问此网络地址");
          if (network != null) network.bindCurrent(this);
          super.connect(ip, timeout);
        }
      };
    }

    @Override
    public Socket createSocket(String host, int port) throws IOException {
      throw new IOException("需要已解析的媒体地址");
    }

    @Override
    public Socket createSocket(String host, int port, InetAddress bind, int bindPort)
        throws IOException {
      throw new IOException("需要已解析的媒体地址");
    }

    @Override
    public Socket createSocket(InetAddress address, int port) throws IOException {
      throw new IOException("需要已检查的网络路由");
    }

    @Override
    public Socket createSocket(InetAddress address, int port, InetAddress bind, int bindPort)
        throws IOException {
      throw new IOException("需要已检查的网络路由");
    }
  }
}
