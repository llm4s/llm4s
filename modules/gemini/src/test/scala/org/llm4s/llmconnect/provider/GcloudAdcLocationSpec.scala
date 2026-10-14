package org.llm4s.llmconnect.provider

import com.typesafe.config.ConfigFactory
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
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

  "loadSettings" should "read the APPDATA and CLOUDSDK_CONFIG bindings" in {
    val source = ConfigSource.fromConfig(
      ConfigFactory.parseString("""llm4s.vertexai.adc { appData = "A", cloudSdkConfig = "C" }""")
    )
    loadSettings(source) shouldBe Right(Settings(Some("A"), Some("C")))
    loadSettings(ConfigSource.fromConfig(ConfigFactory.parseString("llm4s.vertexai.adc {}"))) shouldBe
      Right(Settings.empty)
  }

  "systemDefault" should "name the ADC file under a gcloud directory on this OS" in {
    val path = Paths.get(systemDefault())
    path.isAbsolute shouldBe true
    path.getFileName.toString shouldBe File
    val expectedDir =
      if (isWindows(System.getProperty("os.name"))) "gcloud" else "gcloud"
    // CLOUDSDK_CONFIG may relocate the directory; only its name under the default layouts is fixed
    val settings = loadSettings(ConfigSource.default).getOrElse(Settings.empty)
    if (settings.cloudSdkConfig.forall(_.trim.isEmpty))
      path.getParent.getFileName.toString shouldBe expectedDir
    systemDefault() shouldBe forThisPlatform(settings)
  }
}
