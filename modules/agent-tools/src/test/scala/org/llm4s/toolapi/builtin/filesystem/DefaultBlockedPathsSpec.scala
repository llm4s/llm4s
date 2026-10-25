package org.llm4s.toolapi.builtin.filesystem

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path }

/**
 * The default `blockedPaths` of the file tools on Linux, macOS and Windows, and the case-insensitive match of blocked
 * entries (issue #1408, finding F6).
 *
 * The lists themselves are checked for every platform wherever the suite runs. The checks against the real file
 * system run only on the platform they are about; the suite is in `agentTools`, which CI's Windows job runs whole.
 * Nothing here reads or writes a file under the user's home: a path is judged without being opened.
 */
class DefaultBlockedPathsSpec extends AnyFlatSpec with Matchers {

  import FileConfig.Platform

  private val platform = Platform.current
  private def home     = FileConfig.userHome.getOrElse(cancel("user.home is not set"))

  private def blocked(path: Path): Boolean = !FileConfig().isPathAllowed(path)

  private def newRoot(): Path = Files.createTempDirectory("default-blocked").toRealPath()

  "Platform.fromOsName" should "tell Windows, macOS and other POSIX systems apart" in {
    Platform.fromOsName("Windows 11") shouldBe Platform.Windows
    Platform.fromOsName("Windows Server 2022") shouldBe Platform.Windows
    Platform.fromOsName("Mac OS X") shouldBe Platform.MacOS
    Platform.fromOsName("Darwin") shouldBe Platform.MacOS
    Platform.fromOsName("Linux") shouldBe Platform.Posix
    Platform.fromOsName("FreeBSD") shouldBe Platform.Posix
    Platform.fromOsName("") shouldBe Platform.Posix
  }

  "The Linux defaults" should "keep the previous entries first and add system and home credential locations" in {
    val paths = FileConfig.defaultBlockedPaths(Platform.Posix, Some("/home/alice"))
    paths.take(5) shouldBe Seq("/etc", "/var", "/sys", "/proc", "/dev")
    (paths should contain).allOf("/boot", "/root", "/run")
    (paths should contain).allOf(
      "/home/alice/.ssh",
      "/home/alice/.gnupg",
      "/home/alice/.aws",
      "/home/alice/.azure",
      "/home/alice/.config/gcloud",
      "/home/alice/.kube",
      "/home/alice/.docker",
      "/home/alice/.netrc",
      "/home/alice/.git-credentials",
      "/home/alice/.local/share/keyrings"
    )
    paths.filter(_.contains("\\")) shouldBe empty
    paths should not contain "/home/alice/Library/Keychains"
  }

  "The macOS defaults" should "add the /private real paths and the keychains" in {
    val paths = FileConfig.defaultBlockedPaths(Platform.MacOS, Some("/Users/alice/"))
    paths.take(5) shouldBe Seq("/etc", "/var", "/sys", "/proc", "/dev")
    (paths should contain).allOf("/private/etc", "/private/var", "/Library/Keychains")
    (paths should contain).allOf("/Users/alice/.ssh", "/Users/alice/.aws", "/Users/alice/Library/Keychains")
    paths should not contain "/run"
    paths should not contain "/Users/alice/.local/share/keyrings"
    paths.filter(_.contains("//")) shouldBe empty
  }

  "The Windows defaults" should "be Windows paths: the system directory, machine keys and the user's credential stores" in {
    val paths = FileConfig.defaultBlockedPaths(Platform.Windows, Some("C:\\Users\\alice"))
    (paths should contain).allOf("C:\\Windows", "C:\\ProgramData\\Microsoft\\Crypto", "C:\\ProgramData\\ssh")
    (paths should contain).allOf(
      "C:\\Users\\alice\\.ssh",
      "C:\\Users\\alice\\.aws",
      "C:\\Users\\alice\\.config\\gcloud",
      "C:\\Users\\alice\\_netrc",
      "C:\\Users\\alice\\NTUSER.DAT",
      "C:\\Users\\alice\\AppData\\Roaming\\Microsoft\\Credentials",
      "C:\\Users\\alice\\AppData\\Local\\Microsoft\\Credentials",
      "C:\\Users\\alice\\AppData\\Roaming\\Microsoft\\Protect",
      "C:\\Users\\alice\\AppData\\Roaming\\gcloud"
    )
    paths.filter(_.startsWith("/")) shouldBe empty
    paths.filter(_.contains("/")) shouldBe empty
  }

  "The defaults without a home directory" should "hold only the system locations" in {
    FileConfig.defaultBlockedPaths(Platform.Posix, None) shouldBe
      Seq("/etc", "/var", "/sys", "/proc", "/dev", "/boot", "/root", "/run")
    FileConfig.defaultBlockedPaths(Platform.Windows, None) shouldBe
      Seq("C:\\Windows", "C:\\ProgramData\\Microsoft\\Crypto", "C:\\ProgramData\\ssh")
  }

  "FileConfig.DefaultBlockedPaths" should "be the list for the platform the JVM runs on" in {
    FileConfig.DefaultBlockedPaths shouldBe FileConfig.defaultBlockedPaths(platform, Some(home))
    FileConfig().blockedPaths shouldBe FileConfig.DefaultBlockedPaths
  }

  "A tool description" should "write the home directory as ~, so the account name is not sent to the model" in {
    FileConfig.describe(Seq("/etc", "/home/alice/.ssh", "/home/alicex/.ssh"), Some("/home/alice/")) shouldBe
      "/etc, ~/.ssh, /home/alicex/.ssh"
    FileConfig.describe(Seq("C:\\Users\\alice\\.ssh"), Some("C:\\Users\\alice")) shouldBe "~\\.ssh"
    FileConfig.describe(Seq("/etc"), None) shouldBe "/etc"

    val description = ReadFileTool.createSafe().fold(e => fail(e.formatted), identity).description
    description should include("~")
    if (home.length > 3) (description should not).include(home)
  }

  "The default config" should "block the credential directories under the user's home, whatever their case" in {
    val h = Path.of(home)
    blocked(h.resolve(".ssh").resolve("id_rsa")) shouldBe true
    blocked(h.resolve(".SSH").resolve("id_rsa")) shouldBe true
    blocked(h.resolve(".aws").resolve("credentials")) shouldBe true
    blocked(h.resolve(".config").resolve("gcloud").resolve("credentials.db")) shouldBe true
    blocked(h.resolve(".kube").resolve("config")) shouldBe true
    blocked(h.resolve(".git-credentials")) shouldBe true
  }

  it should "still allow an ordinary file outside the blocked locations" in {
    // Under target/, which is not blocked on any platform (the system temp directory is under /var on macOS).
    val target = Path.of(System.getProperty("user.dir"), "target", "default-blocked", "notes.txt")
    FileConfig().isPathAllowed(target) shouldBe true
    FileConfig().isPathAllowed(Path.of(home).resolve(".sshkeys-notes.txt")) shouldBe true
  }

  "On Linux the default config" should "block /proc, /sys, /run, /boot and /root" in {
    assume(platform == Platform.Posix, "Linux and other POSIX systems only")
    blocked(Path.of("/proc/self/environ")) shouldBe true
    blocked(Path.of("/proc/self/root/etc/shadow")) shouldBe true
    blocked(Path.of("/sys/kernel/notes")) shouldBe true
    blocked(Path.of("/run/secrets/db-password")) shouldBe true
    blocked(Path.of("/boot/grub/grub.cfg")) shouldBe true
    blocked(Path.of("/root/.bashrc")) shouldBe true
    blocked(Path.of("/etc/shadow")) shouldBe true
    blocked(Path.of(home).resolve(".local/share/keyrings/login.keyring")) shouldBe true
  }

  "On macOS the default config" should "block /private, a differently cased /etc and the keychains" in {
    assume(platform == Platform.MacOS, "macOS only")
    blocked(Path.of("/private/etc/hosts")) shouldBe true
    blocked(Path.of("/etc/hosts")) shouldBe true
    blocked(Path.of("/ETC/hosts")) shouldBe true
    blocked(Path.of("/Private/Var/db/x")) shouldBe true
    blocked(Path.of("/Library/Keychains/System.keychain")) shouldBe true
    blocked(Path.of(home).resolve("Library/Keychains/login.keychain-db")) shouldBe true
    blocked(Path.of(home).resolve("library/keychains/login.keychain-db")) shouldBe true
  }

  "On Windows the default config" should "block the system directory and the credential stores, whatever their case" in {
    assume(platform == Platform.Windows, "Windows only")
    blocked(Path.of("C:\\Windows\\System32\\config\\SAM")) shouldBe true
    blocked(Path.of("c:\\windows\\system32\\drivers\\etc\\hosts")) shouldBe true
    blocked(Path.of("C:/Windows/win.ini")) shouldBe true
    blocked(Path.of("C:\\ProgramData\\ssh\\ssh_host_ed25519_key")) shouldBe true
    blocked(Path.of("C:\\ProgramData\\Microsoft\\Crypto\\RSA\\MachineKeys\\k")) shouldBe true
    val h = Path.of(home)
    blocked(h.resolve("NTUSER.DAT")) shouldBe true
    blocked(h.resolve("ntuser.dat")) shouldBe true
    blocked(h.resolve("AppData\\Roaming\\Microsoft\\Credentials\\x")) shouldBe true
    blocked(h.resolve("appdata\\local\\microsoft\\credentials\\x")) shouldBe true
    blocked(h.resolve("AppData\\Roaming\\Microsoft\\Protect\\S-1-5-21\\key")) shouldBe true
    blocked(h.resolve(".ssh\\id_ed25519")) shouldBe true
    blocked(h.resolve("AppData\\Local\\Temp\\notes.txt")) shouldBe false
  }

  "A blocked entry" should "block a differently cased spelling of a path that does not exist yet" in {
    val t = newRoot()
    Files.createDirectories(t.resolve("blocked"))
    val config = FileConfig(blockedPaths = Seq(t.resolve("blocked").toString, t.resolve("missing").toString))
    config.isPathAllowed(t.resolve("BLOCKED").resolve("new.txt")) shouldBe false
    config.isPathAllowed(t.resolve("Missing").resolve("x")) shouldBe false
    config.isPathAllowed(t.resolve("blocked-not").resolve("x")) shouldBe true
    config.isPathAllowed(t.resolve("other.txt")) shouldBe true
  }

  it should "block an existing file read through a differently cased directory name" in {
    val t      = newRoot()
    val secret = Files.writeString(Files.createDirectories(t.resolve("blocked")).resolve("s.txt"), "secret")
    val config = FileConfig(blockedPaths = Seq(t.resolve("blocked").toString))
    config.isPathAllowed(secret) shouldBe false
    config.isPathAllowed(t.resolve("BLOCKED").resolve("s.txt")) shouldBe false
    config.isPathAllowed(t.resolve("Blocked").resolve("S.TXT")) shouldBe false
  }

  "PathPolicy.within" should "compare whole components without regard to case or Unicode normalisation" in {
    val t = newRoot()
    PathPolicy.within(t.resolve("A").resolve("b"), t.resolve("a")) shouldBe true
    PathPolicy.within(t.resolve("a"), t.resolve("A")) shouldBe true
    PathPolicy.within(t.resolve("ab").resolve("c"), t.resolve("a")) shouldBe false
    PathPolicy.within(t, t.resolve("a")) shouldBe false
    PathPolicy.within(t.resolve("e\u0301").resolve("x"), t.resolve("\u00e9")) shouldBe true
    PathPolicy.within(t.resolve("x"), Path.of("relative")) shouldBe false
  }
}
