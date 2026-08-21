/**
 * ValidationNotice Component
 *
 * Displays validation issues and suggestions in entity panels.
 */

import type { DependencyIssue } from '../utils/dependencyResolver'

export interface ValidationNoticeProps {
  issues: DependencyIssue[]
  showSuggestions?: boolean
  maxItems?: number
}

/**
 * Get icon for issue severity
 */
function getIssueIcon(severity: DependencyIssue['severity']): string {
  switch (severity) {
    case 'error':
      return '❌'
    case 'warning':
      return '⚠️'
    case 'info':
      return 'ℹ️'
    default:
      return '•'
  }
}

/**
 * Get CSS class for issue severity
 */
function getSeverityClass(severity: DependencyIssue['severity']): string {
  switch (severity) {
    case 'error':
      return 'validation-notice__item--error'
    case 'warning':
      return 'validation-notice__item--warning'
    case 'info':
      return 'validation-notice__item--info'
    default:
      return ''
  }
}

/**
 * Component to display validation issues
 */
export function ValidationNotice({
  issues,
  showSuggestions = true,
  maxItems = 5,
}: ValidationNoticeProps) {
  if (issues.length === 0) {
    return null
  }

  // Sort by severity (errors first)
  const sortedIssues = [...issues].sort((a, b) => {
    const order = { error: 0, warning: 1, info: 2 }
    return order[a.severity] - order[b.severity]
  })

  const displayIssues = sortedIssues.slice(0, maxItems)
  const remaining = sortedIssues.length - maxItems

  // Count by severity
  const errorCount = issues.filter((i) => i.severity === 'error').length
  const warningCount = issues.filter((i) => i.severity === 'warning').length

  return (
    <div className="validation-notice">
      <div className="validation-notice__header">
        <span className="validation-notice__title">
          {errorCount > 0 ? '🔧 Configuration Issues' : '💡 Suggestions'}
        </span>
        <span className="validation-notice__count">
          {errorCount > 0 && (
            <span className="validation-notice__badge validation-notice__badge--error">
              {errorCount} error{errorCount > 1 ? 's' : ''}
            </span>
          )}
          {warningCount > 0 && (
            <span className="validation-notice__badge validation-notice__badge--warning">
              {warningCount} warning{warningCount > 1 ? 's' : ''}
            </span>
          )}
        </span>
      </div>

      <ul className="validation-notice__list">
        {displayIssues.map((issue, index) => (
          <li key={index} className={`validation-notice__item ${getSeverityClass(issue.severity)}`}>
            <span className="validation-notice__icon">{getIssueIcon(issue.severity)}</span>
            <div className="validation-notice__content">
              <span className="validation-notice__message">{issue.message}</span>
              {showSuggestions && issue.suggestion && (
                <span className="validation-notice__suggestion">💡 {issue.suggestion}</span>
              )}
            </div>
          </li>
        ))}
      </ul>

      {remaining > 0 && (
        <div className="validation-notice__more">+{remaining} more issue{remaining > 1 ? 's' : ''}</div>
      )}
    </div>
  )
}

/**
 * Compact version for use in node tooltips
 */
export function ValidationBadge({ issues }: { issues: DependencyIssue[] }) {
  if (issues.length === 0) return null

  const errorCount = issues.filter((i) => i.severity === 'error').length
  const warningCount = issues.filter((i) => i.severity === 'warning').length

  if (errorCount === 0 && warningCount === 0) return null

  return (
    <div className="validation-badge">
      {errorCount > 0 && (
        <span className="validation-badge__error" title={`${errorCount} error(s)`}>
          ❌ {errorCount}
        </span>
      )}
      {warningCount > 0 && (
        <span className="validation-badge__warning" title={`${warningCount} warning(s)`}>
          ⚠️ {warningCount}
        </span>
      )}
    </div>
  )
}
