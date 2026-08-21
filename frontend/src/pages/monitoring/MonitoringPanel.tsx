import { useCallback, useEffect, useState } from 'react'
import {
  fetchMonitoringDashboard,
  resetTdLibLogMetrics,
  testLoadChats,
  repairDialogState,
  triggerScoringRefresh,
  type MonitoringDashboard,
  type TdLibLogMetrics,
  type CoordinatorStatus,
  type SchedulerStatus,
  type TelegramClientInfo,
  type ScoringStatus,
} from '../../api/monitoringClient'
import './MonitoringPanel.css'

type SubPage = 'dashboard' | 'tdlib' | 'telegram' | 'scheduler'

export function MonitoringPanel() {
  const [subPage, setSubPage] = useState<SubPage>('dashboard')
  const [dashboard, setDashboard] = useState<MonitoringDashboard | null>(null)
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)
  const [actionResult, setActionResult] = useState<string | null>(null)
  const [autoRefresh, setAutoRefresh] = useState(false)

  const loadDashboard = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchMonitoringDashboard()
      setDashboard(data)
      setError(null)
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Ошибка загрузки')
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadDashboard()
  }, [loadDashboard])

  useEffect(() => {
    if (!autoRefresh) return
    const interval = setInterval(loadDashboard, 10000) // 10 секунд
    return () => clearInterval(interval)
  }, [autoRefresh, loadDashboard])

  const handleResetMetrics = async () => {
    try {
      await resetTdLibLogMetrics()
      setActionResult('Метрики сброшены')
      loadDashboard()
    } catch (e) {
      setActionResult(`Ошибка: ${e instanceof Error ? e.message : 'unknown'}`)
    }
  }

  const handleTestLoadChats = async () => {
    try {
      setActionResult('Выполняется LoadChats...')
      const result = await testLoadChats()
      setActionResult(result)
      loadDashboard()
    } catch (e) {
      setActionResult(`Ошибка: ${e instanceof Error ? e.message : 'unknown'}`)
    }
  }

  const handleRepairDialog = async () => {
    try {
      setActionResult('Выполняется восстановление...')
      const result = await repairDialogState()
      setActionResult(result)
      loadDashboard()
    } catch (e) {
      setActionResult(`Ошибка: ${e instanceof Error ? e.message : 'unknown'}`)
    }
  }

  const handleScoringRefresh = async () => {
    try {
      setActionResult('Запуск пересчёта скоринга...')
      const result = await triggerScoringRefresh()
      if (result.status === 'success') {
        setActionResult(`✅ Скоринг пересчитан за ${result.executionTimeMs}ms\n\nПараметры:\n- windowDays: ${result.parameters?.windowDays}\n- halfLifeHours: ${result.parameters?.halfLifeHours}\n- limit: ${result.parameters?.limit}`)
      } else {
        setActionResult(`❌ Ошибка: ${result.message}`)
      }
      loadDashboard()
    } catch (e) {
      setActionResult(`Ошибка: ${e instanceof Error ? e.message : 'unknown'}`)
    }
  }

  return (
    <div className="monitoring-panel">
      <div className="monitoring-header">
        <h2>Мониторинг системы</h2>
        <div className="monitoring-controls">
          <label className="auto-refresh-toggle">
            <input
              type="checkbox"
              checked={autoRefresh}
              onChange={(e) => setAutoRefresh(e.target.checked)}
            />
            Авто-обновление (10с)
          </label>
          <button onClick={loadDashboard} disabled={loading}>
            {loading ? 'Загрузка...' : 'Обновить'}
          </button>
        </div>
      </div>

      <div className="monitoring-nav">
        <button
          className={`tab ${subPage === 'dashboard' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('dashboard')}
        >
          Обзор
        </button>
        <button
          className={`tab ${subPage === 'tdlib' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('tdlib')}
        >
          TDLib Логи
        </button>
        <button
          className={`tab ${subPage === 'telegram' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('telegram')}
        >
          Telegram
        </button>
        <button
          className={`tab ${subPage === 'scheduler' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('scheduler')}
        >
          Планировщик
        </button>
      </div>

      {error && <div className="monitoring-error">{error}</div>}
      {actionResult && (
        <div className="monitoring-action-result">
          <pre>{actionResult}</pre>
          <button onClick={() => setActionResult(null)}>Закрыть</button>
        </div>
      )}

      {subPage === 'dashboard' && dashboard && (
        <DashboardView dashboard={dashboard} />
      )}

      {subPage === 'tdlib' && dashboard && (
        <TdLibView
          metrics={dashboard.tdlibMetrics}
          coordinator={dashboard.coordinatorStatus}
          onResetMetrics={handleResetMetrics}
          onTestLoadChats={handleTestLoadChats}
          onRepairDialog={handleRepairDialog}
        />
      )}

      {subPage === 'telegram' && dashboard && (
        <TelegramView
          client={dashboard.telegramClient}
          clients={dashboard.telegramClients}
        />
      )}

      {subPage === 'scheduler' && dashboard && (
        <SchedulerView
          status={dashboard.schedulerStatus}
          scoringStatus={dashboard.scoringStatus}
          onScoringRefresh={handleScoringRefresh}
        />
      )}
    </div>
  )
}

// ============================================================
// Sub-components
// ============================================================

function DashboardView({
  dashboard,
}: {
  dashboard: MonitoringDashboard
}) {
  const healthStatus = dashboard.health?.status || 'UNKNOWN'
  const healthClass =
    healthStatus === 'UP' ? 'healthy' :
    healthStatus === 'DEGRADED' ? 'degraded' :
    healthStatus === 'UNKNOWN' ? 'unknown' : 'unhealthy'

  const botHealth = dashboard.botHealth
  const botStatus = botHealth?.status || 'UNKNOWN'
  const botClass =
    botStatus === 'UP' ? 'healthy' :
    botStatus === 'DEGRADED' ? 'degraded' :
    botStatus === 'UNKNOWN' ? 'unknown' : 'unhealthy'

  return (
    <div className="dashboard-view">
      <div className="metric-cards">
        <div className={`metric-card metric-card--${healthClass}`}>
          <div className="metric-card__label">Статус системы</div>
          <div className="metric-card__value">{healthStatus}</div>
        </div>

        <div className={`metric-card metric-card--${botClass}`}>
          <div className="metric-card__label">Bot status</div>
          <div className="metric-card__value">{botStatus}</div>
          <div className="metric-card__hint">
            {botHealth
              ? `${botHealth.initializedCount} / ${botHealth.configuredCount} initialized`
              : 'N/A'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-card__label">TDLib состояние</div>
          <div className="metric-card__value">
            {dashboard.coordinatorStatus?.state || 'N/A'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-card__label">Dialog Date Warnings</div>
          <div className="metric-card__value">
            {dashboard.tdlibMetrics?.dialogDateWarnings ?? 'N/A'}
          </div>
          <div className="metric-card__hint">
            {(dashboard.tdlibMetrics?.dialogDateWarnings ?? 0) > 0
              ? 'Проверьте, растёт ли после старта'
              : 'Норма'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-card__label">Telegram клиенты</div>
          <div className="metric-card__value">
            {dashboard.telegramClients.length > 0
              ? `${dashboard.telegramClients.filter(c => c.status === 'CONNECTED').length} / ${dashboard.telegramClients.length}`
              : dashboard.telegramClient
                ? '1 / 1'
                : 'N/A'}
          </div>
          <div className="metric-card__hint">
            {dashboard.telegramClients.length > 0
              ? dashboard.telegramClients.map(c => `@${c.username || c.botId}`).join(', ')
              : dashboard.telegramClient?.username
                ? `@${dashboard.telegramClient.username}`
                : ''}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-card__label">Планировщик</div>
          <div className="metric-card__value">
            {(dashboard.schedulerStatus?.enabled || dashboard.schedulerStatus?.schedulerEnabled) ? 'Включён' : 'Выключен'}
          </div>
        </div>

        <div className="metric-card">
          <div className="metric-card__label">Активные персоны</div>
          <div className="metric-card__value">
            {dashboard.schedulerStatus?.enabledPersonas ?? 0} /{' '}
            {dashboard.schedulerStatus?.totalPersonas ?? 0}
          </div>
        </div>
      </div>

      {botHealth && botHealth.bots.length > 0 && (
        <div className="bot-health">
          <h4>Bots</h4>
          <table className="bot-health-table">
            <thead>
              <tr>
                <th>Bot ID</th>
                <th>Role</th>
                <th>Status</th>
              </tr>
            </thead>
            <tbody>
              {botHealth.bots.map((bot) => (
                <tr key={bot.botId}>
                  <td>{bot.botId}</td>
                  <td>{bot.primary ? 'Primary' : 'Secondary'}</td>
                  <td className={bot.initialized ? 'up' : 'down'}>{bot.status}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      {dashboard.errors.length > 0 && (
        <div className="dashboard-errors">
          <h4>Ошибки при загрузке:</h4>
          <ul>
            {dashboard.errors.map((err, i) => (
              <li key={i}>{err}</li>
            ))}
          </ul>
        </div>
      )}
    </div>
  )
}

function TdLibView({
  metrics,
  coordinator,
  onResetMetrics,
  onTestLoadChats,
  onRepairDialog,
}: {
  metrics: TdLibLogMetrics | null
  coordinator: CoordinatorStatus | null
  onResetMetrics: () => void
  onTestLoadChats: () => void
  onRepairDialog: () => void
}) {
  return (
    <div className="tdlib-view">
      <div className="tdlib-section">
        <h3>Метрики логирования TDLib</h3>
        {metrics ? (
          <table className="monitoring-table">
            <tbody>
              <tr>
                <td>Всего сообщений</td>
                <td>{metrics.totalMessages.toLocaleString()}</td>
              </tr>
              <tr>
                <td>Отфильтровано</td>
                <td>{metrics.filteredMessages.toLocaleString()}</td>
              </tr>
              <tr>
                <td>Dialog Date Warnings</td>
                <td className={metrics.dialogDateWarnings > 100 ? 'warning' : ''}>
                  {metrics.dialogDateWarnings.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td>Процент фильтрации</td>
                <td>{metrics.filterRatio.toFixed(2)}%</td>
              </tr>
            </tbody>
          </table>
        ) : (
          <p>Нет данных</p>
        )}

        <div className="tdlib-interpretation">
          <h4>Интерпретация:</h4>
          <ul>
            <li>
              <strong>Dialog Date Warnings = 0 после старта</strong> — норма
            </li>
            <li>
              <strong>Растёт только при старте</strong> — норма (multi-client)
            </li>
            <li>
              <strong>Постоянно растёт</strong> — требуется расследование
            </li>
          </ul>
        </div>

        <button onClick={onResetMetrics}>Сбросить счётчики</button>
      </div>

      <div className="tdlib-section">
        <h3>Координатор операций</h3>
        {coordinator ? (
          <table className="monitoring-table">
            <tbody>
              <tr>
                <td>Состояние</td>
                <td>{coordinator.state}</td>
              </tr>
              <tr>
                <td>Операция в процессе</td>
                <td>{coordinator.operationInProgress ? 'Да' : 'Нет'}</td>
              </tr>
              <tr>
                <td>Текущая операция</td>
                <td>{coordinator.currentOperation || '—'}</td>
              </tr>
              <tr>
                <td>Длительность</td>
                <td>{coordinator.operationDuration}</td>
              </tr>
            </tbody>
          </table>
        ) : (
          <p>Нет данных</p>
        )}

        <div className="tdlib-actions">
          <button onClick={onTestLoadChats}>Тест LoadChats</button>
          <button onClick={onRepairDialog}>Восстановить Dialog State</button>
        </div>
      </div>
    </div>
  )
}

function TelegramView({
  client,
  clients,
}: {
  client: TelegramClientInfo | null
  clients: TelegramClientInfo[]
}) {
  const hasMultipleClients = clients && clients.length > 0

  if (!client && !hasMultipleClients) {
    return (
      <div className="telegram-view">
        <p>Не удалось получить информацию о Telegram клиентах</p>
      </div>
    )
  }

  return (
    <div className="telegram-view" style={{ maxWidth: '800px' }}>
      <h3>Telegram клиенты ({hasMultipleClients ? clients.length : 1})</h3>

      {hasMultipleClients ? (
        <div style={{ display: 'flex', flexDirection: 'column', gap: '16px' }}>
          {clients.map((c, idx) => (
            <div
              key={c.botId || idx}
              style={{
                background: '#162030',
                borderRadius: '6px',
                padding: '16px',
                border: c.status === 'CONNECTED' ? '1px solid #2e7d32' : '1px solid #c62828',
              }}
            >
              <div style={{ display: 'flex', justifyContent: 'space-between', marginBottom: '12px' }}>
                <strong style={{ color: '#e0e0e0' }}>
                  {c.name || `Client ${idx + 1}`}
                </strong>
                <span
                  style={{
                    padding: '2px 8px',
                    borderRadius: '4px',
                    fontSize: '12px',
                    background: c.status === 'CONNECTED' ? '#1b5e20' : '#b71c1c',
                    color: '#fff',
                  }}
                >
                  {c.status || 'UNKNOWN'}
                </span>
              </div>
              <table className="monitoring-table">
                <tbody>
                  <tr>
                    <td>Bot ID</td>
                    <td>{c.botId || 'N/A'}</td>
                  </tr>
                  <tr>
                    <td>User ID</td>
                    <td>{c.id || 'N/A'}</td>
                  </tr>
                  <tr>
                    <td>Username</td>
                    <td>{c.username ? `@${c.username}` : 'N/A'}</td>
                  </tr>
                  <tr>
                    <td>Тип</td>
                    <td>{c.userType || 'N/A'}</td>
                  </tr>
                  <tr>
                    <td>Бот</td>
                    <td>{c.isBot ? 'Да' : 'Нет'}</td>
                  </tr>
                  {c.error && (
                    <tr>
                      <td>Ошибка</td>
                      <td style={{ color: '#ff8080' }}>{c.error}</td>
                    </tr>
                  )}
                </tbody>
              </table>
            </div>
          ))}
        </div>
      ) : client ? (
        <table className="monitoring-table">
          <tbody>
            <tr>
              <td>ID</td>
              <td>{client.id}</td>
            </tr>
            <tr>
              <td>Имя</td>
              <td>{client.name}</td>
            </tr>
            <tr>
              <td>Username</td>
              <td>@{client.username}</td>
            </tr>
            <tr>
              <td>Тип</td>
              <td>{client.userType}</td>
            </tr>
            <tr>
              <td>Бот</td>
              <td>{client.isBot ? 'Да' : 'Нет'}</td>
            </tr>
          </tbody>
        </table>
      ) : null}
    </div>
  )
}

function SchedulerView({
  status,
  scoringStatus,
  onScoringRefresh,
}: {
  status: SchedulerStatus | null
  scoringStatus: ScoringStatus | null
  onScoringRefresh: () => void
}) {
  return (
    <div className="scheduler-view">
      <h3>Статус планировщика дайджестов</h3>
      {status ? (
        <table className="monitoring-table">
          <tbody>
            <tr>
              <td>Планировщик</td>
              <td className={(status.enabled || status.schedulerEnabled) ? 'enabled' : 'disabled'}>
                {(status.enabled || status.schedulerEnabled) ? 'Включён' : 'Выключен'}
              </td>
            </tr>
            <tr>
              <td>Всего персон</td>
              <td>{status.totalPersonas}</td>
            </tr>
            <tr>
              <td>Активных персон</td>
              <td>{status.enabledPersonas}</td>
            </tr>
            <tr>
              <td>Дайджестов сегодня</td>
              <td>{status.digestsGeneratedToday ?? 0}</td>
            </tr>
            <tr>
              <td>Последний запуск</td>
              <td>{status.lastRunAt ? new Date(status.lastRunAt).toLocaleString() : 'никогда'}</td>
            </tr>
          </tbody>
        </table>
      ) : (
        <p>Не удалось получить статус планировщика</p>
      )}

      <div className="scheduler-hint">
        <h4>Для активации:</h4>
        <pre>{`CLUSTERING_JOB_ENABLED=true
DIGEST_JOB_ENABLED=true
DIGEST_SCHEDULER_ENABLED=true`}</pre>
      </div>

      <h3 style={{ marginTop: '2rem' }}>Система скоринга сообщений</h3>
      {scoringStatus ? (
        <>
          <table className="monitoring-table">
            <tbody>
              <tr>
                <td>Сообщений с importance</td>
                <td>{scoringStatus.messagesWithImportance.toLocaleString()}</td>
              </tr>
              <tr>
                <td>Кластеризованных сообщений</td>
                <td className={scoringStatus.clusteredMessages === 0 ? 'warning' : ''}>
                  {scoringStatus.clusteredMessages.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td>Каналов со score</td>
                <td className={scoringStatus.channelsWithScore === 0 ? 'warning' : ''}>
                  {scoringStatus.channelsWithScore.toLocaleString()}
                </td>
              </tr>
              <tr>
                <td>Последний запуск Python</td>
                <td className={scoringStatus.lastPythonRun === 'never' ? 'warning' : ''}>
                  {scoringStatus.lastPythonRun}
                </td>
              </tr>
            </tbody>
          </table>

          <div className="scheduler-actions" style={{ marginTop: '1rem' }}>
            <button onClick={onScoringRefresh}>
              🔄 Пересчитать скоринг (fn_refresh_all)
            </button>
          </div>

          <div className="scheduler-hint" style={{ marginTop: '1rem' }}>
            <h4>Пересчёт включает:</h4>
            <ul style={{ paddingLeft: '1.5rem', margin: '0.5rem 0' }}>
              <li>fn_update_clusters() - кластеризация похожих сообщений</li>
              <li>fn_update_channel_reliability() - надёжность каналов</li>
              <li>fn_recalc_importance() - пересчёт важности сообщений</li>
              <li>fn_recalc_channel_score() - обновление score каналов</li>
              <li>fn_build_agg_top_daily() - топ-агрегации</li>
            </ul>
          </div>
        </>
      ) : (
        <p>Не удалось получить статус скоринга</p>
      )}
    </div>
  )
}
