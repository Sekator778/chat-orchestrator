import { useEffect, useState, useCallback } from 'react'
import type { PersonaReactionConfig, ReactionSystemHealth } from '../../types/reaction'
import { BOT_INSTANCES } from '../../types/reaction'
import { fetchDashboardData } from '../../api/reactionClient'

type NoticeTone = 'ok' | 'warn' | 'error'

interface Notice {
  message: string
  tone: NoticeTone
}

export function ReactionDashboard() {
  const [configs, setConfigs] = useState<PersonaReactionConfig[]>([])
  const [health, setHealth] = useState<ReactionSystemHealth | null>(null)
  const [loading, setLoading] = useState(true)
  const [notice, setNotice] = useState<Notice | null>(null)

  const loadData = useCallback(async () => {
    try {
      setLoading(true)
      const data = await fetchDashboardData()
      setConfigs(data.configs)
      setHealth(data.health)
      setNotice(null)
    } catch (error) {
      const message = error instanceof Error ? error.message : 'Не удалось загрузить данные'
      setNotice({ tone: 'error', message })
    } finally {
      setLoading(false)
    }
  }, [])

  useEffect(() => {
    loadData()
  }, [loadData])

  const enabledCount = configs.filter(c => c.enabled).length

  const configsByPersona = BOT_INSTANCES.map(bot => ({
    bot,
    configs: configs.filter(c => c.personaId === bot.id),
    enabled: configs.filter(c => c.personaId === bot.id && c.enabled).length,
  }))

  if (loading) {
    return (
      <div className="reaction-dashboard">
        <div className="placeholder">Загрузка данных...</div>
      </div>
    )
  }

  return (
    <div className="reaction-dashboard">
      {notice && (
        <div className={`notice notice--${notice.tone}`}>
          {notice.message}
        </div>
      )}

      <div className="reaction-stats">
        <div className="reaction-stats__card">
          <h4>Всего конфигов</h4>
          <div className="value">{health?.totalConfigs ?? configs.length}</div>
        </div>
        <div className="reaction-stats__card">
          <h4>Активных</h4>
          <div className="value">{health?.enabledConfigs ?? enabledCount}</div>
        </div>
        <div className="reaction-stats__card">
          <h4>Ожидают</h4>
          <div className="value">{health?.pendingCount ?? 0}</div>
        </div>
        <div className="reaction-stats__card">
          <h4>Сегодня выполнено</h4>
          <div className="value">{health?.doneToday ?? 0}</div>
        </div>
        <div className="reaction-stats__card">
          <h4>Ошибок сегодня</h4>
          <div className="value">{health?.failedToday ?? 0}</div>
        </div>
        <div className="reaction-stats__card">
          <h4>Flood Wait</h4>
          <div className="value">{health?.floodWaitToday ?? 0}</div>
        </div>
      </div>

      <div className="reaction-dashboard__layout">
        <div className="reaction-dashboard__personas">
          <p className="eyebrow">Конфиги по персонам</p>
          {configsByPersona.map(({ bot, configs: botConfigs, enabled }) => (
            <div key={bot.id} className="reaction-dashboard__persona-group">
              <div className="reaction-dashboard__persona-header">
                <span>{bot.name}</span>
                <span className="chip chip--outline tiny">
                  {enabled}/{botConfigs.length} активных
                </span>
              </div>
              {botConfigs.length === 0 ? (
                <p className="muted tiny">Нет конфигураций</p>
              ) : (
                <div className="reaction-dashboard__persona-channels">
                  {botConfigs.map(c => (
                    <div key={c.id ?? `${c.personaId}-${c.channelId}`} className="reaction-dashboard__channel-item">
                      <span className="tiny">Канал {c.channelId}</span>
                      <span className={`chip tiny ${c.enabled ? 'chip--green' : 'chip--outline'}`}>
                        {c.enabled ? `до ${c.maxPerDay}/день` : 'выкл'}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          ))}
        </div>

        <div className="reaction-dashboard__emoji-pool">
          <p className="eyebrow">Пул эмодзи</p>
          <p className="muted tiny" style={{ marginBottom: '12px' }}>
            Реакции выбираются случайно согласно весам для имитации живого поведения.
          </p>
          <div className="reaction-emoji-pool">
            <div className="reaction-emoji-pool__item">
              <span>👍</span>
              <span className="tiny">60%</span>
            </div>
            <div className="reaction-emoji-pool__item">
              <span>🔥</span>
              <span className="tiny">30%</span>
            </div>
            <div className="reaction-emoji-pool__item">
              <span>💯</span>
              <span className="tiny">10%</span>
            </div>
          </div>
          <p className="muted tiny" style={{ marginTop: '16px' }}>
            Задержка: 30–180 секунд после публикации поста. Антидетекшн включен.
          </p>
        </div>
      </div>

      <div style={{ marginTop: '16px', textAlign: 'right' }}>
        <button className="ghost" onClick={loadData} disabled={loading}>
          Обновить
        </button>
      </div>
    </div>
  )
}
