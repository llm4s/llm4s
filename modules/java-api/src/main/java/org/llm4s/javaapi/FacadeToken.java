package org.llm4s.javaapi;

/**
 * The first parameter of every constructor of this package's Scala classes, so that only this package
 * can call one (#1666).
 *
 * <p>Scala 3 emits a {@code private} constructor that its companion object calls, and every
 * {@code private[javaapi]} member, as {@code public} in bytecode: without this, Java and Kotlin code could
 * build a {@code JAgent} around a Scala {@code Either} or an {@code LlmResult} around {@code null}, and a field
 * added later would change a constructor callers had compiled against. This class is package-private and
 * its one instance too, so code outside the package cannot name it, and a constructor handed anything but
 * {@link #INSTANCE} - {@code null}, from Java - throws.
 *
 * <p>The package's value types ({@code JAgentResult}, {@code JCompletion}, ...) do not need it: they are
 * Java classes, whose package-private constructors are package-private in bytecode too.
 */
final class FacadeToken {

  /** The only instance. */
  static final FacadeToken INSTANCE = new FacadeToken();

  private FacadeToken() {}

  /**
   * Refuses a constructor call from outside the package: {@code token} is {@link #INSTANCE} only when this
   * package passed it.
   *
   * @throws UnsupportedOperationException when it is not
   */
  static void check(FacadeToken token, String type) {
    if (token != INSTANCE) {
      throw new UnsupportedOperationException(
          type + " is built by llm4s-java-api, not by its constructor: obtain one from Llm4s or a builder");
    }
  }
}
