package org.signalengine.infrastructure.ingestion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetAddress;
import java.net.spi.InetAddressResolver.LookupPolicy;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class OutboundAddressPinRegistryTest {

  private static final LookupPolicy ANY_FAMILY =
      LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6);

  private final MutableClock clock = new MutableClock(Instant.parse("2026-09-08T00:00:00Z"));
  private final OutboundAddressPinRegistry registry =
      new OutboundAddressPinRegistry(Duration.ofSeconds(30), clock);

  private static InetAddress address(String literal) {
    try {
      return InetAddress.getByName(literal);
    } catch (Exception unexpected) {
      throw new IllegalStateException(unexpected);
    }
  }

  @Test
  void lookupReturnsThePinnedAddressesWhileThePinIsLive() {
    registry.pin("news.example", List.of(address("93.184.216.34")));

    assertThat(registry.hasActivePin("news.example")).isTrue();
    assertThat(registry.lookup("news.example", ANY_FAMILY).orElseThrow())
        .extracting(InetAddress::getHostAddress)
        .containsExactly("93.184.216.34");
  }

  @Test
  void lookupIsCaseInsensitiveOnTheHostname() {
    registry.pin("News.Example", List.of(address("93.184.216.34")));

    assertThat(registry.lookup("news.example", ANY_FAMILY)).isPresent();
  }

  @Test
  void unpinRemovesThePin() {
    registry.pin("news.example", List.of(address("93.184.216.34")));
    registry.unpin("news.example");

    assertThat(registry.hasActivePin("news.example")).isFalse();
    assertThat(registry.lookup("news.example", ANY_FAMILY)).isEmpty();
  }

  @Test
  void unpinIsSafeWhenNoPinExists() {
    registry.unpin("never-pinned.example");

    assertThat(registry.lookup("never-pinned.example", ANY_FAMILY)).isEmpty();
  }

  @Test
  void aLookupForAnUnpinnedHostIsEmptySoTheResolverFallsBackToTheSystem() {
    registry.pin("pinned.example", List.of(address("93.184.216.34")));

    assertThat(registry.lookup("other.example", ANY_FAMILY)).isEmpty();
  }

  @Test
  void aPinExpiresAfterItsTtl() {
    registry.pin("news.example", List.of(address("93.184.216.34")));

    clock.advance(Duration.ofSeconds(29));
    assertThat(registry.lookup("news.example", ANY_FAMILY)).isPresent();

    clock.advance(Duration.ofSeconds(2));
    assertThat(registry.lookup("news.example", ANY_FAMILY)).isEmpty();
    assertThat(registry.hasActivePin("news.example")).isFalse();
  }

  @Test
  void pinRejectsAnEmptyAddressList() {
    assertThatThrownBy(() -> registry.pin("news.example", List.of()))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void constructorRejectsANonPositiveTtl() {
    assertThatThrownBy(() -> new OutboundAddressPinRegistry(Duration.ZERO, clock))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new OutboundAddressPinRegistry(Duration.ofSeconds(-1), clock))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void lookupPolicyIpv4OnlyReturnsOnlyIpv4Addresses() {
    registry.pin("dual.example", List.of(address("93.184.216.34"), address("2606:2800:220:1::1")));

    List<InetAddress> resolved =
        registry.lookup("dual.example", LookupPolicy.of(LookupPolicy.IPV4)).orElseThrow();

    assertThat(resolved).extracting(InetAddress::getHostAddress).containsExactly("93.184.216.34");
  }

  @Test
  void lookupPolicyIpv6OnlyReturnsOnlyIpv6Addresses() {
    registry.pin("dual.example", List.of(address("93.184.216.34"), address("2606:2800:220:1::1")));

    List<InetAddress> resolved =
        registry.lookup("dual.example", LookupPolicy.of(LookupPolicy.IPV6)).orElseThrow();

    assertThat(resolved)
        .singleElement()
        .satisfies(a -> assertThat(a).isInstanceOf(java.net.Inet6Address.class));
  }

  @Test
  void lookupPolicyIpv6FirstOrdersIpv6BeforeIpv4() {
    registry.pin("dual.example", List.of(address("93.184.216.34"), address("2606:2800:220:1::1")));

    List<InetAddress> resolved =
        registry
            .lookup(
                "dual.example",
                LookupPolicy.of(LookupPolicy.IPV4 | LookupPolicy.IPV6 | LookupPolicy.IPV6_FIRST))
            .orElseThrow();

    assertThat(resolved.get(0)).isInstanceOf(java.net.Inet6Address.class);
    assertThat(resolved.get(1)).isInstanceOf(java.net.Inet4Address.class);
  }

  @Test
  void concurrentPinUnpinAndLookupAreThreadSafe() throws Exception {
    int threads = 16;
    ExecutorService pool = Executors.newFixedThreadPool(threads);
    CountDownLatch start = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    InetAddress pinned = address("93.184.216.34");

    for (int t = 0; t < threads; t++) {
      String host = "host-" + (t % 4) + ".example";
      pool.submit(
          () -> {
            try {
              start.await();
              for (int i = 0; i < 2_000; i++) {
                registry.pin(host, List.of(pinned));
                registry.lookup(host, ANY_FAMILY);
                registry.hasActivePin(host);
                registry.unpin(host);
              }
            } catch (Throwable caught) {
              failure.compareAndSet(null, caught);
            }
          });
    }
    start.countDown();
    pool.shutdown();
    assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).isTrue();
    assertThat(failure.get()).isNull();
  }

  /** A hand-advanced {@link Clock} for deterministic TTL assertions. */
  private static final class MutableClock extends Clock {
    private Instant now;

    private MutableClock(Instant start) {
      this.now = start;
    }

    void advance(Duration by) {
      now = now.plus(by);
    }

    @Override
    public Instant instant() {
      return now;
    }

    @Override
    public ZoneOffset getZone() {
      return ZoneOffset.UTC;
    }

    @Override
    public Clock withZone(java.time.ZoneId zone) {
      return this;
    }
  }
}
