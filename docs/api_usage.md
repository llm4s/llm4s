# Using `llm4s`: Basic API Example

This document provides a concise guide on how to interact with the `llm4s` API to generate text using a Large Language Model (LLM).

## **📌 Prerequisites**
Ensure that you have the necessary dependencies installed before running the example.

```scala
// Add this to your `build.sbt` file
libraryDependencies += "com.llm4s" %% "llm4s-core" % "0.1.0"
```

## **🚀 Example: Generating Text using LLM**
```scala
import llm4s.LLMModel

object LLMExample {
  def main(args: Array[String]): Unit = {
    val model = LLMModel("gpt-4") 
    val prompt = "What are the benefits of Scala for AI development?"
    val response = model.generate(prompt)
    println(s"Response: $response")
  }
}
```

## **🛠 Running the Example**
Compile and execute the program using the following commands:

```bash
sbt compile
sbt run
```

---

✅ **This guide provides a simple way to get started with `llm4s`.** For more advanced usage, refer to the official documentation.  
