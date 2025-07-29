package $package;format="package"$

/**
 * This code is part of the Giter8 template llm4s.g8 in llm4s project, which provides a set standard template/archetype
 * for improve developer onboarding, creating new projects using the llm4s library.
 */

/** The Main object serves as the entry point for the application,
 * allowing users to run prompts against the LLM. */

object Main {
  def main(args: Array[String]): Unit = {
    val prompt: String = args.headOption.getOrElse("Explain what a Monad is in Scala")
    val result: String = PromptExecutor.run(prompt)
    println(result)
  }
}
