package com.example.telegramuserbot.scheduler;

import com.example.telegramuserbot.service.python.PythonExecutionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import reactor.core.scheduler.Schedulers;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Scheduler for periodic execution of Python discovery and channel management workflow.
 * Executes the 6-step Python process: seed → scan → discover → join → mute → report
 */
@Component
@ConditionalOnProperty(name = "python.scheduler.enabled", havingValue = "true", matchIfMissing = true)
public final class PythonWorkflowScheduler {

    private static final Logger log = LoggerFactory.getLogger(PythonWorkflowScheduler.class);

    private final PythonExecutionService pythonExecutionService;
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    @Value("${python.scheduler.concurrent-execution:false}")
    private boolean allowConcurrentExecution;

    public PythonWorkflowScheduler(PythonExecutionService pythonExecutionService) {
        this.pythonExecutionService = pythonExecutionService;
    }

    /**
     * Full Python workflow execution - runs every 2 hours by default
     * first start after 1 hour of application startup
     * 1. seed_joined.py - Sync subscribed channels + auto-mute
     * 2. app.py - Scan + aggregations in DB
     * 3. discover.py - Process candidate queue
     * 4. joiner.py - Join new channels + auto-mute
     * 5. mute_all.py - Mute remaining channels
     * 6. report.py - Generate markdown report
     */
    @Scheduled(
            initialDelayString = "${python.scheduler.full-workflow.initial-delay:3600000}", // 1 hour default
            fixedRateString = "${python.scheduler.full-workflow.rate:7200000}" // 2 hours default
    )
    public void executeFullWorkflow() {
        if (!allowConcurrentExecution && !isRunning.compareAndSet(false, true)) {
            log.warn("Python workflow is already running, skipping this execution");
            return;
        }

        log.info("🐍 Starting Python discovery and channel management workflow...");

        pythonExecutionService.executeFullWorkflow()
                .doFinally(signal -> isRunning.set(false))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        success -> {
                            if (success) {
                                log.info("✅ Python workflow completed successfully");
                            } else {
                                log.error("❌ Python workflow failed");
                            }
                        },
                        error -> {
                            log.error("💥 Python workflow encountered unexpected error", error);
                            isRunning.set(false);
                        }
                );
    }

    /**
     * Daily maintenance task - seed and mute existing channels
     * Runs every day at 3:00 AM
     */
    @Scheduled(cron = "${python.scheduler.daily-maintenance.cron:0 0 3 * * ?}")
    public void executeDailyMaintenance() {
        log.info("🔧 Starting daily Python maintenance...");

        pythonExecutionService.executeDailyMaintenancePipeline()
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe(
                        success -> {
                            if (success) {
                                log.info("✅ Daily maintenance completed");
                            } else {
                                log.warn("⚠️ Daily maintenance had issues");
                            }
                        },
                        error -> log.error("Daily maintenance error", error)
                );
    }
}
