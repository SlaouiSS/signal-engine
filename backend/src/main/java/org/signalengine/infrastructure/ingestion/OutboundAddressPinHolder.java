package org.signalengine.infrastructure.ingestion;

import java.util.Optional;

/**
 * The bridge between the Spring composition root and the JDK {@link
 * java.net.spi.InetAddressResolverProvider}.
 *
 * <p>The provider is instantiated by the {@link java.util.ServiceLoader}, not by Spring, and the
 * first {@link java.net.InetAddress} resolution in the JVM can happen before the Spring context
 * exists. The provider therefore cannot be given the {@link OutboundAddressPinRegistry} by
 * constructor injection. Instead {@code IngestionConfiguration} creates the single registry bean
 * and {@link #install(OutboundAddressPinRegistry) installs} it here; {@link PinAwareResolver} reads
 * it through {@link #current()} on every lookup.
 *
 * <p>Until a registry is installed — and whenever no hostname is pinned — the resolver is
 * completely inert and every lookup is delegated to the JDK built-in resolver. Only one registry is
 * meaningful per JVM, matching the one-provider-per-JVM constraint of the SPI.
 */
final class OutboundAddressPinHolder {

  private static volatile OutboundAddressPinRegistry registry;

  private OutboundAddressPinHolder() {}

  static void install(OutboundAddressPinRegistry pinRegistry) {
    registry = pinRegistry;
  }

  /** Clears the installed registry only if it is still {@code pinRegistry} (test isolation). */
  static void uninstall(OutboundAddressPinRegistry pinRegistry) {
    if (registry == pinRegistry) {
      registry = null;
    }
  }

  static Optional<OutboundAddressPinRegistry> current() {
    return Optional.ofNullable(registry);
  }
}
