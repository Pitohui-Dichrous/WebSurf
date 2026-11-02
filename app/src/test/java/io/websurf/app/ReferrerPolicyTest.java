package io.websurf.app;

import static org.junit.Assert.*;

import org.junit.Test;

public final class ReferrerPolicyTest {
  @Test
  public void sameOriginAndNoReferrerPoliciesDoNotLeakCrossOriginPages() {
    String page = "https://page.example/private?key=1#fragment";
    assertEquals(
        "", ReferrerPolicy.forRequest(page, "https://media.example/asset.mp4", "same-origin"));
    assertEquals(
        "", ReferrerPolicy.forRequest(page, "https://page.example/asset.mp4", "no-referrer"));
    assertEquals(
        "https://page.example/private?key=1",
        ReferrerPolicy.forRequest(page, "https://page.example/file", "same-origin"));
    assertEquals(
        "", ReferrerPolicy.forRequest(page, "https://page.example:8443/file", "same-origin"));
  }

  @Test
  public void browserDefaultReducesOriginsStripsFragmentsAndBlocksDowngrades() {
    String page = "https://page.example/a%2Fb?q=x%26y#fragment";
    assertEquals(
        "https://page.example/", ReferrerPolicy.forRequest(page, "https://media.example/file", ""));
    assertEquals(
        "https://page.example/a%2Fb?q=x%26y",
        ReferrerPolicy.forRequest(page, "https://page.example/file", ""));
    assertEquals("", ReferrerPolicy.forRequest(page, "http://media.example/file", ""));
    assertEquals(
        "",
        ReferrerPolicy.forRequest("file:///private", "https://media.example/file", "unsafe-url"));
  }

  @Test
  public void explicitPoliciesRemainAvailableForSitesThatRequireAReferrer() {
    String page = "https://page.example/watch?id=1";
    assertEquals(page, ReferrerPolicy.forRequest(page, "https://media.example/file", "unsafe-url"));
    assertEquals(
        "https://page.example/",
        ReferrerPolicy.forRequest(page, "https://page.example/file", "origin"));
    assertEquals("", ReferrerPolicy.forRequest(page, "http://media.example/file", "strict-origin"));
  }
}
