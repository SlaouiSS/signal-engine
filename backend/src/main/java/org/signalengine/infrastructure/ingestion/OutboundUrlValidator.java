package org.signalengine.infrastructure.ingestion;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * The SSRF / URL-safety boundary for outbound source fetches (docs/03-technical-spec.md Section
 * 20.2; docs/10-security.md Section 5–6). Applied before the first request and to every redirect
 * target.
 *
 * <p>Checks: the URL parses and is absolute; its scheme is in the allowed set; it has a host; and
 * <em>every</em> address the host resolves to is a public unicast address — none in a loopback,
 * any-local, link-local (which also covers the {@code 169.254.169.254} cloud-metadata address),
 * site-local / RFC 1918, IPv6 unique-local, or multicast range.
 *
 * <p>{@link #validate} returns the original URI together with the exact addresses it resolved and
 * approved. {@link HttpSourceCollector} pins those addresses for the hostname for the duration of
 * the single {@link java.net.http.HttpClient} call, so the client connects to an address that was
 * validated rather than re-resolving the name — closing the DNS-rebinding time-of-check/time-of-use
 * gap (docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md; docs/10-security.md Section 6).
 */
final class OutboundUrlValidator {

  private final Set<String> allowedSchemes;
  private final boolean rejectPrivateAddresses;
  private final HostAddressResolver hostAddressResolver;

  OutboundUrlValidator(Set<String> allowedSchemes) {
    this(allowedSchemes, true);
  }

  /**
   * {@code rejectPrivateAddresses} is always {@code true} in production wiring; the seam exists
   * only so tests can point the collector at a loopback HTTP server. It must never be {@code false}
   * outside tests.
   */
  OutboundUrlValidator(Set<String> allowedSchemes, boolean rejectPrivateAddresses) {
    this(allowedSchemes, rejectPrivateAddresses, HostAddressResolver.SYSTEM);
  }

  OutboundUrlValidator(
      Set<String> allowedSchemes,
      boolean rejectPrivateAddresses,
      HostAddressResolver hostAddressResolver) {
    this.allowedSchemes =
        allowedSchemes.stream()
            .map(scheme -> scheme.toLowerCase(Locale.ROOT))
            .collect(Collectors.toUnmodifiableSet());
    this.rejectPrivateAddresses = rejectPrivateAddresses;
    this.hostAddressResolver = hostAddressResolver;
  }

  ValidatedFetchTarget validate(String rawUrl) throws UnsafeUrlException {
    URI uri = parseAbsolute(rawUrl);

    String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
    if (!allowedSchemes.contains(scheme)) {
      throw new UnsafeUrlException("scheme '" + scheme + "' is not allowed");
    }

    String host = uri.getHost();
    if (host == null || host.isBlank()) {
      throw new UnsafeUrlException("URL has no host");
    }

    List<InetAddress> addresses = List.of(resolve(host));
    if (rejectPrivateAddresses) {
      for (InetAddress address : addresses) {
        if (isDisallowed(address)) {
          throw new UnsafeUrlException(
              "host '" + host + "' resolves to a disallowed address " + address.getHostAddress());
        }
      }
    }
    return new ValidatedFetchTarget(uri, addresses);
  }

  private static URI parseAbsolute(String rawUrl) throws UnsafeUrlException {
    if (rawUrl == null || rawUrl.isBlank()) {
      throw new UnsafeUrlException("URL is blank");
    }
    try {
      URI uri = new URI(rawUrl.strip());
      if (!uri.isAbsolute()) {
        throw new UnsafeUrlException("URL is not absolute");
      }
      return uri;
    } catch (URISyntaxException malformed) {
      throw new UnsafeUrlException("URL is malformed: " + malformed.getMessage());
    }
  }

  private InetAddress[] resolve(String host) throws UnsafeUrlException {
    try {
      InetAddress[] addresses = hostAddressResolver.resolve(host);
      if (addresses.length == 0) {
        throw new UnsafeUrlException("host '" + host + "' does not resolve");
      }
      return addresses;
    } catch (UnknownHostException unresolvable) {
      throw new UnsafeUrlException("host '" + host + "' does not resolve");
    }
  }

  private static boolean isDisallowed(InetAddress address) {
    return address.isLoopbackAddress()
        || address.isAnyLocalAddress()
        || address.isLinkLocalAddress()
        || address.isSiteLocalAddress()
        || address.isMulticastAddress()
        || isIpv6UniqueLocal(address);
  }

  private static boolean isIpv6UniqueLocal(InetAddress address) {
    return address instanceof Inet6Address && (address.getAddress()[0] & 0xFE) == 0xFC;
  }
}
