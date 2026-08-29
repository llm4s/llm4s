package org.llm4s.it.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.scalatest.TagAnnotation;

/**
 * Tier 2: needs a Docker daemon plus a locally built {@code workspace-runner} image
 * ({@code sbt workspaceRunner/Docker/publishLocal}).
 *
 * <p>Run with {@code sbt testWorkspace}. Separate from {@link Docker} because the image build is
 * expensive, so CI runs this tier on pushes to main rather than on every PR.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface Workspace {}
