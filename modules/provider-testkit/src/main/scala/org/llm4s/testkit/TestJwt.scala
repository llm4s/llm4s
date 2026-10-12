package org.llm4s.testkit

import java.nio.charset.StandardCharsets
import java.security.{ KeyPairGenerator, Signature }
import java.security.spec.ECGenParameterSpec
import java.time.Instant
import java.util.{ Base64, UUID }
import scala.concurrent.duration.*

/** Signed JWTs for tests that need a realistic subject token, using the JDK only. */
object TestJwt:

  private val keys =
    val generator = KeyPairGenerator.getInstance("EC")
    generator.initialize(new ECGenParameterSpec("secp256r1"))
    generator.generateKeyPair()

  private def b64(bytes: Array[Byte]): String = Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  /** An ES256 JWT with `sub`, `aud`, `iss`, `iat`, `exp` and a unique `jti`. */
  def es256(
    subject: String,
    audience: String,
    lifetime: FiniteDuration = 5.minutes,
    issuer: String = "https://spire.llm4s.test"
  ): String =
    val now    = Instant.now().getEpochSecond
    val header = b64("""{"alg":"ES256","typ":"JWT"}""".getBytes(StandardCharsets.UTF_8))
    val payload = b64(
      ujson
        .Obj(
          "sub" -> subject,
          "aud" -> ujson.Arr(audience),
          "iss" -> issuer,
          // ujson writes a Scala Long as a JSON string; a JWT's numeric dates must be numbers.
          "iat" -> ujson.Num(now.toDouble),
          "exp" -> ujson.Num((now + lifetime.toSeconds).toDouble),
          "jti" -> UUID.randomUUID().toString
        )
        .render()
        .getBytes(StandardCharsets.UTF_8)
    )
    val signer = Signature.getInstance("SHA256withECDSAinP1363Format")
    signer.initSign(keys.getPrivate)
    signer.update(s"$header.$payload".getBytes(StandardCharsets.US_ASCII))
    s"$header.$payload.${b64(signer.sign())}"
