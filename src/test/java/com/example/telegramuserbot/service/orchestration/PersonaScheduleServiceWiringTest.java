package com.example.telegramuserbot.service.orchestration;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression guard for the smoke-gate break: a @Service with multiple
 * constructors and no no-arg one MUST mark its injection constructor with
 * @Autowired, or Spring cannot instantiate it (context fails to load).
 */
class PersonaScheduleServiceWiringTest {

    @Test
    void hasExactlyOneAutowiredConstructorForSpring() {
        long autowired = 0;
        for (Constructor<?> c : PersonaScheduleService.class.getDeclaredConstructors()) {
            if (c.isAnnotationPresent(org.springframework.beans.factory.annotation.Autowired.class)) {
                autowired++;
            }
        }
        assertThat(autowired)
                .as("Spring needs exactly one @Autowired constructor when several exist and none is no-arg")
                .isEqualTo(1);
    }
}
