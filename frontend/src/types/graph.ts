import type { Node, Edge } from '@xyflow/react'
import type {
  ChannelOverview,
  EnhancedChatConfig,
  LlmParameters,
  RateLimits,
  ContextSettings,
  TriggerCondition,
  ResponseTemplate,
  TopicRestriction,
  SearchConfig,
  PersonaBundle,
} from './api'
import type { DigestPersona } from './digest'

/**
 * Configuration status indicating completeness and health of an entity
 */
export type ConfigStatus =
  | 'configured'      // Fully configured and active
  | 'partial'         // Partially configured (missing required fields)
  | 'warning'         // Configured but has warnings/issues
  | 'unconfigured'    // Not configured
  | 'loading'         // Configuration in progress
  | 'saved'           // Saved but not activated

/**
 * Types of nodes in the configuration graph
 */
export type ConfigNodeType =
  | 'channel'
  | 'chatConfig'
  | 'llmParams'
  | 'rateLimits'
  | 'contextSettings'
  | 'trigger'
  | 'template'
  | 'restriction'
  | 'searchConfig'
  | 'digestPersona'
  | 'botPersona'

/**
 * Base data structure for all configuration nodes
 */
export interface ConfigNodeDataBase {
  label: string
  status: ConfigStatus
  entityType: ConfigNodeType
  entityId: number | string
  parentId?: number | string
  isExpanded?: boolean
  [key: string]: unknown
}

/**
 * Channel node data
 */
export interface ChannelNodeData extends ConfigNodeDataBase {
  entityType: 'channel'
  channel: ChannelOverview
}

/**
 * Chat configuration node data
 */
export interface ChatConfigNodeData extends ConfigNodeDataBase {
  entityType: 'chatConfig'
  config: EnhancedChatConfig
}

/**
 * LLM parameters node data
 */
export interface LlmNodeData extends ConfigNodeDataBase {
  entityType: 'llmParams'
  params: LlmParameters | null
}

/**
 * Rate limits node data
 */
export interface RateLimitsNodeData extends ConfigNodeDataBase {
  entityType: 'rateLimits'
  limits: RateLimits | null
}

/**
 * Context settings node data
 */
export interface ContextSettingsNodeData extends ConfigNodeDataBase {
  entityType: 'contextSettings'
  settings: ContextSettings | null
}

/**
 * Trigger condition node data
 */
export interface TriggerNodeData extends ConfigNodeDataBase {
  entityType: 'trigger'
  trigger: TriggerCondition
}

/**
 * Response template node data
 */
export interface TemplateNodeData extends ConfigNodeDataBase {
  entityType: 'template'
  template: ResponseTemplate
}

/**
 * Topic restriction node data
 */
export interface RestrictionNodeData extends ConfigNodeDataBase {
  entityType: 'restriction'
  restriction: TopicRestriction
}

/**
 * Search configuration node data
 */
export interface SearchConfigNodeData extends ConfigNodeDataBase {
  entityType: 'searchConfig'
  searchConfig: SearchConfig | null
}

/**
 * Digest persona node data
 */
export interface DigestPersonaNodeData extends ConfigNodeDataBase {
  entityType: 'digestPersona'
  persona: DigestPersona
}

/**
 * Bot persona bundle node data
 */
export interface BotPersonaNodeData extends ConfigNodeDataBase {
  entityType: 'botPersona'
  bundle: PersonaBundle
}

/**
 * Union type of all node data types
 */
export type AnyConfigNodeData =
  | ChannelNodeData
  | ChatConfigNodeData
  | LlmNodeData
  | RateLimitsNodeData
  | ContextSettingsNodeData
  | TriggerNodeData
  | TemplateNodeData
  | RestrictionNodeData
  | SearchConfigNodeData
  | DigestPersonaNodeData
  | BotPersonaNodeData

/**
 * Entity type alias for validation
 */
export type EntityType = ConfigNodeType

/**
 * Custom node type for React Flow
 * Using Node<AnyConfigNodeData> for flexibility with reducer operations
 */
export type ChannelNode = Node<ChannelNodeData, 'channel'>
export type ChatConfigNode = Node<ChatConfigNodeData, 'chatConfig'>
export type LlmParamsNode = Node<LlmNodeData, 'llmParams'>
export type TriggerNode = Node<TriggerNodeData, 'trigger'>
export type TemplateNode = Node<TemplateNodeData, 'template'>
export type RestrictionNode = Node<RestrictionNodeData, 'restriction'>

/**
 * ConfigNode uses AnyConfigNodeData to allow reducer operations that merge data
 */
export type ConfigNode = Node<AnyConfigNodeData, string>

/**
 * GraphNode alias for compatibility
 */
export type GraphNode = ConfigNode

/**
 * Edge types in the configuration graph
 */
export type ConfigEdgeType = 'dependency' | 'optional' | 'contains'

/**
 * Custom edge data
 */
export interface ConfigEdgeData {
  edgeType: ConfigEdgeType
  label?: string
  [key: string]: unknown
}

/**
 * Custom edge type for React Flow
 */
export type ConfigEdge = Edge<ConfigEdgeData>

/**
 * State for the configuration graph
 */
export interface ConfigGraphState {
  nodes: ConfigNode[]
  edges: ConfigEdge[]
  selectedNodeId: string | null
  isLoading: boolean
  error: string | null
}

/**
 * Actions for the configuration graph
 */
export type ConfigGraphAction =
  | { type: 'SET_LOADING'; payload: boolean }
  | { type: 'SET_ERROR'; payload: string | null }
  | { type: 'SET_NODES'; payload: ConfigNode[] }
  | { type: 'SET_EDGES'; payload: ConfigEdge[] }
  | { type: 'SELECT_NODE'; payload: string | null }
  | { type: 'UPDATE_NODE'; payload: { id: string; data: Partial<AnyConfigNodeData> } }
  | { type: 'ADD_NODE'; payload: ConfigNode }
  | { type: 'REMOVE_NODE'; payload: string }
  | { type: 'TOGGLE_NODE_EXPANSION'; payload: string }

/**
 * Context value for configuration graph
 */
export interface ConfigGraphContextValue {
  state: ConfigGraphState
  dispatch: React.Dispatch<ConfigGraphAction>
  refreshData: () => Promise<void>
  selectNode: (nodeId: string | null) => void
}

/**
 * Layout configuration for the graph
 */
export interface GraphLayoutConfig {
  nodeWidth: number
  nodeHeight: number
  horizontalGap: number
  verticalGap: number
  groupPadding: number
}

/**
 * Default layout configuration
 */
export const DEFAULT_LAYOUT_CONFIG: GraphLayoutConfig = {
  nodeWidth: 280,
  nodeHeight: 120,
  horizontalGap: 40,
  verticalGap: 60,
  groupPadding: 20,
}
