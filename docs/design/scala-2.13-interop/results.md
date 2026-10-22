| Probe | Scala | Result | Output or first compiler error |
|---|---|---|---|
| A_conversation | 2.13.16 | compiles and runs | `4 messages, last: And llm4s?` |
| A_conversation | 2.13.18 | compiles and runs | `4 messages, last: And llm4s?` |
| B_config_client | 2.13.16 | compiles and runs | `Hello from the fake Ollama. (usage: Some(23))` |
| B_config_client | 2.13.18 | compiles and runs | `Hello from the fake Ollama. (usage: Some(23))` |
| C_streaming | 2.13.16 | compiles and runs | `2 chunks [streame\|d reply], final: streamed reply` |
| C_streaming | 2.13.18 | compiles and runs | `2 chunks [streame\|d reply], final: streamed reply` |
| D_options_enums | 2.13.16 | compiles and runs | `temperature=0.2 maxTokens=Some(256) reasoning=high parsed=Some(Medium) budget=Some(32768)` |
| D_options_enums | 2.13.18 | compiles and runs | `temperature=0.2 maxTokens=Some(256) reasoning=high parsed=Some(Medium) budget=Some(32768)` |
| E_tools | 2.13.16 | does not compile | `can't find term required by object upickle.core.Types.ReadWriter: upickle.core.Types.this.ReadWriter; perhaps it is missing from the classpath.` |
| E_tools | 2.13.18 | does not compile | `can't find term required by object upickle.core.Types.ReadWriter: upickle.core.Types.this.ReadWriter; perhaps it is missing from the classpath.` |
| E2_tool_registry | 2.13.16 | compiles and runs | `an unknown tool is an error value: true` |
| E2_tool_registry | 2.13.18 | compiles and runs | `an unknown tool is an error value: true` |
| F_structured | 2.13.16 | does not compile | `can't find term required by object upickle.core.Types.Reader: upickle.core.Types.this.Reader; perhaps it is missing from the classpath.` |
| F_structured | 2.13.18 | does not compile | `can't find term required by object upickle.core.Types.Reader: upickle.core.Types.this.Reader; perhaps it is missing from the classpath.` |
| G_agent | 2.13.16 | compiles and runs | `List(Some(Agent answer.), Some(Agent answer.)), same thread: true` |
| G_agent | 2.13.18 | compiles and runs | `List(Some(Agent answer.), Some(Agent answer.)), same thread: true` |
| H_subclass_client | 2.13.16 | does not compile | `can't find term required by object upickle.core.Types.ReadWriter: upickle.core.Types.this.ReadWriter; perhaps it is missing from the classpath.` |
| H_subclass_client | 2.13.18 | does not compile | `can't find term required by object upickle.core.Types.ReadWriter: upickle.core.Types.this.ReadWriter; perhaps it is missing from the classpath.` |
| I_opaque_ids | 2.13.16 | does not compile | `object ThreadId is not a member of package org.llm4s.agent.graph` |
| I_opaque_ids | 2.13.18 | does not compile | `object ThreadId is not a member of package org.llm4s.agent.graph` |
