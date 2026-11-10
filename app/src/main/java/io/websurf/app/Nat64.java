package io.websurf.app;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/** RFC 6052 and RFC 7050. A translator may ignore the bits following the embedded IPv4. */
final class Nat64 {
  private static final int[] LENGTHS = {32, 40, 48, 56, 64, 96};
  private static final Prefix WELL_KNOWN = new Prefix(InetAddresses.forString("64:ff9b::"), 96);

  enum Verdict {
    ALLOW,
    BLOCK,
    AMBIGUOUS
  }

  static final class Prefix {
    private final byte[] prefix;
    final int bits;

    Prefix(InetAddress network, int bits) {
      this.bits = bits;
      this.prefix = Arrays.copyOf(network.getAddress(), bits / 8);
    }

    boolean matches(byte[] addr) {
      for (int i = 0; i < prefix.length; i++) if (addr[i] != prefix[i]) return false;
      return true;
    }
  }

  static boolean validLength(int bits) {
    for (int length : LENGTHS) if (length == bits) return true;
    return false;
  }

  static byte[] embedded(byte[] address, int bits) {
    byte[] v4 = new byte[4];
    int pos = bits / 8;
    for (int i = 0; i < 4; i++) {
      if (pos == 8) pos++; // reserved u octet
      v4[i] = address[pos++];
    }
    return v4;
  }

  static List<Prefix> discover(InetAddress[] dns64Answers) {
    List<Prefix> result = new ArrayList<>();
    for (InetAddress answer : dns64Answers) {
      byte[] address = answer.getAddress();
      if (address.length != 16 || address[8] != 0) continue;
      for (int length : LENGTHS) {
        byte[] v4 = embedded(address, length);
        if ((v4[0] & 255) == 192
            && v4[1] == 0
            && v4[2] == 0
            && ((v4[3] & 255) == 170 || (v4[3] & 255) == 171))
          result.add(new Prefix(answer, length));
      }
    }
    return List.copyOf(result);
  }

  static Verdict verdict(InetAddress address, boolean local, List<Prefix> prefixes) {
    byte[] ip = address.getAddress();
    if (ip.length == 4 || address.isLoopbackAddress())
      return NativeMediaHttp.permittedBytes(ip, local) ? Verdict.ALLOW : Verdict.BLOCK;
    if (WELL_KNOWN.matches(ip))
      return !local && NativeMediaHttp.permittedBytes(embedded(ip, 96), false)
          ? Verdict.ALLOW
          : Verdict.BLOCK;
    boolean translated = false;
    for (Prefix prefix : prefixes) {
      if (!prefix.matches(ip)) continue;
      translated = true;
      if (ip[8] != 0 || !NativeMediaHttp.permittedBytes(embedded(ip, prefix.bits), local))
        return Verdict.BLOCK;
    }
    if (translated) return Verdict.ALLOW;
    if (!NativeMediaHttp.permittedBytes(ip, local)) return Verdict.BLOCK;
    // No network prefix was available. Preserve native IPv6 where an RFC 6052
    // interpretation cannot cross this boundary; prefer a verified A answer otherwise.
    if (prefixes.isEmpty() && ip[8] == 0)
      for (int bits : LENGTHS)
        if (!NativeMediaHttp.permittedBytes(embedded(ip, bits), local)) return Verdict.AMBIGUOUS;
    return Verdict.ALLOW;
  }

  private Nat64() {}
}
