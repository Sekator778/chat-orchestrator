package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Objects;

@Table("response_templates")
public class ResponseTemplate {
    @Id
    private Long id;

    @Column("chat_config_id")
    private Long chatConfigId;

    @Column("template_name")
    private String templateName;

    @Column("template_content")
    private String templateContent;

    @Column("response_style")
    private ResponseStyle responseStyle = ResponseStyle.ADAPTIVE;

    @Column("response_tone")
    private ResponseTone responseTone = ResponseTone.NEUTRAL;

    @Column("max_response_length")
    private Integer maxResponseLength = 500;

    @Column("is_default")
    private boolean isDefault = false;

    @Column("priority")
    private Integer priority = 1;

    @Column("active")
    private boolean active = true;

    // Constructors
    public ResponseTemplate() {}

    public ResponseTemplate(Long chatConfigId, String templateName, String templateContent) {
        this.chatConfigId = chatConfigId;
        this.templateName = templateName;
        this.templateContent = templateContent;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatConfigId() { return chatConfigId; }
    public void setChatConfigId(Long chatConfigId) { this.chatConfigId = chatConfigId; }

    public String getTemplateName() { return templateName; }
    public void setTemplateName(String templateName) { this.templateName = templateName; }

    public String getTemplateContent() { return templateContent; }
    public void setTemplateContent(String templateContent) { this.templateContent = templateContent; }

    public ResponseStyle getResponseStyle() { return responseStyle; }
    public void setResponseStyle(ResponseStyle responseStyle) { this.responseStyle = responseStyle; }

    public ResponseTone getResponseTone() { return responseTone; }
    public void setResponseTone(ResponseTone responseTone) { this.responseTone = responseTone; }

    public Integer getMaxResponseLength() { return maxResponseLength; }
    public void setMaxResponseLength(Integer maxResponseLength) { this.maxResponseLength = maxResponseLength; }

    public boolean isDefault() { return isDefault; }
    public void setDefault(boolean isDefault) { this.isDefault = isDefault; }

    public Integer getPriority() { return priority; }
    public void setPriority(Integer priority) { this.priority = priority; }

    public boolean isActive() { return active; }
    public void setActive(boolean active) { this.active = active; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        ResponseTemplate that = (ResponseTemplate) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "ResponseTemplate{" +
                "id=" + id +
                ", templateName='" + templateName + '\'' +
                ", responseStyle=" + responseStyle +
                ", isDefault=" + isDefault +
                ", active=" + active +
                '}';
    }
}