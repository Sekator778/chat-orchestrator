package com.example.telegramuserbot.dto;

import java.util.List;

public record DbTableMetaDto(
        String schema,
        String table,
        List<DbColumnDto> columns
) {
}

