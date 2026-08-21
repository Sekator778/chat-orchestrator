/**
 * Data Transformation Utilities for Configuration Graph
 *
 * Transforms API data into React Flow nodes and edges for visualization.
 * Uses the layout engine for hierarchical positioning and edge creation.
 */

import type { ConfigurationOverview } from '../../../api/configClient'
import type {
  ChannelOverview,
  PersonaBundle,
  TriggerCondition,
  ResponseTemplate,
  TopicRestriction,
} from '../../../types/api'
import type { DigestPersona } from '../../../types/digest'
import type {
  ConfigNode,
  ConfigEdge,
  ConfigStatus,
  ChannelNodeData,
  DigestPersonaNodeData,
  BotPersonaNodeData,
  TriggerNodeData,
  TemplateNodeData,
  RestrictionNodeData,
  GraphLayoutConfig,
} from '../../../types/graph'
import {
  performLayout,
  createLayoutEdge,
  type LayoutResult,
} from './layoutEngine'
import {
  calculateChannelStatus,
  calculateDigestPersonaStatus,
  calculateTriggerStatus,
  calculateTemplateStatus,
  calculateRestrictionStatus,
} from './statusCalculator'

// Re-export for backward compatibility
export { calculateChannelStatus, calculateDigestPersonaStatus }

/**
 * Creates a channel node from channel overview data
 */
export function createChannelNode(
  channel: ChannelOverview,
  position: { x: number; y: number }
): ConfigNode {
  const data: ChannelNodeData = {
    label: channel.title ?? `Channel ${channel.chatId}`,
    status: calculateChannelStatus(channel),
    entityType: 'channel',
    entityId: channel.chatId,
    channel,
    isExpanded: false,
  }

  return {
    id: `channel-${channel.chatId}`,
    type: 'channel',
    position,
    data,
  }
}

/**
 * Creates a digest persona node
 */
export function createDigestPersonaNode(
  persona: DigestPersona,
  position: { x: number; y: number }
): ConfigNode {
  const data: DigestPersonaNodeData = {
    label: persona.name,
    status: calculateDigestPersonaStatus(persona),
    entityType: 'digestPersona',
    entityId: persona.id ?? 0,
    persona,
    isExpanded: false,
  }

  return {
    id: `digest-persona-${persona.id}`,
    type: 'digestPersona',
    position,
    data,
  }
}

/**
 * Calculate status for a bot persona bundle
 */
export function calculateBotPersonaStatusFromBundle(bundle: PersonaBundle): ConfigStatus {
  // PersonaBundle only has botId, languages, previewName, previewDescription
  // Consider configured if it has languages set
  if (bundle.languages.length === 0) {
    return 'partial'
  }
  return 'configured'
}

/**
 * Creates a bot persona node from bundle data
 */
export function createBotPersonaNode(
  bundle: PersonaBundle,
  position: { x: number; y: number },
  index: number
): ConfigNode {
  const data: BotPersonaNodeData = {
    label: bundle.previewName ?? `Bot ${bundle.botId}`,
    status: calculateBotPersonaStatusFromBundle(bundle),
    entityType: 'botPersona',
    entityId: bundle.botId,
    bundle,
    isExpanded: false,
  }

  return {
    id: `bot-persona-${bundle.botId}-${index}`,
    type: 'botPersona',
    position,
    data,
  }
}

/**
 * Creates a trigger node from trigger condition data
 */
export function createTriggerNode(
  trigger: TriggerCondition,
  parentChatId: number,
  position: { x: number; y: number }
): ConfigNode {
  const data: TriggerNodeData = {
    label: trigger.condition_name || `Trigger ${trigger.id}`,
    status: calculateTriggerStatus(trigger),
    entityType: 'trigger',
    entityId: trigger.id,
    parentId: parentChatId,
    trigger,
    isExpanded: false,
  }

  return {
    id: `trigger-${trigger.id}`,
    type: 'trigger',
    position,
    data,
  }
}

/**
 * Creates a template node from response template data
 */
export function createTemplateNode(
  template: ResponseTemplate,
  parentChatId: number,
  position: { x: number; y: number }
): ConfigNode {
  const data: TemplateNodeData = {
    label: template.template_name || `Template ${template.id}`,
    status: calculateTemplateStatus(template),
    entityType: 'template',
    entityId: template.id,
    parentId: parentChatId,
    template,
    isExpanded: false,
  }

  return {
    id: `template-${template.id}`,
    type: 'template',
    position,
    data,
  }
}

/**
 * Creates a restriction node from topic restriction data
 */
export function createRestrictionNode(
  restriction: TopicRestriction,
  parentChatId: number,
  position: { x: number; y: number }
): ConfigNode {
  const data: RestrictionNodeData = {
    label: restriction.restriction_name || `Restriction ${restriction.id}`,
    status: calculateRestrictionStatus(restriction),
    entityType: 'restriction',
    entityId: restriction.id,
    parentId: parentChatId,
    restriction,
    isExpanded: false,
  }

  return {
    id: `restriction-${restriction.id}`,
    type: 'restriction',
    position,
    data,
  }
}

/**
 * Creates an edge between two nodes
 * Uses custom edge types for visual distinction
 */
export function createEdge(
  source: string,
  target: string,
  edgeType: 'dependency' | 'optional' | 'contains' = 'dependency',
  label?: string
): ConfigEdge {
  return createLayoutEdge(source, target, edgeType, label)
}

/**
 * Layout configuration for different sections
 */
interface SectionLayout {
  startX: number
  startY: number
  itemsPerRow: number
  itemWidth: number
  itemHeight: number
}

/**
 * Calculate positions for channel nodes in a grid layout
 */
function layoutChannels(
  channels: ChannelOverview[],
  config: SectionLayout
): { nodes: ConfigNode[]; edges: ConfigEdge[] } {
  const nodes: ConfigNode[] = []
  const edges: ConfigEdge[] = []

  channels.forEach((channel, index) => {
    const col = index % config.itemsPerRow
    const row = Math.floor(index / config.itemsPerRow)
    const x = config.startX + col * config.itemWidth
    const y = config.startY + row * config.itemHeight

    nodes.push(createChannelNode(channel, { x, y }))
  })

  return { nodes, edges }
}

/**
 * Calculate positions for digest persona nodes
 */
function layoutDigestPersonas(
  personas: DigestPersona[],
  config: SectionLayout
): { nodes: ConfigNode[]; edges: ConfigEdge[] } {
  const nodes: ConfigNode[] = []
  const edges: ConfigEdge[] = []

  personas.forEach((persona, index) => {
    const col = index % config.itemsPerRow
    const row = Math.floor(index / config.itemsPerRow)
    const x = config.startX + col * config.itemWidth
    const y = config.startY + row * config.itemHeight

    nodes.push(createDigestPersonaNode(persona, { x, y }))
  })

  return { nodes, edges }
}

/**
 * Find edges between digest personas and their target channels
 */
function findDigestChannelEdges(
  personas: DigestPersona[],
  channelIds: Set<number>
): ConfigEdge[] {
  const edges: ConfigEdge[] = []

  personas.forEach((persona) => {
    if (persona.targetChannelId && channelIds.has(persona.targetChannelId)) {
      edges.push(
        createEdge(
          `digest-persona-${persona.id}`,
          `channel-${persona.targetChannelId}`,
          'dependency',
          'publishes to'
        )
      )
    }
  })

  return edges
}

/**
 * Calculate positions for bot persona nodes
 */
function layoutBotPersonas(
  bundles: PersonaBundle[],
  config: SectionLayout
): { nodes: ConfigNode[]; edges: ConfigEdge[] } {
  const nodes: ConfigNode[] = []
  const edges: ConfigEdge[] = []

  bundles.forEach((bundle, index) => {
    const col = index % config.itemsPerRow
    const row = Math.floor(index / config.itemsPerRow)
    const x = config.startX + col * config.itemWidth
    const y = config.startY + row * config.itemHeight

    nodes.push(createBotPersonaNode(bundle, { x, y }, index))
  })

  return { nodes, edges }
}

/**
 * Find edges between bot personas and their default channels
 * Note: PersonaBundle doesn't have a direct channel reference,
 * so this returns an empty array. Bot personas are standalone entities.
 * Parameters are kept for future API compatibility.
 */
function findBotPersonaChannelEdges(
  bundles: PersonaBundle[],
  channelIds: Set<number>
): ConfigEdge[] {
  // Bot personas don't have direct channel references in the current API
  // They're standalone entities, so no edges are created here
  // Use void to suppress unused parameter warnings
  void bundles
  void channelIds
  return []
}

/**
 * Transform configuration overview into graph nodes and edges
 * Uses layout engine for hierarchical positioning
 */
export function transformToGraphData(
  overview: ConfigurationOverview,
  layoutConfig: GraphLayoutConfig = {
    nodeWidth: 280,
    nodeHeight: 120,
    horizontalGap: 40,
    verticalGap: 60,
    groupPadding: 20,
  }
): LayoutResult {
  // Create all nodes with temporary positions (layout engine will reposition)
  const allNodes: ConfigNode[] = []

  // Create channel nodes
  overview.channels.forEach((channel) => {
    allNodes.push(createChannelNode(channel, { x: 0, y: 0 }))
  })

  // Create digest persona nodes
  overview.digestPersonas.forEach((persona) => {
    allNodes.push(createDigestPersonaNode(persona, { x: 0, y: 0 }))
  })

  // Create bot persona nodes
  if (overview.botPersonas) {
    overview.botPersonas.forEach((bundle, index) => {
      allNodes.push(createBotPersonaNode(bundle, { x: 0, y: 0 }, index))
    })
  }

  // Use layout engine to position nodes and create edges
  const layoutResult = performLayout(allNodes, [], layoutConfig)

  return layoutResult
}

/**
 * Transform configuration overview into graph nodes and edges (legacy simple version)
 * Kept for backward compatibility
 */
export function transformToGraphDataSimple(
  overview: ConfigurationOverview,
  layoutConfig: GraphLayoutConfig = {
    nodeWidth: 280,
    nodeHeight: 120,
    horizontalGap: 40,
    verticalGap: 60,
    groupPadding: 20,
  }
): { nodes: ConfigNode[]; edges: ConfigEdge[] } {
  const allNodes: ConfigNode[] = []
  const allEdges: ConfigEdge[] = []

  const columnWidth = layoutConfig.nodeWidth + layoutConfig.horizontalGap
  const rowHeight = layoutConfig.nodeHeight + layoutConfig.verticalGap
  const itemsPerRow = 4

  // Layout channels section
  const channelsLayout: SectionLayout = {
    startX: 0,
    startY: 0,
    itemsPerRow,
    itemWidth: columnWidth,
    itemHeight: rowHeight,
  }

  const channelResult = layoutChannels(overview.channels, channelsLayout)
  allNodes.push(...channelResult.nodes)
  allEdges.push(...channelResult.edges)

  // Calculate Y offset for digest personas section
  const channelRows = Math.ceil(overview.channels.length / itemsPerRow)
  const digestStartY = channelRows * rowHeight + layoutConfig.groupPadding * 2

  // Layout digest personas section
  if (overview.digestPersonas.length > 0) {
    const digestLayout: SectionLayout = {
      startX: 0,
      startY: digestStartY,
      itemsPerRow,
      itemWidth: columnWidth,
      itemHeight: rowHeight,
    }

    const digestResult = layoutDigestPersonas(overview.digestPersonas, digestLayout)
    allNodes.push(...digestResult.nodes)
    allEdges.push(...digestResult.edges)

    // Create edges between digest personas and their target channels
    const channelIds = new Set(overview.channels.map((c) => c.chatId))
    const digestChannelEdges = findDigestChannelEdges(overview.digestPersonas, channelIds)
    allEdges.push(...digestChannelEdges)
  }

  // Layout bot personas section
  if (overview.botPersonas && overview.botPersonas.length > 0) {
    const digestRows = Math.ceil(overview.digestPersonas.length / itemsPerRow)
    const botPersonaStartY = digestStartY + digestRows * rowHeight + layoutConfig.groupPadding * 2

    const botPersonaLayout: SectionLayout = {
      startX: 0,
      startY: botPersonaStartY,
      itemsPerRow,
      itemWidth: columnWidth,
      itemHeight: rowHeight,
    }

    const botPersonaResult = layoutBotPersonas(overview.botPersonas, botPersonaLayout)
    allNodes.push(...botPersonaResult.nodes)
    allEdges.push(...botPersonaResult.edges)

    // Create edges between bot personas and their default channels
    const channelIds = new Set(overview.channels.map((c) => c.chatId))
    const botPersonaEdges = findBotPersonaChannelEdges(overview.botPersonas, channelIds)
    allEdges.push(...botPersonaEdges)
  }

  return { nodes: allNodes, edges: allEdges }
}

/**
 * Group channels by their configuration status
 */
export function groupChannelsByStatus(
  channels: ChannelOverview[]
): Record<ConfigStatus, ChannelOverview[]> {
  const groups: Record<ConfigStatus, ChannelOverview[]> = {
    configured: [],
    partial: [],
    warning: [],
    unconfigured: [],
    loading: [],
    saved: [],
  }

  channels.forEach((channel) => {
    const status = calculateChannelStatus(channel)
    groups[status].push(channel)
  })

  return groups
}

/**
 * Get summary statistics for the configuration overview
 */
export function getConfigurationSummary(overview: ConfigurationOverview): {
  totalChannels: number
  configuredChannels: number
  enabledChannels: number
  totalDigestPersonas: number
  activeDigestPersonas: number
} {
  const configuredChannels = overview.channels.filter((c) => c.hasConfig).length
  const enabledChannels = overview.channels.filter((c) => c.enabled).length
  const activeDigestPersonas = overview.digestPersonas.filter((p) => p.enabled).length

  return {
    totalChannels: overview.channels.length,
    configuredChannels,
    enabledChannels,
    totalDigestPersonas: overview.digestPersonas.length,
    activeDigestPersonas,
  }
}
