# Agent-as-a-Service (AaaS) Gateway Spec

> Status: 2026-07-04 · Owner: open · Target: llm4s 1.0-RC / 1.0

This document defines the technical specification for the **Agent-as-a-Service (AaaS) Gateway**, a containerized, configuration-driven HTTP/gRPC microservice running the LLM4S engine under the hood. It exposes LLM4S's robust agent orchestration, memory management, and guardrails to non-JVM developers (particularly the Python and Node.js communities) via language-agnostic APIs.

---

## 1. Context & Motivation

LLM4S is a premier framework for building type-safe, functional AI systems on the JVM. However, the machine learning and web frontend ecosystems are dominated by Python and JavaScript. Many organizations have hybrid environments where:
* The core ML research, scripting, and local data science tools are written in **Python**.
* The frontend or orchestration layers are written in **TypeScript / React**.

Forcing these teams to write Scala or compile complex JVM toolchains prevents adoption. The **AaaS Gateway** bridges this gap by packaging the LLM4S Scala engine into a lightweight Docker container that acts as an "API Gateway for Agents." Developers can configure agent topologies, guardrails, and vector memory using a single YAML/JSON file, then query them using standard HTTP REST, GraphQL, or gRPC interfaces from any language.

---

## 2. Goals

* **Zero-JVM Setup**: Allow non-JVM developers to leverage LLM4S agents without writing Scala/Java or installing `sbt`/JDK.
* **Unified REST/gRPC API**: Provide standard HTTP endpoints for agent initialization, conversation management, streaming, and tool execution.
* **Config-Driven Orchestration**: Define complex multi-agent DAGs, memory databases, and guardrails using a single, version-controlled YAML configuration file.
* **Secure Tool Execution**: Run tool calls inside a containerized sandbox out-of-the-box, ensuring host safety.
* **Compatibility with Python Tooling**: Provide a clear path for Python developers to expose local scripts as tools via the Model Context Protocol (MCP).

---

## 3. Non-Goals

* **Replacing JVM-Native APIs**: The Scala-native library remains the foundation and primary focus for Scala developers.
* **Direct Model Hosting**: The gateway does not host weights; it delegates completion requests to external LLM providers (OpenAI, Anthropic, Gemini, DeepSeek, or local Ollama instances).
* **Config UI Builder**: A visual dashboard interface is a separate, future extension.

---

## 4. Architecture

The Gateway runs as an embedded Web Server (e.g., using `http4s` + `tapir` or `ZIO HTTP`) inside a Docker container.

```mermaid
graph TD
    subgraph Client Applications
        PyApp[Python Web App]
        JSApp[Node.js Frontend]
    end

    subgraph LLM4S AaaS Container (JVM)
        HTTP[REST / gRPC Endpoint Server]
        Parser[YAML/JSON Configuration Parser]
        Registry[Agent Registry]
        Engine[LLM4S Core Engine]
        Sandbox[Docker-in-Docker Sandboxed Runner]
    end

    subgraph Providers & Infrastructure
        LLM[External LLM Providers]
        VectorDB[Qdrant / Postgres Vector DB]
        MCPServer[External Python MCP Server]
    end

    PyApp -->|HTTP POST /v1/agents/chat| HTTP
    JSApp -->|HTTP WebSocket Stream| HTTP
    Parser -->|Loads config.yaml| Registry
    Registry -->|Spins up instances| Engine
    Engine -->|Calls Completion| LLM
    Engine -->|Queries embeddings| VectorDB
    Engine -->|Executes Shell/Python tools| Sandbox
    Engine -->|Calls Python scrapers| MCPServer
```

---

## 5. Configuration Schema (`agents.yaml`)

The gateway bootloader reads a standard YAML file to initialize the agents:

```yaml
version: "1.0"

# Global database & provider connections
global:
  default_provider: "openai"
  vector_stores:
    qdrant-db:
      type: "qdrant"
      url: "http://qdrant:6333"
      api_key: "${QDRANT_API_KEY}"

# Register individual agent configurations
agents:
  - id: "support_agent"
    model: "anthropic/claude-3-5-sonnet"
    temperature: 0.5
    systemPrompt: "You are a customer support agent. Help the user solve issues with their accounts."
    memory:
      type: "short-term"
      max_turns: 15
    guardrails:
      input:
        - type: "profanity-filter"
      output:
        - type: "json-validator"
          schema_path: "/etc/llm4s/schemas/support_response.json"

  - id: "data_analyst"
    model: "openai/gpt-4o"
    temperature: 0.1
    systemPrompt: "You are a database analyst. Write and run SQL queries to pull data."
    memory:
      type: "vector-store"
      store_ref: "qdrant-db"
      collection: "sql-documentation"
    tools:
      - type: "sandbox-command"
        command_allowlist: ["psql", "sqlite3"]
      - type: "mcp-server"
        url: "http://localhost:5001/mcp" # External Python MCP server
```

---

## 6. API Endpoints Specification

### 6.1 Chat Completion (Non-Streaming)
* **Endpoint**: `POST /v1/agents/{agent_id}/chat`
* **Request Body**:
```json
{
  "session_id": "session-123",
  "message": "Find the average order value from last month.",
  "variables": {
    "user_tier": "premium"
  }
}
```
* **Response Body**:
```json
{
  "reply": "The average order value for premium users was $42.50.",
  "session_id": "session-123",
  "usage": {
    "prompt_tokens": 120,
    "completion_tokens": 45,
    "cost_usd": 0.0012
  },
  "trace_id": "trace-abc-987"
}
```

### 6.2 Chat Completion (Streaming)
* **Endpoint**: `GET /v1/agents/{agent_id}/chat/stream`
* **Protocol**: Server-Sent Events (SSE) or WebSockets
* **Query Parameters**: `session_id`, `message`
* **Event Format**:
```event
event: token
data: {"text": "The"}

event: token
data: {"text": " average"}

event: complete
data: {"usage": {"prompt_tokens": 120, "completion_tokens": 45}}
```

### 6.3 Session Management
* **Endpoint**: `DELETE /v1/sessions/{session_id}`
* **Description**: Clears the conversation history and resets short-term memory for a given session.
* **Response**: `{"status": "cleared"}`

---

## 7. Python Client Integration Example

Python developers can interact with the JVM-powered agent server seamlessly using their native tools:

```python
import os
import requests
import json

class LLM4SAgentClient:
    def __init__(self, base_url="http://localhost:8080"):
        self.base_url = base_url

    def send_message(self, agent_id, session_id, prompt):
        url = f"{self.base_url}/v1/agents/{agent_id}/chat"
        headers = {"Content-Type": "application/json"}
        payload = {
            "session_id": session_id,
            "message": prompt
        }
        response = requests.post(url, headers=headers, data=json.dumps(payload))
        response.raise_for_status()
        return response.json()

# Usage
client = LLM4SAgentClient()
result = client.send_message(
    agent_id="data_analyst", 
    session_id="user-456", 
    prompt="What tables are available?"
)
print("Agent Reply:", result["reply"])
print("Cost:", result["usage"]["cost_usd"])
```

---

## 8. Security & Sandboxing Policies

When executing commands or code blocks generated by the LLM:
1. **Container Isolation**: The gateway must run commands in a separate, unprivileged Docker sibling container using Docker-in-Docker or sibling Docker socket mount (`/var/run/docker.sock`).
2. **Resource Limits**: CPU limit of 1.0 core, 512MB RAM, and execution timeout of 10 seconds per command.
3. **Default-Deny Network Policies**: Sandboxed environments have no outbound internet access unless explicitly whitelisted in the YAML configuration.

---

## 9. Open Questions

1. **How should multi-agent handoffs be exposed to HTTP clients?**
   * *Option A*: The gateway handles the entire handoff loop internally, returning the final agent's reply to the HTTP client.
   * *Option B*: The gateway returns a redirect-like status (e.g., `{"status": "handoff", "target_agent": "billing_agent"}`) and lets the HTTP client call the next agent explicitly.
   * *Recommendation*: Option A by default (easier for client applications), with trace logs revealing the routing steps.
2. **WebSocket vs Server-Sent Events (SSE) for streaming?**
   * *Recommendation*: Support SSE for simple downstream token streaming. WebSocket is better suited if bi-directional interaction (such as interactive tool approvals mid-stream) is required.
