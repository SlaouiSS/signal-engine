package org.signalengine.infrastructure.ingestion;

import java.net.InetAddress;
import java.net.URI;
import java.util.List;

/**
 * The result of {@link OutboundUrlValidator#validate(String)}: the original, unmodified request
 * {@link URI} (hostname preserved for HTTP {@code Host}, TLS SNI, and certificate hostname
 * verification) together with the exact set of IP addresses the hostname resolved to at validation
 * time and that passed the SSRF address policy.
 *
 * <p>The collector pins {@code addresses} for the hostname for the duration of the single {@link
 * java.net.http.HttpClient} call, so the address the client connects to is the address that was
 * validated — closing the DNS-rebinding time-of-check/time-of-use gap (docs/adr/0017;
 * docs/10-security.md Section 6). {@code addresses} is empty only when address checking is disabled
 * through the test-only validator seam.
 */
record ValidatedFetchTarget(URI uri, List<InetAddress> addresses) {

  ValidatedFetchTarget {
    addresses = List.copyOf(addresses);
  }

  String host() {
    return uri.getHost();
  }
}
