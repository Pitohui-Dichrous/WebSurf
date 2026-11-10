package io.websurf.app;

import static io.websurf.app.Nat64.Verdict.*;
import static org.junit.Assert.*;

import com.google.common.net.InetAddresses;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;
import org.junit.Test;

public class Nat64Test {
  private static InetAddress ip(String text) {
    return InetAddresses.forString(text);
  }

  @Test
  public void rfc6052TableExtractsIpv4AcrossTheReservedOctetAtEveryLength() {
    int[] bits = {32, 40, 48, 56, 64, 96};
    String[] table = {
      "2001:db8:c000:221::",
      "2001:db8:1c0:2:21::",
      "2001:db8:122:c000:2:2100::",
      "2001:db8:122:3c0:0:221::",
      "2001:db8:122:344:c0:2:2100::",
      "2001:db8:122:344::c000:221"
    };
    for (int i = 0; i < bits.length; i++)
      assertArrayEquals(
          ip("192.0.2.33").getAddress(), Nat64.embedded(ip(table[i]).getAddress(), bits[i]));
  }

  @Test
  public void fixedAndNetworkSpecificTranslationsApplyTheIpv4Policy() {
    var nsp = new Nat64.Prefix(ip("2606:4700:f001:1234::"), 64);
    assertEquals(ALLOW, Nat64.verdict(ip("64:ff9b::808:808"), false, List.of()));
    assertEquals(BLOCK, Nat64.verdict(ip("64:ff9b::a9fe:a9fe"), false, List.of()));
    assertEquals(
        BLOCK, Nat64.verdict(ip("2606:4700:f001:1234:a9:fea9:fe00:abcd"), false, List.of(nsp)));
    assertEquals(
        ALLOW, Nat64.verdict(ip("2606:4700:f001:1234:8:808:0800:cccc"), false, List.of(nsp)));
    assertEquals(
        BLOCK, Nat64.verdict(ip("2606:4700:f001:1234:ff08:808:0800:0"), false, List.of(nsp)));
    assertEquals(ALLOW, Nat64.verdict(ip("2606:4700:4700::1111"), false, List.of(nsp)));
  }

  @Test
  public void oldProvidersDiscoverAllPrefixesAndCheckEveryMatchingTranslation() {
    var discovered =
        Nat64.discover(
            new InetAddress[] {
              ip("192.0.0.170"), ip("2606:4700:1c0:0:aa::"), ip("2606:4701:1234:4567::c000:ab")
            });
    assertEquals(2, discovered.size());
    assertEquals(BLOCK, Nat64.verdict(ip("2606:4700:17f:0:1::"), false, discovered));
    assertEquals(BLOCK, Nat64.verdict(ip("2606:4701:1234:4567::a00:1"), false, discovered));
    assertEquals(ALLOW, Nat64.verdict(ip("2606:4701:1234:4567::909:909"), false, discovered));
  }

  @Test
  public void unknownPrefixSkipsAmbiguousIpv6WithoutLosingVerifiedAlternatives() throws Exception {
    InetAddress privateTranslation = ip("2606:4700:f001:1234::c0a8:0101");
    InetAddress safeNative = ip("2606:4700:1234:5678:fa01:1234:5678:9000");
    InetAddress v4 = ip("1.1.1.1");
    assertEquals(AMBIGUOUS, Nat64.verdict(privateTranslation, false, List.of()));
    assertEquals(
        List.of(safeNative, v4),
        NativeMediaHttp.checkedAnswers(List.of(privateTranslation, safeNative, v4), false, null));
    assertThrows(
        UnknownHostException.class,
        () -> NativeMediaHttp.checkedAnswers(List.of(privateTranslation), false, null));
    assertThrows(
        UnknownHostException.class,
        () ->
            NativeMediaHttp.checkedAnswers(
                List.of(privateTranslation, v4, ip("10.0.0.1")), false, null));
  }
}
