package org.signalengine.infrastructure.ingestion;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A thread-safe, short-lived registry of validated outbound DNS answers, keyed by hostname.
 *
 * <p>{@link HttpSourceCollector} resolves and SSRF-validates a hostname, {@link #pin pins} the
 * validated {@link InetAddress} list for that hostname, performs exactly one {@link
 * java.net.http.HttpClient} call, then {@link #unpin unpins} in a {@code finally} block. While a
 * pin is live, {@link PinAwareResolver} (installed as the JVM {@link
 * java.net.spi.InetAddressResolverProvider}) answers lookups for that hostname from the pin instead
 * of the operating-system resolver, so the address the client connects to is provably the address
 * that was validated. This closes the DNS-rebinding time-of-check/time-of-use gap
 * (docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md).
 *
 * <p>Design notes:
 *
 * <ul>
 *   <li>Keyed by hostname, not by request or thread: {@code HttpClient} resolves on its own
 *       internal threads, so a {@link ThreadLocal} could not carry the pin to the resolution site.
 *       Every pinned address set was SSRF-validated, so a hostname briefly shared by two concurrent
 *       fetches is still safe.
 *   <li>The explicit {@link #unpin} in {@code finally} is the normal lifecycle; the {@code ttl} is
 *       only a backstop that bounds a pin leaked by an abnormal exit to at most one fetch duration.
 *   <li>No business, domain, application, or persistence dependency; no I/O.
 * </ul>
 */
final class OutboundAddressPinRegistry {

  private final Map<String, Pin> pinsByHost = new ConcurrentHashMap<>();
  private final Duration ttl;
  private final Clock clock;

  OutboundAddressPinRegistry(Duration ttl, Clock clock) {
    if (ttl.isNegative() || ttl.isZero()) {
      throw new IllegalArgumentException("pin ttl must be positive");
    }
    this.ttl = ttl;
    this.clock = clock;
  }

  /**
   * Pins the validated addresses for {@code host} until {@link #unpin} is called or the TTL
   * elapses, whichever comes first. A subsequent {@code pin} for the same host replaces the
   * previous entry.
   */
  void pin(String host, List<InetAddress> validatedAddresses) {
    if (validatedAddresses.isEmpty()) {
      throw new IllegalArgumentException("refusing to pin an empty address list for " + host);
    }
    pinsByHost.put(key(host), new Pin(List.copyOf(validatedAddresses), clock.instant().plus(ttl)));
  }

  /** Removes the pin for {@code host}, if any. Safe to call when no pin exists. */
  void unpin(String host) {
    pinsByHost.remove(key(host));
  }

  /**
   * Returns the pinned addresses for {@code host} filtered and ordered per {@code lookupPolicy}, or
   * {@link Optional#empty()} when no live pin exists for the host. A present result — even an empty
   * list — means the pin governs this lookup and the caller must not fall back to the system
   * resolver (that fallback is exactly the rebinding gap this registry closes).
   */
  Optional<List<InetAddress>> lookup(String host, LookupPolicy lookupPolicy) {
    Pin pin = livePin(host);
    if (pin == null) {
      return Optional.empty();
    }
    return Optional.of(applyPolicy(pin.addresses(), lookupPolicy));
  }

  /** Whether a non-expired pin currently exists for {@code host}. Used by tests and diagnostics. */
  boolean hasActivePin(String host) {
    return livePin(host) != null;
  }

  private Pin livePin(String host) {
    String key = key(host);
    Pin pin = pinsByHost.get(key);
    if (pin == null) {
      return null;
    }
    if (!pin.expiresAt().isAfter(clock.instant())) {
      pinsByHost.remove(key, pin);
      return null;
    }
    return pin;
  }

  private static String key(String host) {
    return host.toLowerCase(Locale.ROOT);
  }

  private static List<InetAddress> applyPolicy(List<InetAddress> addresses, LookupPolicy policy) {
    int characteristics = policy.characteristics();
    boolean ipv4Requested = (characteristics & LookupPolicy.IPV4) != 0;
    boolean ipv6Requested = (characteristics & LookupPolicy.IPV6) != 0;
    boolean acceptIpv4 = ipv4Requested || !ipv6Requested;
    boolean acceptIpv6 = ipv6Requested || !ipv4Requested;

    List<InetAddress> selected = new ArrayList<>(addresses.size());
    for (InetAddress address : addresses) {
      if (address instanceof Inet4Address && acceptIpv4) {
        selected.add(address);
      } else if (address instanceof Inet6Address && acceptIpv6) {
        selected.add(address);
      }
    }

    if ((characteristics & LookupPolicy.IPV6_FIRST) != 0) {
      selected.sort(Comparator.comparingInt(address -> address instanceof Inet6Address ? 0 : 1));
    } else if ((characteristics & LookupPolicy.IPV4_FIRST) != 0) {
      selected.sort(Comparator.comparingInt(address -> address instanceof Inet4Address ? 0 : 1));
    }
    return List.copyOf(selected);
  }

  private record Pin(List<InetAddress> addresses, Instant expiresAt) {}
}
