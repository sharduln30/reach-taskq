package com.merakilabs.taskq.core.domain;

public record Priority(int value) implements Comparable<Priority> {

    public static final Priority HIGH = new Priority(10);
    public static final Priority NORMAL = new Priority(50);
    public static final Priority LOW = new Priority(100);

    public Priority {
        if (value < 0 || value > 1000) {
            throw new IllegalArgumentException("Priority must be in [0, 1000], got " + value);
        }
    }

    @Override
    public int compareTo(final Priority o) {
        return Integer.compare(this.value, o.value);
    }
}
