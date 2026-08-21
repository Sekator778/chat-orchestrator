package com.example.telegramuserbot.service.orchestration;

import com.example.telegramuserbot.domain.ChatConfig;
import com.example.telegramuserbot.domain.LlmParameters;
import com.example.telegramuserbot.domain.RateLimits;
import com.example.telegramuserbot.domain.ResponseTemplate;
import com.example.telegramuserbot.config.BotInstanceProvider;
import com.example.telegramuserbot.repository.ChatConfigRepository;
import com.example.telegramuserbot.repository.LlmParametersRepository;
import com.example.telegramuserbot.repository.RateLimitsRepository;
import com.example.telegramuserbot.repository.ResponseTemplateRepository;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import reactor.core.publisher.Mono;

/**
 * Resolves active chat configuration and template for the current bot instance.
 * Centralizes selection logic to reuse across orchestrators/listeners.
 */
@Component
public class BotContextResolver {

    private final ChatConfigRepository chatConfigRepository;
    private final ResponseTemplateRepository responseTemplateRepository;
    private final RateLimitsRepository rateLimitsRepository;
    private final LlmParametersRepository llmParametersRepository;
    private final BotInstanceProvider botInstanceProvider;
    private final Map<Long, CachedBase> cache = new ConcurrentHashMap<>();
    private static final Duration TTL = Duration.ofMinutes(5);

    public BotContextResolver(ChatConfigRepository chatConfigRepository,
                              ResponseTemplateRepository responseTemplateRepository,
                              RateLimitsRepository rateLimitsRepository,
                              LlmParametersRepository llmParametersRepository,
                              BotInstanceProvider botInstanceProvider) {
        this.chatConfigRepository = chatConfigRepository;
        this.responseTemplateRepository = responseTemplateRepository;
        this.rateLimitsRepository = rateLimitsRepository;
        this.llmParametersRepository = llmParametersRepository;
        this.botInstanceProvider = botInstanceProvider;
    }

    public Mono<ResolvedConfig> resolve(long chatId) {
        String botId = botInstanceProvider != null ? botInstanceProvider.getInstanceId() : null;
        return resolveForBot(chatId, botId);
    }

    public Mono<ResolvedConfig> resolveForBot(long chatId, String botInstanceId) {
        return resolveBase(chatId)
                .map(base -> new ResolvedConfig(base.config(), base.template(), base.rateLimits(), base.llmParameters(), botInstanceId));
    }

    public Mono<ResolvedBaseConfig> resolveBase(long chatId) {
        CachedBase cached = cache.get(chatId);
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return Mono.just(cached.value());
        }

        return chatConfigRepository.findByChannelChatId(chatId)
                .filter(ChatConfig::isEnabled)
                .flatMap(chatConfig -> Mono.zip(
                                getActiveTemplate(chatConfig)
                                        .map(Optional::of)
                                        .defaultIfEmpty(Optional.empty()),
                                ensureRateLimits(chatConfig.getId()),
                                ensureLlmParameters(chatConfig.getId())
                        )
                        .map(tuple -> new ResolvedBaseConfig(chatConfig, tuple.getT1().orElse(null), tuple.getT2(), tuple.getT3())))
                .doOnNext(cfg -> cache.put(chatId, new CachedBase(cfg, Instant.now().plus(TTL))));
    }

    public void invalidateAll() {
        cache.clear();
    }

    public void invalidate(long chatId) {
        cache.remove(chatId);
    }

    private Mono<ResponseTemplate> getActiveTemplate(ChatConfig config) {
        return responseTemplateRepository.findByChatConfigIdAndIsDefaultTrueAndActiveTrue(config.getId())
                .switchIfEmpty(responseTemplateRepository.findByChatConfigIdAndActiveTrueOrderByPriorityDesc(config.getId()).next());
    }

    private Mono<RateLimits> ensureRateLimits(Long chatConfigId) {
        if (chatConfigId == null) {
            return Mono.just(new RateLimits(null));
        }
        return rateLimitsRepository.findByChatConfigId(chatConfigId)
                .switchIfEmpty(rateLimitsRepository.save(new RateLimits(chatConfigId))
                        .onErrorResume(e -> rateLimitsRepository.findByChatConfigId(chatConfigId))
                        .defaultIfEmpty(new RateLimits(chatConfigId)));
    }

    private Mono<LlmParameters> ensureLlmParameters(Long chatConfigId) {
        if (chatConfigId == null) {
            return Mono.just(new LlmParameters(null));
        }
        return llmParametersRepository.findByChatConfigId(chatConfigId)
                .switchIfEmpty(llmParametersRepository.save(new LlmParameters(chatConfigId))
                        .onErrorResume(e -> llmParametersRepository.findByChatConfigId(chatConfigId))
                        .defaultIfEmpty(new LlmParameters(chatConfigId)));
    }

    public record ResolvedBaseConfig(ChatConfig config, ResponseTemplate template, RateLimits rateLimits, LlmParameters llmParameters) { }

    public record ResolvedConfig(ChatConfig config,
                                 ResponseTemplate template,
                                 RateLimits rateLimits,
                                 LlmParameters llmParameters,
                                 String botInstanceId) { }

    private record CachedBase(ResolvedBaseConfig value, Instant expiresAt) { }
}
