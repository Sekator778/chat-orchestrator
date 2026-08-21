package com.example.telegramuserbot.service.persistence;

import com.example.telegramuserbot.domain.MediaKind;
import com.example.telegramuserbot.domain.MessageEntity;
import com.example.telegramuserbot.service.TelegramClientManager;
import com.example.telegramuserbot.service.ranking.GeoTaggingService;
import com.example.telegramuserbot.service.ranking.KeywordMatchingService;
import com.example.telegramuserbot.service.ranking.SimHashService;
import com.example.telegramuserbot.service.telegram.TelegramSenderInfoService;
import com.example.telegramuserbot.service.util.MessageTypeClassifier;
import com.example.telegramuserbot.telegram.TelegramClientFacade;
import it.tdlight.jni.TdApi;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.Objects;

/**
 * Central place for mapping TdApi.Message into MessageEntity and filling sender fields.
 * Every write into the {@code messages} table should go through this hydrator.
 */
@Service
public final class MessageEntityHydrator {

    private final TelegramSenderInfoService senderInfoService;
    private final TelegramClientManager telegramClientManager;
    private final MessageTypeClassifier messageTypeClassifier;
    private final SimHashService simHashService;
    private final KeywordMatchingService keywordMatchingService;
    private final GeoTaggingService geoTaggingService;

    public MessageEntityHydrator(TelegramSenderInfoService senderInfoService,
                                 TelegramClientManager telegramClientManager,
                                 MessageTypeClassifier messageTypeClassifier,
                                 SimHashService simHashService,
                                 KeywordMatchingService keywordMatchingService,
                                 GeoTaggingService geoTaggingService) {
        this.senderInfoService = senderInfoService;
        this.telegramClientManager = telegramClientManager;
        this.messageTypeClassifier = messageTypeClassifier;
        this.simHashService = simHashService;
        this.keywordMatchingService = keywordMatchingService;
        this.geoTaggingService = geoTaggingService;
    }

    public Mono<MessageEntity> create(long chatId, TdApi.Message msg, String botInstanceId) {
        if (msg == null) {
            return Mono.empty();
        }
        MessageEntity entity = new MessageEntity();
        applyAll(entity, chatId, msg);
        entity.setReceivedByBotId(botInstanceId);
        return enrichSender(entity, msg.senderId, botInstanceId).defaultIfEmpty(entity);
    }

    public Mono<Boolean> enrichSenderIfMissing(MessageEntity entity, TdApi.MessageSender sender, String botInstanceId) {
        if (entity == null || sender == null) {
            return Mono.just(false);
        }
        boolean missing = isBlank(entity.getSenderName())
                || isBlank(entity.getSenderUsername())
                || isBlank(entity.getSenderFirstName())
                || isBlank(entity.getSenderLastName());
        if (!missing) {
            return Mono.just(false);
        }
        String beforeName = entity.getSenderName();
        String beforeUsername = entity.getSenderUsername();
        String beforeFirst = entity.getSenderFirstName();
        String beforeLast = entity.getSenderLastName();

        return enrichSender(entity, sender, botInstanceId)
                .map(updated -> !Objects.equals(beforeName, updated.getSenderName())
                        || !Objects.equals(beforeUsername, updated.getSenderUsername())
                        || !Objects.equals(beforeFirst, updated.getSenderFirstName())
                        || !Objects.equals(beforeLast, updated.getSenderLastName()))
                .defaultIfEmpty(false);
    }

    public void applyAll(MessageEntity entity, long chatId, TdApi.Message msg) {
        if (entity == null || msg == null) {
            return;
        }
        entity.setChatId(chatId);

        entity.setTelegramMessageId(msg.id);
        entity.setOutgoing(msg.isOutgoing);
        entity.setDate(Instant.ofEpochSecond(msg.date));
        entity.setEditDate(msg.editDate > 0 ? Instant.ofEpochSecond(msg.editDate) : null);

        MediaKind mediaKind = determineMediaKind(msg.content);
        entity.setMediaType(mediaKind);
        entity.setMediaKind(mediaKind);
        entity.setMediaFilePath(null);
        entity.setMediaPath(null);

        entity.setContent(extractTextContentOnly(msg.content));
        entity.setCaption(extractCaptionText(msg.content));
        String textForHashing = entity.getContent() != null && !entity.getContent().isBlank()
                ? entity.getContent()
                : entity.getCaption();
        applyContentDerived(entity, textForHashing);

        setSenderId(entity, msg.senderId);
        applyReplyMetadata(entity, msg.replyTo);
        entity.setForwardFromChatId(extractForwardFromChatId(msg.forwardInfo, msg.senderId));

        if (msg.interactionInfo != null) {
            entity.setViews((long) msg.interactionInfo.viewCount);
            entity.setForwards((long) msg.interactionInfo.forwardCount);
        }

        entity.setRawMessageDump(serializeMessage(msg));
        messageTypeClassifier.classifyAndSetMessageType(entity);
    }

    /**
     * Applies the four content-derived fields (hash, simhash, keywords, geo) to the given
     * entity from {@code text}. Extracted so that both the Telegram ingest path
     * ({@link #applyAll}) and future web-harvest ingest paths can reuse it without
     * duplicating logic.
     *
     * @param entity the entity to mutate
     * @param text   the plain text to derive fields from (may be {@code null})
     */
    public void applyContentDerived(MessageEntity entity, String text) {
        entity.setContentHash(ContentHash.of(text));
        entity.setContentSimhash(simHashService.hash(text));
        entity.setMatchedKeywords(keywordMatchingService.match(text));
        entity.setGeo(geoTaggingService.classify(text));
    }

    private Mono<MessageEntity> enrichSender(MessageEntity entity, TdApi.MessageSender sender, String botInstanceId) {
        if (entity == null || sender == null) {
            return Mono.just(entity);
        }

        // Use the ingesting persona's client to resolve sender identity.
        // If botInstanceId is absent or its client is not yet registered, fall back
        // to the current behaviour (senderInfoService will pick any available client).
        String resolvedBotId = resolveIngestingClientId(botInstanceId);

        return senderInfoService.resolve(resolvedBotId, sender)
                .doOnNext(info -> applySenderInfo(entity, info))
                .thenReturn(entity)
                .onErrorReturn(entity);
    }

    /**
     * Returns {@code botInstanceId} when its Telegram client is registered,
     * or {@code null} when it is absent / not yet initialised so that
     * {@link TelegramSenderInfoService} falls back to any available client.
     */
    private String resolveIngestingClientId(String botInstanceId) {
        if (botInstanceId == null || botInstanceId.isBlank()) {
            return null;
        }
        TelegramClientFacade client = telegramClientManager.getClient(botInstanceId);
        return client != null ? botInstanceId : null;
    }

    private void setSenderId(MessageEntity entity, TdApi.MessageSender sender) {
        if (sender instanceof TdApi.MessageSenderUser userSender) {
            entity.setUserId(userSender.userId);
        } else if (sender instanceof TdApi.MessageSenderChat chatSender) {
            entity.setUserId(chatSender.chatId);
        }
    }

    private void applySenderInfo(MessageEntity entity, TelegramSenderInfoService.SenderInfo info) {
        if (entity == null || info == null) {
            return;
        }
        Long senderId = entity.getSenderId();
        if (senderId == null || senderId.equals(info.id())) {
            entity.setUserId(info.id());
        }

        entity.setSenderName(trimToNull(info.senderName()));
        entity.setSenderFirstName(trimToNull(info.senderFirstName()));
        entity.setSenderLastName(trimToNull(info.senderLastName()));
        entity.setUsername(trimToNull(info.senderUsername()));
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
            default -> {
            }
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

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.strip();
        return trimmed.isBlank() ? null : trimmed;
    }

    private record ReplyMetadata(Long messageId, Long chatId) {
    }
}
