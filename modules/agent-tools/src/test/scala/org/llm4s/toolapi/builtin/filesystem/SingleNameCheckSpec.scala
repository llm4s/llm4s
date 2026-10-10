package org.llm4s.toolapi.builtin.filesystem

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

import java.nio.file.{ Files, Path, Paths }
import scala.collection.mutable
import scala.util.Try

/**
 * The shortcut the shell tool takes for a value that is one plain path component (#1723): one existence lookup, and
 * the policy applied to the name's spelling when nothing is there. It must give exactly the verdict of the full
 * check, whatever the length of the name, so no file-system limit on name length is assumed; a name that exists, or
 * that is not one plain component on every platform, is resolved in full.
 */
class SingleNameCheckSpec extends AnyFlatSpec with Matchers {

  private def newRoot(): Path = Files.createTempDirectory("single-name").toRealPath()

  private val parent = PathPolicy.Readings(Paths.get("/w").toAbsolutePath, Paths.get("/p"), Paths.get("/l"))
  private val same   = PathPolicy.Readings(Paths.get("/w").toAbsolutePath, Paths.get("/w"), Paths.get("/w"))

  "PathPolicy.isSingleName" should "accept a plain name of any length" in {
    Seq("x", "lout", "in.txt", "a~1", "-flout", "n" * 255, "n" * 1000, "n" * 3000, "n" * 4094, "名前", "é")
      .foreach(name => withClue(name.take(20))(PathPolicy.isSingleName(name) shouldBe true))
  }

  it should "send everything else to the full check, Windows' special names included on every platform" in {
    Seq(
      "",
      ".",
      "..",
      "a/b",
      "a\\b",
      "C:x",        // drive-relative on Windows
      "x:stream",   // an alternate data stream
      "x::$DATA",
      "x.",         // Windows drops a trailing dot ...
      "x ",         // ... and a trailing space
      "CON",        // reserved device names, with or without an extension, in any case
      "nul.txt",
      "com1",
      "Lpt9.log",
      "AUX",
      "prn",
      "a*b",
      "a?b",
      "a\"b",
      "a<b",
      "a>b",
      "a|b",
      "a\u0000b",
      "a\tb",
      "a\u007fb",
      ("n" * 3000) + ":s",
      ("n" * 3000) + "."
    ).foreach(name => withClue(name.take(20))(PathPolicy.isSingleName(name) shouldBe false))
  }

  "PathPolicy.childReadings" should "append the name to every reading when nothing is there, however long it is" in {
    Seq("x", "n" * 1000, "n" * 3000, "n" * 4095).foreach { name =>
      val looked = mutable.ArrayBuffer.empty[Path]
      PathPolicy.childReadings(parent, name, p => { looked += p; false }) shouldBe PathPolicy.Child.Absent(
        PathPolicy.Readings(parent.spelled.resolve(name), Paths.get("/p").resolve(name), Paths.get("/l").resolve(name))
      )
      // One lookup in each reading's directory
      looked.toSeq shouldBe Seq(Paths.get("/p").resolve(name), Paths.get("/l").resolve(name))
    }
    parent.probes shouldBe 2
  }

  it should "look once when both readings of the parent agree" in {
    val looked = mutable.ArrayBuffer.empty[Path]
    PathPolicy.childReadings(same, "x", p => { looked += p; false }) shouldBe a[PathPolicy.Child.Absent]
    looked.toSeq shouldBe Seq(Paths.get("/w/x"))
    same.probes shouldBe 1
  }

  it should "resolve in full a name that exists in either reading, a long one included" in {
    // A file system may hold names of thousands of bytes (FUSE allows 4095 on recent Linux): an existing long name
    // is a link as likely as a short one, so it must be resolved
    Seq("lout", "n" * 1000, "n" * 3000, "n" * 4095).foreach { name =>
      withClue(name.take(20)) {
        PathPolicy.childReadings(parent, name, _ => true) shouldBe PathPolicy.Child.Resolve
        PathPolicy.childReadings(parent, name, _.startsWith("/l")) shouldBe PathPolicy.Child.Resolve
        PathPolicy.childReadings(same, name, _ => true) shouldBe PathPolicy.Child.Resolve
      }
    }
  }

  it should "resolve in full, without looking, a name that is not one plain component" in {
    Seq("..", "a/b", "C:x", "x.", "nul", ("n" * 3000) + ":s").foreach { name =>
      PathPolicy.childReadings(parent, name, _ => fail(s"looked up '$name'")) shouldBe PathPolicy.Child.Resolve
    }
  }

  it should "resolve in full when the lookup fails" in {
    PathPolicy.childReadings(parent, "x", _ => throw new SecurityException("denied")) shouldBe PathPolicy.Child.Resolve
  }

  it should "treat a dangling link as there, not absent" in {
    val root = newRoot()
    assume(Try(Files.createSymbolicLink(root.resolve("dangling"), root.resolve("nowhere"))).isSuccess, "no links")
    val readings = PathPolicy.readings(root).getOrElse(fail("root has no readings"))

    PathPolicy.childReadings(readings, "dangling") shouldBe PathPolicy.Child.Resolve
    PathPolicy.childReadings(readings, "nowhere") shouldBe a[PathPolicy.Child.Absent]
  }

  it should "give exactly the readings and verdict of the full check for an absent name" in {
    // root/l -> root/a/b, so root/l/.. is root/a physically and root lexically: the two readings differ
    val root = newRoot()
    Files.createDirectories(root.resolve("a/b"))
    Files.writeString(root.resolve("a/here"), "x")
    assume(Try(Files.createSymbolicLink(root.resolve("l"), root.resolve("a/b"))).isSuccess, "no links")
    val base     = root.resolve("l/..")
    val readings = PathPolicy.readings(base).getOrElse(fail("base has no readings"))
    readings.physical shouldBe root.resolve("a")
    readings.lexical shouldBe root

    val policies = Seq(
      PathPolicy.prepare(Some(Seq(root.toString)), Seq.empty),
      PathPolicy.prepare(Some(Seq(root.toString)), Seq(root.resolve("a/absent").toString)),
      PathPolicy.prepare(Some(Seq(root.toString)), Seq(root.resolve("absent").toString)),
      PathPolicy.prepare(Some(Seq(root.resolve("a").toString)), Seq.empty),
      PathPolicy.prepare(None, Seq(root.resolve("a").toString))
    )
    Seq("absent", "n" * 200, "other.txt").foreach { name =>
      val child = PathPolicy.childReadings(readings, name) match {
        case PathPolicy.Child.Absent(r) => r
        case other                      => fail(s"'$name' gave $other")
      }
      child shouldBe PathPolicy.readings(base.resolve(name)).getOrElse(fail(s"'$name' has no readings"))
      policies.foreach { entries =>
        PathPolicy.permits(child, entries) shouldBe PathPolicy.resolve(base.resolve(name), entries).isDefined
      }
    }
    // Present in the physical reading only: resolved in full
    PathPolicy.childReadings(readings, "here") shouldBe PathPolicy.Child.Resolve
  }
}
