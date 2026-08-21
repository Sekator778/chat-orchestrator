import { useState } from 'react';
import { DigestDashboard } from './DigestDashboard';
import { DigestPersonaList } from './DigestPersonaList';
import { DigestPersonaEditor } from './DigestPersonaEditor';
import { DigestAnalytics } from './DigestAnalytics';
import { DigestHistory } from './DigestHistory';
import type { DigestPersona } from '../../types/digest';

type DigestSubPage = 'dashboard' | 'personas' | 'editor' | 'analytics' | 'history';

interface DigestPanelProps {
  notice?: { message: string; tone: 'ok' | 'warn' | 'error' } | null;
  onNotice?: (notice: { message: string; tone: 'ok' | 'warn' | 'error' } | null) => void;
}

export function DigestPanel({ notice, onNotice }: DigestPanelProps) {
  const [subPage, setSubPage] = useState<DigestSubPage>('dashboard');
  const [selectedPersonaId, setSelectedPersonaId] = useState<number | null>(null);
  const [localNotice, setLocalNotice] = useState<{ message: string; tone: 'ok' | 'warn' | 'error' } | null>(null);
  const [refreshTrigger, setRefreshTrigger] = useState(0);

  const handleNotice = onNotice ?? setLocalNotice;
  const displayNotice = notice ?? localNotice;

  const handleSelectPersona = (persona: DigestPersona) => {
    setSelectedPersonaId(persona.id ?? null);
    setSubPage('editor');
  };

  const handleCreateNew = () => {
    setSelectedPersonaId(null);
    setSubPage('editor');
  };

  const handleBackToList = () => {
    setSelectedPersonaId(null);
    setSubPage('personas');
  };

  const handlePersonaSaved = (_persona: DigestPersona) => {
    handleNotice({ message: 'Персона сохранена', tone: 'ok' });
    setRefreshTrigger(prev => prev + 1);
    setSubPage('personas');
    setSelectedPersonaId(null);
  };

  return (
    <div className="digest-panel">
      <div className="digest-hero">
        <div>
          <p className="eyebrow">News Digests</p>
          <h2>Система дайджестов новостей</h2>
          <p className="muted">
            Управление персонами, расписанием публикаций и аналитикой дайджестов.
            Автоматическая кластеризация и синтез новостей с помощью AI.
          </p>
        </div>
        <div className="chips">
          <span className="chip chip--violet">Live</span>
          <span className="chip chip--outline">AI-Powered</span>
        </div>
      </div>

      {displayNotice && (
        <div className={`notice notice--${displayNotice.tone}`}>{displayNotice.message}</div>
      )}

      <div className="digest-nav">
        <button
          className={`tab ${subPage === 'dashboard' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('dashboard')}
        >
          Обзор
        </button>
        <button
          className={`tab ${subPage === 'personas' || subPage === 'editor' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('personas')}
        >
          Персоны
        </button>
        <button
          className={`tab ${subPage === 'analytics' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('analytics')}
        >
          Аналитика
        </button>
        <button
          className={`tab ${subPage === 'history' ? 'tab--active' : ''}`}
          onClick={() => setSubPage('history')}
        >
          История
        </button>
      </div>

      <div className="digest-content">
        {subPage === 'dashboard' && (
          <DigestDashboard />
        )}

        {subPage === 'personas' && (
          <DigestPersonaList
            onSelectPersona={handleSelectPersona}
            onCreateNew={handleCreateNew}
            selectedPersonaId={selectedPersonaId}
            refreshTrigger={refreshTrigger}
          />
        )}

        {subPage === 'editor' && (
          <DigestPersonaEditor
            personaId={selectedPersonaId}
            onSaved={handlePersonaSaved}
            onCancel={handleBackToList}
          />
        )}

        {subPage === 'analytics' && (
          <DigestAnalytics />
        )}

        {subPage === 'history' && (
          <DigestHistory />
        )}
      </div>
    </div>
  );
}
