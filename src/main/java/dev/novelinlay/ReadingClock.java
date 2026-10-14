package dev.novelinlay;

final class ReadingClock {
    private long previous;
    private long elapsed;
    private boolean wasEligible;

    boolean tick(long now, boolean eligible, int intervalSeconds) {
        // A suspended IDE must not consume unread pages when it wakes up.
        if (eligible && wasEligible) elapsed += Math.max(0, Math.min(now - previous, 500_000_000L));
        previous = now;
        wasEligible = eligible;
        if (elapsed < intervalSeconds * 1_000_000_000L) return false;
        elapsed = 0;
        return true;
    }

    void reset() { elapsed = 0; wasEligible = false; }
}
