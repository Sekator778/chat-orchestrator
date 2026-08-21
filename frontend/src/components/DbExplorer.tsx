import { useEffect, useMemo, useState } from 'react'
import {
  fetchDbSchemas,
  fetchDbTableMeta,
  fetchDbTables,
  runDbQuery,
} from '../api/client'
import type {
  DbColumn,
  DbFilter,
  DbFilterOp,
  DbQueryResponse,
  DbSchema,
  DbTable,
  DbTableMeta,
  SortDirection,
} from '../types/api'
import { Section } from './Section'

const filterOps: { value: DbFilterOp; label: string; needsValue: boolean }[] = [
  { value: 'EQ', label: '=', needsValue: true },
  { value: 'NE', label: '≠', needsValue: true },
  { value: 'GT', label: '>', needsValue: true },
  { value: 'GTE', label: '≥', needsValue: true },
  { value: 'LT', label: '<', needsValue: true },
  { value: 'LTE', label: '≤', needsValue: true },
  { value: 'CONTAINS', label: 'contains (ILIKE)', needsValue: true },
  { value: 'STARTS_WITH', label: 'starts_with (ILIKE)', needsValue: true },
  { value: 'ENDS_WITH', label: 'ends_with (ILIKE)', needsValue: true },
  { value: 'IS_NULL', label: 'is null', needsValue: false },
  { value: 'IS_NOT_NULL', label: 'is not null', needsValue: false },
]

type Notice = { tone: 'ok' | 'warn' | 'bad'; message: string }

function columnTypeLabel(column: DbColumn) {
  const base = column.data_type || column.udt_name || 'unknown'
  const parts: string[] = [base]
  if (column.character_maximum_length != null) parts.push(`(${column.character_maximum_length})`)
  if (column.numeric_precision != null) {
    parts.push(
      `(${column.numeric_precision}${column.numeric_scale != null ? `,${column.numeric_scale}` : ''})`,
    )
  }
  return parts.join('')
}

function safeTrim(value: string) {
  return value.trim()
}

export function DbExplorer() {
  const [schemas, setSchemas] = useState<DbSchema[]>([])
  const [tables, setTables] = useState<DbTable[]>([])
  const [meta, setMeta] = useState<DbTableMeta | null>(null)
  const [selectedSchema, setSelectedSchema] = useState<string>('')
  const [selectedTable, setSelectedTable] = useState<string>('')

  const [selectedColumns, setSelectedColumns] = useState<Set<string>>(new Set())
  const [filters, setFilters] = useState<DbFilter[]>([])
  const [sortColumn, setSortColumn] = useState<string>('')
  const [sortDirection, setSortDirection] = useState<SortDirection>('DESC')
  const [limit, setLimit] = useState<number>(50)
  const [offset, setOffset] = useState<number>(0)

  const [result, setResult] = useState<DbQueryResponse | null>(null)
  const [loading, setLoading] = useState(false)
  const [notice, setNotice] = useState<Notice | null>(null)

  const columns = meta?.columns ?? []

  const columnsByName = useMemo(() => {
    const map = new Map<string, DbColumn>()
    for (const col of columns) map.set(col.name, col)
    return map
  }, [columns])

  const selectableColumns = useMemo(() => columns.map((c) => c.name), [columns])

  useEffect(() => {
    let alive = true
    setLoading(true)
    fetchDbSchemas()
      .then((data) => {
        if (!alive) return
        setSchemas(data)
        const first = data[0]?.name ?? ''
        setSelectedSchema(first)
      })
      .catch((err) => alive && setNotice({ tone: 'bad', message: String(err?.message ?? err) }))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [])

  useEffect(() => {
    if (!selectedSchema) return
    let alive = true
    setLoading(true)
    setTables([])
    setMeta(null)
    setSelectedTable('')
    setSelectedColumns(new Set())
    setResult(null)

    fetchDbTables(selectedSchema)
      .then((data) => {
        if (!alive) return
        setTables(data)
      })
      .catch((err) => alive && setNotice({ tone: 'bad', message: String(err?.message ?? err) }))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [selectedSchema])

  useEffect(() => {
    if (!selectedSchema || !selectedTable) return
    let alive = true
    setLoading(true)
    setMeta(null)
    setSelectedColumns(new Set())
    setFilters([])
    setSortColumn('')
    setResult(null)

    fetchDbTableMeta(selectedSchema, selectedTable)
      .then((data) => {
        if (!alive) return
        setMeta(data)
        setSelectedColumns(new Set(data.columns.map((c) => c.name)))
      })
      .catch((err) => alive && setNotice({ tone: 'bad', message: String(err?.message ?? err) }))
      .finally(() => alive && setLoading(false))
    return () => {
      alive = false
    }
  }, [selectedSchema, selectedTable])

  const toggleColumn = (name: string) => {
    setSelectedColumns((prev) => {
      const next = new Set(prev)
      if (next.has(name)) next.delete(name)
      else next.add(name)
      return next
    })
  }

  const selectAll = () => setSelectedColumns(new Set(selectableColumns))
  const clearSelection = () => setSelectedColumns(new Set())

  const addFilter = () => {
    const defaultColumn = selectableColumns[0] ?? ''
    setFilters((prev) => [
      ...prev,
      { column: defaultColumn, op: 'CONTAINS', value: '' },
    ])
  }

  const updateFilter = (index: number, patch: Partial<DbFilter>) => {
    setFilters((prev) =>
      prev.map((item, i) => (i === index ? { ...item, ...patch } : item)),
    )
  }

  const removeFilter = (index: number) => setFilters((prev) => prev.filter((_, i) => i !== index))

  const run = async () => {
    if (!selectedSchema || !selectedTable) {
      setNotice({ tone: 'warn', message: 'Выберите schema и table' })
      return
    }

    const selection = Array.from(selectedColumns)
    if (selection.length === 0) {
      setNotice({ tone: 'warn', message: 'Выберите хотя бы одну колонку' })
      return
    }

    const normalizedFilters: DbFilter[] = []
    for (const filter of filters) {
      const opConfig = filterOps.find((item) => item.value === filter.op)
      if (!filter.column || !filter.op || !opConfig) continue
      if (opConfig.needsValue) {
        const raw = filter.value == null ? '' : String(filter.value)
        const value = safeTrim(raw)
        if (!value) continue
        normalizedFilters.push({ column: filter.column, op: filter.op, value })
      } else {
        normalizedFilters.push({ column: filter.column, op: filter.op })
      }
    }

    setLoading(true)
    setNotice(null)
    try {
      const response = await runDbQuery({
        schema: selectedSchema,
        table: selectedTable,
        select: selection,
        filters: normalizedFilters.length ? normalizedFilters : undefined,
        order_by: sortColumn
          ? [{ column: sortColumn, direction: sortDirection }]
          : undefined,
        limit,
        offset,
      })
      setResult(response)
      setNotice({ tone: 'ok', message: `OK: ${response.rows.length} строк` })
    } catch (err: any) {
      setNotice({ tone: 'bad', message: String(err?.message ?? err) })
      setResult(null)
    } finally {
      setLoading(false)
    }
  }

  const previewColumns = result?.columns ?? []
  const previewRows = result?.rows ?? []

  return (
    <div className="explorer">
      {notice ? <div className={`notice notice--${notice.tone}`}>{notice.message}</div> : null}

      <div className="explorer__layout">
        <aside className="explorer__rail">
          <div className="rail__header">
            <div>
              <p className="eyebrow">DB Explorer</p>
              <h3>Схема и таблица</h3>
              <p className="muted tiny">Без raw SQL: только безопасный query DSL.</p>
            </div>
          </div>

          <div className="input-line">
            <label className="stack">
              <span className="tiny muted">Schema</span>
              <select
                value={selectedSchema}
                onChange={(e) => setSelectedSchema(e.target.value)}
                disabled={loading}
              >
                {schemas.length === 0 ? <option value="">—</option> : null}
                {schemas.map((s) => (
                  <option key={s.name} value={s.name}>
                    {s.name}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="input-line">
            <label className="stack">
              <span className="tiny muted">Table</span>
              <select
                value={selectedTable}
                onChange={(e) => setSelectedTable(e.target.value)}
                disabled={loading || !selectedSchema}
              >
                <option value="" disabled>
                  ➕ Выберите таблицу
                </option>
                {tables.map((t) => (
                  <option key={`${t.name}-${t.type}`} value={t.name}>
                    {t.name} {t.type === 'VIEW' ? '(view)' : ''}
                  </option>
                ))}
              </select>
            </label>
          </div>

          <div className="explorer__rail-actions">
            <button className="ghost" onClick={selectAll} disabled={loading || !meta}>
              Выбрать все
            </button>
            <button className="ghost" onClick={clearSelection} disabled={loading || !meta}>
              Сбросить
            </button>
          </div>

          <div className="explorer__columns">
            {!meta ? (
              <p className="muted tiny">Выберите таблицу — появятся колонки.</p>
            ) : (
              columns.map((col) => (
                <label key={col.name} className="explorer__col">
                  <input
                    type="checkbox"
                    checked={selectedColumns.has(col.name)}
                    onChange={() => toggleColumn(col.name)}
                  />
                  <span className="explorer__col-name">{col.name}</span>
                  <span className="explorer__col-type">{columnTypeLabel(col)}</span>
                  {!col.nullable ? <span className="chip chip--outline tiny">NOT NULL</span> : null}
                </label>
              ))
            )}
          </div>
        </aside>

        <div className="explorer__main">
          <Section
            title="Фильтры"
            accent="query"
            description="Несколько фильтров объединяются через AND."
            actions={
              <button className="ghost" onClick={addFilter} disabled={loading || !meta}>
                + Добавить
              </button>
            }
          >
            {filters.length === 0 ? (
              <p className="muted tiny">Фильтров нет.</p>
            ) : (
              <div className="explorer__filters">
                {filters.map((filter, idx) => {
                  const opConfig = filterOps.find((item) => item.value === filter.op)
                  const col = columnsByName.get(filter.column)
                  return (
                    <div className="explorer__filter" key={`f-${idx}`}>
                      <select
                        value={filter.column}
                        onChange={(e) => updateFilter(idx, { column: e.target.value })}
                        disabled={loading}
                      >
                        {selectableColumns.map((c) => (
                          <option key={c} value={c}>
                            {c}
                          </option>
                        ))}
                      </select>
                      <select
                        value={filter.op}
                        onChange={(e) => updateFilter(idx, { op: e.target.value as DbFilterOp })}
                        disabled={loading}
                      >
                        {filterOps.map((op) => (
                          <option key={op.value} value={op.value}>
                            {op.label}
                          </option>
                        ))}
                      </select>
                      {opConfig?.needsValue ? (
                        <input
                          placeholder={col ? columnTypeLabel(col) : 'value'}
                          value={filter.value == null ? '' : String(filter.value)}
                          onChange={(e) => updateFilter(idx, { value: e.target.value })}
                          disabled={loading}
                        />
                      ) : (
                        <input value="(no value)" disabled />
                      )}
                      <button className="ghost danger" onClick={() => removeFilter(idx)} disabled={loading}>
                        Удалить
                      </button>
                    </div>
                  )
                })}
              </div>
            )}
          </Section>

          <Section title="Параметры" accent="query">
            <div className="form-grid">
              <label>
                <span>Sort column</span>
                <select
                  value={sortColumn}
                  onChange={(e) => setSortColumn(e.target.value)}
                  disabled={loading || !meta}
                >
                  <option value="">(none)</option>
                  {selectableColumns.map((c) => (
                    <option key={c} value={c}>
                      {c}
                    </option>
                  ))}
                </select>
              </label>
              <label>
                <span>Direction</span>
                <select
                  value={sortDirection}
                  onChange={(e) => setSortDirection(e.target.value as SortDirection)}
                  disabled={loading || !meta || !sortColumn}
                >
                  <option value="DESC">DESC</option>
                  <option value="ASC">ASC</option>
                </select>
              </label>
              <label>
                <span>Limit</span>
                <input
                  type="number"
                  value={limit}
                  onChange={(e) => setLimit(Number(e.target.value))}
                  disabled={loading}
                />
              </label>
              <label>
                <span>Offset</span>
                <input
                  type="number"
                  value={offset}
                  onChange={(e) => setOffset(Number(e.target.value))}
                  disabled={loading}
                />
              </label>
            </div>
            <div className="explorer__run">
              <button className="ghost" onClick={run} disabled={loading || !meta}>
                {loading ? 'Выполняем…' : 'Выполнить'}
              </button>
              {result?.sql ? <p className="muted tiny">SQL: {result.sql}</p> : null}
            </div>
          </Section>

          <Section title="Результат" accent="preview">
            {!result ? (
              <p className="muted tiny">Пока нет результатов.</p>
            ) : (
              <div className="explorer__results">
                <div className="explorer__table-wrap">
                  <table className="explorer__table">
                    <thead>
                      <tr>
                        {previewColumns.map((c) => (
                          <th key={c}>{c}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {previewRows.map((row, idx) => (
                        <tr key={`r-${idx}`}>
                          {row.map((cell, j) => (
                            <td key={`c-${idx}-${j}`}>{cell == null ? 'null' : String(cell)}</td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              </div>
            )}
          </Section>
        </div>
      </div>
    </div>
  )
}

