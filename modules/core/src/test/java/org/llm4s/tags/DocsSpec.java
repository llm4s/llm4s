package org.llm4s.tags;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import org.scalatest.TagAnnotation;

/**
 * A suite that reads the repository's {@code docs/}: a guide's code blocks, links or tables, tested against the code.
 *
 * <p>A pull request that changes docs and no code skips CI's test jobs and runs only the suites with this tag, with
 * {@code sbt testDocs} (the modules in {@code docsTestProjects} in build.sbt). {@code scripts/check-docs-tests.sh}
 * (Quick Checks) fails when a suite reads {@code docs/} without it. The tag does not change what {@code sbt test} runs.
 *
 * <p>This file is compiled into the tests of every module in {@code docsTestProjects}, not only core's (build.sbt), so
 * a module can use the tag without depending on core's tests.
 */
@TagAnnotation
@Retention(RetentionPolicy.RUNTIME)
@Target({ElementType.TYPE})
public @interface DocsSpec {}
