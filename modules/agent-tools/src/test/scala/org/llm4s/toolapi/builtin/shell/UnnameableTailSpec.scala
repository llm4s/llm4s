package org.llm4s.toolapi.builtin.shell

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

/**
 * The boundary of the tails `ShellTool` skips as too long to name a file (#1723). Names over 255 units cannot be
 * created on ext4, APFS or NTFS, so a test cannot plant a 1000-character link where it runs; the predicate itself is
 * tested instead. OpenZFS 2.3+ with `longname=on` allows 1023 bytes and Linux FUSE 1024, so a tail of up to 1024
 * plain characters must still be checked.
 */
class UnnameableTailSpec extends AnyFlatSpec with Matchers {

  private def skipped(value: String): Boolean = ShellTool.unnameable(value)(0)

  "ShellTool's long-tail skip" should "assume no file system allows a name over 1024 units" in {
    ShellTool.MaxNameLength shouldBe 1024
    ShellTool.MinUnnameableRun shouldBe 1025
  }

  it should "check a plain tail of 1024 characters, the longest name FUSE allows" in {
    skipped("n" * 1024) shouldBe false
    skipped("a1-_+=,@" * 128) shouldBe false
  }

  it should "check a plain tail of about 1000 characters, which ZFS longname or FUSE can hold" in {
    skipped("n" * 1000) shouldBe false
    skipped("n" * 1023) shouldBe false
    skipped("n" * 256) shouldBe false
  }

  it should "skip a plain tail of 1025 characters or more" in {
    skipped("n" * 1025) shouldBe true
    skipped("n" * 4094) shouldBe true
    // Whatever follows the run does not matter: the name is already over every limit
    skipped(("n" * 1025) + "~.:́") shouldBe true
  }

  it should "check a tail whose plain run is cut short by another character" in {
    skipped(("n" * 1024) + "~" + ("n" * 3000)) shouldBe false
    skipped(("n" * 1024) + "́" + ("n" * 3000)) shouldBe false
  }

  it should "check a tail holding a separator, however long its plain run" in {
    skipped(("n" * 2000) + "/x") shouldBe false
    skipped(("n" * 2000) + "\\x") shouldBe false
    // A tail after the last separator is one component again
    ShellTool.unnameable("x/" + ("n" * 1025))(2) shouldBe true
    ShellTool.unnameable("x/" + ("n" * 1024))(2) shouldBe false
  }

  it should "judge each start of a value by the run from it" in {
    val value = "-" + ("i" * 1100)
    val skip  = ShellTool.unnameable(value)
    skip(1) shouldBe true                    // 1100 plain characters
    skip(value.length - 1025) shouldBe true  // exactly 1025
    skip(value.length - 1024) shouldBe false // 1024, as long as a FUSE name
    skip(value.length - 1) shouldBe false
  }
}
