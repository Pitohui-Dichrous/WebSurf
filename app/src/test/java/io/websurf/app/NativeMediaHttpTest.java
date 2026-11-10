package io.websurf.app;

import static org.junit.Assert.*;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.FutureTask;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import okhttp3.Request;
import okhttp3.Response;
import org.junit.Test;

public class NativeMediaHttpTest {
  @Test
  public void specialAddressFamiliesCannotBeReachedInPublicMode() throws Exception {
    for (String ip :
        new String[] {
          "0.1.2.3",
          "10.0.0.1",
          "100.100.100.200",
          "127.0.0.1",
          "169.254.169.254",
          "172.16.0.1",
          "192.168.1.1",
          "192.0.0.170",
          "192.0.2.1",
          "192.88.99.1",
          "198.18.0.1",
          "198.51.100.1",
          "203.0.113.9",
          "224.0.0.1",
          "255.255.255.255",
          "::",
          "::1",
          "::ffff:127.0.0.1",
          "fc00::1",
          "fe80::1",
          "ff02::1",
          "64:ff9b::7f00:1",
          "64:ff9b:1::1",
          "100::1",
          "2001::1",
          "2001:db8::1",
          "2002:7f00:1::1",
          "3fff:fff::1"
        }) {
      assertFalse(ip, NativeMediaHttp.permitted(InetAddress.getByName(ip), false));
    }
    for (String ip : new String[] {"8.8.8.8", "172.32.0.1", "2606:4700:4700::1111"})
      assertTrue(ip, NativeMediaHttp.permitted(InetAddress.getByName(ip), false));
    for (String ip : new String[] {"127.0.0.1", "10.0.0.1", "192.168.2.3", "::1", "fd42::10"})
      assertTrue(ip, NativeMediaHttp.permitted(InetAddress.getByName(ip), true));
    for (String ip : new String[] {"8.8.8.8", "169.254.169.254", "ff02::1", "::"})
      assertFalse(ip, NativeMediaHttp.permitted(InetAddress.getByName(ip), true));
    assertFalse(NativeMediaHttp.isLocalOriginCandidate("https://public.example/path"));
  }

  @Test
  public void addressLiteralsAndMixedDnsResponsesNeverOpenLoopbackSocket() throws Exception {
    InetAddress loopback = InetAddress.getByName("127.0.0.1");
    AtomicInteger lookups = new AtomicInteger();
    var client =
        NativeMediaHttp.client(
            false,
            host -> {
              lookups.incrementAndGet();
              return List.of(InetAddress.getByName("8.8.8.8"), loopback);
            });
    try (ServerSocket server = new ServerSocket(0, 4, loopback)) {
      server.setSoTimeout(120);
      for (String host :
          new String[] {
            "mixed.example", "127.0.0.1", "127.1", "2130706433", "[::ffff:127.0.0.1]"
          }) {
        Request request =
            new Request.Builder().url("http://" + host + ":" + server.getLocalPort() + "/").build();
        assertThrows(IOException.class, () -> client.newCall(request).execute());
      }
      assertTrue(lookups.get() > 0);
      assertThrows(SocketTimeoutException.class, server::accept);
    }
  }

  @Test
  public void approvedSocketUsesTheCheckedAnswerWithOriginalHostAndNoSecondLookup()
      throws Exception {
    AtomicInteger lookups = new AtomicInteger();
    var client =
        NativeMediaHttp.client(
            true,
            host -> {
              if (lookups.incrementAndGet() != 1) throw new UnknownHostException("rebind");
              return List.of(InetAddress.getByName("127.0.0.1"));
            });
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      FutureTask<String> receiver = serve(server);
      String host = "fixture.local:" + server.getLocalPort();
      try (Response response =
          client
              .newCall(new Request.Builder().url("http://" + host + "/video").build())
              .execute()) {
        assertEquals("ok", response.body().string());
      }
      assertEquals(1, lookups.get());
      assertTrue(receiver.get(2, TimeUnit.SECONDS).contains("Host: " + host));
    }
  }

  @Test
  public void publicRedirectOrChildManifestCannotReenterAnApprovedLanOrigin() throws Exception {
    String root = "http://192.168.1.2/video/master.m3u8";
    String remote = "http://cdn.example/variant.m3u8";
    String returned = "http://192.168.1.2/admin/probe";
    assertEquals(remote, io.websurf.app.download.HlsPlaylist.resolve(root, remote));
    assertThrows(IOException.class, () -> MediaRequestPolicy.requireNetwork(remote, returned));
    for (String nested :
        new String[] {
          "#EXT-X-STREAM-INF:BANDWIDTH=100\n" + returned,
          "#EXT-X-KEY:METHOD=AES-128,URI=\"" + returned + "\"\n#EXTINF:1,\ns.ts",
          "#EXT-X-MAP:URI=\"" + returned + "\"\n#EXTINF:1,\ns.m4s",
          "#EXTINF:1,\n" + returned
        }) {
      assertThrows(
          IOException.class,
          () ->
              io.websurf.app.download.HlsPlaylist.parse(
                  remote, "#EXTM3U\n" + nested + "\n#EXT-X-ENDLIST\n"));
    }
    assertEquals(
        "http://192.168.1.2/video/seg.ts",
        io.websurf.app.download.HlsPlaylist.resolve(root, "seg.ts"));
  }

  @Test
  public void localPermissionIsExactAndNeverSentAsAHeader() throws Exception {
    try (ServerSocket server = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))) {
      String entry = "http://127.0.0.1:" + server.getLocalPort() + "/master.m3u8";
      String local = entry.replace("master.m3u8", "seg.ts");
      String publicRoot = "http://video.example/export";
      assertThrows(
          IOException.class,
          () -> NativeMediaHttp.get(local, local, null, Map.of(), null, null, 1000, 1000));
      assertThrows(
          IOException.class,
          () ->
              NativeMediaHttp.get(
                  local, local, "http://127.0.0.1:1", Map.of(), null, null, 1000, 1000));
      assertThrows(
          IOException.class,
          () -> NativeMediaHttp.get(publicRoot, local, entry, Map.of(), null, null, 1000, 1000));
      assertThrows(IOException.class, () -> MediaRequestPolicy.requireNetwork(publicRoot, local));
      FutureTask<String> receiver = serve(server);
      try (Response response =
          NativeMediaHttp.get(
                  entry,
                  local,
                  entry,
                  Map.of(NativeMediaHttp.LOCAL_ORIGIN, entry),
                  "bytes=0-1",
                  null,
                  1000,
                  1000)
              .execute()) {
        assertEquals("ok", response.body().string());
      }
      String request = receiver.get(2, TimeUnit.SECONDS);
      assertTrue(request.contains("Range: bytes=0-1"));
      assertFalse(request.contains(NativeMediaHttp.LOCAL_ORIGIN));
    }
  }

  private static FutureTask<String> serve(ServerSocket server) throws IOException {
    server.setSoTimeout(2000);
    FutureTask<String> result =
        new FutureTask<>(
            () -> {
              try (Socket socket = server.accept()) {
                socket.setSoTimeout(2000);
                BufferedReader reader =
                    new BufferedReader(
                        new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                StringBuilder headers = new StringBuilder();
                for (String line; (line = reader.readLine()) != null && !line.isEmpty(); )
                  headers.append(line).append('\n');
                socket
                    .getOutputStream()
                    .write(
                        "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\nok"
                            .getBytes(StandardCharsets.US_ASCII));
                return headers.toString();
              }
            });
    new Thread(result).start();
    return result;
  }
}
