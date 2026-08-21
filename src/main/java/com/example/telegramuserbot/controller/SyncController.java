package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.service.llm.client.DeepSeekApiClient;
import com.example.telegramuserbot.service.llm.dto.ApiMessage;
import com.example.telegramuserbot.service.llm.dto.DeepSeekChatRequest;
import com.example.telegramuserbot.service.messagesync.SyncOrchestrationService;
import com.example.telegramuserbot.dto.QuickScanRequestDto;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * REST controller for managing chat history synchronization operations.
 * Provides endpoints for initiating, monitoring, and managing sync jobs.
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/sync")
@Tag(name = "Sync Management", description = "Chat history synchronization operations")
public class SyncController {

    private static final DateTimeFormatter EXPORT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneOffset.UTC);
    /** Approximate chars-per-token ratio for rough chunking math. */
    private static final int CHARS_PER_TOKEN = 4;
    /** Max tokens per context chunk sent to LLM. */
    private static final int CHUNK_MAX_TOKENS = 8000;
    private static final int CHUNK_MAX_CHARS = CHUNK_MAX_TOKENS * CHARS_PER_TOKEN;
    /** Max tokens for the LLM answer. */
    private static final int ANSWER_MAX_TOKENS = 2048;

    private final SyncOrchestrationService syncOrchestrationService;
    private final MessageRepository messageRepository;
    private final DeepSeekApiClient deepSeekApiClient;

    public SyncController(SyncOrchestrationService syncOrchestrationService,
                          MessageRepository messageRepository,
                          DeepSeekApiClient deepSeekApiClient) {
        this.syncOrchestrationService = syncOrchestrationService;
        this.messageRepository = messageRepository;
        this.deepSeekApiClient = deepSeekApiClient;
    }

    @PostMapping("/jobs")
    @Operation(
        summary = "Initiate chat history sync",
        description = "Starts a new synchronization job for the specified channel with given depth"
    )
    public Mono<ResponseEntity<SyncJobDto>> initiateSync(
            @Valid @RequestBody SyncRequestDto request,
            @Parameter(description = "User ID initiating the sync") 
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {
        
        return syncOrchestrationService.initiateSync(request, userId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(
        summary = "Get sync job status",
        description = "Retrieves detailed status and progress information for a sync job"
    )
    public Mono<ResponseEntity<SyncJobDto>> getSyncJobStatus(@PathVariable Long jobId) {
        return syncOrchestrationService.getSyncJobStatus(jobId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/jobs/{jobId}/progress", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(
        summary = "Get real-time sync progress",
        description = "Server-sent events stream providing real-time progress updates for a sync job"
    )
    public Flux<SyncProgressDto> getSyncProgress(@PathVariable Long jobId) {
        return syncOrchestrationService.getSyncProgress(jobId);
    }

    @PostMapping("/jobs/{jobId}/cancel")
    @Operation(
        summary = "Cancel sync job",
        description = "Cancels a running or pending sync job"
    )
    public Mono<ResponseEntity<SyncJobDto>> cancelSync(
            @PathVariable Long jobId,
            @Parameter(description = "User ID requesting cancellation")
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {
        
        return syncOrchestrationService.cancelSync(jobId, userId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PostMapping("/jobs/{jobId}/retry")
    @Operation(
        summary = "Retry failed sync job",
        description = "Creates a new sync job with the same parameters as a failed job"
    )
    public Mono<ResponseEntity<SyncJobDto>> retrySync(
            @PathVariable Long jobId,
            @Parameter(description = "User ID requesting retry")
            @RequestHeader(value = "X-User-ID", required = false) Long userId) {
        
        return syncOrchestrationService.retrySync(jobId, userId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/channels/{channelId}/jobs")
    @Operation(
        summary = "Get channel sync history",
        description = "Retrieves all sync jobs for a specific channel"
    )
    public Mono<ResponseEntity<List<SyncJobDto>>> getChannelSyncHistory(@PathVariable Long channelId) {
        return syncOrchestrationService.getChannelSyncHistory(channelId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.notFound().build());
    }

    @GetMapping("/users/{userId}/jobs")
    @Operation(
        summary = "Get user sync history",
        description = "Retrieves all sync jobs initiated by a specific user"
    )
    public Mono<ResponseEntity<List<SyncJobDto>>> getUserSyncHistory(@PathVariable Long userId) {
        return syncOrchestrationService.getUserSyncHistory(userId)
                .map(ResponseEntity::ok);
    }

    @GetMapping("/jobs/active")
    @Operation(
        summary = "Get active sync jobs",
        description = "Retrieves all currently running or pending sync jobs across all channels"
    )
    public Mono<ResponseEntity<List<SyncJobDto>>> getActiveSyncJobs() {
        return syncOrchestrationService.getActiveSyncJobs()
                .map(ResponseEntity::ok);
    }

    @GetMapping("/channels/{channelId}/config")
    @Operation(
        summary = "Get sync configuration",
        description = "Retrieves sync configuration settings for a channel"
    )
    public Mono<ResponseEntity<SyncConfigurationDto>> getSyncConfiguration(@PathVariable Long channelId) {
        return syncOrchestrationService.getSyncConfiguration(channelId)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.notFound().build());
    }

    @PutMapping("/channels/{channelId}/config")
    @Operation(
        summary = "Update sync configuration",
        description = "Updates sync configuration settings for a channel"
    )
    public Mono<ResponseEntity<SyncConfigurationDto>> updateSyncConfiguration(
            @PathVariable Long channelId,
            @Valid @RequestBody SyncConfigurationDto config) {
        
        return syncOrchestrationService.updateSyncConfiguration(channelId, config)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PostMapping("/maintenance")
    @Operation(
        summary = "Perform sync maintenance",
        description = "Triggers maintenance tasks like cleaning up old completed jobs"
    )
    public Mono<ResponseEntity<MaintenanceResultDto>> performMaintenance() {
        return syncOrchestrationService.performMaintenance()
                .map(cleanedCount -> ResponseEntity.ok(
                    new MaintenanceResultDto(cleanedCount, "Maintenance completed successfully")
                ))
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }

    @PostMapping("/auto-sync")
    @Operation(
        summary = "Process automatic syncs",
        description = "Checks for and initiates automatic syncs for channels with auto-sync enabled"
    )
    public Mono<ResponseEntity<AutoSyncResultDto>> processAutoSyncs() {
        return syncOrchestrationService.processAutoSyncs()
                .map(initiatedCount -> ResponseEntity.ok(
                    new AutoSyncResultDto(initiatedCount, "Auto-sync processing completed")
                ))
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }

    @GetMapping("/channels/available")
    @Operation(
        summary = "Get available channels for sync",
        description = "Returns channels from tgscan that can be enabled for sync"
    )
    public Mono<ResponseEntity<List<ChannelSyncInfoDto>>> getAvailableChannels(
            @RequestParam(required = false, defaultValue = "0") Integer minSubscribers,
            @RequestParam(required = false, defaultValue = "0.0") Double minWeight,
            @RequestParam(required = false, defaultValue = "50") Integer limit) {
        return syncOrchestrationService.getAvailableChannelsForSync(minSubscribers, minWeight, limit)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.internalServerError().build());
    }

    @PostMapping("/quick-scan")
    @Operation(
        summary = "Quick scan chat history",
        description = "Scans a chat's message history by Telegram chat ID, skipping trivial messages (≤3 words). Auto-detects the bot persona that is a member of the chat."
    )
    public Mono<ResponseEntity<SyncJobDto>> quickScan(@Valid @RequestBody QuickScanRequestDto request) {
        return syncOrchestrationService.quickScan(request)
                .map(ResponseEntity::ok)
                .onErrorResume(e -> Mono.just(ResponseEntity.badRequest().<SyncJobDto>build()));
    }

    @PostMapping("/channels/bulk-enable")
    @Operation(
        summary = "Bulk enable sync for channels",
        description = "Enable sync for multiple channels based on criteria"
    )
    public Mono<ResponseEntity<BulkSyncResultDto>> bulkEnableSync(
            @Valid @RequestBody BulkSyncEnableRequest request) {
        return syncOrchestrationService.bulkEnableSync(request)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @PostMapping("/channels/{channelId}/toggle-sync")
    @Operation(
        summary = "Toggle sync for a channel",
        description = "Enable or disable sync for a specific channel"
    )
    public Mono<ResponseEntity<ChannelSyncInfoDto>> toggleChannelSync(
            @PathVariable Long channelId,
            @RequestParam boolean enabled) {
        return syncOrchestrationService.toggleChannelSync(channelId, enabled)
                .map(ResponseEntity::ok)
                .onErrorReturn(ResponseEntity.badRequest().build());
    }

    @GetMapping("/channels/{chatId}/export")
    @Operation(summary = "Export chat history as Markdown", description = "Downloads chat messages as a readable .md file for the specified depth in days")
    public Mono<ResponseEntity<byte[]>> exportChatHistory(
            @PathVariable Long chatId,
            @RequestParam(defaultValue = "7") int days) {
        Instant since = Instant.now().minus(days, ChronoUnit.DAYS);
        return messageRepository.findByChatIdAndDateAfterOrderByDateAsc(chatId, since)
                .collectList()
                .map(messages -> {
                    StringBuilder sb = new StringBuilder();
                    sb.append("# Chat export — ID: ").append(chatId).append("\n");
                    sb.append("**Period:** last ").append(days).append(" days  \n");
                    sb.append("**Messages:** ").append(messages.size()).append("  \n");
                    sb.append("**Exported:** ").append(EXPORT_FMT.format(Instant.now())).append(" UTC\n\n");
                    sb.append("---\n\n");
                    String lastDay = "";
                    for (MessageEntity msg : messages) {
                        String day = DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneOffset.UTC).format(msg.getDate());
                        if (!day.equals(lastDay)) {
                            sb.append("## ").append(day).append("\n\n");
                            lastDay = day;
                        }
                        String time = DateTimeFormatter.ofPattern("HH:mm").withZone(ZoneOffset.UTC).format(msg.getDate());
                        String author = msg.getSenderName() != null ? msg.getSenderName() : (msg.isOutgoing() ? "Bot" : "Unknown");
                        sb.append("**").append(escapeMarkdown(author)).append("** `").append(time).append("`");
                        if (msg.getReplyToMessageId() != null) {
                            sb.append(" ↩");
                        }
                        sb.append("  \n");
                        if (msg.getContent() != null && !msg.getContent().isBlank()) {
                            sb.append(msg.getContent().trim()).append("\n");
                        } else if (msg.getMediaType() != null) {
                            sb.append("_[").append(msg.getMediaType().name().toLowerCase()).append("]_\n");
                        }
                        sb.append("\n");
                    }
                    byte[] bytes = sb.toString().getBytes(StandardCharsets.UTF_8);
                    HttpHeaders headers = new HttpHeaders();
                    headers.setContentType(MediaType.valueOf("text/markdown;charset=UTF-8"));
                    headers.setContentDisposition(ContentDisposition.attachment()
                            .filename("chat-" + chatId + "-" + days + "d.md").build());
                    return ResponseEntity.ok().headers(headers).body(bytes);
                });
    }

    private static String escapeMarkdown(String text) {
        return text.replace("*", "\\*").replace("_", "\\_").replace("`", "\\`").replace("[", "\\[");
    }

    /**
     * Asks the LLM a question based on a chunk of the chat history.
     * Messages are stripped of author names and timestamps — only content is sent.
     * The caller specifies which chunk (0-based) to include as context.
     */
    @PostMapping("/channels/{chatId}/ask")
    @Operation(summary = "Ask LLM a question about chat history",
            description = "Sends a chunk of the chat's message content (stripped of metadata) to the LLM together with the user's question")
    public Mono<ResponseEntity<ChatAskResponseDto>> askLlm(
            @PathVariable Long chatId,
            @RequestBody @Valid ChatAskRequestDto request) {
        Instant since = request.days() != null
                ? Instant.now().minus(request.days(), ChronoUnit.DAYS)
                : Instant.EPOCH;
        return messageRepository.findByChatIdAndDateAfterOrderByDateAsc(chatId, since)
                .filter(msg -> msg.getContent() != null && !msg.getContent().isBlank())
                .map(msg -> msg.getContent().trim())
                .collectList()
                .flatMap(lines -> {
                    List<String> chunks = splitIntoChunks(lines, CHUNK_MAX_CHARS);
                    int totalChunks = chunks.isEmpty() ? 1 : chunks.size();
                    int idx = Math.max(0, Math.min(request.chunkIndex(), totalChunks - 1));
                    String chunkText = chunks.isEmpty() ? "(нет сообщений)" : chunks.get(idx);
                    String systemPrompt = """
                            Ты — аналитик чата. Тебе дан фрагмент истории переписки (только текст сообщений, без имён и меток времени).
                            Отвечай на вопрос пользователя, опираясь исключительно на предоставленный контекст.
                            Если ответ в данном фрагменте не найден — прямо скажи об этом и предложи проверить следующий фрагмент.
                            Отвечай на языке вопроса.""";
                    String userContent = "Контекст переписки (фрагмент " + (idx + 1) + " из " + totalChunks + "):\n\n"
                            + chunkText + "\n\n---\nВопрос: " + request.question();
                    List<ApiMessage> messages = List.of(
                            new ApiMessage("system", systemPrompt),
                            new ApiMessage("user", userContent)
                    );
                    DeepSeekChatRequest llmRequest = new DeepSeekChatRequest(
                            messages, null, ANSWER_MAX_TOKENS, 0.3);
                    return deepSeekApiClient.chat(llmRequest, chatId, 90)
                            .map(answer -> ResponseEntity.ok(new ChatAskResponseDto(
                                    answer, idx, totalChunks, idx < totalChunks - 1)))
                            .defaultIfEmpty(ResponseEntity.ok(new ChatAskResponseDto(
                                    "LLM не вернул ответ. Попробуйте позже.", idx, totalChunks, idx < totalChunks - 1)));
                });
    }

    /** Splits lines of text into chunks that fit within maxChars each. */
    private static List<String> splitIntoChunks(List<String> lines, int maxChars) {
        List<String> chunks = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        for (String line : lines) {
            if (current.length() + line.length() + 1 > maxChars && !current.isEmpty()) {
                chunks.add(current.toString().trim());
                current = new StringBuilder();
            }
            current.append(line).append('\n');
        }
        if (!current.isEmpty()) {
            chunks.add(current.toString().trim());
        }
        return chunks;
    }

    /**
     * Request DTO for the LLM chat-history Q&A endpoint.
     */
    public record ChatAskRequestDto(
            @NotBlank(message = "Question is required")
            @Size(max = 2000, message = "Question must not exceed 2000 characters")
            String question,
            Integer days,
            int chunkIndex
    ) {}

    /**
     * Response DTO for the LLM chat-history Q&A endpoint.
     */
    public record ChatAskResponseDto(
            String answer,
            int chunkIndex,
            int totalChunks,
            boolean hasMore
    ) {}

    /**
     * Result DTO for maintenance operations.
     */
    public record MaintenanceResultDto(
            Integer itemsProcessed,
            String message
    ) {}

    /**
     * Result DTO for auto-sync operations.
     */
    public record AutoSyncResultDto(
            Integer syncsInitiated,
            String message
    ) {}

    /**
     * DTO for channel sync info.
     */
    public record ChannelSyncInfoDto(
            Long channelId,
            String title,
            String username,
            Integer subscribers,
            Double weight,
            boolean syncEnabled,
            String joinStatus
    ) {}

    /**
     * Request for bulk enabling sync.
     */
    public record BulkSyncEnableRequest(
            Integer minSubscribers,
            Double minWeight,
            List<Long> channelIds,
            boolean enable
    ) {}

    /**
     * Result DTO for bulk sync operations.
     */
    public record BulkSyncResultDto(
            Integer channelsProcessed,
            Integer channelsEnabled,
            Integer channelsDisabled,
            String message
    ) {}
}
