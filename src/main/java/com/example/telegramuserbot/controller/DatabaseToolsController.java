package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.dto.MessageCountDto;
import com.example.telegramuserbot.dto.MessagePurgeRequestDto;
import com.example.telegramuserbot.dto.MessagePurgeResultDto;
import com.example.telegramuserbot.repository.MessageRepository;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/admin/db")
@Tag(name = "Database Tools", description = "Admin database tools (safe data operations)")
public class DatabaseToolsController {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");

    private final MessageRepository messageRepository;

    public DatabaseToolsController(MessageRepository messageRepository) {
        this.messageRepository = messageRepository;
    }

    @GetMapping("/messages/{chatId}/count")
    @Operation(summary = "Count messages for chat", description = "Returns how many records exist in bot.messages for a given chatId")
    public Mono<ResponseEntity<MessageCountDto>> countMessages(
            @Parameter(description = "Telegram chat/channel ID") @PathVariable long chatId) {
        uiLog.info("UI:countMessages chatId={}", chatId);
        return messageRepository.countByChatId(chatId)
                .defaultIfEmpty(0L)
                .map(count -> ResponseEntity.ok(new MessageCountDto(chatId, count)));
    }

    @PostMapping("/messages/purge")
    @Operation(summary = "Purge messages for chat", description = "Deletes all records from bot.messages for the given chatId. Requires confirm_chat_id == chat_id.")
    public Mono<ResponseEntity<MessagePurgeResultDto>> purgeMessages(@RequestBody MessagePurgeRequestDto request) {
        if (request == null) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        long chatId = request.chatId();
        if (chatId == 0 || request.confirmChatId() != chatId) {
            return Mono.just(ResponseEntity.badRequest().build());
        }
        uiLog.warn("UI:purgeMessages chatId={} (confirmed)", chatId);

        return messageRepository.countByChatId(chatId)
                .defaultIfEmpty(0L)
                .flatMap(before -> messageRepository.purgeByChatId(chatId)
                        .defaultIfEmpty(0)
                        .map(deleted -> ResponseEntity.ok(new MessagePurgeResultDto(
                                chatId,
                                before,
                                deleted.longValue()
                        ))));
    }
}

