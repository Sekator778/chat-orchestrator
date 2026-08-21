package com.example.telegramuserbot.service.admin;

import com.example.telegramuserbot.dto.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;
import reactor.core.publisher.Mono;

import java.util.*;
import java.util.regex.Pattern;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.NOT_FOUND;

@Service
public class DbExplorerService {

    private static final Pattern IDENTIFIER = Pattern.compile("^[a-zA-Z_][a-zA-Z0-9_]*$");

    private final DatabaseClient databaseClient;
    private final Set<String> allowedSchemas;
    private final int maxLimit;
    private final int defaultLimit;

    public DbExplorerService(
            DatabaseClient databaseClient,
            @Value("${admin.db.explorer.allowed-schemas:bot,tgscan}") String allowedSchemas,
            @Value("${admin.db.explorer.max-limit:200}") int maxLimit,
            @Value("${admin.db.explorer.default-limit:50}") int defaultLimit
    ) {
        this.databaseClient = databaseClient;
        this.allowedSchemas = parseAllowedSchemas(allowedSchemas);
        this.maxLimit = maxLimit;
        this.defaultLimit = defaultLimit;
    }

    public Mono<List<DbSchemaDto>> listSchemas() {
        return Mono.just(
                allowedSchemas.stream()
                        .sorted()
                        .map(DbSchemaDto::new)
                        .toList()
        );
    }

    public Mono<List<DbTableDto>> listTables(String schema) {
        String normalizedSchema = validateSchema(schema);

        return databaseClient.sql("""
                        SELECT table_name, table_type
                        FROM information_schema.tables
                        WHERE table_schema = :schema
                          AND table_type IN ('BASE TABLE', 'VIEW')
                        ORDER BY table_name
                        """)
                .bind("schema", normalizedSchema)
                .map((row, meta) -> new DbTableDto(
                        Objects.toString(row.get("table_name"), ""),
                        Objects.toString(row.get("table_type"), "")
                ))
                .all()
                .collectList();
    }

    public Mono<DbTableMetaDto> getTableMeta(String schema, String table) {
        String normalizedSchema = validateSchema(schema);
        String normalizedTable = validateIdentifierOrThrow(table, "table");

        return databaseClient.sql("""
                        SELECT
                          column_name,
                          data_type,
                          udt_name,
                          is_nullable,
                          ordinal_position,
                          character_maximum_length,
                          numeric_precision,
                          numeric_scale
                        FROM information_schema.columns
                        WHERE table_schema = :schema
                          AND table_name = :table
                        ORDER BY ordinal_position
                        """)
                .bind("schema", normalizedSchema)
                .bind("table", normalizedTable)
                .map((row, meta) -> new DbColumnDto(
                        Objects.toString(row.get("column_name"), ""),
                        Objects.toString(row.get("data_type"), ""),
                        Objects.toString(row.get("udt_name"), ""),
                        "YES".equalsIgnoreCase(Objects.toString(row.get("is_nullable"), "")),
                        ((Number) row.get("ordinal_position")).intValue(),
                        (Integer) row.get("character_maximum_length"),
                        (Integer) row.get("numeric_precision"),
                        (Integer) row.get("numeric_scale")
                ))
                .all()
                .collectList()
                .flatMap(columns -> {
                    if (columns.isEmpty()) {
                        return Mono.error(new ResponseStatusException(
                                NOT_FOUND,
                                "Table not found: " + normalizedSchema + "." + normalizedTable
                        ));
                    }
                    return Mono.just(new DbTableMetaDto(normalizedSchema, normalizedTable, columns));
                });
    }

    public Mono<DbQueryResponseDto> query(DbQueryRequestDto request) {
        if (request == null) {
            return Mono.error(new ResponseStatusException(BAD_REQUEST, "Missing request body"));
        }

        String schema = validateSchema(request.schema());
        String table = validateIdentifierOrThrow(request.table(), "table");

        return getTableMeta(schema, table).flatMap(meta -> {
            List<String> availableColumnsOrdered = meta.columns().stream().map(DbColumnDto::name).toList();
            Set<String> availableColumns = new HashSet<>(availableColumnsOrdered);

            List<String> selected = normalizeSelection(request.select(), availableColumns, availableColumnsOrdered);
            List<DbFilterDto> filters = request.filters() == null ? List.of() : request.filters();
            List<DbOrderByDto> orderBy = request.orderBy() == null ? List.of() : request.orderBy();

            int limit = normalizeLimit(request.limit());
            int offset = normalizeOffset(request.offset());

            SqlSpec sqlSpec = buildSql(schema, table, selected, filters, orderBy, availableColumns, limit, offset);

            DatabaseClient.GenericExecuteSpec exec = databaseClient.sql(sqlSpec.sql);
            for (Map.Entry<String, Object> entry : sqlSpec.binds.entrySet()) {
                exec = exec.bind(entry.getKey(), entry.getValue());
            }

            return exec.map((row, rowMeta) -> {
                        List<Object> values = new ArrayList<>(selected.size());
                        for (int i = 0; i < selected.size(); i++) {
                            values.add(row.get(i, Object.class));
                        }
                        return values;
                    })
                    .all()
                    .collectList()
                    .map(rows -> new DbQueryResponseDto(selected, rows, sqlSpec.sql));
        });
    }

    private String validateSchema(String schema) {
        String normalized = validateIdentifierOrThrow(schema, "schema");
        if (!allowedSchemas.contains(normalized)) {
            throw new ResponseStatusException(BAD_REQUEST, "Schema not allowed: " + normalized);
        }
        return normalized;
    }

    private static String validateIdentifierOrThrow(String raw, String label) {
        if (raw == null || raw.isBlank()) {
            throw new ResponseStatusException(BAD_REQUEST, "Missing " + label);
        }
        String normalized = raw.trim();
        if (!IDENTIFIER.matcher(normalized).matches()) {
            throw new ResponseStatusException(BAD_REQUEST, "Invalid " + label + ": " + normalized);
        }
        return normalized;
    }

    private static Set<String> parseAllowedSchemas(String raw) {
        if (raw == null) return Set.of("bot", "tgscan");
        Set<String> result = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String item = part.trim();
            if (!item.isBlank()) {
                result.add(item);
            }
        }
        if (result.isEmpty()) {
            return Set.of("bot", "tgscan");
        }
        return Collections.unmodifiableSet(result);
    }

    private int normalizeLimit(Integer requested) {
        int limit = requested == null ? defaultLimit : requested;
        if (limit <= 0) {
            throw new ResponseStatusException(BAD_REQUEST, "limit must be > 0");
        }
        if (limit > maxLimit) {
            throw new ResponseStatusException(BAD_REQUEST, "limit must be <= " + maxLimit);
        }
        return limit;
    }

    private static int normalizeOffset(Integer requested) {
        int offset = requested == null ? 0 : requested;
        if (offset < 0) {
            throw new ResponseStatusException(BAD_REQUEST, "offset must be >= 0");
        }
        return offset;
    }

    private static List<String> normalizeSelection(
            List<String> select,
            Set<String> availableColumns,
            List<String> availableColumnsOrdered
    ) {
        if (select == null || select.isEmpty()) {
            return availableColumnsOrdered;
        }
        List<String> result = new ArrayList<>(select.size());
        for (String raw : select) {
            String column = validateIdentifierOrThrow(raw, "column");
            if (!availableColumns.contains(column)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown column: " + column);
            }
            result.add(column);
        }
        if (result.isEmpty()) {
            return availableColumnsOrdered;
        }
        return result;
    }

    private static SqlSpec buildSql(
            String schema,
            String table,
            List<String> selected,
            List<DbFilterDto> filters,
            List<DbOrderByDto> orderBy,
            Set<String> availableColumns,
            int limit,
            int offset
    ) {
        StringBuilder sql = new StringBuilder();
        sql.append("SELECT ");
        sql.append(selected.stream().map(DbExplorerService::quote).reduce((a, b) -> a + ", " + b).orElse("*"));
        sql.append(" FROM ").append(quote(schema)).append(".").append(quote(table));

        Map<String, Object> binds = new LinkedHashMap<>();
        int bindIndex = 0;

        List<String> where = new ArrayList<>();
        for (DbFilterDto filter : filters) {
            if (filter == null) continue;
            String column = validateIdentifierOrThrow(filter.column(), "column");
            if (!availableColumns.contains(column)) {
                throw new ResponseStatusException(BAD_REQUEST, "Unknown column: " + column);
            }
            DbFilterOp op = filter.op();
            if (op == null) {
                throw new ResponseStatusException(BAD_REQUEST, "Missing filter.op for column: " + column);
            }
            String colSql = quote(column);
            switch (op) {
                case IS_NULL -> where.add(colSql + " IS NULL");
                case IS_NOT_NULL -> where.add(colSql + " IS NOT NULL");
                case CONTAINS, STARTS_WITH, ENDS_WITH -> {
                    Object value = filter.value();
                    if (value == null) {
                        throw new ResponseStatusException(BAD_REQUEST, "Missing filter.value for column: " + column);
                    }
                    String param = "p" + bindIndex++;
                    String text = String.valueOf(value);
                    String pattern = switch (op) {
                        case CONTAINS -> "%" + text + "%";
                        case STARTS_WITH -> text + "%";
                        case ENDS_WITH -> "%" + text;
                        default -> throw new IllegalStateException("Unexpected op: " + op);
                    };
                    binds.put(param, pattern);
                    where.add("CAST(" + colSql + " AS TEXT) ILIKE :" + param);
                }
                case EQ, NE, GT, GTE, LT, LTE -> {
                    Object value = filter.value();
                    if (value == null) {
                        throw new ResponseStatusException(BAD_REQUEST, "Missing filter.value for column: " + column);
                    }
                    String param = "p" + bindIndex++;
                    binds.put(param, value);
                    String operator = switch (op) {
                        case EQ -> "=";
                        case NE -> "<>";
                        case GT -> ">";
                        case GTE -> ">=";
                        case LT -> "<";
                        case LTE -> "<=";
                        default -> throw new IllegalStateException("Unexpected op: " + op);
                    };
                    where.add(colSql + " " + operator + " :" + param);
                }
            }
        }
        if (!where.isEmpty()) {
            sql.append(" WHERE ").append(String.join(" AND ", where));
        }

        if (!orderBy.isEmpty()) {
            List<String> orderParts = new ArrayList<>();
            for (DbOrderByDto item : orderBy) {
                if (item == null) continue;
                String column = validateIdentifierOrThrow(item.column(), "column");
                if (!availableColumns.contains(column)) {
                    throw new ResponseStatusException(BAD_REQUEST, "Unknown column: " + column);
                }
                SortDirection direction = item.direction() == null ? SortDirection.ASC : item.direction();
                orderParts.add(quote(column) + " " + direction.name());
            }
            if (!orderParts.isEmpty()) {
                sql.append(" ORDER BY ").append(String.join(", ", orderParts));
            }
        }

        sql.append(" LIMIT ").append(limit);
        if (offset > 0) {
            sql.append(" OFFSET ").append(offset);
        }

        return new SqlSpec(sql.toString(), binds);
    }

    private static String quote(String identifier) {
        return "\"" + identifier + "\"";
    }

    private record SqlSpec(String sql, Map<String, Object> binds) {
    }
}

