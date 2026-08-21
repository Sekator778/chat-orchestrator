package com.example.telegramuserbot.service.persistence;

import com.example.telegramuserbot.domain.MediaKind;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.domain.MessageType;
import com.example.telegramuserbot.repository.ChatMessageStatsRepository;
import com.example.telegramuserbot.repository.MessageRepository;
import com.example.telegramuserbot.repository.ChannelRepository;
import com.example.telegramuserbot.service.MediaStorageService;
import com.example.telegramuserbot.service.cache.SyncEnabledChatsCache;
import com.example.telegramuserbot.service.util.MessageTypeClassifier;
import com.example.telegramuserbot.util.TelegramChatIdUtils;
import it.tdlight.jni.TdApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Lazy;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

@Service
public class MessagePersistenceServiceImpl implements MessagePersistenceService {

    private static final Logger log = LoggerFactory.getLogger(MessagePersistenceServiceImpl.class);
    private static final String KAFKA_ERROR_PREFIX = "🚨 KAFKA PROCESSING ERROR";

    private final MessageRepository messageRepository;
    private final ChannelRepository channelRepository;
    private final MediaStorageService mediaStorageService;
    private final ExecutorService mediaExecutor;
    private final MessageTypeClassifier messageTypeClassifier;
    private final Long allowedCommandChatId;
    private final SyncEnabledChatsCache syncEnabledChatsCache;
    private final ChatMessageStatsRepository chatMessageStatsRepository;
    private final MessageEntityHydrator messageEntityHydrator;
    private ReplyChainBackfillService backfillService;

    /**
     * Kill-switch for media (photo/video/document/…) download + storage. Default OFF:
     * harvested media is currently UNUSED downstream (personas post text; captions live in
     * a separate column) and the collector's TDLib {@code downloadFile} was failing 100% of
     * the time (30s timeouts) — thousands of pointless API calls/day on the irreplaceable
     * collector account. Stubbed until there is a real product use for images/video, at which
     * point the root-cause download failure must also be fixed. Flip via
     * {@code media.download.enabled=true}.
     */
    @Value("${media.download.enabled:false}")
    private boolean mediaDownloadEnabled;

    public MessagePersistenceServiceImpl(
            MessageRepository messageRepository,
            ChannelRepository channelRepository,
            MediaStorageService mediaStorageService,
            @Qualifier("mediaTaskExecutor") ExecutorService mediaExecutor,
            MessageTypeClassifier messageTypeClassifier,
            @Qualifier("allowedCommandChatId") Long allowedCommandChatId,
            SyncEnabledChatsCache syncEnabledChatsCache,
            ChatMessageStatsRepository chatMessageStatsRepository,
            MessageEntityHydrator messageEntityHydrator
    ) {
        this.messageRepository = messageRepository;
        this.channelRepository = channelRepository;
        this.mediaStorageService = mediaStorageService;
        this.mediaExecutor = mediaExecutor;
        this.messageTypeClassifier = messageTypeClassifier;
        this.allowedCommandChatId = allowedCommandChatId;
        this.syncEnabledChatsCache = syncEnabledChatsCache;
        this.chatMessageStatsRepository = chatMessageStatsRepository;
        this.messageEntityHydrator = messageEntityHydrator;

        log.info("MessagePersistenceService initialized (allowedCommandChatId={}, mediaExecutor={}, classifier={}, syncCache={})",
                allowedCommandChatId,
                mediaExecutor != null ? mediaExecutor.getClass().getSimpleName() : "none",
                messageTypeClassifier != null ? messageTypeClassifier.getClass().getSimpleName() : "none",
                syncEnabledChatsCache != null ? syncEnabledChatsCache.getClass().getSimpleName() : "none");
    }

    @Autowired
    public void setBackfillService(@Lazy ReplyChainBackfillService backfillService) {
        this.backfillService = backfillService;
    }

    @Override
    public Mono<MessageEntity> persistMessage(String botInstanceId, long chatId, TdApi.Message msg) {
        return syncEnabledChatsCache.syncEnabled(chatId)
                .onErrorResume(error -> {
                    log.warn("[Chat {}] Failed to resolve sync-enabled flag, proceeding as enabled. Reason: {}", chatId, error.getMessage());
                    return Mono.just(true);
                })
                .flatMap(syncEnabled -> {
                    if (!syncEnabled) {
                        log.debug("[Chat {}] Message synchronization disabled, skipping message {}", chatId, msg.id);
                        return Mono.empty();
                    }
                    return doSaveMessage(botInstanceId, chatId, msg);
                });
    }

    @Override
    public Mono<MessageEntity> forcePersistMessage(String botInstanceId, long chatId, TdApi.Message msg) {
        return doSaveMessage(botInstanceId, chatId, msg);
    }

    private Mono<MessageEntity> doSaveMessage(String botInstanceId, long chatId, TdApi.Message msg) {
        return messageEntityHydrator.create(chatId, msg, botInstanceId)
                .flatMap(entity -> {
                    if (shouldSkipPersistence(entity)) {
                        log.debug("[Chat {}] Skipping persistence of system notification message {}", chatId, msg.id);
                        return Mono.empty();
                    }
                    return messageRepository.save(entity)
                            .doOnSuccess(savedEntity -> log.debug("--- MessageEntity saved (id={}) for msgId={}. Triggering async media handling.",
                                    savedEntity.getId(), msg.id))
                            .map(PersistenceOutcome::created)
                            .onErrorResume(DuplicateKeyException.class, e -> {
                                log.debug("--- Duplicate message {} in chat {}, updating existing record.", msg.id, chatId);
                                return messageRepository.findByChatIdAndMessageId(chatId, msg.id)
                                        .switchIfEmpty(Mono.defer(() -> {
                                            log.error("[Chat {}] Duplicate detected for message {} but record not found in DB.", chatId, msg.id);
                                            return Mono.error(e);
                                        }))
                                        .flatMap(existingEntity -> updateExistingMessage(existingEntity, msg, chatId, botInstanceId))
                                        .map(PersistenceOutcome::existing);
                            })
                            .flatMap(result -> processPostPersistence(result, chatId))
                            .doOnNext(result -> handleMediaAsync(msg, chatId, result.entity().getMediaType()).subscribe())
                            .map(PersistenceOutcome::entity);
                })
                .switchIfEmpty(Mono.empty());
    }

    @Override
    public Mono<MessageEntity> updateMessageAfterSend(String botInstanceId, long chatId, long temporaryMessageId, TdApi.Message finalMessage) {
        // Using original TDLib chat ID directly - no normalization or fallback needed

        return messageRepository.findByChatIdAndMessageId(chatId, temporaryMessageId)
                .flatMap(entity -> {
                    long oldMessageId = entity.getMessageId();
                    updateEntityFromFinalMessage(entity, finalMessage);
                    return messageEntityHydrator.enrichSenderIfMissing(entity, finalMessage.senderId, botInstanceId)
                            .flatMap(ignored -> updateChannelReference(entity, entity.getForwardFromChatId())
                                    .flatMap(messageRepository::save)
                                    .doOnSuccess(saved -> log.debug("[Chat {}] Updated outgoing message provisionalId={} -> finalId={}", chatId, oldMessageId, finalMessage.id))
                                    .onErrorResume(DuplicateKeyException.class, ex ->
                                            mergeDuplicateAfterSend(botInstanceId, chatId, oldMessageId, finalMessage, entity)));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.warn("[Chat {}] Provisional message {} not found. Persisting final message as new record.", chatId, temporaryMessageId);
                    return chatId > 0
                            ? forcePersistMessage(botInstanceId, chatId, finalMessage)
                            : persistMessage(botInstanceId, chatId, finalMessage);
                }));
    }

    private Mono<MessageEntity> mergeDuplicateAfterSend(String botInstanceId,
                                                       long chatId,
                                                       long provisionalMessageId,
                                                       TdApi.Message finalMessage,
                                                       MessageEntity provisionalEntity) {
        return messageRepository.findByChatIdAndMessageId(chatId, finalMessage.id)
                .flatMap(existing -> {
                    updateEntityFromFinalMessage(existing, finalMessage);
                    return messageEntityHydrator.enrichSenderIfMissing(existing, finalMessage.senderId, botInstanceId)
                            .flatMap(ignored -> updateChannelReference(existing, existing.getForwardFromChatId())
                                    .flatMap(messageRepository::save)
                                    .flatMap(saved -> messageRepository.delete(provisionalEntity).thenReturn(saved))
                                    .doOnSuccess(saved -> log.warn("[Chat {}] Merged duplicate outgoing message provisionalId={} into finalId={}",
                                            chatId, provisionalMessageId, finalMessage.id)));
                })
                .switchIfEmpty(Mono.defer(() -> {
                    log.error("[Chat {}] Duplicate detected for final message {} but record not found. Retrying persist.",
                            chatId, finalMessage.id);
                    return chatId > 0
                            ? forcePersistMessage(botInstanceId, chatId, finalMessage)
                            : persistMessage(botInstanceId, chatId, finalMessage);
                }));
    }

    private Mono<PersistenceOutcome> processPostPersistence(PersistenceOutcome outcome, long chatId) {
        MessageEntity savedOrUpdated = outcome.entity();
        Mono<MessageEntity> backfilled;
        if (savedOrUpdated.getReplyToMessageId() != null && backfillService != null) {
            Long parentChatId = savedOrUpdated.getReplyToChatId() != null ? savedOrUpdated.getReplyToChatId() : chatId;
            backfilled = backfillService.backfillIfMissing(parentChatId, savedOrUpdated.getReplyToMessageId())
                    .thenReturn(savedOrUpdated);
        } else {
            backfilled = Mono.just(savedOrUpdated);
        }

        return backfilled
                .flatMap(processed -> recordHumanIfNeeded(processed, outcome.newlyCreated())
                        .thenReturn(outcome.withEntity(processed)));
    }

    private Mono<Void> recordHumanIfNeeded(MessageEntity savedEntity, boolean newlyCreated) {
        if (!newlyCreated) {
            log.debug("[Chat {}] Skipping human count increment - message is duplicate (msgId={})",
                    savedEntity.getChatId(), savedEntity.getMessageId());
            return Mono.empty();
        }

        if (!isHumanMessage(savedEntity)) {
            log.debug("[Chat {}] Skipping human count increment - not human message (msgId={}, outgoing={}, senderId={})",
                    savedEntity.getChatId(), savedEntity.getMessageId(), savedEntity.isOutgoing(), savedEntity.getSenderId());
            return Mono.empty();
        }

        Long chatId = savedEntity.getChatId();
        return chatMessageStatsRepository.incrementHumanCount(chatId)
                .doOnSuccess(count -> log.info("[Chat {}] human_message_count incremented to {} for msgId={}", chatId, count, savedEntity.getMessageId()))
                .onErrorResume(error -> {
                    log.error("[Chat {}] Failed to increment human_message_count: {}", chatId, error.getMessage(), error);
                    return Mono.empty();
                })
                .then();
    }

    private boolean isHumanMessage(MessageEntity entity) {
        if (entity == null || entity.isOutgoing()) {
            return false;
        }

        Long senderId = entity.getSenderId();
        if (senderId == null) {
            return false;
        }

        // Human messages have positive sender_id (user ID)
        // Messages from channels/groups (senderId < 0) are NOT human
        return senderId > 0;
    }

    private Mono<MessageEntity> updateExistingMessage(MessageEntity existingEntity, TdApi.Message msg, long chatId, String botInstanceId) {
        boolean updated = false;
        Instant newEditDate = (msg.editDate > 0) ? Instant.ofEpochSecond(msg.editDate) : null;
        String newContent = extractTextContentOnly(msg.content);
        String newCaption = extractCaptionText(msg.content);
        MediaKind newMediaType = determineMediaKind(msg.content);
        String serializedMessage = serializeMessage(msg);

        if (!Objects.equals(existingEntity.getContent(), newContent)) {
            existingEntity.setContent(newContent);
            updated = true;
        }
        if (!Objects.equals(existingEntity.getCaption(), newCaption)) {
            existingEntity.setCaption(newCaption);
            updated = true;
        }
        if (!Objects.equals(existingEntity.getMediaType(), newMediaType)) {
            existingEntity.setMediaType(newMediaType);
            updated = true;
        }
        if (!Objects.equals(existingEntity.getEditDate(), newEditDate)) {
            existingEntity.setEditDate(newEditDate);
            updated = true;
        }

        ReplyMetadata newReply = extractReplyMetadata(msg.replyTo);
        if (!Objects.equals(existingEntity.getReplyToMessageId(), newReply.messageId()) || !Objects.equals(existingEntity.getReplyToChatId(), newReply.chatId())) {
            existingEntity.setReplyToMessageId(newReply.messageId());
            existingEntity.setReplyToChatId(newReply.chatId());
            updated = true;
        }

        Long newForwardFromChatId = extractForwardFromChatId(msg.forwardInfo, msg.senderId);
        if (!Objects.equals(existingEntity.getForwardFromChatId(), newForwardFromChatId)) {
            existingEntity.setForwardFromChatId(newForwardFromChatId);
            updated = true;
        }

        if (!Objects.equals(existingEntity.getRawMessageDump(), serializedMessage)) {
            existingEntity.setRawMessageDump(serializedMessage);
            updated = true;
        }

        boolean updatedEntity = updated;
        return messageEntityHydrator.enrichSenderIfMissing(existingEntity, msg.senderId, botInstanceId)
                .flatMap(senderUpdated -> {
                    boolean needsSave = updatedEntity || Boolean.TRUE.equals(senderUpdated);
                    if (!needsSave) {
                        log.trace("Message {}/{} already exists and is up-to-date. Skipping.", chatId, msg.id);
                        return Mono.just(existingEntity);
                    }
                    log.debug("[Chat {}] Updating message {}", chatId, msg.id);
                    messageTypeClassifier.classifyAndSetMessageType(existingEntity);
                    return updateChannelReference(existingEntity, newForwardFromChatId)
                            .flatMap(messageRepository::save)
                            .doOnSuccess(saved -> log.debug("[Chat {}] Updated existing message {} with type {}", chatId, msg.id, saved.getMessageType()));
                });
    }

    private Mono<MessageEntity> createNewMessage(long chatId, TdApi.Message msg) {
        log.debug(">>> Persisting NEW message: chatId={}, msgId={}, outgoing={}, contentType={}",
                chatId, msg.id, msg.isOutgoing, msg.content.getClass().getSimpleName());
        MessageEntity entity = createMessageEntity(chatId, msg);

        return updateChannelReference(entity, entity.getForwardFromChatId())
                .flatMap(messageRepository::save)
                .doOnSuccess(savedEntity -> {
                    log.debug("--- MessageEntity saved (id={}) for msgId={}. Triggering async media handling.",
                            savedEntity.getId(), msg.id);
                    handleMediaAsync(msg, chatId, savedEntity.getMediaType()).subscribe();
                });
    }

    private MessageEntity createMessageEntity(long chatId, TdApi.Message msg) {
        MessageEntity e = new MessageEntity();
        e.setChatId(chatId);
        e.setMessageId(msg.id);
        e.setTelegramMessageId(msg.id);
        e.setOutgoing(msg.isOutgoing);
        e.setDate(Instant.ofEpochSecond(msg.date));
        e.setEditDate(msg.editDate > 0 ? Instant.ofEpochSecond(msg.editDate) : null);
        e.setMediaType(determineMediaKind(msg.content));
        e.setMediaFilePath(null);
        e.setContent(extractTextContentOnly(msg.content));
        e.setCaption(extractCaptionText(msg.content));

        setSenderInfo(e, msg.senderId);
        applyReplyMetadata(e, msg.replyTo);
        Long forwardFromChatId = extractForwardFromChatId(msg.forwardInfo, msg.senderId);
        e.setForwardFromChatId(forwardFromChatId);

        if (msg.interactionInfo != null) {
            e.setViews((long) msg.interactionInfo.viewCount);
            e.setForwards((long) msg.interactionInfo.forwardCount);
        }

        e.setRawMessageDump(serializeMessage(msg));

        messageTypeClassifier.classifyAndSetMessageType(e);
        log.debug("Classified message {} as {} with senderId {}", msg.id, e.getMessageType(), e.getSenderId());

        return e;
    }

    private void updateEntityFromFinalMessage(MessageEntity entity, TdApi.Message finalMessage) {
        entity.setMessageId(finalMessage.id);
        entity.setTelegramMessageId(finalMessage.id);
        entity.setOutgoing(finalMessage.isOutgoing);
        entity.setDate(Instant.ofEpochSecond(finalMessage.date));
        entity.setEditDate(finalMessage.editDate > 0 ? Instant.ofEpochSecond(finalMessage.editDate) : null);
        entity.setContent(extractTextContentOnly(finalMessage.content));
        entity.setCaption(extractCaptionText(finalMessage.content));
        entity.setMediaType(determineMediaKind(finalMessage.content));
        setSenderInfo(entity, finalMessage.senderId);
        applyReplyMetadata(entity, finalMessage.replyTo);
        Long forwardFromChatId = extractForwardFromChatId(finalMessage.forwardInfo, finalMessage.senderId);
        entity.setForwardFromChatId(forwardFromChatId);

        if (finalMessage.interactionInfo != null) {
            entity.setViews((long) finalMessage.interactionInfo.viewCount);
            entity.setForwards((long) finalMessage.interactionInfo.forwardCount);
        }

        entity.setRawMessageDump(serializeMessage(finalMessage));
        messageTypeClassifier.classifyAndSetMessageType(entity);
    }

    private Mono<MessageEntity> updateChannelReference(MessageEntity entity, Long forwardFromChatId) {
        if (forwardFromChatId == null) {
            entity.setChannelId(null);
            return Mono.just(entity);
        }
        return channelRepository.findByChatId(forwardFromChatId)
                .map(channel -> {
                    entity.setChannelId(channel.getId());
                    return entity;
                })
                .defaultIfEmpty(entity);
    }

    private void setSenderInfo(MessageEntity entity, TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser userSender) {
            entity.setUserId(userSender.userId);
        } else if (sender instanceof TdApi.MessageSenderChat chatSender) {
            entity.setUserId(chatSender.chatId);
        }
    }

    private void applyReplyMetadata(MessageEntity entity, TdApi.MessageReplyTo replyTo) {
        ReplyMetadata metadata = extractReplyMetadata(replyTo);
        entity.setReplyToMessageId(metadata.messageId());
        entity.setReplyToChatId(metadata.chatId());
    }

    private ReplyMetadata extractReplyMetadata(TdApi.MessageReplyTo replyTo) {
        if (replyTo instanceof TdApi.MessageReplyToMessage replyToMessage) {
            long chatId = replyToMessage.chatId;
            long msgId = replyToMessage.messageId;
            if (msgId == 0) {
                return new ReplyMetadata(null, null);
            }
            return new ReplyMetadata(msgId, chatId == 0 ? null : chatId);
        }
        return new ReplyMetadata(null, null);
    }

    private Long extractForwardFromChatId(TdApi.MessageForwardInfo forwardInfo, TdApi.MessageSender sender) {
        if (forwardInfo != null && forwardInfo.source != null && forwardInfo.source.chatId != 0) {
            return forwardInfo.source.chatId;
        }
        if (sender instanceof TdApi.MessageSenderChat chatSender) {
            return chatSender.chatId;
        }
        return null;
    }

    private String serializeMessage(TdApi.Message message) {
        return message != null ? message.toString() : null;
    }

    private record ReplyMetadata(Long messageId, Long chatId) { }

    private record PersistenceOutcome(MessageEntity entity, boolean newlyCreated) {
        static PersistenceOutcome created(MessageEntity entity) {
            return new PersistenceOutcome(entity, true);
        }

        static PersistenceOutcome existing(MessageEntity entity) {
            return new PersistenceOutcome(entity, false);
        }

        PersistenceOutcome withEntity(MessageEntity updated) {
            return new PersistenceOutcome(updated, newlyCreated);
        }
    }

    private boolean shouldSkipPersistence(MessageEntity entity) {
        if (entity == null) {
            return false;
        }
        if (allowedCommandChatId == null) {
            return false;
        }
        Long entityChatId = entity.getChatId();
        if (entityChatId == null || !allowedCommandChatId.equals(entityChatId)) {
            return false;
        }
        if (!entity.isOutgoing()) {
            return false;
        }

        String content = entity.getContent();
        boolean matchesKafkaPattern = content != null && content.stripLeading().startsWith(KAFKA_ERROR_PREFIX);
        MessageType messageType = entity.getMessageType();
        boolean isSystemNotification = messageType == MessageType.SYSTEM_NOTIFICATION;

        return matchesKafkaPattern || isSystemNotification;
    }

    private String extractTextContentOnly(TdApi.MessageContent content) {
        if (content instanceof TdApi.MessageText textContent) {
            return textContent.text != null ? textContent.text.text : null;
        }
        return null;
    }

    private String extractCaptionText(TdApi.MessageContent content) {
        TdApi.FormattedText caption = null;
        switch (content) {
            case TdApi.MessagePhoto p -> caption = p.caption;
            case TdApi.MessageVideo v -> caption = v.caption;
            case TdApi.MessageAnimation a -> caption = a.caption;
            case TdApi.MessageDocument d -> caption = d.caption;
            case TdApi.MessageVoiceNote vo -> caption = vo.caption;
            default -> {}
        }
        return (caption != null && caption.text != null) ? caption.text : null;
    }

    private MediaKind determineMediaKind(TdApi.MessageContent content) {
        return switch (content) {
            case TdApi.MessagePhoto p -> MediaKind.PHOTO;
            case TdApi.MessageVideo v -> MediaKind.VIDEO;
            case TdApi.MessageVideoNote vn -> MediaKind.VIDEO_NOTE;
            case TdApi.MessageAnimation a -> MediaKind.ANIMATION;
            case TdApi.MessageDocument d -> MediaKind.DOCUMENT;
            case TdApi.MessageSticker s -> MediaKind.STICKER;
            case TdApi.MessageVoiceNote vo -> MediaKind.VOICE;
            case TdApi.MessageAudio au -> MediaKind.AUDIO;
            default -> MediaKind.UNKNOWN;
        };
    }

    private record MediaInfo(long fileId, String fileName) {}

    private Mono<Void> handleMediaAsync(TdApi.Message msg, long chatId, MediaKind kind) {
        // Stubbed by default — see mediaDownloadEnabled. Short-circuit BEFORE any download
        // attempt so no API call is made and no "ASYNC MEDIA FAIL" noise is logged.
        if (!mediaDownloadEnabled) {
            return Mono.empty();
        }
        if (kind == null || kind == MediaKind.UNKNOWN || msg.content instanceof TdApi.MessageText) {
            return Mono.empty();
        }

        return Mono.fromCallable(() -> {
            log.debug(">>> ASYNC MEDIA START: Handling type={} for msgId={}", kind, msg.id);
            return switch (msg.content) {
                case TdApi.MessagePhoto photo -> {
                    if (photo.photo.sizes.length > 0) {
                        int bestSizeIndex = photo.photo.sizes.length - 1;
                        if (bestSizeIndex > 0 && photo.photo.sizes[bestSizeIndex].type.equals("s")) bestSizeIndex--;
                        long fileId = photo.photo.sizes[bestSizeIndex].photo.id;
                        yield new MediaInfo(fileId, fileId + ".jpg");
                    }
                    yield new MediaInfo(0, null);
                }
                case TdApi.MessageVideo video -> {
                    long fileId = video.video.video.id;
                    String fileName = video.video.fileName != null && !video.video.fileName.isEmpty() ? video.video.fileName : fileId + ".mp4";
                    yield new MediaInfo(fileId, fileName);
                }
                case TdApi.MessageVideoNote videoNote -> {
                    long fileId = videoNote.videoNote.video.id;
                    yield new MediaInfo(fileId, fileId + ".mp4");
                }
                case TdApi.MessageAnimation animation -> {
                    long fileId = animation.animation.animation.id;
                    String fileName = animation.animation.fileName != null && !animation.animation.fileName.isEmpty() ? animation.animation.fileName : fileId + ".mp4";
                    yield new MediaInfo(fileId, fileName);
                }
                case TdApi.MessageDocument document -> {
                    long fileId = document.document.document.id;
                    String fileName = document.document.fileName != null && !document.document.fileName.isEmpty() ? document.document.fileName : fileId + ".bin";
                    yield new MediaInfo(fileId, fileName);
                }
                case TdApi.MessageSticker stickerMsg -> {
                    TdApi.Sticker sticker = stickerMsg.sticker;
                    if (sticker != null && sticker.sticker != null) {
                        long fileId = sticker.sticker.id;
                        String fileName = null;
                        if (sticker.format instanceof TdApi.StickerFormatWebp) fileName = fileId + ".webp";
                        else if (sticker.format instanceof TdApi.StickerFormatTgs) fileName = fileId + ".tgs";
                        else if (sticker.format instanceof TdApi.StickerFormatWebm) fileName = fileId + ".webm";
                        yield new MediaInfo(fileId, fileName);
                    }
                    yield new MediaInfo(0, null);
                }
                case TdApi.MessageVoiceNote voiceNoteMsg -> {
                    long fileId = voiceNoteMsg.voiceNote.voice.id;
                    yield new MediaInfo(fileId, fileId + ".oga");
                }
                case TdApi.MessageAudio audio -> {
                    long fileId = audio.audio.audio.id;
                    String ext = ".mp3";
                    if (audio.audio.mimeType != null) {
                        ext = switch (audio.audio.mimeType) {
                            case "audio/ogg" -> ".oga";
                            case "audio/mpeg" -> ".mp3";
                            case "audio/aac" -> ".aac";
                            case "audio/wav" -> ".wav";
                            case "audio/flac" -> ".flac";
                            default -> ".audio";
                        };
                    }
                    String fileName = audio.audio.fileName != null && !audio.audio.fileName.isEmpty() ? audio.audio.fileName : fileId + ext;
                    yield new MediaInfo(fileId, fileName);
                }
                default -> new MediaInfo(0, null);
            };
        })
        .subscribeOn(Schedulers.fromExecutor(mediaExecutor))
        .flatMap(mediaInfo -> {
            if (mediaInfo.fileId() != 0 && mediaInfo.fileName() != null) {
                log.debug("--- ASYNC MEDIA STORE: Attempting fileId={} name='{}'", mediaInfo.fileId(), mediaInfo.fileName());
                return mediaStorageService.storeMedia(chatId, mediaInfo.fileId(), mediaInfo.fileName())
                        .doOnSuccess(path -> {
                            if (path != null) {
                                log.debug("<<< ASYNC MEDIA OK: Stored fileId={} msgId={} path='{}'", mediaInfo.fileId(), msg.id, path);
                            } else {
                                log.warn("--- ASYNC MEDIA FAIL: Storing fileId={} msgId={} failed.", mediaInfo.fileId(), msg.id);
                            }
                        }).then();
            } else if (kind != MediaKind.UNKNOWN) {
                log.warn("--- ASYNC MEDIA SKIP: No fileId/fileName for type {} msgId={}", kind, msg.id);
            }
            return Mono.empty();
        })
        .doOnError(e -> log.error("!!! ASYNC MEDIA ERROR: Type={} msgId={}. Error: {}", kind, msg.id, e.getMessage(), e))
        .then();
    }

}
