# Java + Gradle Quickstart

A minimal, standalone example of using LLM4S from Java via Gradle.

## Prerequisites
- Java 11+
- [Gradle](https://gradle.org/install/) (or use the provided wrapper)
- An OpenAI API key

## Running

1. Set your model and API key:
   ```bash
   export LLM_MODEL=openai/gpt-4o
   export OPENAI_API_KEY=sk-...
   ```

2. Run the application:
   ```bash
   ./gradlew run
   ```

*(If you don't have gradle installed, you can generate the wrapper by running `gradle wrapper` if gradle is available, or use a local gradle installation.)*
