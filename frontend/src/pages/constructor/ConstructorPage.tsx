import { useCallback, useState } from 'react'
import {
  ConfigGraphProvider,
  ConfigurationGraph,
  useConfigGraph,
  useSelectedNode,
  ChannelPanel,
  ChatConfigPanel,
  LlmPanel,
  RateLimitsPanel,
  ContextSettingsPanel,
  SearchConfigPanel,
  DigestPersonaPanel,
  DigestCreationWizard,
  BotPersonaPanel,
  TriggerPanel,
  TemplatePanel,
  RestrictionPanel,
} from '../../components/config-graph'
import type { ChannelOverview } from '../../types/api'
import type {
  AnyConfigNodeData,
  ChannelNodeData,
  ChatConfigNodeData,
  LlmNodeData,
  RateLimitsNodeData,
  ContextSettingsNodeData,
  SearchConfigNodeData,
  DigestPersonaNodeData,
  BotPersonaNodeData,
  TriggerNodeData,
  TemplateNodeData,
  RestrictionNodeData,
} from '../../types/graph'

/**
 * Type guards for node data types
 */
function isChannelNodeData(data: AnyConfigNodeData): data is ChannelNodeData {
  return data.entityType === 'channel' && 'channel' in data
}

function isChatConfigNodeData(data: AnyConfigNodeData): data is ChatConfigNodeData {
  return data.entityType === 'chatConfig' && 'config' in data
}

function isLlmNodeData(data: AnyConfigNodeData): data is LlmNodeData {
  return data.entityType === 'llmParams' && 'params' in data
}

function isRateLimitsNodeData(data: AnyConfigNodeData): data is RateLimitsNodeData {
  return data.entityType === 'rateLimits' && 'limits' in data
}

function isContextSettingsNodeData(data: AnyConfigNodeData): data is ContextSettingsNodeData {
  return data.entityType === 'contextSettings' && 'settings' in data
}

function isSearchConfigNodeData(data: AnyConfigNodeData): data is SearchConfigNodeData {
  return data.entityType === 'searchConfig' && 'searchConfig' in data
}

function isDigestPersonaNodeData(data: AnyConfigNodeData): data is DigestPersonaNodeData {
  return data.entityType === 'digestPersona' && 'persona' in data
}

function isBotPersonaNodeData(data: AnyConfigNodeData): data is BotPersonaNodeData {
  return data.entityType === 'botPersona' && 'bundle' in data
}

function isTriggerNodeData(data: AnyConfigNodeData): data is TriggerNodeData {
  return data.entityType === 'trigger' && 'trigger' in data
}

function isTemplateNodeData(data: AnyConfigNodeData): data is TemplateNodeData {
  return data.entityType === 'template' && 'template' in data
}

function isRestrictionNodeData(data: AnyConfigNodeData): data is RestrictionNodeData {
  return data.entityType === 'restriction' && 'restriction' in data
}

/**
 * Loading spinner component
 */
function LoadingSpinner() {
  return (
    <div className="constructor-loading">
      <div className="constructor-loading__spinner" />
      <p className="muted">Loading configuration data...</p>
    </div>
  )
}

/**
 * Error display component
 */
function ErrorDisplay({ error, onRetry }: { error: string; onRetry: () => void }) {
  return (
    <div className="constructor-error">
      <div className="constructor-error__icon">!</div>
      <h3>Failed to load configuration</h3>
      <p className="muted">{error}</p>
      <button className="ghost" onClick={onRetry}>
        Try Again
      </button>
    </div>
  )
}

/**
 * Empty state component
 */
function EmptyState() {
  return (
    <div className="constructor-empty">
      <p className="muted">No configuration data found.</p>
      <p className="tiny muted">
        Channels will appear here once they are discovered by the system.
      </p>
    </div>
  )
}

/**
 * Extract parent chat ID from node data
 */
function getParentChatId(data: AnyConfigNodeData): number {
  // For child entities, parentId is the channel/chat ID
  if (data.parentId !== undefined) {
    return typeof data.parentId === 'number' ? data.parentId : Number(data.parentId)
  }
  // For chatConfig, use channel_id
  if (isChatConfigNodeData(data)) {
    return data.config.channel_id
  }
  // Default fallback
  return typeof data.entityId === 'number' ? data.entityId : Number(data.entityId)
}

/**
 * Selected node panel component
 * Renders the appropriate panel based on node type
 */
function SelectedNodePanel() {
  const selectedNode = useSelectedNode()
  const { selectNode } = useConfigGraph()

  if (!selectedNode) {
    return (
      <div className="constructor-panel">
        <div className="placeholder">
          <p className="muted">Select a node to view details</p>
          <p className="tiny muted">Click on any node in the graph to see its configuration</p>
        </div>
      </div>
    )
  }

  const data = selectedNode.data as AnyConfigNodeData
  const parentChatId = getParentChatId(data)

  // Render appropriate panel based on entity type
  if (isChannelNodeData(data)) {
    return <ChannelPanel data={data} />
  }

  if (isChatConfigNodeData(data)) {
    return <ChatConfigPanel data={data} />
  }

  if (isLlmNodeData(data)) {
    return <LlmPanel data={data} parentChatId={parentChatId} />
  }

  if (isRateLimitsNodeData(data)) {
    return <RateLimitsPanel data={data} parentChatId={parentChatId} />
  }

  if (isContextSettingsNodeData(data)) {
    return <ContextSettingsPanel data={data} parentChatId={parentChatId} />
  }

  if (isSearchConfigNodeData(data)) {
    return <SearchConfigPanel data={data} parentChatId={parentChatId} />
  }

  if (isDigestPersonaNodeData(data)) {
    return <DigestPersonaPanel data={data} />
  }

  if (isBotPersonaNodeData(data)) {
    return <BotPersonaPanel data={data} />
  }

  if (isTriggerNodeData(data)) {
    return <TriggerPanel data={data} />
  }

  if (isTemplateNodeData(data)) {
    return <TemplatePanel data={data} />
  }

  if (isRestrictionNodeData(data)) {
    return <RestrictionPanel data={data} />
  }

  // Fallback for unknown types (should not be reached due to exhaustive type checking)
  const unknownData = data as AnyConfigNodeData
  return (
    <div className="constructor-panel">
      <div className="constructor-panel__header">
        <div>
          <p className="eyebrow">{unknownData.entityType}</p>
          <h3>{unknownData.label}</h3>
        </div>
        <button className="constructor-panel__close ghost" onClick={() => selectNode(null)}>
          &times;
        </button>
      </div>
      <div className="constructor-panel__body">
        <p className="muted">Panel for this entity type is not yet implemented.</p>
      </div>
    </div>
  )
}

/**
 * Summary statistics bar component
 */
function SummaryBar() {
  const { state } = useConfigGraph()
  const extendedState = state as typeof state & {
    summary: {
      totalChannels: number
      configuredChannels: number
      enabledChannels: number
      totalDigestPersonas: number
      activeDigestPersonas: number
    } | null
  }

  if (!extendedState.summary) return null

  const { summary } = extendedState

  return (
    <div className="constructor-summary">
      <div className="constructor-summary__item">
        <span className="constructor-summary__value">{summary.totalChannels}</span>
        <span className="constructor-summary__label">Channels</span>
      </div>
      <div className="constructor-summary__item">
        <span className="constructor-summary__value">{summary.configuredChannels}</span>
        <span className="constructor-summary__label">Configured</span>
      </div>
      <div className="constructor-summary__item">
        <span className="constructor-summary__value">{summary.enabledChannels}</span>
        <span className="constructor-summary__label">Enabled</span>
      </div>
      <div className="constructor-summary__divider" />
      <div className="constructor-summary__item">
        <span className="constructor-summary__value">{summary.totalDigestPersonas}</span>
        <span className="constructor-summary__label">Digest Personas</span>
      </div>
      <div className="constructor-summary__item">
        <span className="constructor-summary__value">{summary.activeDigestPersonas}</span>
        <span className="constructor-summary__label">Active</span>
      </div>
    </div>
  )
}

/**
 * Extract channels from graph nodes for wizard
 */
function extractChannelsFromNodes(nodes: { data: AnyConfigNodeData }[]): ChannelOverview[] {
  const channels: ChannelOverview[] = []
  for (const node of nodes) {
    if (isChannelNodeData(node.data) && node.data.channel) {
      channels.push(node.data.channel)
    }
  }
  return channels
}

/**
 * Constructor page content (inside provider)
 */
function ConstructorContent() {
  const { state, refreshData } = useConfigGraph()
  const [showCreateWizard, setShowCreateWizard] = useState(false)

  const channels = extractChannelsFromNodes(state.nodes)

  const handleWizardCreated = useCallback(() => {
    setShowCreateWizard(false)
    refreshData()
  }, [refreshData])

  if (state.isLoading && state.nodes.length === 0) {
    return <LoadingSpinner />
  }

  if (state.error && state.nodes.length === 0) {
    return <ErrorDisplay error={state.error} onRetry={refreshData} />
  }

  if (state.nodes.length === 0) {
    return <EmptyState />
  }

  return (
    <>
      <SummaryBar />
      <nav className="constructor-actions-bar" aria-label="Configuration actions">
        <button
          className="ghost"
          onClick={() => setShowCreateWizard(true)}
          aria-label="Create new digest persona"
        >
          + Create Digest Persona
        </button>
      </nav>
      <div
        className={`constructor-layout ${!state.selectedNodeId ? 'constructor-layout--no-panel' : ''}`}
      >
        <section
          id="graph-area"
          className="constructor-graph-area"
          aria-label="Configuration dependency graph"
        >
          <ConfigurationGraph />
          {state.isLoading && (
            <div
              className="constructor-graph-area__loading"
              role="status"
              aria-label="Loading updates"
            >
              <div className="constructor-loading__spinner constructor-loading__spinner--small" aria-hidden="true" />
            </div>
          )}
        </section>
        {state.selectedNodeId && (
          <aside
            className="constructor-panel-area"
            aria-label="Selected node details"
            role="complementary"
          >
            <SelectedNodePanel />
          </aside>
        )}
      </div>
      {showCreateWizard && (
        <DigestCreationWizard
          channels={channels}
          onClose={() => setShowCreateWizard(false)}
          onCreated={handleWizardCreated}
        />
      )}
    </>
  )
}

/**
 * Main constructor page component
 * Now uses automatic data loading via ConfigGraphProvider
 * Includes accessibility features like skip link and ARIA landmarks
 */
export function ConstructorPage() {
  const handleRefresh = useCallback(() => {
    // Force remount of the provider to trigger fresh data load
    window.location.reload()
  }, [])

  return (
    <div className="constructor-page" role="main" aria-label="Configuration Constructor">
      {/* Skip link for keyboard users */}
      <a href="#graph-area" className="skip-link">
        Skip to graph
      </a>

      <header className="constructor-hero">
        <div>
          <p className="eyebrow">Visual Configuration</p>
          <h2 id="page-title">Configuration Constructor</h2>
          <p className="muted">
            Interactive visual interface for managing channel configurations, triggers, and AI settings.
            Click on nodes to view and edit their properties.
            Use keyboard: Tab to cycle nodes, arrow keys for spatial navigation, Enter to select.
          </p>
        </div>
        <div className="actions">
          <button
            className="ghost"
            onClick={handleRefresh}
            aria-label="Refresh configuration data"
          >
            Refresh
          </button>
        </div>
      </header>

      <ConfigGraphProvider autoLoad={true}>
        <ConstructorContent />
      </ConfigGraphProvider>
    </div>
  )
}
