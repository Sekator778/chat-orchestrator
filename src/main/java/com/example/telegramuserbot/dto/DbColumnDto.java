package com.example.telegramuserbot.dto;

public record DbColumnDto(
        String name,
        String dataType,
        String udtName,
        boolean nullable,
        int ordinalPosition,
        Integer characterMaximumLength,
        Integer numericPrecision,
        Integer numericScale
) {
}

