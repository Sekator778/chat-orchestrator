package com.example.telegramuserbot.dto;

import java.util.List;

public record DbQueryResponseDto(
        List<String> columns,
        List<List<Object>> rows,
        String sql
) {
}

