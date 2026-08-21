package com.example.telegramuserbot.dto;

public record ChannelDto(
        Long chatId,
        String title,
        boolean isChannel
) {}
