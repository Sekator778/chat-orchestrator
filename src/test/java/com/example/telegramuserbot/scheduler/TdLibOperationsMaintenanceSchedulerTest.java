package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TdLibOperationLockService;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;

import java.time.Duration;

import static org.mockito.Mockito.*;

/**
 * Unit tests for {@link TdLibOperationsMaintenanceScheduler}.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class TdLibOperationsMaintenanceSchedulerTest {

    @Mock
    private TdLibOperationLockService lockService;

    @Mock
    private TdLibOperationCoordinator operationCoordinator;

    @Mock
    private BotInstanceProvider botInstanceProvider;

    private TdLibOperationsMaintenanceScheduler scheduler;

    @BeforeEach
    void setUp() {
        when(botInstanceProvider.getInstanceId()).thenReturn("test-instance");
        scheduler = new TdLibOperationsMaintenanceScheduler(
                lockService, operationCoordinator, botInstanceProvider);
    }

    @Test
    @DisplayName("cleanupStaleOperations should call lock service cleanup")
    void shouldCleanupStaleOperations() {
        when(lockService.cleanupStaleOperations()).thenReturn(Mono.just(3));

        scheduler.cleanupStaleOperations();

        verify(lockService).cleanupStaleOperations();
    }

    @Test
    @DisplayName("cleanupStaleOperations should handle zero stale operations")
    void shouldHandleZeroStaleOperations() {
        when(lockService.cleanupStaleOperations()).thenReturn(Mono.just(0));

        scheduler.cleanupStaleOperations();

        verify(lockService).cleanupStaleOperations();
    }

    @Test
    @DisplayName("cleanupStaleOperations should handle errors gracefully")
    void shouldHandleCleanupErrors() {
        when(lockService.cleanupStaleOperations())
                .thenReturn(Mono.error(new RuntimeException("Database error")));

        scheduler.cleanupStaleOperations();

        verify(lockService).cleanupStaleOperations();
    }

    @Test
    @DisplayName("deleteOldOperations should call lock service delete")
    void shouldDeleteOldOperations() {
        when(lockService.deleteOldOperations()).thenReturn(Mono.just(10));

        scheduler.deleteOldOperations();

        verify(lockService).deleteOldOperations();
    }

    @Test
    @DisplayName("deleteOldOperations should handle zero deleted operations")
    void shouldHandleZeroDeletedOperations() {
        when(lockService.deleteOldOperations()).thenReturn(Mono.just(0));

        scheduler.deleteOldOperations();

        verify(lockService).deleteOldOperations();
    }

    @Test
    @DisplayName("deleteOldOperations should handle errors gracefully")
    void shouldHandleDeleteErrors() {
        when(lockService.deleteOldOperations())
                .thenReturn(Mono.error(new RuntimeException("Database error")));

        scheduler.deleteOldOperations();

        verify(lockService).deleteOldOperations();
    }

    @Test
    @DisplayName("recoverStuckOperations should not take action when no operation in progress")
    void shouldNotRecoverWhenNoOperationInProgress() {
        when(operationCoordinator.isOperationInProgress()).thenReturn(false);

        scheduler.recoverStuckOperations();

        verify(operationCoordinator).isOperationInProgress();
        verify(operationCoordinator, never()).getCurrentOperationDuration();
        verify(operationCoordinator, never()).forceReleaseIfStuck(any());
    }

    @Test
    @DisplayName("recoverStuckOperations should not release operation running less than threshold")
    void shouldNotReleaseOperationBelowThreshold() {
        when(operationCoordinator.isOperationInProgress()).thenReturn(true);
        when(operationCoordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(5));
        when(operationCoordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");

        scheduler.recoverStuckOperations();

        verify(operationCoordinator).isOperationInProgress();
        verify(operationCoordinator).getCurrentOperationDuration();
        verify(operationCoordinator, never()).forceReleaseIfStuck(any());
    }

    @Test
    @DisplayName("recoverStuckOperations should force release operation running longer than threshold")
    void shouldForceReleaseStuckOperation() {
        when(operationCoordinator.isOperationInProgress()).thenReturn(true);
        when(operationCoordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(15));
        when(operationCoordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");
        when(operationCoordinator.forceReleaseIfStuck(any())).thenReturn(true);

        scheduler.recoverStuckOperations();

        verify(operationCoordinator).isOperationInProgress();
        verify(operationCoordinator).getCurrentOperationDuration();
        verify(operationCoordinator).forceReleaseIfStuck(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("recoverStuckOperations should handle force release returning false")
    void shouldHandleForceReleaseReturningFalse() {
        when(operationCoordinator.isOperationInProgress()).thenReturn(true);
        when(operationCoordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(15));
        when(operationCoordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");
        when(operationCoordinator.forceReleaseIfStuck(any())).thenReturn(false);

        scheduler.recoverStuckOperations();

        verify(operationCoordinator).forceReleaseIfStuck(Duration.ofMinutes(10));
    }

    @Test
    @DisplayName("recoverStuckOperations should handle operation at exactly threshold")
    void shouldNotReleaseOperationAtExactThreshold() {
        when(operationCoordinator.isOperationInProgress()).thenReturn(true);
        when(operationCoordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(10));
        when(operationCoordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");

        scheduler.recoverStuckOperations();

        verify(operationCoordinator).isOperationInProgress();
        verify(operationCoordinator).getCurrentOperationDuration();
        verify(operationCoordinator, never()).forceReleaseIfStuck(any());
    }

    @Test
    @DisplayName("recoverStuckOperations should handle operation just above threshold")
    void shouldReleaseOperationJustAboveThreshold() {
        when(operationCoordinator.isOperationInProgress()).thenReturn(true);
        when(operationCoordinator.getCurrentOperationDuration()).thenReturn(Duration.ofMinutes(10).plusSeconds(1));
        when(operationCoordinator.getCurrentOperation()).thenReturn("LoadChats(ChatListMain)");
        when(operationCoordinator.forceReleaseIfStuck(any())).thenReturn(true);

        scheduler.recoverStuckOperations();

        verify(operationCoordinator).forceReleaseIfStuck(Duration.ofMinutes(10));
    }
}
