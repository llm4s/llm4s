package org.llm4s.configpolicy

/**
 * The doctor from a checkout of this repository, with every built-in provider module on the classpath:
 * `sbt "configPolicy/runMain org.llm4s.configpolicy.DoctorCli [options]"`. The doctor itself is
 * `org.llm4s.doctor.DoctorCli` in the published `llm4s-doctor`, which an application runs on its own classpath
 * (docs/reference/doctor.md); this keeps the in-repository command working.
 */
object DoctorCli {
  def main(args: Array[String]): Unit = org.llm4s.doctor.DoctorCli.main(args)
}
