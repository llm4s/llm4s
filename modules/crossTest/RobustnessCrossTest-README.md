# RobustnessCrossTest Suite

## Overview

This is a comprehensive cross-version robustness test suite for the LLM4S Agent framework. It ensures that the Agent can gracefully recover from LLM failures, hallucinations, and network-related issues.
## Location

- **Scala 2.13**: `modules/crossTest/scala2/src/test/scala/org/llm4s/sc2/RobustnessCrossTest.scala`
- **Scala 3.x**: `modules/crossTest/scala3/src/test/scala/org/llm4s/sc3/RobustnessCrossTest.scala`

## Test Coverage

### 1. EvilLLM - Malformed JSON Response
Scenario: The LLM returns tool calls containing invalid JSON within the arguments field.

Test: "Agent" should "handle malformed JSON responses gracefully"

Verification: Confirms the Agent continues execution instead of crashing (verified via noException tests).

Real-world case: Protects the system against unexpected LLM provider API changes or corrupted data packets.

### 2. Tool Not Found - Hallucinated Tool Names
Scenario: The LLM attempts to call a tool name that does not exist in the current registry.

Test: "Agent" should "handle hallucinated tool calls"

Verification:

The Agent transitions correctly to the WaitingForTools state.

The assistant's message containing the hallucinated tool is successfully captured in the conversation history.

Real-world case: Prevents crashes caused by common LLM "hallucination" behaviors.

### 3. Recoverable vs Non-Recoverable Errors
Scenario: Validates which HTTP error codes should trigger an automatic retry attempt based on the canonical API.

Recoverable Errors: Covers 408 (Timeout), 5xx (Server Error), and 429 (Rate Limit).

Non-Recoverable Errors: Covers 400 (Bad Request), 401 (Unauthorized), and 404 (Not Found).

Verification: Strictly utilizes ServiceError.isRecoverableStatus for logic consistency.

### 4 Agent Lifecycle Robustness
Scenario: Ensures the Agent remains stable even if the initial LLM connection fails.

Verification:

Confirms the Agent starts in the InProgress state even if the first client call fails.

Ensures errors are correctly propagated through runStep as a Left(ServiceError).
run this 
bash 
```

sbt "++2.13.16 crossTestScala2/testOnly org.llm4s.sc2.RobustnessCrossTest" "++3.7.1 crossTestScala3/testOnly org.llm4s.sc3.RobustnessCrossTest"

```

### 5. Cross-Version Compatibility
As per  requirements, the logic between Scala 2 and Scala 3 modules is 100% identical. We use consistent List types and canonical helpers to ensure identical behavior across environments.

