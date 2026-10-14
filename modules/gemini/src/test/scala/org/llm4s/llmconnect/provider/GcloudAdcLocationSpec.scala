package org.llm4s.llmconnect.provider

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import org.llm4s.config.VertexAIAdcConfigLoader
import pureconfig.ConfigSource

import java.nio.file.Paths

/** gcloud's well-known ADC file is located as Google's client libraries do (#1939). */
class GcloudAdcLocationSpec extends AnyFlatSpec with Matchers {
  import GcloudAdcLocation._

  private val File = "application_default_credentials.json"

  "wellKnownPath" should "use %APPDATA%\\gcloud on Windows" in {
    wellKnownPath("Windows 11", """C:\Users\me""", Some("""C:\Users\me\AppData\Roaming"""), None) shouldBe
      s"""C:\\Users\\me\\AppData\\Roaming\\gcloud\\$File"""
  }

  it should "use ~/.config/gcloud on Unix without CLOUDSDK_CONFIG" in {
    wellKnownPath("Linux", "/home/me", Some("ignored"), None) shouldBe s"/home/me/.config/gcloud/$File"
    wellKnownPath("Mac OS X", "/Users/me", None, Some("  ")) shouldBe s"/Users/me/.config/gcloud/$File"
  }

  it should "use CLOUDSDK_CONFIG on Unix when set" in {
    wellKnownPath("Linux", "/home/me", None, Some("/opt/gcloud-cfg")) shouldBe s"/opt/gcloud-cfg/$File"
  }

  it should "let CLOUDSDK_CONFIG win over APPDATA on Windows, as Google's libraries do" in {
    wellKnownPath("Windows 10", """C:\Users\me""", Some("""C:\AppData"""), Some("""D:\gcloud""")) shouldBe
      s"""D:\\gcloud\\$File"""
  }

  it should "fall back to home\\AppData\\Roaming on Windows without APPDATA" in {
    wellKnownPath("Windows 10", """C:\Users\me""", None, None) shouldBe
      s"""C:\\Users\\me\\AppData\\Roaming\\gcloud\\$File"""
  }

  "VertexAIAdcConfigLoader" should "read the APPDATA and CLOUDSDK_CONFIG bindings" in {
    val source = ConfigSource.fromConfig(
      ConfigFactory.parseString("""llm4s.vertexai.adc { appData = "A", cloudSdkConfig = "C" }""")
    )
    VertexAIAdcConfigLoader.loadSettings(source) shouldBe Right(Settings(Some("A"), Some("C")))
    VertexAIAdcConfigLoader.loadSettings(
      ConfigSource.fromConfig(ConfigFactory.parseString("llm4s.vertexai.adc {}"))
    ) shouldBe Right(Settings.empty)
  }

  it should "resolve the configured CLOUDSDK_CONFIG into the path, on this OS" in {
    val source = ConfigSource.fromConfig(
      ConfigFactory.parseString("""llm4s.vertexai.adc { cloudSdkConfig = "/opt/gcloud-cfg" }""")
    )
    VertexAIAdcConfigLoader.load(source) shouldBe Right(
      wellKnownPath(
        System.getProperty("os.name"),
        System.getProperty("user.home"),
        None,
        Some("/opt/gcloud-cfg")
      )
    )
  }

  it should "name the ADC file under a gcloud directory for the application's configuration on this OS" in {
    val path = Paths.get(VertexAIAdcConfigLoader.load().value)
    path.isAbsolute shouldBe true
    path.getFileName.toString shouldBe File
    // CLOUDSDK_CONFIG may relocate the directory; only its name under the default layouts is fixed
    val settings = VertexAIAdcConfigLoader.loadSettings(ConfigSource.default).value
    if (settings.cloudSdkConfig.forall(_.trim.isEmpty))
      path.getParent.getFileName.toString shouldBe "gcloud"
  }

  implicit private class ResultOps[A](r: org.llm4s.types.Result[A]) {
    def value: A = r.fold(e => fail(e.message), identity)
  }
}
