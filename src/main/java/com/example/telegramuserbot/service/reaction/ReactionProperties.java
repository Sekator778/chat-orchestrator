package com.example.telegramuserbot.service.reaction;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Configuration properties for the persona reaction system.
 * Controls rate limits, delays, emoji pool, and execution behavior.
 */
@Component
@ConfigurationProperties(prefix = "persona.reaction")
public final class ReactionProperties {

    private boolean enabled = false;
    private long executorIntervalMs = 60000;
    private int dailyLimitPerPersona = 15;
    // Probability (0-100) that a persona reacts to any single qualifying message.
    // <100 makes reactions sparse and lets the two personas diverge naturally.
    private int reactProbabilityPercent = 100;
    private int delayMinMinutes = 5;
    private int delayMaxMinutes = 40;
    private int minGapBetweenReactionsSeconds = 30;
    private int minGapSameChannelMinutes = 30;
    private int maxConcurrentExecutions = 5;
    private int floodWaitBackoffMinutes = 60;
    private int openChatDelaySeconds = 2;
    private List<EmojiWeight> emojiPool = List.of(
        new EmojiWeight("\uD83D\uDC4D", 60),
        new EmojiWeight("\uD83D\uDD25", 30),
        new EmojiWeight("\uD83D\uDCAF", 10)
    );

    /**
     * Default constructor for Spring configuration binding.
     */
    public ReactionProperties() {
    }

    /**
     * Returns whether the persona reaction system is enabled.
     *
     * @return true if enabled
     */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Sets whether the system is enabled.
     *
     * @param enabled the enabled flag
     */
    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    /**
     * Returns the executor polling interval in milliseconds.
     *
     * @return interval in ms
     */
    public long executorIntervalMs() {
        return executorIntervalMs;
    }

    /**
     * Sets the executor polling interval.
     *
     * @param executorIntervalMs interval in ms
     */
    public void setExecutorIntervalMs(long executorIntervalMs) {
        this.executorIntervalMs = executorIntervalMs;
    }

    /**
     * Returns the global daily reaction limit per persona.
     *
     * @return max reactions per day across all channels
     */
    public int dailyLimitPerPersona() {
        return dailyLimitPerPersona;
    }

    /**
     * Sets the global daily limit per persona.
     *
     * @param dailyLimitPerPersona the daily limit
     */
    public void setDailyLimitPerPersona(int dailyLimitPerPersona) {
        this.dailyLimitPerPersona = dailyLimitPerPersona;
    }

    /**
     * Returns the per-message reaction probability (0-100). A value below 100
     * makes reactions sparse, so personas do not react to every message.
     *
     * @return reaction probability percent
     */
    public int reactProbabilityPercent() {
        return reactProbabilityPercent;
    }

    /**
     * Sets the per-message reaction probability (0-100).
     *
     * @param reactProbabilityPercent the probability percent
     */
    public void setReactProbabilityPercent(int reactProbabilityPercent) {
        this.reactProbabilityPercent = reactProbabilityPercent;
    }

    /**
     * Returns the minimum delay before executing a reaction in minutes.
     *
     * @return minimum delay in minutes
     */
    public int delayMinMinutes() {
        return delayMinMinutes;
    }

    /**
     * Sets the minimum execution delay.
     *
     * @param delayMinMinutes delay in minutes
     */
    public void setDelayMinMinutes(int delayMinMinutes) {
        this.delayMinMinutes = delayMinMinutes;
    }

    /**
     * Returns the maximum delay before executing a reaction in minutes.
     *
     * @return maximum delay in minutes
     */
    public int delayMaxMinutes() {
        return delayMaxMinutes;
    }

    /**
     * Sets the maximum execution delay.
     *
     * @param delayMaxMinutes delay in minutes
     */
    public void setDelayMaxMinutes(int delayMaxMinutes) {
        this.delayMaxMinutes = delayMaxMinutes;
    }

    /**
     * Returns the minimum gap between consecutive reaction executions in seconds.
     *
     * @return gap in seconds
     */
    public int minGapBetweenReactionsSeconds() {
        return minGapBetweenReactionsSeconds;
    }

    /**
     * Sets the minimum gap between consecutive executions.
     *
     * @param minGapBetweenReactionsSeconds gap in seconds
     */
    public void setMinGapBetweenReactionsSeconds(int minGapBetweenReactionsSeconds) {
        this.minGapBetweenReactionsSeconds = minGapBetweenReactionsSeconds;
    }

    /**
     * Returns the minimum gap between reactions on the same channel in minutes.
     *
     * @return gap in minutes
     */
    public int minGapSameChannelMinutes() {
        return minGapSameChannelMinutes;
    }

    /**
     * Sets the minimum gap between reactions on the same channel.
     *
     * @param minGapSameChannelMinutes gap in minutes
     */
    public void setMinGapSameChannelMinutes(int minGapSameChannelMinutes) {
        this.minGapSameChannelMinutes = minGapSameChannelMinutes;
    }

    /**
     * Returns the maximum number of reactions to execute in a single scheduler cycle.
     *
     * @return max concurrent executions
     */
    public int maxConcurrentExecutions() {
        return maxConcurrentExecutions;
    }

    /**
     * Sets the maximum concurrent executions per cycle.
     *
     * @param maxConcurrentExecutions the limit
     */
    public void setMaxConcurrentExecutions(int maxConcurrentExecutions) {
        this.maxConcurrentExecutions = maxConcurrentExecutions;
    }

    /**
     * Returns the backoff time in minutes when a FLOOD_WAIT error is received.
     *
     * @return flood wait backoff in minutes
     */
    public int floodWaitBackoffMinutes() {
        return floodWaitBackoffMinutes;
    }

    /**
     * Sets the flood wait backoff duration.
     *
     * @param floodWaitBackoffMinutes backoff in minutes
     */
    public void setFloodWaitBackoffMinutes(int floodWaitBackoffMinutes) {
        this.floodWaitBackoffMinutes = floodWaitBackoffMinutes;
    }

    /**
     * Returns the delay in seconds after OpenChat before loading messages.
     * Gives TDLib time to process channel difference and populate cache.
     *
     * @return delay in seconds
     */
    public int openChatDelaySeconds() {
        return openChatDelaySeconds;
    }

    /**
     * Sets the delay after OpenChat.
     *
     * @param openChatDelaySeconds delay in seconds
     */
    public void setOpenChatDelaySeconds(int openChatDelaySeconds) {
        this.openChatDelaySeconds = openChatDelaySeconds;
    }

    /**
     * Returns the weighted emoji pool used for reaction selection.
     *
     * @return list of emoji weights
     */
    public List<EmojiWeight> emojiPool() {
        return emojiPool;
    }

    /**
     * Sets the emoji pool for reaction selection.
     *
     * @param emojiPool list of emoji weights
     */
    public void setEmojiPool(List<EmojiWeight> emojiPool) {
        this.emojiPool = emojiPool;
    }

    /**
     * Emoji with its relative selection weight.
     */
    public static final class EmojiWeight {

        private String emoji;
        private int weight;

        /**
         * Default constructor for Spring property binding.
         */
        public EmojiWeight() {
        }

        /**
         * Constructor with emoji and weight.
         *
         * @param emoji  the emoji string
         * @param weight the relative selection weight
         */
        public EmojiWeight(String emoji, int weight) {
            this.emoji = emoji;
            this.weight = weight;
        }

        /**
         * Returns the emoji string.
         *
         * @return emoji
         */
        public String emoji() {
            return emoji;
        }

        /**
         * Sets the emoji string.
         *
         * @param emoji the emoji
         */
        public void setEmoji(String emoji) {
            this.emoji = emoji;
        }

        /**
         * Returns the selection weight.
         *
         * @return weight
         */
        public int weight() {
            return weight;
        }

        /**
         * Sets the selection weight.
         *
         * @param weight the weight value
         */
        public void setWeight(int weight) {
            this.weight = weight;
        }
    }
}
