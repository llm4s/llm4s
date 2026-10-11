package org.llm4s.core.safety

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ Inet4Address, Inet6Address, InetAddress }
import java.util.Locale
import scala.util.Try

/**
 * The special-purpose ranges the SSRF guard missed until issue #1734, checked against the IANA IPv4 and IPv6
 * Special-Purpose Address Registries: IPv4 `240.0.0.0/4` (with `255.255.255.255`), `192.0.0.0/24` and
 * `192.88.99.0/24`, in every IPv6 form that carries an IPv4 address; the SIIT IPv4-translated form
 * `::ffff:0:a.b.c.d`; and the IPv6 space outside global unicast `2000::/3`, plus the non-global part of `2001::/23`.
 * Every address is a literal, so nothing is resolved.
 */
class NetworkSecuritySpecialPurposeSpec extends AnyFlatSpec with Matchers {

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
  private def siit(v4: String): Inet6Address       = v6With(Seq.fill(8)(0) ++ Seq(0xff, 0xff, 0, 0), v4)
  private def nat64(v4: String): Inet6Address      = v6With(Seq(0x00, 0x64, 0xff, 0x9b) ++ Seq.fill(8)(0), v4)
  private def sixToFour(v4: String): Inet6Address  = v6With(Seq(0x20, 0x02), v4, Seq.fill(10)(0))

  /** One or more addresses from each IPv4 range this issue adds. */
  private val newlyBlockedV4 = Seq(
    "240.0.0.0",
    "240.0.0.1",
    "250.1.2.3",
    "255.255.255.254",
    "255.255.255.255", // limited broadcast
    "192.0.0.0",
    "192.0.0.1",
    "192.0.0.170", // NAT64/DNS64 discovery
    "192.0.0.255",
    "192.88.99.0",
    "192.88.99.1", // deprecated 6to4 relay anycast
    "192.88.99.255"
  )

  /** Public addresses next to them, and the globally reachable entries of the IPv4 registry, which stay allowed. */
  private val publicV4 = Seq(
    "223.255.255.255", // just below 224.0.0.0/4
    "192.0.1.1",       // just past 192.0.0.0/24
    "192.0.3.1",       // just past TEST-NET-1
    "192.88.98.255",   // just below 192.88.99.0/24
    "192.88.100.0",    // just past it
    "192.31.196.1",    // AS112-v4, globally reachable
    "192.52.193.1",    // AMT, globally reachable
    "192.175.48.1",    // Direct Delegation AS112, globally reachable
    "8.8.8.8"
  )

  "NetworkSecurity.isBlockedIP" should "block 240.0.0.0/4, 255.255.255.255, 192.0.0.0/24 and 192.88.99.0/24" in {
    for (v4 <- newlyBlockedV4) {
      withClue(v4)(blocked(v4) shouldBe true)
      withClue(v4)(NetworkSecurity.validateIP(v4).isLeft shouldBe true)
    }
  }

  it should "keep allowing the public addresses next to those ranges" in {
    for (v4 <- publicV4) withClue(v4)(blocked(v4) shouldBe false)
  }

  it should "block those ranges in every IPv6 form that carries an IPv4 address" in {
    for (v4 <- newlyBlockedV4) {
      withClue(s"mapped $v4")(NetworkSecurity.isBlockedIP(mapped(v4)) shouldBe true)
      withClue(s"::ffff:$v4 by name")(blocked(s"::ffff:$v4") shouldBe true)
      withClue(s"compatible $v4")(NetworkSecurity.isBlockedIP(compatible(v4)) shouldBe true)
      withClue(s"SIIT $v4")(NetworkSecurity.isBlockedIP(siit(v4)) shouldBe true)
      withClue(s"NAT64 $v4")(NetworkSecurity.isBlockedIP(nat64(v4)) shouldBe true)
      withClue(s"6to4 $v4")(NetworkSecurity.isBlockedIP(sixToFour(v4)) shouldBe true)
    }
    // the NAT64 and 6to4 forms of 255.255.255.255 named in the issue
    blocked("64:ff9b::ffff:ffff") shouldBe true
    blocked("2002:ffff:ffff::") shouldBe true
  }

  it should "block the SIIT IPv4-translated form (::ffff:0:0:0/96), whatever IPv4 address it carries" in {
    blocked("::ffff:0:7f00:1") shouldBe true    // 127.0.0.1, the issue's example
    blocked("::ffff:0:a9fe:a9fe") shouldBe true // 169.254.169.254
    blocked("::ffff:0:a00:1") shouldBe true     // 10.0.0.1
    blocked("::ffff:0:808:808") shouldBe true   // 8.8.8.8: a translator-internal form, never a destination
    NetworkSecurity.validateIP("::ffff:0:7f00:1").isLeft shouldBe true
  }

  it should "keep allowing the IPv4-mapped, NAT64 and 6to4 forms of public IPv4 addresses" in {
    for (v4 <- publicV4) {
      withClue(s"mapped $v4")(NetworkSecurity.isBlockedIP(mapped(v4)) shouldBe false)
      withClue(s"NAT64 $v4")(NetworkSecurity.isBlockedIP(nat64(v4)) shouldBe false)
      withClue(s"6to4 $v4")(NetworkSecurity.isBlockedIP(sixToFour(v4)) shouldBe false)
    }
  }

  it should "block IPv6 outside global unicast 2000::/3, which IANA has never allocated for routing" in {
    for (
      a <- Seq(
        "1::1",
        "100:0:0:1::1", // dummy prefix (RFC 9780)
        "64:ff9b:2::1", // reserved, past local-use NAT64
        "400::1",
        "4000::1",
        "5f00::1", // SRv6 SIDs (RFC 9602)
        "5f00:ffff::1",
        "8000::1",
        "c000::1",
        "e000::1",
        "f000::1",
        "fbff:ffff::1" // just below fc00::/7, still outside 2000::/3
      )
    ) withClue(a)(blocked(a) shouldBe true)
  }

  it should "block the non-global part of the IETF protocol assignments block 2001::/23" in {
    for (
      a <- Seq(
        "2001:1::4", // 2001:1::/32 outside its three anycast addresses
        "2001:1::1:1",
        "2001:2:1::1", // 2001:2::/32 past benchmarking
        "2001:4::1",   // 2001:4::/32 outside AS112
        "2001:5::1",
        "2001:10::1", // deprecated ORCHID (RFC 4843)
        "2001:1f::1",
        "2001:40::1",
        "2001:1ff::1" // the last /32 of 2001::/23
      )
    ) withClue(a)(blocked(a) shouldBe true)
  }

  it should "keep allowing the globally reachable entries of 2001::/23 and public IPv6 next to it" in {
    for (
      a <- Seq(
        "2001:1::1",       // PCP anycast
        "2001:1::2",       // TURN anycast
        "2001:1::3",       // DNS-SD service registration anycast
        "2001:3::1",       // AMT
        "2001:4:112::1",   // AS112-v6
        "2001:20::1",      // ORCHIDv2
        "2001:30::1",      // drone remote ID
        "2001:200::1",     // just past 2001::/23
        "2620:4f:8000::1", // Direct Delegation AS112
        "2606:4700:4700::1111",
        "2001:4860:4860::8888",
        "3fff:1000::1" // just past 3fff::/20
      )
    ) withClue(a)(blocked(a) shouldBe false)
  }

  "NetworkSecurity.isBlockedHostname" should "match a blocked name with an I in it under a Turkish default locale" in {
    // Under tr-TR the default-locale "I".toLowerCase is the dotless ı, so "INTERNAL.example" used to miss
    // "internal.example" (#1734). The default locale is restored before the result is checked.
    val previous = Locale.getDefault
    val outcome = Try {
      Locale.setDefault(Locale.forLanguageTag("tr-TR"))
      (
        NetworkSecurity.isBlockedHostname("INTERNAL.example", Set("internal.example")),
        NetworkSecurity.isBlockedHostname("api.INTERNAL.EXAMPLE", Set("Internal.Example")),
        NetworkSecurity.isBlockedHostname("METADATA.GOOGLE.INTERNAL"),
        NetworkSecurity.validateUrl("HTTP://INTERNAL.example/", Set("internal.example")).isLeft
      )
    }
    Locale.setDefault(previous)
    outcome.get shouldBe ((true, true, true, true))
  }
}
