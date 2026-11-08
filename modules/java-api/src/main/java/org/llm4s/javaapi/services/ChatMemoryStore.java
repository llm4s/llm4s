package org.llm4s.javaapi.services;

import java.util.List;
import org.llm4s.annotation.Experimental;

/**
 * Where an AI service keeps the conversations of its {@link MemoryId} methods (#2277), one per id. Implement it over a
 * database or cache to keep conversations across restarts or share them between instances; {@link #inMemory()} is the
 * default.
 *
 * <p>A call loads the id's turns before it asks the model and appends its own turn after a successful answer. Two
 * concurrent calls with the same id each load the turns stored before them, so neither sees the other's turn; give
 * each conversation one caller at a time. An exception the store throws fails the call that met it, with kind
 * {@code OTHER} and the exception as its cause.
 */
@Experimental
public interface ChatMemoryStore {

    /** The turns stored under {@code memoryId}, oldest first; empty for an id never seen. */
    List<ChatTurn> load(Object memoryId);

    /** Stores {@code turn} as the newest turn of {@code memoryId}. */
    void append(Object memoryId, ChatTurn turn);

    /** Forgets every turn of {@code memoryId}. */
    void clear(Object memoryId);

    /** A thread-safe store in this JVM's memory that keeps every turn, until {@link #clear(Object)}. */
    static ChatMemoryStore inMemory() {
        return new InMemoryChatMemoryStore(Integer.MAX_VALUE);
    }

    /**
     * A thread-safe store in this JVM's memory that keeps the newest {@code maxTurns} turns of each id, dropping the
     * oldest.
     *
     * @throws IllegalArgumentException when {@code maxTurns} is below 1
     */
    static ChatMemoryStore inMemory(int maxTurns) {
        if (maxTurns < 1) {
            throw new IllegalArgumentException("maxTurns must be at least 1, was " + maxTurns);
        }
        return new InMemoryChatMemoryStore(maxTurns);
    }
}
