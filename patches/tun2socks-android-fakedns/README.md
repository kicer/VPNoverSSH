# tun2socks Android fake-DNS adaptation

The AAR build (see `.github/workflows/build-tun2socks-aar.yml`) checks out
`xjasonlyu/tun2socks` PR #374 head, which implements remote DNS as a DNS
server bound to a **system-stack** UDP port 53. That design cannot work in an
unrooted Android app: Linux refuses `bind()` to ports < 1024 for processes
without `CAP_NET_BIND_SERVICE`, and app processes have no capabilities, so the
listener silently fails (gomobile discards the engine's logs).

This patch moves the answer point into the **TUN** stack instead:

- `dns/intercept.go` (copied in) exposes `Intercept(adapter.UDPConn)`, which
  reads the first datagram of a UDP session and answers it from the fake-ip
  pool using PR #374's `fakeipHandler`.
- `tunnel/udp.go` is rewritten at build time (see the workflow) to call it
  for sessions with destination port 53, before the proxy dial.
- The app passes an **empty** `FakeDNSListenAddress`, so PR #374's
  `ReCreateServer` registers the pool (`fakePool` is still assigned) but
  never attempts the impossible port-53 bind.

DNS queries from apps reach the TUN because the VPN declares a resolver IP as
its DNS server without any exclude route; the query is answered locally with
fake IPs (198.18.0.0/15), and subsequent connections to those fake IPs are
relayed to the SOCKS5 proxy **by domain name** (also PR #374, unchanged).
