import type { GeneratedDigest, DigestHistory } from '../../types/digest'
import { formatGenerationTime } from '../../types/digest'

interface GeneratedProps {
  digest: GeneratedDigest
  onClose: () => void
  onPublish?: () => void
  publishing?: boolean
}

interface HistoryProps {
  history: DigestHistory
  onClose: () => void
  onRepublish?: () => void
  republishing?: boolean
}

type Props = GeneratedProps | HistoryProps

function isGeneratedDigest(props: Props): props is GeneratedProps {
  return 'digest' in props
}

export function DigestPreview(props: Props) {
  const { onClose } = props

  if (isGeneratedDigest(props)) {
    const { digest, onPublish, publishing } = props

    return (
      <div className="digest-preview-modal" onClick={onClose}>
        <div className="digest-preview-modal__content" onClick={(e) => e.stopPropagation()}>
          <div className="digest-preview-modal__header">
            <h3>Предпросмотр дайджеста</h3>
            <div className="actions">
              {onPublish && (
                <button onClick={onPublish} disabled={publishing}>
                  {publishing ? 'Публикация...' : 'Опубликовать'}
                </button>
              )}
              <button className="ghost" onClick={onClose}>
                Закрыть
              </button>
            </div>
          </div>

          <div className="digest-preview-modal__meta">
            <span className="chip">{digest.personaName}</span>
            <span className="chip chip--outline">
              {digest.messagesIncluded} сообщений
            </span>
            <span className="chip chip--outline">
              {digest.clustersUsed} кластеров
            </span>
            <span className="chip chip--outline">
              {formatGenerationTime(digest.generationTimeMs)}
            </span>
            {digest.digestId && (
              <span className="chip chip--violet tiny">
                ID: {digest.digestId.substring(0, 8)}...
              </span>
            )}
          </div>

          <div className="digest-preview-modal__body">
            <pre>{digest.content}</pre>
          </div>

          {digest.sourceSummary && digest.sourceSummary.length > 0 && (
            <div className="digest-preview-modal__sources">
              <p className="muted tiny">Источники ({digest.sourceSummary.length}):</p>
              <div className="chips">
                {digest.sourceSummary.map((source, i) => (
                  <span key={i} className="chip chip--outline tiny">
                    {source}
                  </span>
                ))}
              </div>
            </div>
          )}

          <div className="digest-preview-modal__footer">
            <span className="muted tiny">
              Сгенерировано: {new Date(digest.generatedAt).toLocaleString('ru-RU')}
            </span>
          </div>
        </div>
      </div>
    )
  }

  const { history, onRepublish, republishing } = props

  return (
    <div className="digest-preview-modal" onClick={onClose}>
      <div className="digest-preview-modal__content" onClick={(e) => e.stopPropagation()}>
        <div className="digest-preview-modal__header">
          <h3>Просмотр дайджеста</h3>
          <div className="actions">
            {onRepublish && (
              <button onClick={onRepublish} disabled={republishing}>
                {republishing ? 'Публикация...' : 'Переопубликовать'}
              </button>
            )}
            <button className="ghost" onClick={onClose}>
              Закрыть
            </button>
          </div>
        </div>

        <div className="digest-preview-modal__meta">
          {history.personaName && (
            <span className="chip">{history.personaName}</span>
          )}
          <span className={`chip ${history.status === 'PUBLISHED' ? 'chip--green' : history.status === 'FAILED' ? 'chip--warn' : 'chip--outline'}`}>
            {history.status}
          </span>
          <span className="chip chip--outline">
            {history.messagesIncluded} сообщений
          </span>
          <span className="chip chip--outline">
            {history.clustersUsed} кластеров
          </span>
          <span className="chip chip--outline">
            {formatGenerationTime(history.generationTimeMs)}
          </span>
        </div>

        <div className="digest-preview-modal__body">
          <pre>{history.content}</pre>
        </div>

        {history.errorMessage && (
          <div className="digest-preview-modal__error">
            <p className="muted tiny">Ошибка:</p>
            <p className="digest-preview-modal__error-text">{history.errorMessage}</p>
          </div>
        )}

        <div className="digest-preview-modal__footer">
          <span className="muted tiny">
            Создано: {new Date(history.createdAt).toLocaleString('ru-RU')}
          </span>
          {history.publishedAt && (
            <span className="muted tiny">
              | Опубликовано: {new Date(history.publishedAt).toLocaleString('ru-RU')}
            </span>
          )}
          {history.telegramMessageId && (
            <span className="muted tiny">
              | Telegram ID: {history.telegramMessageId}
            </span>
          )}
        </div>
      </div>
    </div>
  )
}

interface QuickPreviewProps {
  content: string
  title?: string
  maxLength?: number
}

export function DigestQuickPreview({ content, title, maxLength = 300 }: QuickPreviewProps) {
  const truncated = content.length > maxLength
  const displayContent = truncated ? content.substring(0, maxLength) + '...' : content

  return (
    <div className="digest-quick-preview">
      {title && <p className="digest-quick-preview__title muted tiny">{title}</p>}
      <div className="digest-quick-preview__content">
        <pre>{displayContent}</pre>
      </div>
      {truncated && (
        <p className="digest-quick-preview__more muted tiny">
          + ещё {content.length - maxLength} символов
        </p>
      )}
    </div>
  )
}
