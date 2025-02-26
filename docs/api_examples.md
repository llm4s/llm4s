# 📚 Using `llm4s`: API Examples  

This document provides examples of how to use the `llm4s` API for basic LLM actions like text generation and token counting.

---

## **🔧 1⃣ Setup**  
Before using the API, ensure your project includes the required dependencies.

```scala
// Add this to your `build.sbt` file
libraryDependencies += "com.llm4s" %% "llm4s-core" % "0.1.0"
```

---

## **📝 2⃣ Example: Generate Text with LLM**  
This example demonstrates how to use `llm4s` to generate text from a given prompt.

```scala
import llm4s.LLMModel

object GenerateTextExample {
  def main(args: Array[String]): Unit = {
    val model = LLMModel("gpt-4")
    val prompt = "What are the key advantages of using Scala for AI development?"
    val response = model.generate(prompt)
    println(s"Generated Response: $response")
  }
}
```

---

## **🔢 3⃣ Example: Count Tokens in Text**  
Use `llm4s` to count the number of tokens in a given string.

```scala
import llm4s.TokenCounter

object TokenCountingExample {
  def main(args: Array[String]): Unit = {
    val text = "Scala is an excellent choice for AI!"
    val tokenCount = TokenCounter.countTokens(text)
    println(s"Token Count: $tokenCount")
  }
}
```

---

## **🚀 4⃣ Running the Examples**  
Compile and run the examples using `sbt`:

```bash
sbt compile
sbt run
```


