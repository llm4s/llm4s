package org.llm4s.javaapi.services;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/** {@link ChatMemoryStore#inMemory()}: a map from id to its newest {@code maxTurns} turns. */
final class InMemoryChatMemoryStore implements ChatMemoryStore {

    private final int maxTurns;
    private final ConcurrentHashMap<Object, List<ChatTurn>> turns = new ConcurrentHashMap<>();

    InMemoryChatMemoryStore(int maxTurns) {
        this.maxTurns = maxTurns;
    }

    @Override
    public List<ChatTurn> load(Object memoryId) {
        List<ChatTurn> stored = turns.get(Objects.requireNonNull(memoryId, "memoryId must not be null"));
        return stored == null ? List.of() : List.copyOf(stored);
    }

    @Override
    public void append(Object memoryId, ChatTurn turn) {
        Objects.requireNonNull(turn, "turn must not be null");
        turns.compute(Objects.requireNonNull(memoryId, "memoryId must not be null"), (id, old) -> {
            List<ChatTurn> next = old == null ? new ArrayList<>() : new ArrayList<>(old);
            next.add(turn);
            return next.size() > maxTurns ? List.copyOf(next.subList(next.size() - maxTurns, next.size())) : List.copyOf(next);
        });
    }

    @Override
    public void clear(Object memoryId) {
        turns.remove(Objects.requireNonNull(memoryId, "memoryId must not be null"));
    }

    @Override
    public String toString() {
        return "ChatMemoryStore.inMemory(" + turns.size() + " conversations)";
    }
}
