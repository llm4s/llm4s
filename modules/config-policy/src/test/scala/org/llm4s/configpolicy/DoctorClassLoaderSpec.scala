package org.llm4s.configpolicy

import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpec

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.{ Files, Path }
import scala.jdk.CollectionConverters.*
import scala.util.Using

/**
 * `DoctorCli.classLoaderFor` splits `--classpath` on the platform's path separator (`;` on Windows, `:` elsewhere)
 * and turns each entry into a URL. On Windows an entry has a drive letter, which a `:` split would cut in two, and
 * a path may hold a space or a backslash; every case below builds its classpath with `File.pathSeparator` and
 * absolute paths from the temp directory, so on Windows they are drive-letter paths.
 */
class DoctorClassLoaderSpec extends AnyWordSpec with Matchers {

  private val separator = File.pathSeparator

  private def loaderFor(classpath: String): java.net.URLClassLoader =
    DoctorCli.classLoaderFor(classpath).fold(e => fail(s"expected a class loader, got: $e"), identity)

  private def withDir[A](prefix: String)(body: Path => A): A = {
    val dir = Files.createTempDirectory(prefix)
    try body(dir)
    finally
      Using.resource(Files.walk(dir))(_.iterator().asScala.toList.reverse.foreach(p => Files.deleteIfExists(p): Unit))
  }

  private def resourceIn(dir: Path, name: String, content: String): Path = {
    val file = dir.resolve(name)
    Files.createDirectories(file.getParent)
    Files.write(file, content.getBytes(StandardCharsets.UTF_8))
  }

  private def readAll(url: java.net.URL): String =
    Using.resource(url.openStream())(s => new String(s.readAllBytes(), StandardCharsets.UTF_8))

  "DoctorCli.classLoaderFor" should {

    "find resources in each directory of a classpath joined with the platform's separator" in
      withDir("llm4s-doctor-a") { a =>
        withDir("llm4s-doctor-b") { b =>
          resourceIn(a, "only-a.txt", "from a")
          resourceIn(b, "only-b.txt", "from b")
          val loader = loaderFor(a.toAbsolutePath.toString + separator + b.toAbsolutePath.toString)
          Using.resource(loader) { l =>
            (l.getURLs should have).length(2)
            readAll(l.getResource("only-a.txt")) shouldBe "from a"
            readAll(l.getResource("only-b.txt")) shouldBe "from b"
          }
        }
      }

    "keep a drive-letter or otherwise absolute entry whole, in the path it names" in
      withDir("llm4s-doctor-abs") { dir =>
        resourceIn(dir, "marker.txt", "here")
        val entry = dir.toAbsolutePath.toString
        // On Windows `entry` is `C:\...`: splitting on `:` would leave `C` and `\...`.
        if (File.separatorChar == '\\') entry.charAt(1) shouldBe ':'
        val loader = loaderFor(entry)
        Using.resource(loader) { l =>
          l.getURLs.map(u => Path.of(u.toURI)).toList shouldBe List(dir.toAbsolutePath)
          readAll(l.getResource("marker.txt")) shouldBe "here"
        }
      }

    "accept a path with a space in it" in
      withDir("llm4s doctor spaced") { dir =>
        resourceIn(dir, "marker.txt", "spaced")
        val loader = loaderFor(dir.toAbsolutePath.toString)
        Using.resource(loader)(l => readAll(l.getResource("marker.txt")) shouldBe "spaced")
      }

    "accept a jar entry as well as a directory" in
      withDir("llm4s-doctor-jar") { dir =>
        val jar = dir.resolve("lib.jar")
        Using.resource(new java.util.jar.JarOutputStream(Files.newOutputStream(jar))) { out =>
          out.putNextEntry(new java.util.zip.ZipEntry("in-jar.txt"))
          out.write("jarred".getBytes(StandardCharsets.UTF_8))
          out.closeEntry()
        }
        val loader = loaderFor(jar.toAbsolutePath.toString)
        Using.resource(loader)(l => readAll(l.getResource("in-jar.txt")) shouldBe "jarred")
      }

    "ignore blank entries around the separators, and trim the rest" in
      withDir("llm4s-doctor-blank") { dir =>
        resourceIn(dir, "marker.txt", "x")
        val entry  = dir.toAbsolutePath.toString
        val loader = loaderFor(separator + " " + entry + " " + separator + separator)
        Using.resource(loader)(_.getURLs should have length 1)
      }

    "find resources on the given classpath only, not on the doctor's own" in
      withDir("llm4s-doctor-own") { dir =>
        val loader = loaderFor(dir.toAbsolutePath.toString)
        Using.resource(loader) { l =>
          // reference.conf is on the doctor's classpath (core's), and is not on this one.
          l.getResources("reference.conf").hasMoreElements shouldBe false
        }
      }

    "load classes from the doctor's own classpath first" in
      withDir("llm4s-doctor-parent") { dir =>
        val loader = loaderFor(dir.toAbsolutePath.toString)
        Using.resource(loader)(_.loadClass(DoctorCli.getClass.getName) shouldBe DoctorCli.getClass)
      }

    "refuse an entry that is not a path, and name it" in {
      // NUL is not a legal path character on any platform.
      val bad = DoctorCli.classLoaderFor("bad\u0000entry")
      bad.left.toOption.get should (include("--classpath").and(include("bad")))
    }
  }
}
