# ADR 0017 — Outbound source-fetch DNS-rebinding hardening

Status: Accepted
Date: 2026-09-08
Phase: 11 (docs/11-roadmap.md) — Evaluation / Hardening, brought forward for the
Principal Engineer Code Review (`docs/2026-09-08-principal-engineer-code-review.md`,
High finding 2, fix-order item 2)

Supersedes the "Known residual risk — DNS rebinding" paragraph of ADR 0005.

---

## Context

`HttpSourceCollector` (ADR 0005) fetches every configured source URL and every
redirect target with the JDK `java.net.http.HttpClient`. Before each request
`OutboundUrlValidator` parses the URL, enforces the scheme allow-list, requires a
host, resolves the host with `InetAddress.getAllByName`, and rejects the URL if
**any** resolved address is loopback, any-local, link-local (which covers the
`169.254.169.254` cloud-metadata address), site-local / RFC 1918, IPv6
unique-local, or multicast. It then returned the **original hostname URI**
unchanged, and `HttpClient` performed **its own, second DNS resolution** of that
hostname when it connected.

## Problem

The check and the use resolve the hostname independently, so the address the
validator inspected is not guaranteed to be the address the client connects to.
An attacker who controls DNS for a configured hostname (or a redirect target) can
answer with a public address while the validator resolves — the check passes —
then answer with `127.0.0.1`, an RFC 1918 address, `169.254.169.254`, `::1`,
`fd00::/8`, or a Docker-internal address (`db`, `agents`, `ollama`, the Compose
gateway) a few milliseconds later when `HttpClient` resolves. The connection then
reaches an internal destination the SSRF policy exists to block. This is a
time-of-check/time-of-use (TOCTOU) DNS-rebinding bypass. `docs/10-security.md`
Section 6 already requires that "a DNS answer used to validate a request must be
the same one actually connected to" and names DNS rebinding as a threat that
"must be accounted for architecturally", so the code was knowingly
non-compliant.

## Threat model

- **In scope.** A hostile or compromised source, or a hostile redirect `Location`,
  whose authoritative DNS returns different answers on successive lookups, aiming
  to make the backend connect to a loopback / private / link-local / ULA /
  cloud-metadata / Compose-internal endpoint. The MVP is localhost-first and
  single-user, which reduces but does not remove the impact: sibling Compose
  services are reachable by internal name/IP, a cloud VM exposes
  `169.254.169.254`, and `./gradlew bootRun` on a workstation can reach whatever
  else is listening.
- **Out of scope.** Compromise of the OS resolver or `/etc/hosts`; a source that
  is *statically* configured to a private address (already rejected by the
  existing all-addresses check); egress to a genuinely public but
  attacker-controlled host (that is the source being malicious with its own
  content, handled by the untrusted-content controls); authenticated internal
  services that would also need network policy; the unrelated redirect-body size
  bypass (High finding 1, fixed separately).

## Options considered

A full technical analysis was produced before this ADR; it is summarised here.

### A. Rewrite the request URI to the validated IP literal (+ manual `Host` header)

Connect to `http(s)://<ip>/…` and carry the hostname in a `Host` header.
Rejected: for HTTPS the URI host drives SNI and certificate hostname
verification, so this either breaks the handshake or forces disabling endpoint
identification and re-implementing RFC 6125 verification by hand — exactly what
the security constraints forbid. `Host` is also a restricted header in
`java.net.http` (needs a JVM-wide system property). Production `allowed-schemes`
defaults to `https` only, so this is the normal path, not an edge case.

### B. Custom name resolution integrated with the HTTP layer

- **B1 — JVM `InetAddressResolver` SPI (JEP 418, permanent since Java 18) with a
  request-scoped pin.** `OutboundUrlValidator` returns the validated addresses;
  the collector pins `hostname → addresses` for the duration of one
  `HttpClient.send`; a registered `InetAddressResolverProvider` returns the
  pinned addresses for that hostname while the pin is live and delegates every
  other lookup to the JDK built-in resolver. A throwaway probe on the Java 21
  toolchain confirmed `java.net.http.HttpClient` resolves through this SPI, so no
  custom socket or TLS layer is needed and hostname/SNI/certificate verification
  are completely untouched.
- **B2 — the SPI resolver as a global *enforcing* filter with an
  infrastructure allow-list.** Applies the SSRF rejection to every JVM lookup
  except an allow-list (`db`, `agents`, `ollama`, `localhost`, Testcontainers,
  the Compose subnet). Larger blast radius; the allow-list is environment-specific
  and fragile (a wrong entry either breaks backend↔DB/agents or reopens the
  hole). Rejected as the *primary* mechanism.
- **B3 — switch the collector to Apache HttpClient 5 / OkHttp**, which expose a
  real `DnsResolver` / `Dns` SPI. Cleanest mechanism, no global JVM state, but
  adds a second HTTP stack and a runtime dependency for a problem the JDK can now
  solve. Rejected against `CLAUDE.md` Section 23 and the "no new HTTP client"
  constraint.

### C. Connect to the validated IP while preserving Host/SNI/TLS

Achievable with Apache HttpClient 5 (= B3); not achievable on `java.net.http`
without hand-rolled hostname verification (= A). Rejected on the current client.

### D. Network egress restrictions as a compensating control

Deny the backend process egress to RFC 1918 / loopback / link-local / ULA /
Docker-internal ranges (host firewall, egress proxy, or CNI policy). Robust and
is the review's stated alternative, but it is deployment configuration that
Compose alone cannot fully express, it does nothing for `bootRun` on a host, and
it is beyond the MVP's "single-host Compose, minimal infra" scope
(`docs/10-security.md` Sections 18, 23). Valuable as defence in depth, not
sufficient alone.

### E. Combination — application-level pin (B1) + documented egress (D)

## Decision

Implement **Option E with B1 as the application-level mechanism.**

1. `OutboundUrlValidator.validate` returns `ValidatedFetchTarget(URI uri,
   List<InetAddress> addresses)` — the original, unmodified URI plus the exact
   addresses that resolved and passed the SSRF policy. All existing address
   checks are unchanged; a host is still rejected if **any** resolved address is
   disallowed. A package-private `HostAddressResolver` seam (default
   `InetAddress::getAllByName`) lets tests drive rebinding scenarios
   deterministically.

2. `OutboundAddressPinRegistry` — a thread-safe `ConcurrentHashMap<hostname,
   (addresses, expiry)>`. `pin` / `unpin` are the explicit lifecycle; `lookup`
   honours the JDK `LookupPolicy` (address family and ordering) and, when a pin
   exists, never returns "absent" (so the resolver cannot fall back to the system
   for that lookup). Keyed by hostname, **not** by thread — `HttpClient` resolves
   on its own internal threads, so a `ThreadLocal` could not reach the resolution
   site; every pinned set was SSRF-validated, so a hostname briefly shared by two
   concurrent fetches is still safe. A short TTL (connect + request timeout + 5 s)
   is a backstop that bounds a pin leaked by an abnormal exit; the normal path is
   `unpin` in a `finally` block.

3. `PinAwareResolver implements java.net.spi.InetAddressResolver` — returns the
   pinned addresses for a pinned hostname, delegates every other lookup
   (`localhost`, the PostgreSQL host, the Python agents host, Ollama,
   Testcontainers, anything else in the JVM) unchanged to the built-in resolver,
   and delegates all reverse lookups. If a pin exists but no pinned address
   matches the requested family it throws `UnknownHostException` (fail closed)
   rather than fall back. It never applies SSRF filtering to a non-pinned lookup.

4. `PinningInetAddressResolverProvider extends
   java.net.spi.InetAddressResolverProvider`, registered through
   `backend/src/main/resources/META-INF/services/java.net.spi.InetAddressResolverProvider`.
   The JDK `ServiceLoader` instantiates it once, before the first `InetAddress`
   resolution and possibly before the Spring context. It reaches the registry
   through `OutboundAddressPinHolder`, a package-private static holder that
   `IngestionConfiguration` populates with the single registry bean at the
   composition root. Until a registry is installed — and whenever no hostname is
   pinned — the provider is completely inert.

5. `HttpSourceCollector.fetch` runs, per fetch and per redirect hop:
   *validate URL → obtain validated addresses → pin the hostname → `HttpClient.send`
   → unpin in `finally`*. The request URI keeps the original hostname. A redirect
   hop validates, resolves, and pins its **own** hostname during the recursive
   call; the parent hop's pin stays live (different key) until its `finally`
   runs, which also covers the redirect-body drain (that reads an already-open
   connection and performs no resolution).

6. **Defence in depth:** network egress filtering (Option D) is recorded here and
   in `docs/10-security.md` Section 6 as the recommended production control. It
   is not implemented in the MVP Compose topology.

### Why B1 was selected

- **Security-correct:** the address that is connected to is provably the address
  that was validated — there is no second, unguarded lookup while the pin is
  live, and the pin is gone immediately afterward.
- **Compatible with the current client:** no change to `java.net.http.HttpClient`,
  no new HTTP stack.
- **No new dependency:** JEP 418 is standard in the Java 21 toolchain (and the
  Java 25 target).
- **Minimal blast radius:** unlike B2, only hostnames the collector actively pins
  during an in-flight fetch are affected; infrastructure DNS is untouched.
- **Testable** without Docker or a Spring context (loopback `HttpServer` + the
  real provider on the test classpath).

## TLS / SNI preservation

The request URI is never rewritten and the TLS layer is never touched. SNI,
`SSLParameters`, endpoint identification, and certificate hostname verification
all continue to run against the real hostname. The only thing the pin changes is
which IP that hostname maps to, and that IP passed the SSRF policy. TLS
certificate verification and hostname verification are **not** disabled, weakened,
or replaced with IP-based checks.

## Redirect behaviour

Manual redirect handling (`Redirect.NEVER`, bounded by `max-redirects`) is
unchanged. Each hop is independently re-validated, re-resolved, and re-pinned in
its own recursive `fetch` call, and unpinned in its own `finally`. A hostile
`Location` cannot skip validation or pinning.

## IPv4 / IPv6

`getAllByName` returns every A and AAAA record; all are SSRF-checked and all are
pinned. `OutboundAddressPinRegistry.lookup` filters and orders the pinned set to
the `LookupPolicy` the JDK passes (`IPV4`, `IPV6`, `IPV4_FIRST`, `IPV6_FIRST`).
Whichever address `HttpClient` selects was validated. IPv6 unique-local
(`fc00::/7`) and link-local remain rejected by the validator.

## Connection pooling

The JDK connection pool is keyed on the resolved `InetSocketAddress`. A pooled
keep-alive connection reused after the pin is cleared goes to an
already-validated peer with a still-valid certificate — safe, and it performs no
new resolution. A later fetch of the same host re-resolves and re-validates
before any new connection is opened.

## JVM-global resolver constraint

A JVM permits exactly **one** `InetAddressResolverProvider`. If a future
dependency ships its own, `InetAddress` fails at startup. No current dependency
(Spring, Flyway, the PostgreSQL driver, Testcontainers, JUnit) ships one; this
must be checked when adding networking dependencies. The provider being inert
without an installed registry and an active pin keeps the risk of *behavioural*
interference negligible.

## Residual risks

- **Per-hostname, not per-request, pin scope.** Two concurrent fetches of the
  same hostname share one validated pin. Both sets were validated; MVP collection
  is sequential and single-user. A future need for per-request isolation would
  point back to Option B3.
- **JVM `InetAddress` positive cache (~30 s).** A validated answer can be served
  slightly past its DNS TTL — harmless, and it means the validator and the client
  already tend to agree.
- **`bootRun` on a host without egress filtering.** The application-level control
  still applies; the network-level defence in depth does not.
- **OS-level resolution between the SPI call and the `connect()` syscall.**
  Negligible: the SPI returns a concrete `InetAddress` that the client connects
  to directly with no further lookup.
- **A hostname pinned at the instant unrelated JVM code resolves it** would get
  the pinned answer. Pins exist only for external source hostnames during a
  bounded fetch; collision with an internal name is implausible.

## Consequences

- New infrastructure types in `org.signalengine.infrastructure.ingestion`:
  `ValidatedFetchTarget`, `HostAddressResolver`, `OutboundAddressPinRegistry`,
  `OutboundAddressPinHolder`, `PinAwareResolver`, and the `public`
  `PinningInetAddressResolverProvider` (public only because `ServiceLoader`
  requires it — the rest stay package-private).
- One new resource file registers the SPI provider.
- `OutboundUrlValidator.validate` changes return type from `URI` to
  `ValidatedFetchTarget`; `HttpSourceCollector` gains an
  `OutboundAddressPinRegistry` constructor parameter; `IngestionConfiguration`
  gains one bean and installs it into the holder.
- No application- or domain-layer change. No Java↔Python contract change. No
  configuration key added (the TTL derives from the existing timeouts).
- Docker image and Compose topology unchanged; egress filtering is documented,
  not enforced.

## Trade-offs

- **A JVM-global SPI provider for a narrow purpose.** Accepted: it is inert
  unless the collector has an active pin, its scope is the smallest that closes
  the gap, and JEP 418 is the only supported per-resolution hook for
  `java.net.http.HttpClient`.
- **A static holder bridges `ServiceLoader` and Spring.** Not elegant, but it is
  the smallest testable bridge for a provider that must exist before the context;
  tests install and uninstall their own registry for isolation.
- **Network egress filtering is documented, not implemented.** Consistent with
  the MVP's minimal-infra scope; the application-level control is the enforced
  one for now.
