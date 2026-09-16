package org.signalengine.infrastructure.ingestion;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.stream.Stream;

/**
 * The {@link InetAddressResolver} installed JVM-wide by {@link PinningInetAddressResolverProvider}.
 *
 * <p>For a hostname that {@link HttpSourceCollector} has pinned (see {@link
 * OutboundAddressPinRegistry}) it answers from the pin — the exact addresses that were
 * SSRF-validated moments earlier. For every other hostname — {@code localhost}, the PostgreSQL
 * host, the Python agents host, Ollama, Testcontainers, anything the rest of the JVM resolves — it
 * delegates unchanged to the JDK built-in resolver. It never applies SSRF filtering to a non-pinned
 * lookup, so it is inert outside an in-flight source fetch.
 */
final class PinAwareResolver implements InetAddressResolver {

  private final InetAddressResolver builtinResolver;
  private final Supplier<Optional<OutboundAddressPinRegistry>> pinRegistry;

  PinAwareResolver(
      InetAddressResolver builtinResolver,
      Supplier<Optional<OutboundAddressPinRegistry>> pinRegistry) {
    this.builtinResolver = builtinResolver;
    this.pinRegistry = pinRegistry;
  }

  @Override
  public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy)
      throws UnknownHostException {
    Optional<List<InetAddress>> pinned =
        pinRegistry.get().flatMap(registry -> registry.lookup(host, lookupPolicy));
    if (pinned.isEmpty()) {
      return builtinResolver.lookupByName(host, lookupPolicy);
    }
    List<InetAddress> addresses = pinned.get();
    if (addresses.isEmpty()) {
      // A pin exists but no pinned address matches the requested family: fail closed rather than
      // fall back to the system resolver, which would reopen the rebinding gap for this lookup.
      throw new UnknownHostException(
          host + ": no pinned address matches the requested address family");
    }
    return addresses.stream();
  }

  @Override
  public String lookupByAddress(byte[] addr) throws UnknownHostException {
    return builtinResolver.lookupByAddress(addr);
  }
}
