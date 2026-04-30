package com.merakilabs.taskq.core.domain;

import java.util.Objects;
import java.util.regex.Pattern;

public record QueueName(String value) {
    private static final Pattern VALID = Pattern.compile("^[a-zA-Z0-9._:-]{1,64}$");
    public static final QueueName DEFAULT = new QueueName("default");

    public QueueName {
        Objects.requireNonNull(value, "queue name must not be null");
        if (!VALID.matcher(value).matches()) {
            throw new IllegalArgumentException("Invalid queue name: " + value);
        }
    }

    @Override
    public String toString() {
        return value;
    }
}
