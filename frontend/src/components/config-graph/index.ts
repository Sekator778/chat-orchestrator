/**
 * Configuration Graph Component Library
 *
 * This module provides a complete visual configuration interface for managing
 * Telegram bot configurations using an interactive node-based graph.
 *
 * ## Main Components
 *
 * - `ConfigurationGraph` - The main graph visualization component
 * - `ConfigGraphProvider` - Context provider for state management
 * - `GraphControls` - Zoom, search, and navigation controls
 *
 * ## Hooks
 *
 * - `useConfigGraph` - Access graph state and actions
 * - `useSelectedNode` - Get the currently selected node
 * - `useNodesByType` - Filter nodes by entity type
 * - `useValidation` - Validation state management
 *
 * ## Node Components
 *
 * Custom React Flow nodes for each entity type:
 * `ChannelNode`, `ChatConfigNode`, `LlmNode`, `TriggerNode`, etc.
 *
 * ## Panel Components
 *
 * Side panels for viewing and editing entities:
 * `ChannelPanel`, `ChatConfigPanel`, `LlmPanel`, `TriggerPanel`, etc.
 *
 * ## Utilities
 *
 * - `transformData` - Convert API data to graph format
 * - `layoutEngine` - Position nodes hierarchically
 * - `statusCalculator` - Compute entity statuses
 * - `dependencyResolver` - Validate configurations
 * - `performance` - Debounce/throttle helpers
 *
 * @example
 * ```tsx
 * import {
 *   ConfigGraphProvider,
 *   ConfigurationGraph,
 *   useConfigGraph,
 *   useSelectedNode,
 * } from './components/config-graph'
 *
 * function App() {
 *   return (
 *     <ConfigGraphProvider autoLoad>
 *       <ConfigurationGraph />
 *       <SelectedNodePanel />
 *     </ConfigGraphProvider>
 *   )
 * }
 * ```
 *
 * @packageDocumentation
 */

// Main components
export { ConfigurationGraph } from './ConfigurationGraph'
export { ConfigGraphProvider } from './ConfigGraphContext'

// Controls
export { GraphControls } from './controls'
export type { GraphControlsProps } from './controls'

// Hooks
export { useConfigGraph, useSelectedNode, useNodesByType, useNodeById } from './hooks'
export { useConfigData, useConfigDataWithAutoLoad } from './hooks/useConfigData'
export { useValidation, type UseValidationReturn, type ValidationState } from './hooks/useValidation'

// Node components
export { BaseNode } from './nodes/BaseNode'
export { ChannelNode } from './nodes/ChannelNode'
export { ChatConfigNode } from './nodes/ChatConfigNode'
export { LlmNode } from './nodes/LlmNode'
export { DigestPersonaNode } from './nodes/DigestPersonaNode'
export { RateLimitsNode } from './nodes/RateLimitsNode'
export { ContextSettingsNode } from './nodes/ContextSettingsNode'
export { SearchConfigNode } from './nodes/SearchConfigNode'
export { BotPersonaNode } from './nodes/BotPersonaNode'
export { TriggerNode } from './nodes/TriggerNode'
export { TemplateNode } from './nodes/TemplateNode'
export { RestrictionNode } from './nodes/RestrictionNode'

// Edge components
export { DependencyEdge, OptionalEdge, ContainsEdge } from './edges'

// Utilities - Data transformation
export {
  transformToGraphData,
  transformToGraphDataSimple,
  calculateChannelStatus,
  calculateDigestPersonaStatus,
  calculateBotPersonaStatusFromBundle,
  createChannelNode,
  createDigestPersonaNode,
  createBotPersonaNode,
  createTriggerNode,
  createTemplateNode,
  createRestrictionNode,
  createEdge,
  groupChannelsByStatus,
  getConfigurationSummary,
} from './utils/transformData'

// Utilities - Status calculation
// Utilities - Dependency resolver
export {
  validateChatConfig,
  validateLlmParameters,
  validateRateLimits,
  validateContextSettings,
  validateSearchConfig,
  validateDigestPersona,
  findDependentNodes,
  findParentNodes,
  getAllIssues,
  summarizeIssues,
  type DependencyIssue,
  type ValidationResult,
} from './utils/dependencyResolver'

// Utilities - Status calculation
export {
  calculateChatConfigStatus,
  calculateLlmStatus,
  calculateRateLimitsStatus,
  calculateContextSettingsStatus,
  calculateTriggerStatus,
  calculateTemplateStatus,
  calculateRestrictionStatus,
  calculateSearchConfigStatus,
  calculateBotPersonaStatus,
  getStatusColor,
  getStatusIcon,
  getStatusLabel,
  countByStatus,
} from './utils/statusCalculator'

// Utilities - Layout engine
export {
  LAYOUT_CONSTANTS,
  NODE_LEVELS,
  calculateNodePosition,
  createLayoutEdge,
  getEdgeStyle,
  calculateLayoutSections,
  positionNodesInSection,
  autoLayoutNodes,
  createEdgesFromRelationships,
  performLayout,
  type LayoutResult,
} from './utils/layoutEngine'

// Performance utilities
export {
  debounce,
  throttle,
  useDebouncedCallback,
  useThrottledCallback,
  useDebouncedValue,
  DEBOUNCE_DELAYS,
} from './utils/performance'

// Panel components (eager loading)
export {
  EntityPanel,
  EntityPanelSection,
  DetailRow,
  Field,
  Chip,
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
  ValidationNotice,
  ValidationBadge,
} from './panels'

// Lazy-loaded panels (for code splitting)
export {
  LazyChannelPanel,
  LazyChatConfigPanel,
  LazyLlmPanel,
  LazyRateLimitsPanel,
  LazyContextSettingsPanel,
  LazySearchConfigPanel,
  LazyDigestPersonaPanel,
  LazyBotPersonaPanel,
  LazyTriggerPanel,
  LazyTemplatePanel,
  LazyRestrictionPanel,
  LazyDigestCreationWizard,
  PanelLoadingFallback,
  PanelErrorFallback,
} from './panels'

export type {
  EntityPanelProps,
  EntityPanelSectionProps,
  DetailRowProps,
  FieldProps,
  ChipProps,
  ChannelPanelProps,
  ChatConfigPanelProps,
  LlmPanelProps,
  RateLimitsPanelProps,
  ContextSettingsPanelProps,
  SearchConfigPanelProps,
  DigestPersonaPanelProps,
  DigestCreationWizardProps,
  BotPersonaPanelProps,
  TriggerPanelProps,
  TemplatePanelProps,
  RestrictionPanelProps,
} from './panels'
