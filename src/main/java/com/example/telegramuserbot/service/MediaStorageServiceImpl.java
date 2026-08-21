package com.example.telegramuserbot.service;

import com.example.telegramuserbot.telegram.TelegramClientFacade;
import com.example.telegramuserbot.service.TelegramClientManager;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.util.retry.Retry;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;

@Service
public class MediaStorageServiceImpl implements MediaStorageService {
    private static final Logger log = LoggerFactory.getLogger(MediaStorageServiceImpl.class);
    private static final int MAX_DOWNLOAD_ATTEMPTS = 3;
    private static final Duration RETRY_DELAY = Duration.ofSeconds(2);
    private final TelegramClientManager telegramClientManager;
    private final Path storageRoot;

    public MediaStorageServiceImpl(
            TelegramClientManager telegramClientManager,
            @Value("${media.storageDirectory}") String storageDir
    ) throws IOException {
        this.telegramClientManager = telegramClientManager;
        this.storageRoot = Paths.get(storageDir).toAbsolutePath();
        Files.createDirectories(storageRoot);
        log.info("Media storage initialized at: {}", storageRoot);
    }

    @Override
    public Mono<String> storeMedia(Long chatId, long fileId, String originalFileName) {
        log.debug(">>> STORE MEDIA: Attempting to store fileId={} for chatId={} with originalName='{}'", fileId, chatId, originalFileName);

        return downloadFileReactive(fileId)
                .timeout(Duration.ofSeconds(30))  // Таймаут на скачивание
                .doOnSuccess(downloadPath -> {
                    if (downloadPath != null) {
                        log.info("<<< STORE MEDIA OK: FileId={} for chatId={} kept at TDLib path '{}'", fileId, chatId, downloadPath);
                    }
                })
                .onErrorResume(error -> {
                    log.warn("--- STORE MEDIA SKIP: Download failed for fileId={}. Error: {}", fileId, error.getMessage());
                    return Mono.empty(); // Return empty Mono on failure
                });
    }

    private Mono<String> downloadFileReactive(long fileId) {
        return Mono.create(sink -> {
            Instant start = Instant.now();
            log.debug(">>> DOWNLOAD: Starting download for fileId={}", fileId);

            TelegramClientFacade client = telegramClientManager.getAnyClient();
            if (client == null) {
                sink.error(new IOException("No Telegram client available"));
                return;
            }

            try {
                int fileIdInt = Math.toIntExact(fileId);

                // Сначала проверяем, не скачан ли файл уже
                client.send(new TdApi.GetFile(fileIdInt), getFileResult -> {
                    if (getFileResult.isError()) {
                        log.warn("!!! GET FILE FAIL: Cannot get file info for fileId={}, proceeding with download. Error: {}",
                                fileId, getFileResult.getError());
                        initiateDownload(client, fileIdInt, fileId, start, sink, 1);
                        return;
                    }

                    TdApi.File existingFile = getFileResult.get();
                    log.debug(">>> GET FILE OK: fileId={} state={}", fileId, describeFile(existingFile));

                    // Проверяем, скачан ли файл уже
                    if (existingFile != null &&
                        existingFile.local != null &&
                        existingFile.local.isDownloadingCompleted &&
                        existingFile.local.path != null &&
                        !existingFile.local.path.isEmpty()) {

                        // Файл уже скачан!
                        Path existingPath = Path.of(existingFile.local.path);
                        if (Files.exists(existingPath)) {
                            log.info("<<< DOWNLOAD SKIP: File already downloaded for fileId={} at '{}'. Size: {}",
                                    fileId, existingFile.local.path, existingFile.local.downloadedSize);
                            sink.success(existingFile.local.path);
                            return;
                        } else {
                            log.warn("!!! DOWNLOAD WARNING: TDLib says file exists at '{}', but file not found on disk. Re-downloading...",
                                    existingFile.local.path);
                        }
                    }

                    // Файл не скачан или отсутствует - скачиваем
                    initiateDownload(client, fileIdInt, fileId, start, sink, 1);
                });

            } catch (ArithmeticException e) {
                log.error("!!! DOWNLOAD FAIL: fileId {} is too large to fit in an int.", fileId);
                sink.error(e);
            } catch (Exception e) {
                log.error("!!! DOWNLOAD ERROR: Unexpected exception during download setup for fileId={}. Error: {}", fileId, e.getMessage(), e);
                sink.error(e);
            }
        });
    }

    private void initiateDownload(TelegramClientFacade client, int fileIdInt, long fileId, Instant start, reactor.core.publisher.MonoSink<String> sink, int attempt) {
        // Приоритет 1 = высокий приоритет для скачивания
        // synchronous = true - ждём завершения скачивания
        TdApi.Function<TdApi.File> downloadRequest = new TdApi.DownloadFile(fileIdInt, 1, 0L, 0L, true);

        client.send(downloadRequest, result -> {
                Instant end = Instant.now();
                long durationMs = Duration.between(start, end).toMillis();

            if (result.isError()) {
                String msg = result.getError().message;
                log.error("!!! DOWNLOAD FAIL: API error for fileId={} after {} ms (attempt {}/{}). Error: {}", fileId, durationMs, attempt, MAX_DOWNLOAD_ATTEMPTS, result.getError());
                if (attempt < MAX_DOWNLOAD_ATTEMPTS && msg != null && msg.toLowerCase().contains("request aborted")) {
                    log.warn("--- DOWNLOAD RETRY: fileId={} hit 'Request aborted', retrying in {}s (attempt {}/{})", fileId, RETRY_DELAY.toSeconds(), attempt + 1, MAX_DOWNLOAD_ATTEMPTS);
                    Schedulers.boundedElastic().schedule(() -> initiateDownload(client, fileIdInt, fileId, start, sink, attempt + 1), RETRY_DELAY.toMillis(), java.util.concurrent.TimeUnit.MILLISECONDS);
                    return;
                }
                sink.error(new IOException("TDLib error: " + msg));
                return;
            }

                TdApi.File file = result.get();
                if (file == null || file.local == null || !file.local.isDownloadingCompleted || file.local.path == null || file.local.path.isEmpty()) {
                    log.error("!!! DOWNLOAD FAIL: Download did not complete successfully for fileId={} after {} ms. File state: {}", fileId, durationMs, file);
                    sink.error(new IOException("File download did not complete successfully."));
                    return;
                }

                String downloadedPathStr = file.local.path;
                log.debug("<<< DOWNLOAD STATE: fileId={} path='{}' size={} expectedSize={} remote='{}' completed={}",
                        fileId, downloadedPathStr, file.local.downloadedSize, file.expectedSize, file.remote != null ? file.remote.id : "n/a",
                        file.local.isDownloadingCompleted);

                // Wait for file to actually exist on disk (TDLib may report completion before flushing to disk)
                Path downloadedPath = Path.of(downloadedPathStr);
                waitForFileToExist(downloadedPath, fileId, 10, 100)
                        .thenAccept(actualPath -> {
                        log.info("<<< DOWNLOAD OK: Successfully downloaded fileId={} to '{}' in {} ms. Size: {}",
                                fileId, actualPath, durationMs, file.local.downloadedSize);
                        sink.success(actualPath.toString());
                    })
                    .exceptionally(ex -> {
                        log.error("!!! DOWNLOAD FAIL: File not found on disk after download for fileId={}. Path: {}. Error: {}",
                                fileId, downloadedPathStr, ex.getMessage());
                        sink.error(new IOException("Downloaded file not found on disk: " + downloadedPathStr, ex));
                        return null;
                    });
        });
    }

    /**
     * Waits for file to exist on disk, checking periodically
     * TDLib may report download completion before file is flushed to disk
     */
    private java.util.concurrent.CompletableFuture<Path> waitForFileToExist(Path expectedPath, long fileId, int maxAttempts, long delayMs) {
        return java.util.concurrent.CompletableFuture.supplyAsync(() -> {
            for (int attempt = 1; attempt <= maxAttempts; attempt++) {
                // First check exact path
                if (Files.exists(expectedPath)) {
                    return expectedPath;
                }

                // Check if it's a path with suffix that needs resolution
                Path resolvedPath = findActualFile(expectedPath);
                if (resolvedPath != null && Files.exists(resolvedPath)) {
                    log.debug("Found file with resolved name: {} -> {}", expectedPath.getFileName(), resolvedPath.getFileName());
                    return resolvedPath;
                }

                if (attempt < maxAttempts) {
                    try {
                        Thread.sleep(delayMs);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException("Interrupted while waiting for file", e);
                    }
                }
            }

            throw new RuntimeException(String.format(
                    "File not found after %d attempts (%.1fs total): %s",
                    maxAttempts, (maxAttempts * delayMs) / 1000.0, expectedPath
            ));
        });
    }

    /**
     * Finds the actual file when TDLib returns path with numeric suffix like "file_(0).ext"
     * TDLib adds these suffixes to avoid conflicts, but actual file may be named differently
     */
    private Path findActualFile(Path tdlibPath) {
        String fileName = tdlibPath.getFileName().toString();

        // Check if filename has pattern like "name_(N).ext"
        java.util.regex.Pattern pattern = java.util.regex.Pattern.compile("^(.+?)_\\(\\d+\\)(\\.[^.]+)?$");
        java.util.regex.Matcher matcher = pattern.matcher(fileName);

        if (!matcher.matches()) {
            return null; // Not a suffixed filename
        }

        String baseName = matcher.group(1);
        String extension = matcher.group(2);
        String finalExtension = (extension == null) ? "" : extension;

        // Try the file without suffix first
        Path parent = tdlibPath.getParent();
        Path candidateWithoutSuffix = parent.resolve(baseName + finalExtension);

        if (Files.exists(candidateWithoutSuffix)) {
            log.debug("Found actual file without suffix: {}", candidateWithoutSuffix);
            return candidateWithoutSuffix;
        }

        // Try to find any file matching the base name pattern
        try {
            String finalBaseName = baseName;
            return Files.list(parent)
                    .filter(p -> {
                        String name = p.getFileName().toString();
                        return name.startsWith(finalBaseName) && name.endsWith(finalExtension);
                    })
                    .findFirst()
                    .orElse(null);
        } catch (IOException e) {
            log.warn("Failed to search for actual file in directory {}: {}", parent, e.getMessage());
            return null;
        }
    }

    /**
     * Формирует человекочитаемое описание TdApi.File для диагностики.
     */
    private String describeFile(TdApi.File file) {
        if (file == null) {
            return "null";
        }
        String remote = file.remote != null ? String.format("remoteId=%s unique=%s uploading=%s",
                file.remote.id, file.remote.uniqueId, file.remote.isUploadingActive) : "remote=null";
        String local = file.local != null ? String.format("localPath=%s downloaded=%s size=%d canBeDownloaded=%s downloadingActive=%s",
                file.local.path, file.local.isDownloadingCompleted, file.local.downloadedSize, file.local.canBeDownloaded, file.local.isDownloadingActive) : "local=null";
        return String.format("size=%d expected=%d | %s | %s", file.size, file.expectedSize, remote, local);
    }
}
