package com.example.telegramuserbot.service.auth;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Contract tests for the headless-login kind normalization (the behavior the
 * admin API and the TDLib auth handler agree on). DB execution is covered by
 * the integration suite against real PostgreSQL.
 *
 * FR-001: only "PASSWORD" (any case) maps to the 2FA kind; everything else is a CODE.
 */
@ExtendWith(MockitoExtension.class)
class TelegramAuthCodeServiceTest {

    private final TelegramAuthCodeService service = new TelegramAuthCodeService(mock(DatabaseClient.class));

    @Test
    void passwordKindIsRecognizedCaseInsensitively() {
        assertThat(normalize("password")).isEqualTo(TelegramAuthCodeService.KIND_PASSWORD);
        assertThat(normalize("PASSWORD")).isEqualTo(TelegramAuthCodeService.KIND_PASSWORD);
    }

    @Test
    void anythingElseFallsBackToCode() {
        assertThat(normalize("code")).isEqualTo(TelegramAuthCodeService.KIND_CODE);
        assertThat(normalize(null)).isEqualTo(TelegramAuthCodeService.KIND_CODE);
        assertThat(normalize("garbage")).isEqualTo(TelegramAuthCodeService.KIND_CODE);
    }

    private String normalize(String kind) {
        return org.springframework.test.util.ReflectionTestUtils.invokeMethod(service, "normalizeKind", kind);
    }
}
