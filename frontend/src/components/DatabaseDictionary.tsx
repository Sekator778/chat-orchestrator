import { useMemo, useState } from 'react'
import type { TableDefinition } from '../data/tableDictionary'
import { tableDictionary } from '../data/tableDictionary'

type StatusFilter = 'all' | TableDefinition['status']

const statusLabels: Record<TableDefinition['status'], string> = {
  done: 'Обработано',
  pending: 'Осталось оформить',
}

export function DatabaseDictionary() {
  const [schemaFilter, setSchemaFilter] = useState<string>('all')
  const [statusFilter, setStatusFilter] = useState<StatusFilter>('all')
  const [search, setSearch] = useState('')

  const schemaOptions = useMemo(
    () => ['all', ...Array.from(new Set(tableDictionary.map((item) => item.schema)))],
    [],
  )

  const filteredTables = useMemo(() => {
    const query = search.trim().toLowerCase()
    return tableDictionary.filter((table) => {
      if (schemaFilter !== 'all' && table.schema !== schemaFilter) {
        return false
      }
      if (statusFilter !== 'all' && table.status !== statusFilter) {
        return false
      }
      if (!query) return true

      const inTableText =
        table.id.toLowerCase().includes(query) ||
        table.description.toLowerCase().includes(query) ||
        (table.notes?.toLowerCase() ?? '').includes(query)

      if (inTableText) return true

      return table.fields.some(
        (field) =>
          field.name.toLowerCase().includes(query) ||
          field.description.toLowerCase().includes(query) ||
          (field.values?.toLowerCase() ?? '').includes(query),
      )
    })
  }, [schemaFilter, search, statusFilter])

  const progress = useMemo(
    () => ({
      done: tableDictionary.filter((item) => item.status === 'done').length,
      total: tableDictionary.length,
    }),
    [],
  )

  const shownFieldCount = useMemo(
    () => filteredTables.reduce((acc, table) => acc + table.fields.length, 0),
    [filteredTables],
  )

  return (
    <div className="dictionary">
      <div className="dictionary__toolbar">
        <div>
          <p className="eyebrow">Справочник БД</p>
          <h3>Таблицы и поля</h3>
          <p className="muted tiny">
            Все таблицы проекта с описанием влияния полей. Можно искать по названию таблицы, поля или
            значению.
          </p>
        </div>
        <div className="dictionary__filters">
          <input
            placeholder="Поиск: таблица, поле или значение"
            value={search}
            onChange={(e) => setSearch(e.target.value)}
          />
          <select value={schemaFilter} onChange={(e) => setSchemaFilter(e.target.value)}>
            {schemaOptions.map((option) => (
              <option key={option} value={option}>
                {option === 'all' ? 'Все схемы' : option}
              </option>
            ))}
          </select>
          <select value={statusFilter} onChange={(e) => setStatusFilter(e.target.value as StatusFilter)}>
            <option value="all">Все статусы</option>
            <option value="done">Обработано</option>
            <option value="pending">Осталось оформить</option>
          </select>
        </div>
      </div>

      <div className="dictionary__meta">
        <div className="chips">
          <span className="chip chip--green">
            Закрыто {progress.done}/{progress.total} таблиц
          </span>
          <span className="chip chip--outline">
            Показано {filteredTables.length} таблиц · {shownFieldCount} полей
          </span>
        </div>
      </div>

      <div className="dictionary__list">
        {filteredTables.map((table) => (
          <div className="dict-table" key={table.id}>
            <div className="dict-table__header">
              <div>
                <p className="tiny muted">{table.schema}</p>
                <h4>{table.id}</h4>
                <p className="muted tiny">{table.description}</p>
                {table.notes ? <p className="tiny">{table.notes}</p> : null}
              </div>
              <div className="chips">
                <span className={`chip ${table.status === 'done' ? 'chip--green' : 'chip--warn'}`}>
                  {statusLabels[table.status]}
                </span>
                <span className="chip chip--outline">{table.fields.length} полей</span>
              </div>
            </div>
            <div className="dict-table__fields">
              {table.fields.map((field) => (
                <div className="dict-field" key={`${table.id}-${field.name}`}>
                  <div className="dict-field__name">{field.name}</div>
                  <div className="dict-field__body">
                    <p className="dict-field__desc">{field.description}</p>
                    {field.values ? (
                      <p className="dict-field__values">
                        Возможные значения: <span>{field.values}</span>
                      </p>
                    ) : null}
                  </div>
                </div>
              ))}
            </div>
          </div>
        ))}
      </div>
    </div>
  )
}
