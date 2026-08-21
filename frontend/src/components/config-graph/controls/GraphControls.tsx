import { useState, useCallback, useMemo, useEffect, useRef, memo, type KeyboardEvent } from 'react'
import { useReactFlow } from '@xyflow/react'
import type { ConfigNode, AnyConfigNodeData } from '../../../types/graph'

/**
 * Props for the GraphControls component
 */
interface GraphControlsProps {
  nodes: ConfigNode[]
  onNodeSelect: (nodeId: string | null) => void
  selectedNodeId: string | null
  onFitView?: () => void
  isFullscreen?: boolean
  onToggleFullscreen?: () => void
}

/**
 * Entity type filter options
 */
const ENTITY_TYPE_FILTERS = [
  { value: 'all', label: 'All Types' },
  { value: 'channel', label: 'Channels' },
  { value: 'chatConfig', label: 'Chat Configs' },
  { value: 'llmParams', label: 'LLM Params' },
  { value: 'digestPersona', label: 'Digest Personas' },
  { value: 'botPersona', label: 'Bot Personas' },
  { value: 'trigger', label: 'Triggers' },
  { value: 'template', label: 'Templates' },
  { value: 'restriction', label: 'Restrictions' },
  { value: 'rateLimits', label: 'Rate Limits' },
  { value: 'contextSettings', label: 'Context Settings' },
  { value: 'searchConfig', label: 'Search Config' },
] as const

/**
 * Status filter options
 */
const STATUS_FILTERS = [
  { value: 'all', label: 'All Status' },
  { value: 'configured', label: 'Configured' },
  { value: 'partial', label: 'Partial' },
  { value: 'warning', label: 'Warning' },
  { value: 'unconfigured', label: 'Unconfigured' },
] as const

/**
 * Memoized result item to prevent re-renders
 * Includes ARIA attributes for accessibility
 */
const ResultItem = memo(function ResultItem({
  node,
  index,
  isHighlighted,
  isSelected,
  onSelect,
  onHover,
  getEntityIcon,
  getStatusClass,
}: {
  node: ConfigNode
  index: number
  isHighlighted: boolean
  isSelected: boolean
  onSelect: (nodeId: string) => void
  onHover: (index: number) => void
  getEntityIcon: (type: string) => string
  getStatusClass: (status: string) => string
}) {
  const data = node.data as AnyConfigNodeData
  const status = data.status ?? 'unconfigured'

  return (
    <div
      id={`search-result-${node.id}`}
      data-index={index}
      className={`graph-controls__result-item ${isHighlighted ? 'graph-controls__result-item--highlighted' : ''} ${isSelected ? 'graph-controls__result-item--selected' : ''}`}
      onClick={() => onSelect(node.id)}
      onMouseEnter={() => onHover(index)}
      onKeyDown={(e) => {
        if (e.key === 'Enter' || e.key === ' ') {
          e.preventDefault()
          onSelect(node.id)
        }
      }}
      role="option"
      aria-selected={isHighlighted || isSelected}
      tabIndex={isHighlighted ? 0 : -1}
    >
      <span className="graph-controls__result-icon" aria-hidden="true">
        {getEntityIcon(data.entityType)}
      </span>
      <span className="graph-controls__result-label">{data.label}</span>
      <span
        className={`graph-controls__result-status ${getStatusClass(status)}`}
        aria-label={`Status: ${status}`}
      >
        {status}
      </span>
    </div>
  )
})

/**
 * Graph controls component with search, filter, and navigation features
 * Memoized to prevent re-renders when parent state changes
 */
export const GraphControls = memo(function GraphControls({
  nodes,
  onNodeSelect,
  selectedNodeId,
  onFitView,
  isFullscreen = false,
  onToggleFullscreen,
}: GraphControlsProps) {
  const [searchQuery, setSearchQuery] = useState('')
  const [entityTypeFilter, setEntityTypeFilter] = useState<string>('all')
  const [statusFilter, setStatusFilter] = useState<string>('all')
  const [isSearchOpen, setIsSearchOpen] = useState(false)
  const [highlightedIndex, setHighlightedIndex] = useState(-1)
  const [showShortcuts, setShowShortcuts] = useState(false)

  const searchInputRef = useRef<HTMLInputElement>(null)
  const resultsRef = useRef<HTMLDivElement>(null)
  const reactFlow = useReactFlow()

  /**
   * Filter nodes based on search query and filters
   */
  const filteredNodes = useMemo(() => {
    return nodes.filter((node) => {
      const data = node.data as AnyConfigNodeData

      // Search query filter
      if (searchQuery) {
        const query = searchQuery.toLowerCase()
        const label = (data.label ?? '').toLowerCase()
        const entityType = (data.entityType ?? '').toLowerCase()
        const matchesSearch = label.includes(query) || entityType.includes(query)
        if (!matchesSearch) return false
      }

      // Entity type filter
      if (entityTypeFilter !== 'all' && data.entityType !== entityTypeFilter) {
        return false
      }

      // Status filter
      if (statusFilter !== 'all' && data.status !== statusFilter) {
        return false
      }

      return true
    })
  }, [nodes, searchQuery, entityTypeFilter, statusFilter])

  /**
   * Handle search input change
   */
  const handleSearchChange = useCallback((e: React.ChangeEvent<HTMLInputElement>) => {
    setSearchQuery(e.target.value)
    setHighlightedIndex(-1)
  }, [])

  /**
   * Clear all filters
   */
  const clearFilters = useCallback(() => {
    setSearchQuery('')
    setEntityTypeFilter('all')
    setStatusFilter('all')
    setHighlightedIndex(-1)
  }, [])

  /**
   * Navigate to a node in the graph
   */
  const navigateToNode = useCallback(
    (nodeId: string) => {
      const node = nodes.find((n) => n.id === nodeId)
      if (node && node.position) {
        reactFlow.setCenter(node.position.x + 120, node.position.y + 60, {
          zoom: 1.2,
          duration: 300,
        })
        onNodeSelect(nodeId)
        setIsSearchOpen(false)
      }
    },
    [nodes, reactFlow, onNodeSelect]
  )

  /**
   * Handle keyboard navigation in search results
   */
  const handleSearchKeyDown = useCallback(
    (e: KeyboardEvent<HTMLInputElement>) => {
      if (!filteredNodes.length) return

      switch (e.key) {
        case 'ArrowDown':
          e.preventDefault()
          setHighlightedIndex((prev) => (prev < filteredNodes.length - 1 ? prev + 1 : 0))
          break
        case 'ArrowUp':
          e.preventDefault()
          setHighlightedIndex((prev) => (prev > 0 ? prev - 1 : filteredNodes.length - 1))
          break
        case 'Enter':
          e.preventDefault()
          if (highlightedIndex >= 0 && filteredNodes[highlightedIndex]) {
            navigateToNode(filteredNodes[highlightedIndex].id)
          } else if (filteredNodes.length === 1) {
            navigateToNode(filteredNodes[0].id)
          }
          break
        case 'Escape':
          e.preventDefault()
          setIsSearchOpen(false)
          setSearchQuery('')
          break
      }
    },
    [filteredNodes, highlightedIndex, navigateToNode]
  )

  /**
   * Scroll highlighted item into view
   */
  useEffect(() => {
    if (highlightedIndex >= 0 && resultsRef.current) {
      const item = resultsRef.current.querySelector(`[data-index="${highlightedIndex}"]`)
      item?.scrollIntoView({ block: 'nearest', behavior: 'smooth' })
    }
  }, [highlightedIndex])

  /**
   * Focus search input when opened
   */
  useEffect(() => {
    if (isSearchOpen && searchInputRef.current) {
      searchInputRef.current.focus()
    }
  }, [isSearchOpen])

  /**
   * Global keyboard shortcuts
   */
  useEffect(() => {
    const handleKeyDown = (e: globalThis.KeyboardEvent) => {
      // Ctrl+F: Open search
      if (e.ctrlKey && e.key === 'f') {
        e.preventDefault()
        setIsSearchOpen(true)
      }

      // Ctrl+0: Fit view
      if (e.ctrlKey && e.key === '0') {
        e.preventDefault()
        onFitView?.()
      }

      // F11: Toggle fullscreen
      if (e.key === 'F11') {
        e.preventDefault()
        onToggleFullscreen?.()
      }

      // Escape: Close search/deselect node
      if (e.key === 'Escape') {
        if (isSearchOpen) {
          setIsSearchOpen(false)
          setSearchQuery('')
        } else if (selectedNodeId) {
          onNodeSelect(null)
        }
      }

      // ?: Show shortcuts help
      if (e.key === '?' && !e.ctrlKey && !e.altKey) {
        setShowShortcuts((prev) => !prev)
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [isSearchOpen, selectedNodeId, onNodeSelect, onFitView, onToggleFullscreen])

  /**
   * Get entity type icon
   */
  const getEntityIcon = (entityType: string): string => {
    switch (entityType) {
      case 'channel':
        return '📡'
      case 'chatConfig':
        return '⚙️'
      case 'llmParams':
        return '🤖'
      case 'digestPersona':
        return '📰'
      case 'botPersona':
        return '🎭'
      case 'trigger':
        return '⚡'
      case 'template':
        return '📝'
      case 'restriction':
        return '🚫'
      case 'rateLimits':
        return '⏱️'
      case 'contextSettings':
        return '📋'
      case 'searchConfig':
        return '🔍'
      default:
        return '📦'
    }
  }

  /**
   * Get status badge color class
   */
  const getStatusClass = (status: string): string => {
    switch (status) {
      case 'configured':
        return 'graph-controls__status--configured'
      case 'partial':
        return 'graph-controls__status--partial'
      case 'warning':
        return 'graph-controls__status--warning'
      case 'unconfigured':
        return 'graph-controls__status--unconfigured'
      default:
        return ''
    }
  }

  const hasActiveFilters = searchQuery || entityTypeFilter !== 'all' || statusFilter !== 'all'

  return (
    <div className="graph-controls" role="toolbar" aria-label="Graph controls">
      {/* Main Toolbar */}
      <div className="graph-controls__toolbar" role="group" aria-label="Main controls">
        {/* Search Toggle */}
        <button
          className={`graph-controls__btn ${isSearchOpen ? 'graph-controls__btn--active' : ''}`}
          onClick={() => setIsSearchOpen(!isSearchOpen)}
          title="Search nodes (Ctrl+F)"
          aria-label="Search nodes"
          aria-expanded={isSearchOpen}
          aria-controls="graph-search-panel"
        >
          <span aria-hidden="true">🔍</span>
        </button>

        {/* Zoom Controls */}
        <div className="graph-controls__zoom-group" role="group" aria-label="Zoom controls">
          <button
            className="graph-controls__btn"
            onClick={() => reactFlow.zoomIn({ duration: 200 })}
            title="Zoom in (Ctrl++)"
            aria-label="Zoom in"
          >
            <span aria-hidden="true">+</span>
          </button>
          <button
            className="graph-controls__btn"
            onClick={() => reactFlow.zoomOut({ duration: 200 })}
            title="Zoom out (Ctrl+-)"
            aria-label="Zoom out"
          >
            <span aria-hidden="true">−</span>
          </button>
          <button
            className="graph-controls__btn"
            onClick={onFitView}
            title="Fit view (Ctrl+0)"
            aria-label="Fit all nodes in view"
          >
            <span aria-hidden="true">⊙</span>
          </button>
        </div>

        {/* Fullscreen Toggle */}
        {onToggleFullscreen && (
          <button
            className={`graph-controls__btn ${isFullscreen ? 'graph-controls__btn--active' : ''}`}
            onClick={onToggleFullscreen}
            title={isFullscreen ? 'Exit fullscreen (F11)' : 'Fullscreen (F11)'}
            aria-label={isFullscreen ? 'Exit fullscreen mode' : 'Enter fullscreen mode'}
            aria-pressed={isFullscreen}
          >
            <span aria-hidden="true">{isFullscreen ? '⊗' : '⊕'}</span>
          </button>
        )}

        {/* Keyboard Shortcuts Help */}
        <button
          className={`graph-controls__btn ${showShortcuts ? 'graph-controls__btn--active' : ''}`}
          onClick={() => setShowShortcuts(!showShortcuts)}
          title="Keyboard shortcuts (?)"
          aria-label="Show keyboard shortcuts"
          aria-expanded={showShortcuts}
          aria-controls="keyboard-shortcuts-modal"
        >
          <span aria-hidden="true">⌨</span>
        </button>
      </div>

      {/* Search Panel */}
      {isSearchOpen && (
        <div
          id="graph-search-panel"
          className="graph-controls__search-panel"
          role="search"
          aria-label="Search graph nodes"
        >
          <div className="graph-controls__search-header">
            <input
              ref={searchInputRef}
              type="text"
              className="graph-controls__search-input"
              placeholder="Search nodes..."
              value={searchQuery}
              onChange={handleSearchChange}
              onKeyDown={handleSearchKeyDown}
              aria-label="Search query"
              aria-describedby="search-results-count"
              aria-controls="search-results-list"
              aria-activedescendant={
                highlightedIndex >= 0 && filteredNodes[highlightedIndex]
                  ? `search-result-${filteredNodes[highlightedIndex].id}`
                  : undefined
              }
            />
            <button
              className="graph-controls__close-btn"
              onClick={() => {
                setIsSearchOpen(false)
                setSearchQuery('')
              }}
              aria-label="Close search"
            >
              <span aria-hidden="true">×</span>
            </button>
          </div>

          {/* Filters */}
          <div className="graph-controls__filters" role="group" aria-label="Search filters">
            <select
              className="graph-controls__filter-select"
              value={entityTypeFilter}
              onChange={(e) => setEntityTypeFilter(e.target.value)}
              aria-label="Filter by entity type"
            >
              {ENTITY_TYPE_FILTERS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            <select
              className="graph-controls__filter-select"
              value={statusFilter}
              onChange={(e) => setStatusFilter(e.target.value)}
              aria-label="Filter by status"
            >
              {STATUS_FILTERS.map((opt) => (
                <option key={opt.value} value={opt.value}>
                  {opt.label}
                </option>
              ))}
            </select>
            {hasActiveFilters && (
              <button
                className="graph-controls__clear-btn"
                onClick={clearFilters}
                aria-label="Clear all filters"
              >
                Clear
              </button>
            )}
          </div>

          {/* Results Count */}
          <div className="graph-controls__results-header">
            <span
              id="search-results-count"
              className="graph-controls__results-count"
              role="status"
              aria-live="polite"
            >
              {filteredNodes.length} of {nodes.length} nodes
            </span>
          </div>

          {/* Search Results */}
          <div
            id="search-results-list"
            className="graph-controls__results"
            ref={resultsRef}
            role="listbox"
            aria-label="Search results"
          >
            {filteredNodes.length === 0 ? (
              <div className="graph-controls__no-results" role="status">
                No matching nodes found
              </div>
            ) : (
              filteredNodes.slice(0, 50).map((node, index) => (
                <ResultItem
                  key={node.id}
                  node={node}
                  index={index}
                  isHighlighted={index === highlightedIndex}
                  isSelected={node.id === selectedNodeId}
                  onSelect={navigateToNode}
                  onHover={setHighlightedIndex}
                  getEntityIcon={getEntityIcon}
                  getStatusClass={getStatusClass}
                />
              ))
            )}
            {filteredNodes.length > 50 && (
              <div className="graph-controls__more-results" role="status">
                +{filteredNodes.length - 50} more nodes...
              </div>
            )}
          </div>
        </div>
      )}

      {/* Keyboard Shortcuts Modal */}
      {showShortcuts && (
        <div
          id="keyboard-shortcuts-modal"
          className="graph-controls__shortcuts-modal"
          onClick={() => setShowShortcuts(false)}
          role="dialog"
          aria-modal="true"
          aria-labelledby="shortcuts-title"
        >
          <div
            className="graph-controls__shortcuts-content"
            onClick={(e) => e.stopPropagation()}
          >
            <div className="graph-controls__shortcuts-header">
              <h4 id="shortcuts-title">Keyboard Shortcuts</h4>
              <button
                className="graph-controls__close-btn"
                onClick={() => setShowShortcuts(false)}
                aria-label="Close keyboard shortcuts"
              >
                <span aria-hidden="true">×</span>
              </button>
            </div>
            <div className="graph-controls__shortcuts-list" role="list">
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Ctrl</kbd> + <kbd>F</kbd>
                <span>Open search</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Ctrl</kbd> + <kbd>0</kbd>
                <span>Fit view</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Ctrl</kbd> + <kbd>+</kbd>
                <span>Zoom in</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Ctrl</kbd> + <kbd>-</kbd>
                <span>Zoom out</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>F11</kbd>
                <span>Toggle fullscreen</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Tab</kbd>
                <span>Cycle through nodes</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Arrow keys</kbd>
                <span>Spatial navigation</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Home</kbd> / <kbd>End</kbd>
                <span>First / Last node</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Esc</kbd>
                <span>Close search / Deselect</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>↑</kbd> / <kbd>↓</kbd>
                <span>Navigate search results</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>Enter</kbd>
                <span>Select highlighted</span>
              </div>
              <div className="graph-controls__shortcut" role="listitem">
                <kbd>?</kbd>
                <span>Toggle this help</span>
              </div>
            </div>
          </div>
        </div>
      )}
    </div>
  )
})

export type { GraphControlsProps }
