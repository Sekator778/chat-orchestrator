package com.example.telegramuserbot.service.telegram;

import com.example.telegramuserbot.config.BotInstanceProvider;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Answers "was this message sent by one of OUR accounts?" across every
 * configured persona client. Bot-to-bot replies are banned product-wide:
 * personas must never answer each other, no matter which account received
 * the update.
 *
 * <p>Fail-open: while a client is not initialized its self id is unknown and
 * simply not part of the check — a regular user message is never blocked.</p>
 */
@Service
public class OwnAccountSenderFilter {

    private final TelegramSelfUserIdResolver selfUserIdResolver;
    private final BotInstanceProvider botInstanceProvider;

    public OwnAccountSenderFilter(TelegramSelfUserIdResolver selfUserIdResolver,
                                  BotInstanceProvider botInstanceProvider) {
        this.selfUserIdResolver = selfUserIdResolver;
        this.botInstanceProvider = botInstanceProvider;
    }

    public Mono<Boolean> isOwnSender(Long senderId) {
        if (senderId == null) {
            return Mono.just(false);
        }
        return Flux.fromIterable(botInstanceProvider.getInstanceIds())
                .flatMap(selfUserIdResolver::resolveSelfUserId)
                .any(selfId -> selfId.equals(senderId))
                .defaultIfEmpty(false);
    }
}
