package org.llm4s.it.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.scalatest.TagAnnotation;

/**
 * Tier 2: needs the official MCP reference server ({@code @modelcontextprotocol/server-everything})
 * listening at {@code MCP_SERVER_URL}, started from {@code modules/it/mcp-reference-server/}.
 *
 * <p>Run with {@code sbt testMcpReference}. Separate from {@link Docker} because starting the
 * server downloads the {@code node} image from Docker Hub and the server's pinned dependency tree
 * from npm, so an outage of either would fail unrelated PRs. CI therefore runs this tier on pushes
 * to main and on manual {@code workflow_dispatch}, not on every PR.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface McpReference {}
