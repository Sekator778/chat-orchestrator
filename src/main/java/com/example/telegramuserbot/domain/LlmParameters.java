package com.example.telegramuserbot.domain;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.util.Objects;

@Table("llm_parameters")
public class LlmParameters {
    @Id
    private Long id;

    @Column("chat_config_id")
    private Long chatConfigId;

    @Column("model_name")
    private String modelName = "deepseek-chat";

    @Column("temperature")
    private Double temperature = 0.7;

    @Column("max_tokens")
    private Integer maxTokens = 1000;

    @Column("top_p")
    private Double topP = 0.9;

    @Column("frequency_penalty")
    private Double frequencyPenalty = 0.0;

    @Column("presence_penalty")
    private Double presencePenalty = 0.0;

    @Column("system_prompt")
    private String systemPrompt;

    @Column("custom_instructions")
    private String customInstructions;

    @Column("response_format")
    private ResponseFormat responseFormat = ResponseFormat.TEXT;

    // Constructors
    public LlmParameters() {}

    public LlmParameters(Long chatConfigId) {
        this.chatConfigId = chatConfigId;
    }

    // Getters and setters
    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public Long getChatConfigId() { return chatConfigId; }
    public void setChatConfigId(Long chatConfigId) { this.chatConfigId = chatConfigId; }

    public String getModelName() { return modelName; }
    public void setModelName(String modelName) { this.modelName = modelName; }

    public Double getTemperature() { return temperature; }
    public void setTemperature(Double temperature) { this.temperature = temperature; }

    public Integer getMaxTokens() { return maxTokens; }
    public void setMaxTokens(Integer maxTokens) { this.maxTokens = maxTokens; }

    public Double getTopP() { return topP; }
    public void setTopP(Double topP) { this.topP = topP; }

    public Double getFrequencyPenalty() { return frequencyPenalty; }
    public void setFrequencyPenalty(Double frequencyPenalty) { this.frequencyPenalty = frequencyPenalty; }

    public Double getPresencePenalty() { return presencePenalty; }
    public void setPresencePenalty(Double presencePenalty) { this.presencePenalty = presencePenalty; }

    public String getSystemPrompt() { return systemPrompt; }
    public void setSystemPrompt(String systemPrompt) { this.systemPrompt = systemPrompt; }

    public String getCustomInstructions() { return customInstructions; }
    public void setCustomInstructions(String customInstructions) { this.customInstructions = customInstructions; }

    public ResponseFormat getResponseFormat() { return responseFormat; }
    public void setResponseFormat(ResponseFormat responseFormat) { this.responseFormat = responseFormat; }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o == null || getClass() != o.getClass()) return false;
        LlmParameters that = (LlmParameters) o;
        return Objects.equals(id, that.id);
    }

    @Override
    public int hashCode() {
        return Objects.hash(id);
    }

    @Override
    public String toString() {
        return "LlmParameters{" +
                "id=" + id +
                ", modelName='" + modelName + '\'' +
                ", temperature=" + temperature +
                ", maxTokens=" + maxTokens +
                '}';
    }
}