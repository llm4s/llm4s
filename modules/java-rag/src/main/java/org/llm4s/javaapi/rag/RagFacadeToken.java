package org.llm4s.javaapi.rag;

/**
 * The first parameter of {@link JRag}'s constructor, so that only this package can call it - as {@code FacadeToken}
 * does for {@code llm4s-java-api}'s Scala classes (#1666).
 *
 * <p>Scala 3 emits a {@code private[rag]} constructor as {@code public} in bytecode. This class is package-private
 * and its one instance too, so code outside the package cannot name it, and a constructor handed anything but
 * {@link #INSTANCE} - {@code null}, from Java - throws.
 */
final class RagFacadeToken {

  /** The only instance. */
  static final RagFacadeToken INSTANCE = new RagFacadeToken();

  private RagFacadeToken() {}

  /**
   * Refuses a constructor call from outside the package: {@code token} is {@link #INSTANCE} only when this package
   * passed it.
   *
   * @throws UnsupportedOperationException when it is not
   */
  static void check(RagFacadeToken token, String type) {
    if (token != INSTANCE) {
      throw new UnsupportedOperationException(
          type + " is built by llm4s-java-rag, not by its constructor: obtain one from JRag.builder()");
    }
  }
}
