package io.websurf.app;

import static org.junit.Assert.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import org.junit.Test;

public class HlsManifestBudgetTest {
  @Test
  public void codecIdentifiersAreBoundedAfterSubstitutionAcrossRenditions() throws Exception {
    String excessive = "avc1" + " ".repeat(14000) + "b,mp4a";
    String renditions = "#EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID=\"A\",NAME=\"a\"\n".repeat(100);
    String variant = "#EXT-X-STREAM-INF:AUDIO=\"A\",BANDWIDTH=123,CODECS=\"";
    String valid = "avc1.64001f, mp4a.40.2";
    String name = "#EXT-X-DEFINE:NAME=\"v\",VALUE=\"", imported = "#EXT-X-DEFINE:IMPORT=\"v\"\n";
    check(
        "#EXTM3U\n" + name + valid + "\"\n" + variant + "{$v}\"\nr.m3u8\n" + renditions, Map.of());
    check("#EXTM3U\n" + imported + variant + "{$v}\"\nr.m3u8\n", Map.of("v", valid));
    for (String manifest :
        new String[] {
          variant + excessive + "\"\nr.m3u8\n",
          name + excessive + "\"\n" + variant + "{$v}\"\nr.m3u8\n",
          imported + variant + "{$v}\"\nr.m3u8\n",
          variant + "avc1    x,mp4a\"\nr.m3u8\n",
          name
              + excessive
              + "\"\n#EXT-X-STREAM-INF:BANDWIDTH=1\n"
              + name
              + "safe\"\n"
              + variant
              + "{$v}\"\nr.m3u8\n"
        }) {
      assertThrows(
          IOException.class,
          () -> check("#EXTM3U\n" + manifest + renditions, Map.of("v", excessive)));
    }
  }

  private static void check(String text, Map<String, String> imports) throws IOException {
    HlsManifestBudget.check(
        text.getBytes(StandardCharsets.UTF_8),
        "https://media.example/list.m3u8",
        imports,
        null,
        System.nanoTime() + HlsManifestInput.TIMEOUT_NS);
  }

  @Test
  public void relativeSegmentsAndPartsDoNotInheritTheManifestSignature() throws Exception {
    byte[] body =
        ("#EXTM3U\n#EXT-X-TARGETDURATION:4\n"
                + "#EXT-X-PART:DURATION=0.5,URI=\"part.m4s\"\n#EXTINF:4,\nseg.m4s\n".repeat(2000))
            .getBytes(StandardCharsets.UTF_8);
    String base = "https://media.example/", signature = "a".repeat(4096);
    HlsManifestBudget.check(
        body,
        base + "list.m3u8?sig=" + signature,
        Map.of(),
        null,
        System.nanoTime() + HlsManifestInput.TIMEOUT_NS);
    assertThrows(
        IOException.class,
        () ->
            HlsManifestBudget.check(
                body,
                base + signature + "/list.m3u8",
                Map.of(),
                null,
                System.nanoTime() + HlsManifestInput.TIMEOUT_NS));
  }

  @Test
  public void variablesAndDeferredRenditionAttributesHaveAnAggregateBudget() {
    byte[] media =
        ("#EXTM3U\n#EXT-X-MEDIA:TYPE=AUDIO,URI=\"{$token}.m3u8"
                + "{$token}".repeat(1000)
                + "\"\n#EXT-X-STREAM-INF:BANDWIDTH=123\nr.m3u8\n"
                + "#EXT-X-DEFINE:NAME=\"token\",VALUE=\""
                + "x".repeat(6000)
                + "\"")
            .getBytes(StandardCharsets.UTF_8);
    assertThrows(
        IOException.class,
        () ->
            HlsManifestBudget.check(
                media,
                "https://media.example/list.m3u8",
                Map.of(),
                null,
                System.nanoTime() + HlsManifestInput.TIMEOUT_NS));
  }
}
