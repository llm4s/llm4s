package org.llm4s.rag.loader

import org.llm4s.error.NetworkError
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.net.{ InetAddress, URI }

class UrlPolicySpec extends AnyFlatSpec with Matchers {

  private val publicIp = InetAddress.getByName("93.184.215.14")

  /** Resolves every name to `ip`, so no test needs the network. */
  private def dns(ip: String): String => Either[NetworkError, Seq[InetAddress]] =
    _ => Right(Seq(InetAddress.getByName(ip)))

  private def resolve(policy: UrlPolicy, url: String) = policy.resolve(new URI(url))

  private def message(result: Either[NetworkError, ?]): String =
    result.left.toOption.map(_.message).getOrElse(fail(s"expected a refusal, got $result"))

  "UrlPolicy.default" should "refuse loopback, private and metadata addresses" in {
    message(resolve(UrlPolicy.default, "http://127.0.0.1/x")) should include("blocked range")
    message(resolve(UrlPolicy.default, "http://10.1.2.3/x")) should include("blocked range")
    message(resolve(UrlPolicy.default, "http://169.254.169.254/latest")) should include("blocked range")
    message(resolve(UrlPolicy.default, "http://[::1]/x")) should include("blocked range")
  }

  it should "refuse localhost and the metadata host names before resolving them" in {
    message(resolve(UrlPolicy.default, "http://localhost/x")) should include("is blocked")
    message(resolve(UrlPolicy.default, "http://metadata.google.internal/x")) should include("is blocked")
  }

  it should "refuse a public name that resolves to a private address" in {
    val policy = UrlPolicy.default.withResolver(dns("192.168.1.10"))
    message(resolve(policy, "https://intranet.example.com/")) should include("192.168.1.10")
  }

  it should "return the resolved addresses of a public host" in {
    val policy = UrlPolicy.default.withResolver(_ => Right(Seq(publicIp)))
    resolve(policy, "https://example.com/page") shouldBe Right(Seq(publicIp))
  }

  it should "refuse other schemes and URLs without a host" in {
    message(resolve(UrlPolicy.default, "file:///etc/passwd")) should include("Protocol 'file' is not allowed")
    message(resolve(UrlPolicy.default, "ftp://example.com/")) should include("Protocol 'ftp'")
    message(resolve(UrlPolicy.default, "http:///nohost")) should include("no host")
  }

  it should "refuse a host that resolves to nothing, or fails to resolve" in {
    message(resolve(UrlPolicy.default.withResolver(_ => Right(Seq.empty)), "http://example.com/")) should include(
      "no address"
    )
    message(resolve(UrlPolicy.default, "http://no-such-host.invalid/")) should include("DNS resolution failed")
  }

  "withAllowedHosts" should "admit only the listed hosts and their subdomains" in {
    val policy = UrlPolicy.default.withResolver(_ => Right(Seq(publicIp))).withAllowedHosts(Set("Docs.Example.com"))
    resolve(policy, "https://docs.example.com/a").isRight shouldBe true
    resolve(policy, "https://api.docs.example.com/a").isRight shouldBe true
    message(resolve(policy, "https://example.com/a")) should include("not in the allowed hosts")
    message(resolve(policy, "https://evildocs.example.com/a")) should include("not in the allowed hosts")
  }

  it should "still refuse an allowed host that resolves to a private address" in {
    val policy = UrlPolicy.default.withResolver(dns("10.0.0.5")).withAllowedHosts(Set("docs.example.com"))
    message(resolve(policy, "https://docs.example.com/")) should include("blocked range")
  }

  "withBlockedHosts" should "refuse the listed hosts and their subdomains" in {
    val policy = UrlPolicy.default.withResolver(_ => Right(Seq(publicIp))).withBlockedHosts(Set("ads.example.com"))
    message(resolve(policy, "https://ads.example.com/")) should include("is blocked")
    message(resolve(policy, "https://x.ads.example.com/")) should include("is blocked")
    resolve(policy, "https://example.com/").isRight shouldBe true
  }

  it should "match an absolute name with its trailing dot, and an entry written with one" in {
    val policy = UrlPolicy.default
      .withResolver(_ => Right(Seq(publicIp)))
      .withBlockedHosts(Set("ads.example.com", "tracker.example."))
    message(resolve(policy, "https://ads.example.com./")) should include("is blocked")
    message(resolve(policy, "https://tracker.example/")) should include("is blocked")
    message(resolve(UrlPolicy.default, "http://localhost./")) should include("is blocked")
    val allowed = UrlPolicy.default.withResolver(_ => Right(Seq(publicIp))).withAllowedHosts(Set("docs.example.com"))
    resolve(allowed, "https://docs.example.com./").isRight shouldBe true
  }

  "withAllowPrivateNetworks" should "admit loopback, private addresses and localhost" in {
    val policy = UrlPolicy.default.withAllowPrivateNetworks(true)
    resolve(policy, "http://127.0.0.1:8080/").isRight shouldBe true
    resolve(policy, "http://10.1.2.3/").isRight shouldBe true
    resolve(policy, "http://localhost/").isRight shouldBe true
    resolve(policy.withResolver(dns("192.168.1.10")), "http://wiki.corp/").isRight shouldBe true
  }

  it should "still refuse the cloud metadata endpoint, by address and by name" in {
    val policy = UrlPolicy.default.withAllowPrivateNetworks(true)
    message(resolve(policy, "http://169.254.169.254/latest")) should include("blocked range")
    message(resolve(policy, "http://[fd00:ec2::254]/latest")) should include("blocked range")
    // the IPv4 metadata address carried in NAT64, IPv4-mapped and 6to4 IPv6 addresses
    message(resolve(policy, "http://[64:ff9b::a9fe:a9fe]/latest/meta-data/")) should include("blocked range")
    message(resolve(policy, "http://[::ffff:169.254.169.254]/latest")) should include("blocked range")
    message(resolve(policy, "http://[2002:a9fe:a9fe::1]/latest")) should include("blocked range")
    resolve(policy, "http://[64:ff9b::a00:1]/").isRight shouldBe true // NAT64 to 10.0.0.1: private, allowed
    message(resolve(policy, "http://metadata.google.internal/")) should include("is blocked")
    message(resolve(policy.withResolver(dns("169.254.169.254")), "http://rebound.example.com/")) should include(
      "blocked range"
    )
  }

  it should "still apply the extra blocked hosts" in {
    val policy = UrlPolicy.default.withAllowPrivateNetworks(true).withBlockedHosts(Set("vault.corp"))
    message(resolve(policy.withResolver(dns("10.0.0.9")), "http://vault.corp/")) should include("is blocked")
  }

  "withAllowedSchemes" should "narrow the schemes" in {
    val policy = UrlPolicy.default.withResolver(_ => Right(Seq(publicIp))).withAllowedSchemes(Set("HTTPS"))
    message(resolve(policy, "http://example.com/")) should include("Protocol 'http' is not allowed")
    resolve(policy, "https://example.com/").isRight shouldBe true
  }

  "UrlPolicy.Standard" should "have the documented defaults and show its rules" in {
    val policy = UrlPolicy.Standard()
    policy.allowedHosts shouldBe empty
    policy.blockedHosts shouldBe empty
    policy.allowPrivateNetworks shouldBe false
    policy.allowedSchemes shouldBe Set("http", "https")
    policy.toString should include("allowPrivateNetworks=false")
  }

  "a custom UrlPolicy" should "be a single method" in {
    val refuseAll: UrlPolicy = _ => Left(NetworkError("no", None, "ssrf-protection"))
    UrlLoader("https://example.com/").withUrlPolicy(refuseAll).load().next() shouldBe a[LoadResult.Failure]
  }
}
