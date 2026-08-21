package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.telegram.FilteredTdLibLogHandler;
import com.example.telegramuserbot.telegram.TdLibOperationCoordinator;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Debug controller for testing direct TDLib API calls.
 * Helps diagnose chat discovery issues.
 *
 * <p>IMPORTANT: LoadChats operations MUST go through the TdLibOperationCoordinator
 * to prevent dialog date inconsistency errors.</p>
 */
@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/telegram-debug")
public class TelegramDebugController {

    private static final Logger log = LoggerFactory.getLogger(TelegramDebugController.class);

    private final TdLibOperationCoordinator coordinator;
    private final TelegramClientManager clientManager;

    public TelegramDebugController(TdLibOperationCoordinator coordinator,
                                   TelegramClientManager clientManager) {
        this.coordinator = coordinator;
        this.clientManager = clientManager;
    }
    
    /**
     * Test GetMe API call.
     */
    @GetMapping("/test-getme")
    public Mono<ResponseEntity<String>> testGetMe() {
        TelegramClientFacade client = clientManager.getAnyClient();
        if (client == null) {
            return Mono.just(ResponseEntity.status(503).body("No Telegram client available"));
        }
        return Mono.fromFuture(() -> client.send(new TdApi.GetMe()))
                .cast(TdApi.User.class)
                .map(user -> {
                    String username = "None";
                    if (user.usernames != null && user.usernames.activeUsernames != null &&
                        user.usernames.activeUsernames.length > 0) {
                        username = user.usernames.activeUsernames[0];
                    }

                    boolean isBot = user.type instanceof TdApi.UserTypeBot;

                    return ResponseEntity.ok(String.format(
                            "GetMe Test Results:\n" +
                            "ID: %d\n" +
                            "Name: %s %s\n" +
                            "Username: @%s\n" +
                            "Is Bot: %s\n" +
                            "User Type: %s\n" +
                            "Status: SUCCESS - TDLib connection is working",
                            user.id, user.firstName, user.lastName, 
                            username, isBot, user.type.getClass().getSimpleName()
                    ));
                })
                .onErrorReturn(ResponseEntity.status(500).body("GetMe API call failed"));
    }

    /**
     * Get info about all registered Telegram clients.
     * Returns a list of all initialized clients with their user info.
     */
    @GetMapping("/clients")
    public Mono<ResponseEntity<List<Map<String, Object>>>> getAllClients() {
        List<String> botIds = clientManager.getAllBotIds();
        if (botIds.isEmpty()) {
            return Mono.just(ResponseEntity.ok(List.of()));
        }
        return Flux.fromIterable(botIds)
                .flatMap(botId -> {
                    TelegramClientFacade facade = clientManager.getClient(botId);
                    if (facade == null) {
                        Map<String, Object> errorInfo = new LinkedHashMap<>();
                        errorInfo.put("botId", botId);
                        errorInfo.put("status", "NOT_INITIALIZED");
                        return Mono.just(errorInfo);
                    }
                    return Mono.fromFuture(() -> facade.send(new TdApi.GetMe()))
                            .cast(TdApi.User.class)
                            .map(user -> {
                                Map<String, Object> info = new LinkedHashMap<>();
                                info.put("botId", botId);
                                info.put("id", user.id);
                                info.put("name", (user.firstName + " " + (user.lastName != null ? user.lastName : "")).trim());
                                String username = "None";
                                if (user.usernames != null && user.usernames.activeUsernames != null &&
                                        user.usernames.activeUsernames.length > 0) {
                                    username = user.usernames.activeUsernames[0];
                                }
                                info.put("username", username);
                                info.put("isBot", user.type instanceof TdApi.UserTypeBot);
                                info.put("userType", user.type.getClass().getSimpleName());
                                info.put("status", "CONNECTED");
                                return info;
                            })
                            .onErrorResume(e -> {
                                Map<String, Object> errorInfo = new LinkedHashMap<>();
                                errorInfo.put("botId", botId);
                                errorInfo.put("status", "ERROR");
                                errorInfo.put("error", e.getMessage());
                                return Mono.just(errorInfo);
                            });
                })
                .collectList()
                .map(ResponseEntity::ok);
    }

    /**
     * Direct test of GetChats API call.
     */
    @PostMapping("/test-getchats")
    public Mono<ResponseEntity<String>> testGetChats() {
        TelegramClientFacade clientForChats = clientManager.getAnyClient();
        if (clientForChats == null) {
            return Mono.just(ResponseEntity.status(503).body("No Telegram client available"));
        }
        return Mono.fromFuture(() -> {
            log.info("Testing direct GetChats API call...");
            TdApi.GetChats getChats = new TdApi.GetChats(new TdApi.ChatListMain(), 100);
            return clientForChats.send(getChats);
        })
        .cast(TdApi.Chats.class)
        .map(chats -> {
            StringBuilder result = new StringBuilder();
            result.append("GetChats Test Results:\n");
            result.append(String.format("Chats found: %d\n\n", chats.chatIds.length));
            
            if (chats.chatIds.length == 0) {
                result.append("NO CHATS FOUND!\n");
                result.append("Possible reasons:\n");
                result.append("1. Chats need to be loaded first with LoadChats\n");
                result.append("2. User has no active chats\n");
                result.append("3. TDLib session is not properly initialized\n");
                result.append("4. Authorization state is not ready\n");
            } else {
                result.append("Chat IDs found:\n");
                for (long chatId : chats.chatIds) {
                    result.append(String.format("- %d\n", chatId));
                }
            }
            
            return ResponseEntity.ok(result.toString());
        })
        .onErrorResume(error -> {
            log.error("GetChats failed", error);
            return Mono.just(ResponseEntity.status(500).body(
                    "GetChats API call failed:\n" + 
                    "Error: " + error.getMessage() + "\n" +
                    "This indicates a TDLib integration issue."
            ));
        });
    }
    
    /**
     * Test LoadChats followed by GetChats.
     *
     * <p>Uses TdLibOperationCoordinator to ensure LoadChats is serialized,
     * preventing the dialog date inconsistency error.</p>
     */
    @PostMapping("/test-loadchats")
    public Mono<ResponseEntity<String>> testLoadChats() {
        log.info("Testing LoadChats (via coordinator) followed by GetChats...");
        return coordinator.loadChatsSequentially(new TdApi.ChatListMain(), 100)
                .delayElement(java.time.Duration.ofSeconds(2))
                .then(Mono.fromFuture(() -> {
                    TelegramClientFacade clientForLoad = clientManager.getAnyClient();
                    TdApi.GetChats getChats = new TdApi.GetChats(new TdApi.ChatListMain(), 100);
                    if (clientForLoad == null) {
                        throw new IllegalStateException("No Telegram client available");
                    }
                    return clientForLoad.send(getChats);
                }))
                .cast(TdApi.Chats.class)
                .map(chats -> ResponseEntity.ok(String.format(
                        "LoadChats + GetChats Test Results:\n" +
                        "Step 1: LoadChats (via coordinator) - SUCCESS\n" +
                        "Step 2: GetChats - Found %d chats\n" +
                        "Status: %s",
                        chats.chatIds.length,
                        chats.chatIds.length > 0 ? "SUCCESS - Chats discovered!" : "NO CHATS - May need investigation"
                )))
                .onErrorResume(error -> {
                    log.error("LoadChats test failed", error);
                    return Mono.just(ResponseEntity.status(500).body(
                            "LoadChats + GetChats test failed:\n" +
                            "Error: " + error.getMessage()
                    ));
                });
    }
    
    /**
     * Get details of a specific chat.
     */
    @GetMapping("/chat/{chatId}")
    public Mono<ResponseEntity<String>> getChatInfo(@PathVariable Long chatId) {
        TelegramClientFacade clientForChat = clientManager.getAnyClient();
        if (clientForChat == null) {
            return Mono.just(ResponseEntity.status(503).body("No Telegram client available"));
        }
        return Mono.fromFuture(() -> clientForChat.send(new TdApi.GetChat(chatId)))
                .cast(TdApi.Chat.class)
                .map(chat -> {
                    return ResponseEntity.ok(String.format(
                            "Chat Info for ID %d:\n" +
                            "Title: %s\n" +
                            "Type: %s\n" +
                            "Last Message: %s\n" +
                            "Member Count: %s\n" +
                            "Status: Chat exists and is accessible",
                            chatId, chat.title, chat.type.getClass().getSimpleName(),
                            chat.lastMessage != null ? "Yes" : "None",
                            "Unknown" // We'd need additional API calls to get member count
                    ));
                })
                .onErrorReturn(ResponseEntity.status(404).body(
                        String.format("Chat %d not found or not accessible", chatId)
                ));
    }

    /**
     * Attempts to repair corrupted TDLib dialog date state.
     *
     * <p>Use this endpoint when you see the error:
     * "Last server dialog date didn't increase from X to Y"</p>
     *
     * <p>This tries to reset the internal pagination state without deleting
     * the TDLib database directory.</p>
     */
    @PostMapping("/repair-dialog-state")
    public Mono<ResponseEntity<String>> repairDialogState() {
        log.info("Received request to repair TDLib dialog state...");
        return coordinator.repairDialogState()
                .map(result -> {
                    if (result.success()) {
                        return ResponseEntity.ok(String.format(
                                "Dialog State Repair Results:\n" +
                                "Status: SUCCESS\n" +
                                "Message: %s\n\n" +
                                "You can now try using LoadChats again.",
                                result.message()
                        ));
                    } else {
                        return ResponseEntity.status(500).body(String.format(
                                "Dialog State Repair Results:\n" +
                                "Status: FAILED\n" +
                                "Message: %s\n\n" +
                                "If the error persists, you may need to:\n" +
                                "1. Stop the application\n" +
                                "2. Delete the tdlib-sessions/ directory\n" +
                                "3. Restart the application",
                                result.message()
                        ));
                    }
                })
                .onErrorResume(error -> {
                    log.error("Dialog state repair failed with exception", error);
                    return Mono.just(ResponseEntity.status(500).body(
                            "Dialog State Repair Failed:\n" +
                            "Error: " + error.getMessage()
                    ));
                });
    }

    /**
     * Gets the current TDLib coordinator state for diagnostics.
     */
    @GetMapping("/coordinator-status")
    public Mono<ResponseEntity<String>> getCoordinatorStatus() {
        return Mono.fromCallable(() -> {
            String status = String.format(
                    "TDLib Coordinator Status:\n" +
                    "State: %s\n" +
                    "Operation In Progress: %s\n" +
                    "Current Operation: %s\n" +
                    "Operation Duration: %s",
                    coordinator.getState(),
                    coordinator.isOperationInProgress(),
                    coordinator.getCurrentOperation() != null ? coordinator.getCurrentOperation() : "None",
                    coordinator.getCurrentOperationDuration()
            );
            return ResponseEntity.ok(status);
        });
    }

    /**
     * Gets TDLib log filtering metrics to monitor for overhead.
     *
     * <p>If dialogDateWarnings keeps growing after startup, there may be
     * an underlying issue causing repeated warnings.</p>
     *
     * <p>Expected behavior:</p>
     * <ul>
     *   <li>dialogDateWarnings increases during multi-client startup (normal)</li>
     *   <li>dialogDateWarnings stops growing after startup completes (healthy)</li>
     *   <li>dialogDateWarnings keeps growing indefinitely (investigate!)</li>
     * </ul>
     */
    @GetMapping("/tdlib-log-metrics")
    public Mono<ResponseEntity<String>> getTdLibLogMetrics() {
        return Mono.fromCallable(() -> {
            var metrics = FilteredTdLibLogHandler.getMetrics();
            String status = String.format(
                    "TDLib Log Handler Metrics:\n" +
                    "========================\n" +
                    "Total Messages:        %d\n" +
                    "Filtered Messages:     %d\n" +
                    "Dialog Date Warnings:  %d\n" +
                    "Filter Ratio:          %.2f%%\n" +
                    "========================\n\n" +
                    "Interpretation:\n" +
                    "- If 'Dialog Date Warnings' stopped growing after startup = HEALTHY\n" +
                    "- If 'Dialog Date Warnings' keeps growing = INVESTIGATE\n" +
                    "- High filter ratio is normal (TDLib is verbose)\n",
                    metrics.totalMessages(),
                    metrics.filteredMessages(),
                    metrics.dialogDateWarnings(),
                    metrics.filterRatio() * 100
            );
            return ResponseEntity.ok(status);
        });
    }

    /**
     * Resets TDLib log metrics counters.
     * Useful for monitoring from a clean state.
     */
    @PostMapping("/tdlib-log-metrics/reset")
    public Mono<ResponseEntity<String>> resetTdLibLogMetrics() {
        return Mono.fromCallable(() -> {
            FilteredTdLibLogHandler.resetMetrics();
            return ResponseEntity.ok("TDLib log metrics reset to zero");
        });
    }
}
