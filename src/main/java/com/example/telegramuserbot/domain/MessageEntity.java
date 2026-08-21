package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.ReadOnlyProperty;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Objects;

// Must be schema-qualified: a `messages` table exists in BOTH the `bot` and
// `tgscan` schemas. The R2DBC search_path (`tgscan,bot,public`) lists tgscan
// first, so an unqualified name resolves to tgscan.messages (the channel-scanner
// table, which has no chat_id) and every save() fails with "bad SQL grammar".
// Pin to bot.messages — matching the hand-written `FROM bot.messages` reads.
@Table(name = "messages", schema = "bot")
public class MessageEntity {
    @Id
    private Long id; // DB ID

    @Column("chat_id")
    private Long chatId;

    @Column("message_id")
    private Long messageId; // Telegram Message ID

    @Column("content")
    private String content;

    @Column("caption")
    private String caption;

    @Column("date")
    private Instant date;

    @Column("media_type")
    private MediaKind mediaType;

    @Column("media_file_path")
    private String mediaFilePath;

    @Column("is_outgoing")
    private boolean isOutgoing; // True if sent by this user bot

    // Sender information
    @Column("sender_id")
    private Long senderId; // Telegram User ID of the sender (null for channels/service messages)

    @Column("sender_name")
    private String senderName; // Display name of the sender at the time of message

    @Column("sender_username")
    private String senderUsername; // Telegram @username of the sender

    @Column("sender_first_name")
    private String senderFirstName; // First name of the sender

    @Column("sender_last_name")
    private String senderLastName; // Last name of the sender

    @Column("edit_date")
    private Instant editDate;

    @Column("received_by_bot_id")
    private String receivedByBotId; // Which persona client ingested this message; load-bearing for private chats
                                    // where the same human's DMs to different personas share one chat_id

    // Sync-related fields
    @Column("imported_from_sync")
    private Boolean importedFromSync = false; // Whether this message was imported via sync operation

    @Column("sync_job_id")
    private Long syncJobId; // ID of the sync job that imported this message

    @Column("created_at")
    private LocalDateTime createdAt = LocalDateTime.now(); // When this record was created in our DB

    // Additional fields to support the new sync system
    @Column("channel_id")
    private Long channelId; // Reference to the channel

    @Column("telegram_message_id")
    private Long telegramMessageId; // Alias for messageId to match new sync system interface

    @Column("user_id")
    private Long userId; // Alias for senderId to match new sync system interface

    @Column("username")
    private String username; // Alias for senderUsername to match new sync system interface

    @Column("media_kind")
    private MediaKind mediaKind; // Alias for mediaType to match new sync system interface

    @Column("media_path")
    private String mediaPath; // Alias for mediaFilePath to match new sync system interface

    @Column("message_type")
    private MessageType messageType = MessageType.USER_MESSAGE; // Default to user message

    @Column("forward_from_chat_id")
    private Long forwardFromChatId;

    @Column("reply_to_message_id")
    private Long replyToMessageId;

    @Column("reply_to_chat_id")
    private Long replyToChatId;

    @Column("raw_message_dump")
    private String rawMessageDump;

    @Column("importance")
    private Double importance;

    @Column("content_hash")
    private String contentHash;

    @Column("content_simhash")
    private String contentSimhash;

    @Column("matched_keywords")
    private String[] matchedKeywords;

    @Column("consensus")
    private Double consensus;

    @Column("novelty")
    private Double novelty;

    @Column("views")
    private Long views;

    @Column("forwards")
    private Long forwards;

    @Column("cluster_id")
    private String clusterId;

    @Column("is_primary_in_cluster")
    private Boolean isPrimaryInCluster;

    /** Geo scope derived from named-entity heuristics (RU/UA/KZ/BY/GLOBAL). Null = not yet classified. */
    @Column("geo")
    private String geo;

    /**
     * Timestamp when this message's vector was upserted into Qdrant (cs078).
     * NULL means the message has not yet been embedded. Set ONLY after a
     * successful {@code QdrantVectorStore.upsert()} — never before.
     */
    @Column("embedded_at")
    private Instant embeddedAt;

    /**
     * Computed ranking value {@code importance * ln(greatest(subscribers, 2))} projected by
     * {@link com.example.telegramuserbot.repository.MessageRepository#findUnpostedNewsCandidatesForPersona}.
     * Read-only: not a real column in {@code bot.messages}; never written back on save().
     */
    @ReadOnlyProperty
    @Column("value_score")
    private Double valueScore;

    public Instant getEditDate() { return editDate; }
    public void setEditDate(Instant editDate) { this.editDate = editDate; }

    public String getReceivedByBotId() { return receivedByBotId; }
    public void setReceivedByBotId(String receivedByBotId) { this.receivedByBotId = receivedByBotId; }

    public String getCaption() { return caption; }
    public void setCaption(String caption) { this.caption = caption; }
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }
    public Long getChatId() { return chatId; }
    public void setChatId(Long chatId) { this.chatId = chatId; }
    public Long getMessageId() { return messageId; }
    public void setMessageId(Long messageId) { this.messageId = messageId; }
    public String getContent() { return content; }
    public void setContent(String content) { this.content = content; }
    public Instant getDate() { return date; }
    public void setDate(Instant date) { this.date = date; }
    public MediaKind getMediaType() { return mediaType; }
    public void setMediaType(MediaKind mediaType) { this.mediaType = mediaType; }
    public String getMediaFilePath() { return mediaFilePath; }
    public void setMediaFilePath(String mediaFilePath) { this.mediaFilePath = mediaFilePath; }
    public boolean isOutgoing() { return isOutgoing; }
    public void setOutgoing(boolean outgoing) { isOutgoing = outgoing; }

    public Long getSenderId() { return senderId; }
    public void setSenderId(Long senderId) { this.senderId = senderId; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getSenderUsername() { return senderUsername; }
    public void setSenderUsername(String senderUsername) { this.senderUsername = senderUsername; }

    public String getSenderFirstName() { return senderFirstName; }
    public void setSenderFirstName(String senderFirstName) { this.senderFirstName = senderFirstName; }

    public String getSenderLastName() { return senderLastName; }
    public void setSenderLastName(String senderLastName) { this.senderLastName = senderLastName; }

    public Boolean getImportedFromSync() { return importedFromSync; }
    public void setImportedFromSync(Boolean importedFromSync) { this.importedFromSync = importedFromSync; }

    public Long getSyncJobId() { return syncJobId; }
    public void setSyncJobId(Long syncJobId) { this.syncJobId = syncJobId; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public Long getChannelId() { return channelId; }
    public void setChannelId(Long channelId) { this.channelId = channelId; }

    public Long getTelegramMessageId() { return telegramMessageId != null ? telegramMessageId : messageId; }
    public void setTelegramMessageId(Long telegramMessageId) {
        this.telegramMessageId = telegramMessageId;
        this.messageId = telegramMessageId; // Keep both in sync
    }

    public Long getUserId() { return userId != null ? userId : senderId; }
    public void setUserId(Long userId) {
        this.userId = userId;
        this.senderId = userId; // Keep both in sync
    }

    public String getUsername() { return username != null ? username : senderUsername; }
    public void setUsername(String username) {
        this.username = username;
        this.senderUsername = username; // Keep both in sync
    }

    public MediaKind getMediaKind() { return mediaKind != null ? mediaKind : mediaType; }
    public void setMediaKind(MediaKind mediaKind) {
        this.mediaKind = mediaKind;
        this.mediaType = mediaKind; // Keep both in sync
    }

    public String getMediaPath() { return mediaPath != null ? mediaPath : mediaFilePath; }
    public void setMediaPath(String mediaPath) {
        this.mediaPath = mediaPath;
        this.mediaFilePath = mediaPath; // Keep both in sync
    }

    public MessageType getMessageType() { return messageType; }
    public void setMessageType(MessageType messageType) { this.messageType = messageType; }

    public Long getForwardFromChatId() { return forwardFromChatId; }
    public void setForwardFromChatId(Long forwardFromChatId) { this.forwardFromChatId = forwardFromChatId; }

    public Long getReplyToMessageId() { return replyToMessageId; }
    public void setReplyToMessageId(Long replyToMessageId) { this.replyToMessageId = replyToMessageId; }
    public Long getReplyToChatId() { return replyToChatId; }
    public void setReplyToChatId(Long replyToChatId) { this.replyToChatId = replyToChatId; }
    public String getRawMessageDump() { return rawMessageDump; }
    public void setRawMessageDump(String rawMessageDump) { this.rawMessageDump = rawMessageDump; }

    public Double getImportance() { return importance; }
    public void setImportance(Double importance) { this.importance = importance; }

    public String getContentHash() { return contentHash; }
    public void setContentHash(String contentHash) { this.contentHash = contentHash; }

    public String getContentSimhash() { return contentSimhash; }
    public void setContentSimhash(String contentSimhash) { this.contentSimhash = contentSimhash; }

    public String[] getMatchedKeywords() { return matchedKeywords; }
    public void setMatchedKeywords(String[] matchedKeywords) { this.matchedKeywords = matchedKeywords; }

    public Double getConsensus() { return consensus; }
    public void setConsensus(Double consensus) { this.consensus = consensus; }

    public Double getNovelty() { return novelty; }
    public void setNovelty(Double novelty) { this.novelty = novelty; }

    public Long getViews() { return views; }
    public void setViews(Long views) { this.views = views; }

    public Long getForwards() { return forwards; }
    public void setForwards(Long forwards) { this.forwards = forwards; }

    public String getClusterId() { return clusterId; }
    public void setClusterId(String clusterId) { this.clusterId = clusterId; }

    public Boolean getIsPrimaryInCluster() { return isPrimaryInCluster; }
    public void setIsPrimaryInCluster(Boolean isPrimaryInCluster) { this.isPrimaryInCluster = isPrimaryInCluster; }

    public String getGeo() { return geo; }
    public void setGeo(String geo) { this.geo = geo; }

    public Double getValueScore() { return valueScore; }
    public void setValueScore(Double valueScore) { this.valueScore = valueScore; }

    public Instant getEmbeddedAt() { return embeddedAt; }
    public void setEmbeddedAt(Instant embeddedAt) { this.embeddedAt = embeddedAt; }

    public Instant getTimestamp() { return date; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        MessageEntity that = (MessageEntity) o;
        if (id != null && that.id != null) {
            return Objects.equals(id, that.id);
        }
        return Objects.equals(chatId, that.chatId) && Objects.equals(messageId, that.messageId);
    }

    @Override
    public int hashCode() {
        if (id != null) {
            return Objects.hash(id);
        }
        return Objects.hash(chatId, messageId);
    }

    @Override
    public String toString() {
        return "MessageEntity{" +
                "id=" + id +
                ", chatId=" + chatId +
                ", messageId=" + messageId +
                ", isOutgoing=" + isOutgoing +
                ", date=" + date +
                ", mediaType=" + mediaType +
                ", content='" + (content != null ? content.substring(0, Math.min(content.length(), 50)) + "..." : "null") + '\'' +
                '}';
    }
}
