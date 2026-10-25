package org.llm4s.spring

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.beans.Introspector
import java.time.Duration
import scala.io.Source
import scala.util.Using

/**
 * `META-INF/spring-configuration-metadata.json` (#1467), which IDEs read for completion of `llm4s.*` in
 * `application.properties` and `application.yml`. Scala has no Spring annotation processor, so the file is written by
 * hand; this spec keeps it in step with the bound properties - every one listed, with its type and default, and
 * nothing listed that does not bind.
 */
class ConfigurationMetadataSpec extends AnyFlatSpec with Matchers {

  private lazy val metadata: ujson.Value =
    Using.resource(Source.fromResource("META-INF/spring-configuration-metadata.json"))(s => ujson.read(s.mkString))

  private lazy val listed: Map[String, ujson.Value] =
    metadata("properties").arr.map(p => p("name").str -> p).toMap

  /** kebab-case, as Spring's relaxed binding and the metadata spell a property. */
  private def kebab(name: String): String = name.replaceAll("([A-Z])", "-$1").toLowerCase

  /** Every leaf property bound under `prefix` from a `@BeanProperty` class: name -> (type, default). */
  private def bound(prefix: String, instance: AnyRef): Map[String, (Class[?], Any)] =
    Introspector
      .getBeanInfo(instance.getClass, classOf[Object])
      .getPropertyDescriptors
      .toList
      .filter(d => d.getReadMethod != null && d.getWriteMethod != null)
      .flatMap { d =>
        val name  = s"$prefix.${kebab(d.getName)}"
        val value = d.getReadMethod.invoke(instance)
        d.getPropertyType match {
          case t if t == classOf[AsyncProperties] || t == classOf[HealthProperties] => bound(name, value).toList
          case t                                                                    => List(name -> (t, value))
        }
      }
      .toMap

  private lazy val properties = bound("llm4s", new Llm4sProperties)

  private def boxed(c: Class[?]): String = c match {
    case java.lang.Boolean.TYPE => "java.lang.Boolean"
    case java.lang.Integer.TYPE => "java.lang.Integer"
    case other                  => other.getName
  }

  "The configuration metadata" should "list every bound llm4s property with its type" in {
    properties.keySet should not be empty
    properties.foreach { case (name, (tpe, _)) =>
      withClue(name) {
        listed.keySet should contain(name)
        listed(name)("type").str shouldBe boxed(tpe)
        listed(name)("description").str should not be empty
      }
    }
  }

  it should "give each property the default it binds to, and none for an empty string" in {
    properties.foreach { case (name, (_, default)) =>
      withClue(name) {
        val listedDefault = listed(name).obj.get("defaultValue")
        default match {
          case ""          => listedDefault shouldBe None
          case b: Boolean  => listedDefault shouldBe Some(ujson.Bool(b))
          case i: Int      => listedDefault shouldBe Some(ujson.Num(i))
          case d: Duration => listedDefault.map(_.str) shouldBe Some(s"${d.getSeconds}s")
          case other       => fail(s"no rule for a default of ${other.getClass}")
        }
      }
    }
  }

  it should "list nothing else but the llm4s.providers, llm4s.credentials and llm4s.http blocks" in {
    (listed.keySet -- properties.keySet) shouldBe Set(
      "llm4s.providers.provider",
      "llm4s.providers",
      "llm4s.credentials"
    ) ++ Set(
      "llm4s.http.proxy.url",
      "llm4s.http.proxy.username",
      "llm4s.http.proxy.password",
      "llm4s.http.proxy.non-proxy-hosts",
      "llm4s.http.tls.trust-store",
      "llm4s.http.tls.trust-store-password",
      "llm4s.http.tls.key-store",
      "llm4s.http.tls.key-store-password"
    )
  }

  it should "name a group for each properties class, and hint only flat-form providers" in {
    metadata("groups").arr.map(_("name").str).toSet shouldBe Set("llm4s", "llm4s.async", "llm4s.health")
    val hints = metadata("hints").arr.find(_("name").str == "llm4s.provider").getOrElse(fail("no hint"))
    hints("values").arr.map(_("value").str).toSet shouldBe Set("openai", "anthropic", "ollama")
  }
}
