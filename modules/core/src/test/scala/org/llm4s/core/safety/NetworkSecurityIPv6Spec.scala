package org.llm4s.core.safety

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ Inet4Address, Inet6Address, InetAddress }

/**
 * The SSRF guard's IPv6 coverage (issue #1408, finding F5). The JDK's `isSiteLocalAddress` matches only the
 * deprecated `fec0::/10`, so unique-local `fc00::/7` used to pass, as did IPv6 forms that carry a private IPv4 address.
 * Every address here is a literal, so nothing is resolved.
 */
class NetworkSecurityIPv6Spec extends AnyFlatSpec with Matchers {

  private def ip(literal: String): InetAddress = InetAddress.getByName(literal)

  private def blocked(literal: String): Boolean = NetworkSecurity.isBlockedIP(ip(literal))

  private def v4Bytes(literal: String): Array[Byte] = ip(literal) match {
    case a: Inet4Address => a.getAddress
    case other           => fail(s"$literal is not IPv4: $other")
  }

  /** `prefix` followed by `v4`, as a true `Inet6Address`: `InetAddress.getByName` turns `::ffff:a.b.c.d` into IPv4. */
  private def v6With(prefix: Seq[Int], v4: String, suffix: Seq[Int] = Seq.empty): Inet6Address = {
    val bytes = (prefix.map(_.toByte) ++ v4Bytes(v4).toSeq ++ suffix.map(_.toByte)).toArray
    bytes.length shouldBe 16
    Inet6Address.getByAddress(null, bytes, -1)
  }

  private def mapped(v4: String): Inet6Address     = v6With(Seq.fill(10)(0) ++ Seq(0xff, 0xff), v4)
  private def compatible(v4: String): Inet6Address = v6With(Seq.fill(12)(0), v4)
  private def nat64(v4: String): Inet6Address      = v6With(Seq(0x00, 0x64, 0xff, 0x9b) ++ Seq.fill(8)(0), v4)
  private def sixToFour(v4: String): Inet6Address  = v6With(Seq(0x20, 0x02), v4, Seq.fill(10)(0))

  /** One address from each blocked IPv4 range, and the cloud metadata address. */
  private val blockedV4 = Seq(
    "0.0.0.0",
    "0.1.2.3",
    "10.0.0.1",
    "127.0.0.1",
    "127.255.255.254",
    "169.254.169.254",
    "169.254.0.1",
    "172.16.0.1",
    "172.31.255.255",
    "192.168.1.1",
    "224.0.0.1",
    "239.255.255.255",
    "100.64.0.1",
    "198.18.0.1",
    "192.0.2.1",
    "198.51.100.1",
    "203.0.113.1"
  )

  private val publicV4 = Seq("8.8.8.8", "1.1.1.1", "93.184.216.34", "172.32.0.1", "100.128.0.1")

  "NetworkSecurity.isBlockedIP" should "block IPv6 unique-local addresses (fc00::/7)" in {
    for (a <- Seq("fc00::1", "fd00::1", "fd12:3456:789a::1", "fdff:ffff:ffff:ffff:ffff:ffff:ffff:ffff"))
      withClue(a)(blocked(a) shouldBe true)
    NetworkSecurity.validateIP("fd00::1").isLeft shouldBe true
    NetworkSecurity.validateHostname("fc00::1").isLeft shouldBe true
  }

  it should "still block deprecated site-local, link-local, loopback, unspecified and multicast IPv6" in {
    for (a <- Seq("fec0::1", "feff::1", "fe80::1", "febf::1", "::1", "::", "ff02::1", "ff0e::1"))
      withClue(a)(blocked(a) shouldBe true)
  }

  it should "block the IPv4-compatible form (::/96), whatever IPv4 address it carries" in {
    for (a <- Seq("::127.0.0.1", "::10.0.0.1", "::169.254.169.254", "::8.8.8.8"))
      withClue(a)(blocked(a) shouldBe true)
    for (v4 <- blockedV4 ++ publicV4)
      withClue(v4)(NetworkSecurity.isBlockedIP(compatible(v4)) shouldBe true)
  }

  it should "block the IPv4-mapped form (::ffff:0:0/96) of every blocked IPv4 range" in {
    for (v4 <- blockedV4) {
      withClue(s"::ffff:$v4 as Inet6Address")(NetworkSecurity.isBlockedIP(mapped(v4)) shouldBe true)
      withClue(s"::ffff:$v4 by name")(blocked(s"::ffff:$v4") shouldBe true)
    }
  }

  it should "block NAT64 (64:ff9b::/96) addresses that embed a blocked IPv4 address" in {
    blocked("64:ff9b::7f00:1") shouldBe true    // 127.0.0.1
    blocked("64:ff9b::a9fe:a9fe") shouldBe true // 169.254.169.254
    blocked("64:ff9b::10.0.0.1") shouldBe true
    for (v4 <- blockedV4) withClue(v4)(NetworkSecurity.isBlockedIP(nat64(v4)) shouldBe true)
  }

  it should "block local-use NAT64 (64:ff9b:1::/48)" in {
    blocked("64:ff9b:1::1") shouldBe true
    blocked("64:ff9b:1:ffff::808:808") shouldBe true
  }

  it should "block 6to4 (2002::/16) addresses that embed a blocked IPv4 address" in {
    blocked("2002:7f00:1::1") shouldBe true   // 127.0.0.1
    blocked("2002:a00:1::") shouldBe true     // 10.0.0.1
    blocked("2002:c0a8:101::1") shouldBe true // 192.168.1.1
    blocked("2002:a9fe:a9fe::") shouldBe true // 169.254.169.254
    for (v4 <- blockedV4) withClue(v4)(NetworkSecurity.isBlockedIP(sixToFour(v4)) shouldBe true)
  }

  it should "block Teredo (2001::/32), IPv6 documentation, benchmarking and discard-only ranges" in {
    for (
      a <- Seq(
        "2001::1",
        "2001:0:4136:e378:8000:63bf:3fff:fdd2", // Teredo
        "2001:db8::1",
        "2001:db8:ffff:ffff:ffff:ffff:ffff:ffff", // documentation (RFC 3849)
        "3fff::1",
        "3fff:fff:ffff::1", // documentation (RFC 9637)
        "2001:2::1",
        "2001:2:0:ffff::1", // benchmarking (RFC 5180)
        "100::1",
        "100::ffff:ffff:ffff:ffff" // discard-only (RFC 6666)
      )
    ) withClue(a)(blocked(a) shouldBe true)
  }

  it should "allow public IPv6 addresses, including the boundaries of the blocked ranges" in {
    for (
      a <- Seq(
        "2606:4700:4700::1111",
        "2001:4860:4860::8888",
        // "fbff:ffff::1", "100:0:0:1::1" and "64:ff9b:2::1" used to be listed here; they lie outside global unicast
        // 2000::/3 and are blocked since #1734 (NetworkSecuritySpecialPurposeSpec)
        "2001:1::1",        // next to Teredo: PCP anycast, globally reachable
        "2001:db9::1",      // next to documentation
        "2001:3::1",        // next to benchmarking: AMT, globally reachable
        "3fff:1000::1",     // just past 3fff::/20
        "64:ff9b::808:808", // NAT64 of 8.8.8.8
        "2002:808:808::1"   // 6to4 of 8.8.8.8
      )
    ) withClue(a)(blocked(a) shouldBe false)
  }

  it should "allow the IPv4-mapped, NAT64 and 6to4 forms of public IPv4 addresses" in {
    for (v4 <- publicV4) {
      withClue(s"mapped $v4")(NetworkSecurity.isBlockedIP(mapped(v4)) shouldBe false)
      withClue(s"NAT64 $v4")(NetworkSecurity.isBlockedIP(nat64(v4)) shouldBe false)
      withClue(s"6to4 $v4")(NetworkSecurity.isBlockedIP(sixToFour(v4)) shouldBe false)
    }
  }

  it should "block the whole of 0.0.0.0/8 and keep allowing public IPv4" in {
    blocked("0.1.2.3") shouldBe true
    blocked("0.255.255.255") shouldBe true
    for (v4 <- publicV4) withClue(v4)(blocked(v4) shouldBe false)
    for (v4 <- blockedV4) withClue(v4)(blocked(v4) shouldBe true)
  }
}
