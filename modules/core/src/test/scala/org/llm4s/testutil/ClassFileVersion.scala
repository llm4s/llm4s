package org.llm4s.testutil

import java.io.DataInputStream
import scala.util.Using

/**
 * Reads the class-file major version of a compiled class, for the specs that pin the bytecode level llm4s emits (#1493).
 *
 * The build gives the Scala compiler `-java-output-version 21` and `javac` `--release 21` (`Common.javaRelease`).
 * Without them the Scala classes were Java 8 bytecode (version 52) and the Java ones whatever the JDK running the
 * build emits, so the bytecode said nothing about the JDK 21 floor and a release built on a newer JDK would have
 * shipped Java classes that JDK 21 cannot load.
 */
object ClassFileVersion {

  /** The class-file major version of JDK 21, the minimum JDK of every llm4s artifact. */
  val Jdk21: Int = 65

  /** The major version in `cls`'s class file: the unsigned short after the magic number and the minor version. */
  def major(cls: Class[?]): Int = {
    val resource = cls.getName.replace('.', '/') + ".class"
    val in = Option(cls.getClassLoader.getResourceAsStream(resource))
      .getOrElse(sys.error(s"no class file $resource on the classpath"))
    Using.resource(new DataInputStream(in)) { data =>
      require(data.readInt() == 0xcafebabe, s"$resource is not a class file")
      data.readUnsignedShort() // minor version
      data.readUnsignedShort()
    }
  }
}
