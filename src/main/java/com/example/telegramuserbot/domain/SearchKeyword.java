package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.time.Instant;

/**
 * A keyword used by the collector account to discover new Telegram channels
 * via TDLib {@code SearchPublicChats}. Rows live in {@code tgscan.search_keywords}.
 */
@Table(name = "search_keywords", schema = "tgscan")
public class SearchKeyword {

    @Id
    private Long id;

    @Column("keyword")
    private String keyword;

    @Column("language")
    private String language;

    @Column("enabled")
    private boolean enabled;

    @Column("created_at")
    private Instant createdAt;

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getKeyword() { return keyword; }
    public void setKeyword(String keyword) { this.keyword = keyword; }

    public String getLanguage() { return language; }
    public void setLanguage(String language) { this.language = language; }

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public Instant getCreatedAt() { return createdAt; }
    public void setCreatedAt(Instant createdAt) { this.createdAt = createdAt; }
}
