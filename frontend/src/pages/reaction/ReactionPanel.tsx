import { useState } from 'react'
import { ReactionDashboard } from './ReactionDashboard'
import { ReactionConfigList } from './ReactionConfigList'
import { ReactionConfigEditor } from './ReactionConfigEditor'
import { ReactionHistory } from './ReactionHistory'
import type { PersonaReactionConfig } from '../../types/reaction'

type ReactionSubPage = 'dashboard' | 'configs' | 'editor' | 'history'

export function ReactionPanel() {
  const [subPage, setSubPage] = useState<ReactionSubPage>('dashboard')
  const [selectedConfigId, setSelectedConfigId] = useState<number | null>(null)
  const [notice, setNotice] = useState<{ message: string; tone: 'ok' | 'warn' | 'error' } | null>(null)
  const [refreshTrigger, setRefreshTrigger] = useState(0)

  const handleSelectConfig = (config: PersonaReactionConfig) => {
    setSelectedConfigId(config.id ?? null)
    setSubPage('editor')
  }

  const handleCreateNew = () => {
    setSelectedConfigId(null)
    setSubPage('editor')
  }

  const handleBackToList = () => {
    setSelectedConfigId(null)
    setSubPage('configs')
  }

  const handleConfigSaved = (_config: PersonaReactionConfig) => {
    setNotice({ message: 'Конфигурация сохранена', tone: 'ok' })
    setRefreshTrigger(prev => prev + 1)
    setSubPage('configs')
    setSelectedConfigId(null)
  }

  return (
    <div className="reaction-panel">
      <div className="reaction-hero">
        <div>
          <p className="eyebrow">Persona Reactions</p>
          <h2>Автоматические реакции</h2>
          <p className="muted">
            Управление реакциями бот-персон на посты в мониторируемых каналах. Антидетекшн с рандомизацией задержек.
          </p>
        </div>
        <div className="chips">
          <span className="chip chip--violet">Live</span>
          <span className="chip chip--outline">Anti-Detection</span>
        </div>
      </div>

      {notice && (
        <div className={`notice notice--${notice.tone}`}>{notice.message}</div>
      )}

      <div className="reaction-nav">
        <button
          className={`tab ${subPage === 'dashboard' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('dashboard')}
        >
          Обзор
        </button>
        <button
          className={`tab ${subPage === 'configs' || subPage === 'editor' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('configs')}
        >
          Конфигурации
        </button>
        <button
          className={`tab ${subPage === 'history' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('history')}
        >
          История
        </button>
      </div>

      <div className="reaction-content">
        {subPage === 'dashboard' && (
          <ReactionDashboard />
        )}

        {subPage === 'configs' && (
          <ReactionConfigList
            onSelectConfig={handleSelectConfig}
            onCreateNew={handleCreateNew}
            refreshTrigger={refreshTrigger}
          />
        )}

        {subPage === 'editor' && (
          <ReactionConfigEditor
            configId={selectedConfigId}
            onSaved={handleConfigSaved}
            onCancel={handleBackToList}
          />
        )}

        {subPage === 'history' && (
          <ReactionHistory />
        )}
      </div>
    </div>
  )
}
