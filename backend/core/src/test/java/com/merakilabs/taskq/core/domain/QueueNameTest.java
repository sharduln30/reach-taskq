package com.merakilabs.taskq.core.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class QueueNameTest {

    @Test
    void acceptsValidNames() {
        assertThat(new QueueName("default").value()).isEqualTo("default");
        assertThat(new QueueName("orders.high").value()).isEqualTo("orders.high");
        assertThat(new QueueName("emails:fast-lane").value()).isEqualTo("emails:fast-lane");
    }

    @Test
    void rejectsBlankAndOversizeAndForbiddenChars() {
        assertThatThrownBy(() -> new QueueName("")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueName("has whitespace")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueName("/path-like")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new QueueName("a".repeat(65))).isInstanceOf(IllegalArgumentException.class);
    }
}
