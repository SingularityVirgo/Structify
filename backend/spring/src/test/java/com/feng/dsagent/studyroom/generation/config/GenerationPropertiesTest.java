package com.feng.dsagent.studyroom.generation.config;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class GenerationPropertiesTest {

    @Test
    void rejectsNonPositivePollIntervalOrCapacity() {
        assertThatThrownBy(() -> new GenerationProperties(true, 0, 2))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("poll-interval-ms");
        assertThatThrownBy(() -> new GenerationProperties(true, 1000, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("capacity");
    }
}
