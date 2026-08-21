package com.example.telegramuserbot.dto;

public record DbOrderByDto(
        String column,
        SortDirection direction
) {
}

