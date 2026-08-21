interface Props {
  status: 'active' | 'paused' | 'error' | 'idle'
  label?: string
  size?: 'small' | 'medium'
}

const statusConfig = {
  active: {
    color: '#22c55e',
    background: '#ecfdf5',
    border: '#bbf7d0',
    label: 'Активен',
  },
  paused: {
    color: '#f59e0b',
    background: '#fffbeb',
    border: '#fcd34d',
    label: 'Приостановлен',
  },
  error: {
    color: '#ef4444',
    background: '#fef2f2',
    border: '#fecaca',
    label: 'Ошибка',
  },
  idle: {
    color: '#94a3b8',
    background: '#f8fafc',
    border: '#e2e8f0',
    label: 'Неактивен',
  },
}

export function StatusIndicator({ status, label, size = 'medium' }: Props) {
  const config = statusConfig[status]
  const displayLabel = label ?? config.label
  const dotSize = size === 'small' ? 8 : 10
  const fontSize = size === 'small' ? '11px' : '12px'
  const padding = size === 'small' ? '3px 8px' : '4px 10px'

  return (
    <span
      className="status-indicator"
      style={{
        display: 'inline-flex',
        alignItems: 'center',
        gap: '6px',
        padding,
        borderRadius: '999px',
        fontSize,
        fontWeight: 600,
        color: config.color,
        background: config.background,
        border: `1px solid ${config.border}`,
      }}
    >
      <span
        style={{
          width: dotSize,
          height: dotSize,
          borderRadius: '50%',
          background: config.color,
          animation: status === 'active' ? 'pulse 2s infinite' : undefined,
        }}
      />
      {displayLabel}
    </span>
  )
}
