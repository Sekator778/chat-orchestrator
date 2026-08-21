package com.example.telegramuserbot.repository;

import com.example.telegramuserbot.domain.ProblematicChat;
import org.springframework.data.r2dbc.repository.R2dbcRepository;
import reactor.core.publisher.Mono;

public interface ProblematicChatRepository extends R2dbcRepository<ProblematicChat, Long> {

    /**
     * Convenience finder used by the guard to log additional details when a chat is blocked.
     */
    Mono<ProblematicChat> findByChannelChatId(Long channelChatId);
}
