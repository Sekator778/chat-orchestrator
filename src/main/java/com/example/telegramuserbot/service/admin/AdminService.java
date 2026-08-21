package com.example.telegramuserbot.service.admin;

import com.example.telegramuserbot.dto.ChannelDto;
import com.example.telegramuserbot.dto.ChatConfigDto;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface AdminService {
    Flux<ChannelDto> findAllChannels();

    Mono<ChatConfigDto> getConfig(Long channelId);

    Mono<ChatConfigDto> saveConfig(Long channelId, ChatConfigDto dto);

    Mono<Void> logoutAndDeleteSession();
}
