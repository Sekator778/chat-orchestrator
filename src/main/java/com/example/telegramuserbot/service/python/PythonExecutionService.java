package com.example.telegramuserbot.service.python;

import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.service.TelegramConnectionCoordinator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@Service
public final class PythonExecutionService {
    private static final Logger log = LoggerFactory.getLogger(PythonExecutionService.class);

    private final TelegramConnectionCoordinator connectionCoordinator;
    private final BotInstanceProvider botInstanceProvider;

    @Value("${python.scanner.directory:./Telegram_scaner}")
    private String scannerDirectory;

    @Value("${python.scanner.timeout-minutes:30}")
    private int timeoutMinutes;

    @Value("${python.scanner.docker.enabled:true}")
    private boolean dockerEnabled;

    @Value("${python.scanner.docker.build-before-run:true}")
    private boolean dockerBuildBeforeRun;

    public PythonExecutionService(TelegramConnectionCoordinator connectionCoordinator,
                                  BotInstanceProvider botInstanceProvider) {
        this.connectionCoordinator = connectionCoordinator;
        this.botInstanceProvider = botInstanceProvider;
    }

    private record ScriptDescriptor(String scriptName, String description, Map<String, String> extraEnv) {
        ScriptDescriptor {
            extraEnv = extraEnv == null ? Map.of() : Map.copyOf(extraEnv);
        }
    }

    private ScriptDescriptor script(String scriptName, String description) {
        return new ScriptDescriptor(scriptName, description, Map.of());
    }

    private ScriptDescriptor script(String scriptName, String description, Map<String, String> extraEnv) {
        return new ScriptDescriptor(scriptName, description, extraEnv);
    }

    public Mono<Boolean> executeSeedJoined() {
        return executeScriptsWithCoordination(List.of(
                script("seed_joined.py", "SEED: Sync already subscribed channels + auto-mute")
        ));
    }

    public Mono<Boolean> executeScan() {
        return executeScriptsWithCoordination(List.of(
                script("app.py", "SCAN: Search for channels by keywords + aggregations")
        ));
    }

    public Mono<Boolean> executeDiscover() {
        return executeScriptsWithCoordination(List.of(
                script("discover.py", "DISCOVER: Process candidate queue")
        ));
    }

    public Mono<Boolean> executeJoiner() {
        return executeScriptsWithCoordination(List.of(
                script("joiner.py", "JOIN: Join new channels + auto-mute", Map.of("SKIP_MIGRATIONS", "1"))
        ));
    }

    public Mono<Boolean> executeMuteAll() {
        return executeScriptsWithCoordination(List.of(
                script("mute_all.py", "MUTE: Mute remaining channels", Map.of("SKIP_MIGRATIONS", "1"))
        ));
    }

    public Mono<Boolean> executeReport() {
        return executeScriptsWithCoordination(List.of(
                script("report.py", "REPORT: Generate markdown report")
        ));
    }

    public Mono<Boolean> executeFullWorkflow() {
        log.info("🐍 Starting coordinated Python workflow execution...");

        List<ScriptDescriptor> workflowScripts = List.of(
                script("seed_joined.py", "SEED: Sync already subscribed channels + auto-mute"),
                script("app.py", "SCAN: Search for channels by keywords + aggregations"),
                script("discover.py", "DISCOVER: Process candidate queue"),
                script("joiner.py", "JOIN: Join new channels + auto-mute", Map.of("SKIP_MIGRATIONS", "1")),
                script("mute_all.py", "MUTE: Mute remaining channels", Map.of("SKIP_MIGRATIONS", "1")),
                script("report.py", "REPORT: Generate markdown report")
        );

        return executeScriptsWithCoordination(workflowScripts)
                .doOnSuccess(success -> {
                    if (success) {
                        log.info("✅ Python workflow completed successfully inside coordination block");
                    } else {
                        log.error("❌ Python workflow failed inside coordination block");
                    }
                });
    }

    public Mono<Boolean> executeDailyMaintenancePipeline() {
        List<ScriptDescriptor> maintenanceScripts = List.of(
                script("seed_joined.py", "SEED: Sync already subscribed channels + auto-mute"),
                script("mute_all.py", "MUTE: Mute remaining channels", Map.of("SKIP_MIGRATIONS", "1")),
                script("report.py", "REPORT: Generate markdown report")
        );

        return executeScriptsWithCoordination(maintenanceScripts);
    }

    private Mono<Boolean> executeScriptsWithCoordination(List<ScriptDescriptor> scripts) {
        return connectionCoordinator.executeAsWriter(executeScriptsInOrder(scripts));
    }

    private Mono<Boolean> executeScriptsInOrder(List<ScriptDescriptor> scripts) {
        if (scripts.isEmpty()) {
            return Mono.just(true);
        }

        Mono<Boolean> pipeline = prepareDockerIfNeeded();

        for (ScriptDescriptor script : scripts) {
            pipeline = pipeline.flatMap(success -> success
                    ? executeScriptInternal(script.scriptName(), script.description(), script.extraEnv())
                    : Mono.just(false));
        }

        return pipeline;
    }

    private Mono<Boolean> prepareDockerIfNeeded() {
        if (!dockerEnabled || !dockerBuildBeforeRun) {
            return Mono.just(true);
        }

        return Mono.fromCallable(() -> {
                    log.info("🐳 Preparing Docker image via `docker compose build scanner`");
                    return buildDockerImageBlocking();
                })
                .timeout(Duration.ofMinutes(timeoutMinutes))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error("Failed to prepare Docker image for scanner: {}", error.getMessage(), error))
                .onErrorReturn(false);
    }

    private boolean buildDockerImageBlocking() throws IOException, InterruptedException {
        Path scannerPath = resolveScannerPath();
        ensureVolumeDirectories(scannerPath);

        List<String> command = List.of("docker", "compose", "build", "scanner");
        Boolean result = executeCommand(command, scannerPath, Map.of());
        return Boolean.TRUE.equals(result);
    }

    private Path resolveScannerPath() {
        return Paths.get(scannerDirectory).toAbsolutePath().normalize();
    }

    private void ensureVolumeDirectories(Path scannerPath) throws IOException {
        Files.createDirectories(scannerPath.resolve("data"));
        Files.createDirectories(scannerPath.resolve("logs"));
    }

    /**
     * Runs an arbitrary Python script with custom env overrides and extra CLI arguments.
     * Used by PythonAuthController to authorize Telethon sessions via the same Docker path.
     */
    public Mono<Boolean> executeScript(String scriptName, String description,
                                       Map<String, String> extraEnv, String... scriptArgs) {
        return Mono.fromCallable(() -> {
                    log.info("=== {} ===", description);
                    if (dockerEnabled) {
                        return executeDockerScriptWithArgs(scriptName, extraEnv, scriptArgs);
                    } else {
                        return executePythonScriptWithArgs(scriptName, extraEnv, scriptArgs);
                    }
                })
                .timeout(Duration.ofMinutes(timeoutMinutes))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error("Failed to execute {}: {}", scriptName, error.getMessage(), error))
                .onErrorReturn(false);
    }

    private Mono<Boolean> executeScriptInternal(String scriptName, String description, Map<String, String> extraEnv) {
        return Mono.fromCallable(() -> {
                    log.info("=== {} ===", description);

                    if (dockerEnabled) {
                        return executeDockerScript(scriptName, extraEnv);
                    } else {
                        return executePythonScript(scriptName, extraEnv);
                    }
                })
                .timeout(Duration.ofMinutes(timeoutMinutes))
                .subscribeOn(Schedulers.boundedElastic())
                .doOnError(error -> log.error("Failed to execute {}: {}", scriptName, error.getMessage(), error))
                .onErrorReturn(false);
    }

    private Boolean executeDockerScriptWithArgs(String scriptName, Map<String, String> extraEnv,
                                                String[] scriptArgs) throws IOException, InterruptedException {
        Path scannerPath = resolveScannerPath();
        ensureVolumeDirectories(scannerPath);
        Map<String, String> effectiveEnv = resolveEffectiveScriptEnv(extraEnv, scannerPath);
        List<String> command = new ArrayList<>();
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("--env-file");
        command.add(scannerPath.resolve(".env").toString());
        for (Map.Entry<String, String> env : effectiveEnv.entrySet()) {
            command.add("-e");
            command.add(env.getKey() + "=" + env.getValue());
        }
        command.add("-v");
        command.add(scannerPath.resolve("data").toString() + ":/app/data");
        command.add("-v");
        command.add(scannerPath.resolve("logs").toString() + ":/app/logs");
        command.add("telegram_scaner-scanner");
        command.add("python");
        command.add("-u");
        command.add(scriptName);
        for (String arg : scriptArgs) {
            command.add(arg);
        }
        return executeCommand(command, scannerPath, Map.of());
    }

    private Boolean executePythonScriptWithArgs(String scriptName, Map<String, String> extraEnv,
                                                String[] scriptArgs) throws IOException, InterruptedException {
        Path scannerPath = resolveScannerPath();
        ensureVolumeDirectories(scannerPath);
        Map<String, String> effectiveEnv = resolveEffectiveScriptEnv(extraEnv, scannerPath);
        List<String> command = new ArrayList<>();
        command.add("python");
        command.add("-u");
        command.add(scriptName);
        for (String arg : scriptArgs) {
            command.add(arg);
        }
        return executeCommand(command, scannerPath, effectiveEnv);
    }

    private Boolean executeDockerScript(String scriptName, Map<String, String> extraEnv) throws IOException, InterruptedException {
        Path scannerPath = resolveScannerPath();
        ensureVolumeDirectories(scannerPath);
        Map<String, String> effectiveEnv = resolveEffectiveScriptEnv(extraEnv, scannerPath);
        log.info("Python docker exec: script={}, primaryInstanceId={}, overrides={}",
                scriptName, resolvePrimaryBotId(), safeEnvForLogs(effectiveEnv));
        List<String> command = new ArrayList<>();

        // Use `docker run` instead of `docker compose run` to avoid Windows TTY deadlock.
        // `docker compose run` on Windows gets containers stuck in "Created" state
        // even with -T flag when invoked without an interactive console handle.
        command.add("docker");
        command.add("run");
        command.add("--rm");
        command.add("--env-file");
        command.add(scannerPath.resolve(".env").toString());

        for (Map.Entry<String, String> env : effectiveEnv.entrySet()) {
            command.add("-e");
            command.add(env.getKey() + "=" + env.getValue());
        }

        command.add("-v");
        command.add(scannerPath.resolve("data").toString() + ":/app/data");
        command.add("-v");
        command.add(scannerPath.resolve("logs").toString() + ":/app/logs");

        command.add("telegram_scaner-scanner");

        command.add("python");
        command.add("-u");
        command.add(scriptName);

        return executeCommand(command, scannerPath, Map.of());
    }

    private Boolean executePythonScript(String scriptName, Map<String, String> extraEnv) throws IOException, InterruptedException {
        Path scannerPath = resolveScannerPath();
        ensureVolumeDirectories(scannerPath);
        Map<String, String> effectiveEnv = resolveEffectiveScriptEnv(extraEnv, scannerPath);
        log.info("Python local exec: script={}, primaryInstanceId={}, overrides={}",
                scriptName, resolvePrimaryBotId(), safeEnvForLogs(effectiveEnv));
        List<String> command = new ArrayList<>();

        command.add("python");
        command.add("-u");
        command.add(scriptName);

        return executeCommand(command, scannerPath, effectiveEnv);
    }

    private Map<String, String> resolveEffectiveScriptEnv(Map<String, String> extraEnv, Path scannerPath) {
        if (extraEnv.containsKey("SESSION_NAME")) {
            return extraEnv;
        }

        String primaryBotId = resolvePrimaryBotId();
        if (primaryBotId == null || primaryBotId.isBlank()) {
            return extraEnv;
        }

        Path dataDir = scannerPath.resolve("data");
        Path targetSessionPath = dataDir.resolve(primaryBotId + ".session");
        boolean sessionAvailable = ensureTelethonSessionPresent(dataDir, targetSessionPath);
        if (sessionAvailable) {
            log.info("Telethon session resolved for primaryInstanceId={}: {}", primaryBotId, targetSessionPath);
        }
        if (!Files.exists(targetSessionPath)) {
            log.debug("Telethon session {} not found; keeping existing SESSION_NAME from env/.env", targetSessionPath);
            return extraEnv;
        }

        Map<String, String> merged = new HashMap<>(extraEnv);
        merged.put("SESSION_NAME", primaryBotId);
        return Map.copyOf(merged);
    }

    private String resolvePrimaryBotId() {
        return botInstanceProvider.getInstanceId();
    }

    private boolean ensureTelethonSessionPresent(Path dataDir, Path targetSessionPath) {
        if (Files.exists(targetSessionPath)) {
            return true;
        }

        Path legacySessionPath = dataDir.resolve("tgscan.session");
        if (!Files.exists(legacySessionPath)) {
            log.warn("Telethon session missing: target={} legacy={}", targetSessionPath, legacySessionPath);
            return false;
        }

        try {
            Files.copy(legacySessionPath, targetSessionPath);
            log.info("Copied legacy Telethon session {} -> {}", legacySessionPath, targetSessionPath);
            return true;
        } catch (IOException e) {
            log.warn("Failed to copy legacy Telethon session {} -> {}: {}",
                    legacySessionPath, targetSessionPath, e.getMessage());
            return false;
        }
    }

    private Map<String, String> safeEnvForLogs(Map<String, String> env) {
        if (env == null || env.isEmpty()) {
            return Map.of();
        }
        Map<String, String> safe = new HashMap<>();
        if (env.containsKey("SESSION_NAME")) {
            safe.put("SESSION_NAME", env.get("SESSION_NAME"));
        }
        if (env.containsKey("SKIP_MIGRATIONS")) {
            safe.put("SKIP_MIGRATIONS", env.get("SKIP_MIGRATIONS"));
        }
        return Map.copyOf(safe);
    }

    private Boolean executeCommand(List<String> command, Path workingDirectory, Map<String, String> extraEnv) throws IOException, InterruptedException {
        ProcessBuilder pb = new ProcessBuilder(command);
        pb.directory(workingDirectory.toFile());
        pb.redirectErrorStream(true);

        Map<String, String> environment = pb.environment();
        if (!extraEnv.isEmpty()) {
            environment.putAll(extraEnv);
        }

        if (isWindows() && isDockerComposeCommand(command)) {
            environment.putIfAbsent("COMPOSE_CONVERT_WINDOWS_PATHS", "1");
        }

        log.debug("Executing command: {} in directory: {}", formatCommandForLogs(command), workingDirectory);
        log.debug("With extra environment variables: {}", extraEnv);

        Process process = pb.start();
        process.getOutputStream().close();
        Thread outputThread = createStreamThread(process);
        outputThread.start();

        boolean finished = process.waitFor(timeoutMinutes, TimeUnit.MINUTES);

        if (!finished) {
            log.error("Command timed out after {} minutes. Forcibly destroying process.", timeoutMinutes);
            process.destroyForcibly();
            outputThread.interrupt();
            return false;
        }

        int exitCode = process.exitValue();
        if (exitCode == 0) {
            log.debug("Command completed successfully with exit code: {}", exitCode);
            return true;
        } else {
            log.error("Command failed with exit code: {}. Check logs above for Python script output.", exitCode);
            return false;
        }
    }

    private Thread createStreamThread(Process process) {
        Runnable readerTask = new StreamGobbler(process.getInputStream(), log::info);
        Thread thread = new Thread(readerTask, "python-exec-stream");
        thread.setDaemon(true);
        return thread;
    }

    private boolean isWindows() {
        String osName = System.getProperty("os.name", "").toLowerCase();
        return osName.contains("win");
    }

    private boolean isDockerComposeCommand(List<String> command) {
        if (command.size() < 2) {
            return false;
        }
        return "docker".equalsIgnoreCase(command.get(0)) && "compose".equalsIgnoreCase(command.get(1));
    }

    private String formatCommandForLogs(List<String> command) {
        return command.stream()
                .map(arg -> {
                    if (arg == null) {
                        return "null";
                    }
                    boolean needsQuotes = arg.contains(" ") || arg.contains("\t") || arg.contains("\"");
                    if (!needsQuotes) {
                        return arg;
                    }
                    String escaped = arg.replace("\"", "\\\"");
                    return "\"" + escaped + "\"";
                })
                .collect(Collectors.joining(" "));
    }

    private static class StreamGobbler implements Runnable {
        private final java.io.InputStream inputStream;
        private final Consumer<String> consumer;

        public StreamGobbler(java.io.InputStream inputStream, Consumer<String> consumer) {
            this.inputStream = inputStream;
            this.consumer = consumer;
        }

        @Override
        public void run() {
            new BufferedReader(new InputStreamReader(inputStream)).lines()
                    .forEach(consumer);
        }
    }
}
