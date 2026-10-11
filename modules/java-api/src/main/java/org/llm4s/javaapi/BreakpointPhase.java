package org.llm4s.javaapi;

/**
 * Where a static breakpoint holds a task, for a {@link InterruptKind#BREAKPOINT BREAKPOINT}
 * {@link PendingInterrupt}: a Java enum, for the reason {@link InterruptKind} gives.
 */
public enum BreakpointPhase {
  /** Before the task's node ran: nothing of it has happened yet. */
  BEFORE,

  /** After the task's node ran: its update is stored, and what follows it waits. */
  AFTER
}
