package com.example.telegramuserbot.dto;

public record DbFilterDto(
        String column,
        DbFilterOp op,
        Object value
) {
}

