package com.example.telegramuserbot.dto;

import java.util.List;

public record DbQueryRequestDto(
        String schema,
        String table,
        List<String> select,
        List<DbFilterDto> filters,
        List<DbOrderByDto> orderBy,
        Integer limit,
        Integer offset
) {
}

