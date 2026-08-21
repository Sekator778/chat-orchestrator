package com.example.telegramuserbot.controller;

import com.example.telegramuserbot.config.ConditionalOnHttpEnabled;
import com.example.telegramuserbot.dto.*;
import com.example.telegramuserbot.service.admin.DbExplorerService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Mono;

import java.util.List;

@RestController
@ConditionalOnHttpEnabled
@RequestMapping("/api/admin/db/explorer")
@Tag(name = "DB Explorer", description = "Schema-driven DB explorer (read-only, safe DSL)")
public class DbExplorerController {

    private static final Logger uiLog = LoggerFactory.getLogger("frontend.ui");

    private final DbExplorerService dbExplorerService;

    public DbExplorerController(DbExplorerService dbExplorerService) {
        this.dbExplorerService = dbExplorerService;
    }

    @GetMapping("/schemas")
    @Operation(summary = "List allowed schemas")
    public Mono<List<DbSchemaDto>> schemas() {
        uiLog.info("UI:dbExplorer.schemas");
        return dbExplorerService.listSchemas();
    }

    @GetMapping("/schemas/{schema}/tables")
    @Operation(summary = "List tables in schema")
    public Mono<List<DbTableDto>> tables(@PathVariable String schema) {
        uiLog.info("UI:dbExplorer.tables schema={}", schema);
        return dbExplorerService.listTables(schema);
    }

    @GetMapping("/schemas/{schema}/tables/{table}/meta")
    @Operation(summary = "Get table metadata (columns)")
    public Mono<DbTableMetaDto> tableMeta(@PathVariable String schema, @PathVariable String table) {
        uiLog.info("UI:dbExplorer.tableMeta schema={} table={}", schema, table);
        return dbExplorerService.getTableMeta(schema, table);
    }

    @PostMapping("/query")
    @Operation(summary = "Run a safe query DSL")
    public Mono<DbQueryResponseDto> query(@RequestBody DbQueryRequestDto request) {
        int selectCount = request == null || request.select() == null ? 0 : request.select().size();
        int filterCount = request == null || request.filters() == null ? 0 : request.filters().size();
        uiLog.info(
                "UI:dbExplorer.query schema={} table={} select={} filters={} limit={} offset={}",
                request == null ? null : request.schema(),
                request == null ? null : request.table(),
                selectCount,
                filterCount,
                request == null ? null : request.limit(),
                request == null ? null : request.offset()
        );
        return dbExplorerService.query(request);
    }
}

