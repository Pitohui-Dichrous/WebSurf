package io.websurf.app;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Shared input checks for the on-device HTTP test responders. */
public final class FixtureHttp {
  private static final Pattern REQUEST = Pattern.compile("[A-Z]+ (/\\S*) HTTP/1\\.[01]");
  private static final Pattern RANGE = Pattern.compile("bytes=([0-9]+)-([0-9]*)");

  private FixtureHttp() {}

  public static ServerSocket listen() throws IOException {
    return new ServerSocket(0, 32, InetAddress.getByName("127.0.0.1"));
  }

  public static String probeStatus(ServerSocket server, String request) throws IOException {
    try (Socket client = new Socket(server.getInetAddress(), server.getLocalPort())) {
      client.setSoTimeout(5000);
      client.getOutputStream().write((request + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
      return new BufferedReader(
              new InputStreamReader(client.getInputStream(), StandardCharsets.US_ASCII))
          .readLine();
    }
  }

  public static String requestTarget(BufferedReader input, OutputStream output) throws IOException {
    StringBuilder line = new StringBuilder();
    for (int c; line.length() <= 8192 && (c = input.read()) != -1; ) {
      if (c == '\n') {
        int n = line.length();
        if (n > 0 && line.charAt(n - 1) == '\r') line.setLength(n - 1);
        Matcher request = REQUEST.matcher(line);
        if (request.matches()) return request.group(1);
        break;
      }
      line.append((char) c);
    }
    throw reject(output, "400 Bad Request");
  }

  public record Range(int start, int end) {
    public int length() {
      return end - start + 1;
    }
  }

  public static Range range(String value, int size, boolean clipEnd, OutputStream output)
      throws IOException {
    if (value == null) return new Range(0, size - 1);
    Matcher match = RANGE.matcher(value);
    if (match.matches()) {
      try {
        long start = Long.parseLong(match.group(1));
        long end = match.group(2).isEmpty() ? size - 1L : Long.parseLong(match.group(2));
        if (clipEnd) end = Math.min(end, size - 1L);
        if (start <= end && end < size) return new Range((int) start, (int) end);
      } catch (NumberFormatException ignored) {
        // Overlong endpoints are unsatisfiable.
      }
    }
    throw reject(output, "416 Range Not Satisfiable");
  }

  private static IOException reject(OutputStream output, String status) throws IOException {
    output.write(
        ("HTTP/1.1 " + status + "\r\nContent-Length: 0\r\nConnection: close\r\n\r\n")
            .getBytes(StandardCharsets.US_ASCII));
    return new IOException(status);
  }
}
