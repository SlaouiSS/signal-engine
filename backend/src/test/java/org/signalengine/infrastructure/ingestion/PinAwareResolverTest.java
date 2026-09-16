package org.signalengine.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.net.spi.InetAddressResolver;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

class PinAwareResolverTest {

  private static final LookupPolicy ANY_FAMILY =
      LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);

  private final OutboundAddressPinRegistry registry =
      new OutboundAddressPinRegistry(Duration.ofSeconds(30), Clock.systemUTC());
  private final RecordingBuiltinResolver builtin = new RecordingBuiltinResolver();
  private final PinAwareResolver resolver =
      new PinAwareResolver(builtin, () -> Optional.of(registry));

  private static InetAddress address(String literal) throws UnknownHostException {
    return InetAddress.getByName(literal);
  }

  @Test
  void delegatesToTheBuiltinResolverWhenNoPinExists() throws Exception {
    builtin.answer = List.of(address("93.184.216.34"));

    List<InetAddress> resolved = resolver.lookupByName("news.example", ANY_FAMILY).toList();

    assertThat(resolved).extracting(InetAddress::getHostAddress).containsExactly("93.184.216.34");
    assertThat(builtin.forwardCalls.get()).isEqualTo(1);
  }

  @Test
  void returnsThePinnedAddressesAndNeverConsultsTheBuiltinResolver() throws Exception {
    registry.pin("news.example", List.of(address("203.0.113.5")));

    List<InetAddress> resolved = resolver.lookupByName("news.example", ANY_FAMILY).toList();

    assertThat(resolved).extracting(InetAddress::getHostAddress).containsExactly("203.0.113.5");
    assertThat(builtin.forwardCalls.get()).isZero();
  }

  @Test
  void doesNotInterceptAHostThatIsNotPinned() throws Exception {
    registry.pin("pinned-source.example", List.of(address("203.0.113.5")));
    builtin.answer = List.of(address("198.51.100.9"));

    List<InetAddress> resolved = resolver.lookupByName("postgres-db", ANY_FAMILY).toList();

    assertThat(resolved).extracting(InetAddress::getHostAddress).containsExactly("198.51.100.9");
    assertThat(builtin.forwardCalls.get()).isEqualTo(1);
  }

  @Test
  void failsClosedWhenAPinExistsButNoAddressMatchesTheRequestedFamily() throws Exception {
    registry.pin("v6only.example", List.of(address("2606:2800:220:1::1")));

    assertThatThrownBy(
            () -> resolver.lookupByName("v6only.example", LookupPolicy.of(LookupPolicy.IPV4)))
        .isInstanceOf(UnknownHostException.class);
    assertThat(builtin.forwardCalls.get()).isZero();
  }

  @Test
  void reverseLookupsAlwaysDelegate() throws Exception {
    resolver.lookupByAddress(address("93.184.216.34").getAddress());

    assertThat(builtin.reverseCalls.get()).isEqualTo(1);
  }

  @Test
  void isInertWhenNoRegistryIsInstalled() throws Exception {
    PinAwareResolver withoutRegistry = new PinAwareResolver(builtin, Optional::empty);
    builtin.answer = List.of(address("93.184.216.34"));

    withoutRegistry.lookupByName("anything.example", ANY_FAMILY).toList();

    assertThat(builtin.forwardCalls.get()).isEqualTo(1);
  }

  private static final class RecordingBuiltinResolver implements InetAddressResolver {
    private final AtomicInteger forwardCalls = new AtomicInteger();
    private final AtomicInteger reverseCalls = new AtomicInteger();
    private List<InetAddress> answer = List.of();

    @Override
    public Stream<InetAddress> lookupByName(String host, LookupPolicy lookupPolicy) {
      forwardCalls.incrementAndGet();
      return answer.stream();
    }

    @Override
    public String lookupByAddress(byte[] addr) {
      reverseCalls.incrementAndGet();
      return "host.example";
    }
  }
}
