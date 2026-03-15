package org.llm4s.core.safety

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.InetAddress

class NetworkSecuritySpec extends AnyFlatSpec with Matchers {

  "NetworkSecurity.isBlockedIP" should "block loopback addresses" in {
    val loopback = InetAddress.getByName("127.0.0.1")
    NetworkSecurity.isBlockedIP(loopback) shouldBe true

    val loopback2 = InetAddress.getByName("127.0.0.2")
    NetworkSecurity.isBlockedIP(loopback2) shouldBe true
  }

  it should "block private network ranges (RFC 1918)" in {
    // 10.0.0.0/8
    NetworkSecurity.isBlockedIP(InetAddress.getByName("10.0.0.1")) shouldBe true
    NetworkSecurity.isBlockedIP(InetAddress.getByName("10.255.255.255")) shouldBe true

    // 172.16.0.0/12
    NetworkSecurity.isBlockedIP(InetAddress.getByName("172.16.0.1")) shouldBe true
    NetworkSecurity.isBlockedIP(InetAddress.getByName("172.31.255.255")) shouldBe true

    // 192.168.0.0/16
    NetworkSecurity.isBlockedIP(InetAddress.getByName("192.168.0.1")) shouldBe true
    NetworkSecurity.isBlockedIP(InetAddress.getByName("192.168.255.255")) shouldBe true
  }

  it should "block cloud metadata IP (169.254.169.254)" in {
    val metadataIP = InetAddress.getByName("169.254.169.254")
    NetworkSecurity.isBlockedIP(metadataIP) shouldBe true
  }

  it should "block link-local addresses (169.254.x.x)" in {
    NetworkSecurity.isBlockedIP(InetAddress.getByName("169.254.0.1")) shouldBe true
    NetworkSecurity.isBlockedIP(InetAddress.getByName("169.254.255.255")) shouldBe true
  }

  it should "block multicast and any-local addresses" in {
    NetworkSecurity.isBlockedIP(InetAddress.getByName("224.0.0.1")) shouldBe true
    NetworkSecurity.isBlockedIP(InetAddress.getByName("0.0.0.0")) shouldBe true
  }

  it should "block IPv6 link-local addresses" in {
    NetworkSecurity.isBlockedIP(InetAddress.getByName("fe80::1")) shouldBe true
  }

  it should "allow public IP addresses" in {
    NetworkSecurity.isBlockedIP(InetAddress.getByName("8.8.8.8")) shouldBe false
    NetworkSecurity.isBlockedIP(InetAddress.getByName("1.1.1.1")) shouldBe false
    NetworkSecurity.isBlockedIP(InetAddress.getByName("93.184.216.34")) shouldBe false // example.com
  }

  it should "block documentation ranges (RFC 5737)" in {
    // TEST-NET-1: 192.0.2.0/24
    NetworkSecurity.isBlockedIP(InetAddress.getByName("192.0.2.1")) shouldBe true
    // TEST-NET-2: 198.51.100.0/24
    NetworkSecurity.isBlockedIP(InetAddress.getByName("198.51.100.1")) shouldBe true
    // TEST-NET-3: 203.0.113.0/24
    NetworkSecurity.isBlockedIP(InetAddress.getByName("203.0.113.1")) shouldBe true
  }

  it should "block carrier-grade NAT range (100.64.0.0/10)" in {
    NetworkSecurity.isBlockedIP(InetAddress.getByName("100.64.0.1")) shouldBe true
    NetworkSecurity.isBlockedIP(InetAddress.getByName("100.127.255.255")) shouldBe true

    // Should NOT block IPs outside this range
    NetworkSecurity.isBlockedIP(InetAddress.getByName("100.63.255.255")) shouldBe false
    NetworkSecurity.isBlockedIP(InetAddress.getByName("100.128.0.1")) shouldBe false
  }

  it should "block benchmark range (198.18.0.0/15)" in {
    NetworkSecurity.isBlockedIP(InetAddress.getByName("198.18.0.1")) shouldBe true
    NetworkSecurity.isBlockedIP(InetAddress.getByName("198.19.255.255")) shouldBe true

    // Should NOT block IPs outside this range
    NetworkSecurity.isBlockedIP(InetAddress.getByName("198.17.255.255")) shouldBe false
    NetworkSecurity.isBlockedIP(InetAddress.getByName("198.20.0.1")) shouldBe false
  }

  "NetworkSecurity.isBlockedHostname" should "block localhost" in {
    NetworkSecurity.isBlockedHostname("localhost") shouldBe true
    NetworkSecurity.isBlockedHostname("LOCALHOST") shouldBe true
    NetworkSecurity.isBlockedHostname("localhost.localdomain") shouldBe true
  }

  it should "block cloud metadata hostnames" in {
    NetworkSecurity.isBlockedHostname("metadata.google.internal") shouldBe true
    NetworkSecurity.isBlockedHostname("metadata.internal") shouldBe true
    NetworkSecurity.isBlockedHostname("sub.metadata.google.internal") shouldBe true
  }

  it should "allow external hostnames" in {
    NetworkSecurity.isBlockedHostname("example.com") shouldBe false
    NetworkSecurity.isBlockedHostname("api.github.com") shouldBe false
  }

  it should "support additional blocked hostnames" in {
    val additional = Set("evil.com", "malware.net")
    NetworkSecurity.isBlockedHostname("evil.com", additional) shouldBe true
    NetworkSecurity.isBlockedHostname("sub.evil.com", additional) shouldBe true
    NetworkSecurity.isBlockedHostname("malware.net", additional) shouldBe true
    NetworkSecurity.isBlockedHostname("good.com", additional) shouldBe false
  }

  "NetworkSecurity.validateUrl" should "reject localhost URLs" in {
    NetworkSecurity.validateUrl("http://localhost:8080/api") match {
      case Left(error) => error.message should include("localhost")
      case Right(_)    => fail("Expected localhost URL to be rejected")
    }
  }

  it should "reject cloud metadata URLs" in {
    NetworkSecurity.validateUrl("http://169.254.169.254/latest/meta-data/").isLeft shouldBe true
  }

  it should "reject internal IP URLs" in {
    NetworkSecurity.validateUrl("http://192.168.1.1/admin").isLeft shouldBe true
    NetworkSecurity.validateUrl("http://10.0.0.1/internal").isLeft shouldBe true
    NetworkSecurity.validateUrl("http://172.16.0.1/private").isLeft shouldBe true
  }

  it should "reject invalid protocols" in {
    NetworkSecurity.validateUrl("ftp://example.com/file.txt") match {
      case Left(error) => error.message should include("not allowed")
      case Right(_)    => fail("Expected FTP protocol to be rejected")
    }
  }

  it should "reject invalid URLs" in {
    NetworkSecurity.validateUrl("not-a-valid-url") match {
      case Left(error) =>
        // URL parsing may fail with different messages depending on the error
        error.message should (include("Invalid URL").or(include("URI is not absolute")))
      case Right(_) => fail("Expected invalid URL to be rejected")
    }
  }

  it should "reject URLs with empty host" in {
    NetworkSecurity.validateUrl("http:///path").isLeft shouldBe true
  }

  it should "accept custom allowed protocols" in {
    // FTP is blocked by default
    NetworkSecurity.validateUrl("ftp://8.8.8.8/file").isLeft shouldBe true
    // But allowed when explicitly permitted (still fails on DNS for hostname)
    NetworkSecurity.validateUrl("ftp://8.8.8.8/file", allowedProtocols = Set("ftp")).isRight shouldBe true
  }

  "NetworkSecurity.validateIP" should "reject blocked IPs" in {
    NetworkSecurity.validateIP("127.0.0.1").isLeft shouldBe true
    NetworkSecurity.validateIP("192.168.1.1").isLeft shouldBe true
    NetworkSecurity.validateIP("10.0.0.1").isLeft shouldBe true
    NetworkSecurity.validateIP("169.254.169.254").isLeft shouldBe true
  }

  it should "allow public IPs" in {
    NetworkSecurity.validateIP("8.8.8.8").isRight shouldBe true
    NetworkSecurity.validateIP("1.1.1.1").isRight shouldBe true
  }

  it should "reject invalid IP strings" in {
    NetworkSecurity.validateIP("not-an-ip") match {
      case Left(error) => error.message should include("Invalid IP")
      case Right(_)    => fail("Expected invalid IP to be rejected")
    }
  }

  "NetworkSecurity.isBlockedIP" should "handle IPv6 loopback" in {
    NetworkSecurity.isBlockedIP(InetAddress.getByName("::1")) shouldBe true
  }

  "NetworkSecurity constants" should "have correct cloud metadata IP" in {
    NetworkSecurity.CloudMetadataIP shouldBe "169.254.169.254"
  }

  it should "have default blocked hostnames" in {
    NetworkSecurity.DefaultBlockedHostnames should contain("localhost")
    NetworkSecurity.DefaultBlockedHostnames should contain("metadata.google.internal")
    NetworkSecurity.DefaultBlockedHostnames should contain("metadata.internal")
  }
}
