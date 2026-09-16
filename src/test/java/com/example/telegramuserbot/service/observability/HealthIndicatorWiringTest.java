package com.example.telegramuserbot.service.observability;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;

import java.lang.reflect.Constructor;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * A @Component with two constructors and no @Autowired sends Spring looking for a
 * no-arg one and kills the whole context at startup — which is exactly what
 * KafkaHealthIndicator did from 2026-08-23 until it was caught by hand: every unit
 * test stayed green because none of them load a Spring context.
 */
class HealthIndicatorWiringTest {

    @ParameterizedTest
    @ValueSource(classes = {KafkaHealthIndicator.class, QdrantHealthIndicator.class, EmbeddingsHealthIndicator.class})
    @DisplayName("a health indicator with several constructors marks the one Spring must use")
    void multiConstructorIndicatorsDeclareTheInjectionPoint(Class<?> type) {
        Constructor<?>[] constructors = type.getDeclaredConstructors();
        if (constructors.length < 2) {
            return; // single constructor — Spring picks it without help
        }
        assertThat(Arrays.stream(constructors).filter(c -> c.isAnnotationPresent(Autowired.class)).count())
                .as("%s has %d constructors, so exactly one must carry @Autowired",
                        type.getSimpleName(), constructors.length)
                .isEqualTo(1);
    }
}
