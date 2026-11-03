package io.xiyan.agent.core.shizuku;

/** One outstanding command, with cancellation generations and explicit terminal release. */
public final class InputSession {
    public static final class Ticket {
        public final long generation;
        public final long requestId;
        private Ticket(long generation, long requestId) {
            this.generation = generation;
            this.requestId = requestId;
        }
    }

    private long generation;
    private long lastRequestId;
    private Ticket active;
    private boolean attached;

    public synchronized void attach(long nextGeneration) {
        if (nextGeneration <= generation || active != null) throw rejected();
        generation = nextGeneration;
        lastRequestId = 0;
        attached = true;
    }

    public synchronized Ticket begin(long expectedGeneration, long requestId) {
        if (!attached || expectedGeneration != generation || active != null
                || requestId <= lastRequestId || requestId <= 0) throw rejected();
        lastRequestId = requestId;
        active = new Ticket(generation, requestId);
        return active;
    }

    /** A cancelled command keeps its slot until the real worker reaches a terminal state. */
    public synchronized boolean cancel(long nextGeneration) {
        if (nextGeneration <= generation) return false;
        generation = nextGeneration;
        return true;
    }

    public synchronized void detach() {
        attached = false;
    }

    public synchronized boolean isCurrent(Ticket ticket) {
        return attached && active == ticket && ticket.generation == generation;
    }

    public synchronized boolean finish(Ticket ticket) {
        if (active != ticket) return false;
        boolean current = isCurrent(ticket);
        active = null;
        return current;
    }

    public synchronized boolean isBusy() { return active != null; }
    public synchronized long generation() { return generation; }

    private static IllegalStateException rejected() {
        return new IllegalStateException("input_session_rejected");
    }
}
