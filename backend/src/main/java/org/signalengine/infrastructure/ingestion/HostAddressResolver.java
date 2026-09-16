package org.signalengine.infrastructure.ingestion;

import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Name-to-address resolution seam for {@link OutboundUrlValidator}. Production wiring uses {@link
 * InetAddress#getAllByName(String)}; a test can substitute a controlled resolver to exercise
 * DNS-rebinding scenarios deterministically.
 */
@FunctionalInterface
interface HostAddressResolver {

  InetAddress[] resolve(String host) throws UnknownHostException;

  HostAddressResolver SYSTEM = InetAddress::getAllByName;
}
