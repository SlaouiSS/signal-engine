package org.signalengine.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.URI;
import java.util.Set;
import org.junit.jupiter.api.Test;

class OutboundUrlValidatorTest {

  private final OutboundUrlValidator validator = new OutboundUrlValidator(Set.of("https"));

  private static OutboundUrlValidator validatorResolving(String... addresses) {
    InetAddress[] resolved = new InetAddress[addresses.length];
    for (int i = 0; i < addresses.length; i++) {
      try {
        resolved[i] = InetAddress.getByName(addresses[i]);
      } catch (Exception unexpected) {
        throw new IllegalArgumentException(unexpected);
      }
    }
    return new OutboundUrlValidator(Set.of("https"), true, host -> resolved);
  }

  @Test
  void acceptsAnHttpsUrlThatResolvesToAPublicAddress() throws Exception {
    ValidatedFetchTarget validated = validator.validate("https://93.184.216.34/feed");

    assertThat(validated.uri()).isEqualTo(URI.create("https://93.184.216.34/feed"));
  }

  @Test
  void returnsEveryValidatedAddressAlongsideTheOriginalUri() throws Exception {
    ValidatedFetchTarget validated =
        validatorResolving("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946")
            .validate("https://example.test/feed");

    assertThat(validated.uri()).isEqualTo(URI.create("https://example.test/feed"));
    assertThat(validated.host()).isEqualTo("example.test");
    assertThat(validated.addresses())
        .extracting(InetAddress::getHostAddress)
        .containsExactly("93.184.216.34", "2606:2800:220:1:248:1893:25c8:1946");
  }

  @Test
  void rejectsAHostWhenAnyResolvedAddressIsDisallowed() {
    assertThatThrownBy(
            () ->
                validatorResolving("93.184.216.34", "127.0.0.1")
                    .validate("https://rebinding.test/feed"))
        .isInstanceOf(UnsafeUrlException.class)
        .hasMessageContaining("127.0.0.1");
  }

  @Test
  void rejectsABlankOrMalformedUrl() {
    assertThatThrownBy(() -> validator.validate("   ")).isInstanceOf(UnsafeUrlException.class);
    assertThatThrownBy(() -> validator.validate("http ://bad"))
        .isInstanceOf(UnsafeUrlException.class);
  }

  @Test
  void rejectsARelativeUrl() {
    assertThatThrownBy(() -> validator.validate("/only/a/path"))
        .isInstanceOf(UnsafeUrlException.class);
  }

  @Test
  void rejectsDisallowedSchemes() {
    for (String url :
        new String[] {
          "http://93.184.216.34/",
          "ftp://93.184.216.34/",
          "file:///etc/passwd",
          "gopher://93.184.216.34/"
        }) {
      assertThatThrownBy(() -> validator.validate(url))
          .as(url)
          .isInstanceOf(UnsafeUrlException.class);
    }
  }

  @Test
  void rejectsAUrlWithNoHost() {
    assertThatThrownBy(() -> validator.validate("https:///nowhere"))
        .isInstanceOf(UnsafeUrlException.class);
  }

  @Test
  void rejectsLoopbackAddresses() {
    assertThatThrownBy(() -> validator.validate("https://127.0.0.1/"))
        .isInstanceOf(UnsafeUrlException.class);
    assertThatThrownBy(() -> validator.validate("https://[::1]/"))
        .isInstanceOf(UnsafeUrlException.class);
  }

  @Test
  void rejectsPrivateAndLinkLocalAndMetadataAddresses() {
    for (String host : new String[] {"10.0.0.1", "192.168.1.10", "172.16.0.1", "169.254.169.254"}) {
      assertThatThrownBy(() -> validator.validate("https://" + host + "/"))
          .as(host)
          .isInstanceOf(UnsafeUrlException.class);
    }
  }

  @Test
  void rejectsAHostThatDoesNotResolve() {
    assertThatThrownBy(() -> validator.validate("https://no-such-host.invalid/"))
        .isInstanceOf(UnsafeUrlException.class);
  }

  @Test
  void theTestOnlySeamSkipsTheAddressCheckButNotSchemeOrHostChecks() throws Exception {
    OutboundUrlValidator permissive = new OutboundUrlValidator(Set.of("https"), false);

    assertThat(permissive.validate("https://127.0.0.1:8080/x").uri())
        .isEqualTo(URI.create("https://127.0.0.1:8080/x"));
    assertThatThrownBy(() -> permissive.validate("http://127.0.0.1/x"))
        .isInstanceOf(UnsafeUrlException.class);
  }
}
