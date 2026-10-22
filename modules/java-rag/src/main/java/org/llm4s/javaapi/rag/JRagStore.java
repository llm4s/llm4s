package org.llm4s.javaapi.rag;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Where a {@link JRag} keeps its chunks, their vectors and its keyword index: in memory, or in SQLite files.
 * Obtain one from {@link JRag#inMemory()} or {@link JRag#sqlite(Path)}.
 *
 * <p>A value: two are equal when they name the same kind of store and the same file. Built only by this package -
 * its constructor is package-private, so a kind of store can be added without breaking a caller.
 */
public final class JRagStore {

  private static final JRagStore IN_MEMORY = new JRagStore(false, null);

  private final boolean sqlite;
  private final Path file;

  JRagStore(boolean sqlite, Path file) {
    this.sqlite = sqlite;
    this.file = file;
  }

  static JRagStore inMemory() {
    return IN_MEMORY;
  }

  /** A {@code null} file is kept, and makes the builder's {@code build()} a failed result of kind {@code VALIDATION}. */
  static JRagStore sqlite(Path file) {
    return new JRagStore(true, file);
  }

  /** Whether this store lives in memory, and is lost when the pipeline is closed. */
  public boolean isInMemory() {
    return !sqlite;
  }

  /** The SQLite file the vectors are kept in; empty for a store in memory. */
  public Optional<Path> file() {
    return Optional.ofNullable(file);
  }

  @Override
  public boolean equals(Object other) {
    return other instanceof JRagStore that && sqlite == that.sqlite && Objects.equals(file, that.file);
  }

  @Override
  public int hashCode() {
    return Objects.hash(sqlite, file);
  }

  @Override
  public String toString() {
    return sqlite ? "JRagStore(sqlite " + file + ")" : "JRagStore(in memory)";
  }
}
