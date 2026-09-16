package org.signalengine.infrastructure.ingestion;

import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolverProvider;

/**
 * Installs {@link PinAwareResolver} as the JVM-wide name-resolution provider (JEP 418), so an
 * in-flight source fetch resolves its hostname from the validated pin rather than from a second,
 * unguarded DNS lookup (docs/adr/0017-outbound-fetch-ssrf-dns-rebinding.md).
 *
 * <p>Registered through {@code META-INF/services/java.net.spi.InetAddressResolverProvider}. The JDK
 * {@link java.util.ServiceLoader} instantiates this class (public, no-arg constructor) once, before
 * the first {@link java.net.InetAddress} resolution and possibly before the Spring context exists;
 * the resolver reaches the pin registry through {@link OutboundAddressPinHolder}. A JVM allows only
 * one such provider — see the ADR for the residual-risk discussion.
 */
public final class PinningInetAddressResolverProvider extends InetAddressResolverProvider {

  @Override
  public InetAddressResolver get(Configuration configuration) {
    return new PinAwareResolver(configuration.builtinResolver(), OutboundAddressPinHolder::current);
  }

  @Override
  public String name() {
    return "signal-engine-outbound-address-pin";
  }
}
