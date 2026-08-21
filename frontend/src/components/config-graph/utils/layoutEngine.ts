/**
 * Layout Engine for Configuration Graph
 *
 * Provides hierarchical layout algorithms for positioning nodes
 * in a logical, readable arrangement based on entity relationships.
 *
 * Layout Structure:
 * - Level 0: Channels (horizontal row at top)
 * - Level 1: Chat Configs / Digest Personas (below channels)
 * - Level 2: Child entities (LLM, Rates, Context, Search, etc.)
 * - Level 3: Triggers, Templates, Restrictions
 */

import type { ConfigNode, ConfigEdge, ConfigEdgeType, GraphLayoutConfig } from '../../../types/graph'

/**
 * Layout constants for positioning
 */
export const LAYOUT_CONSTANTS = {
  CHANNEL_START_X: 0,
  CHANNEL_START_Y: 0,
  DIGEST_START_Y_OFFSET: 50,
  BOT_PERSONA_START_Y_OFFSET: 100,
  CHILD_VERTICAL_OFFSET: 180,
  CHILD_HORIZONTAL_OFFSET: 320,
  ITEMS_PER_ROW: 4,
} as const

/**
 * Node type hierarchy levels
 */
export const NODE_LEVELS: Record<string, number> = {
  channel: 0,
  chatConfig: 1,
  digestPersona: 1,
  botPersona: 1,
  llmParams: 2,
  rateLimits: 2,
  contextSettings: 2,
  searchConfig: 2,
  trigger: 3,
  template: 3,
  restriction: 3,
} as const

/**
 * Calculate position for a node based on its type and index
 */
export function calculateNodePosition(
  nodeType: string,
  index: number,
  parentPosition: { x: number; y: number } | null,
  config: GraphLayoutConfig
): { x: number; y: number } {
  const level = NODE_LEVELS[nodeType] ?? 0
  const columnWidth = config.nodeWidth + config.horizontalGap
  const rowHeight = config.nodeHeight + config.verticalGap

  switch (level) {
    case 0:
      // Level 0: Channels in horizontal row
      return {
        x: LAYOUT_CONSTANTS.CHANNEL_START_X + (index % LAYOUT_CONSTANTS.ITEMS_PER_ROW) * columnWidth,
        y: LAYOUT_CONSTANTS.CHANNEL_START_Y + Math.floor(index / LAYOUT_CONSTANTS.ITEMS_PER_ROW) * rowHeight,
      }

    case 1:
      // Level 1: Chat configs, digest personas, bot personas
      if (nodeType === 'digestPersona') {
        return {
          x: LAYOUT_CONSTANTS.CHANNEL_START_X + (index % LAYOUT_CONSTANTS.ITEMS_PER_ROW) * columnWidth,
          y: LAYOUT_CONSTANTS.DIGEST_START_Y_OFFSET + Math.floor(index / LAYOUT_CONSTANTS.ITEMS_PER_ROW) * rowHeight,
        }
      }
      if (nodeType === 'botPersona') {
        return {
          x: LAYOUT_CONSTANTS.CHANNEL_START_X + (index % LAYOUT_CONSTANTS.ITEMS_PER_ROW) * columnWidth,
          y: LAYOUT_CONSTANTS.BOT_PERSONA_START_Y_OFFSET + Math.floor(index / LAYOUT_CONSTANTS.ITEMS_PER_ROW) * rowHeight,
        }
      }
      // ChatConfig - positioned below parent channel if available
      if (parentPosition) {
        return {
          x: parentPosition.x,
          y: parentPosition.y + LAYOUT_CONSTANTS.CHILD_VERTICAL_OFFSET,
        }
      }
      return {
        x: index * columnWidth,
        y: rowHeight,
      }

    case 2:
      // Level 2: Child entities in a row below chat config
      if (parentPosition) {
        const childOffset = index * (config.nodeWidth + config.horizontalGap * 0.5)
        return {
          x: parentPosition.x + childOffset - (config.nodeWidth * 1.5),
          y: parentPosition.y + LAYOUT_CONSTANTS.CHILD_VERTICAL_OFFSET,
        }
      }
      return {
        x: index * columnWidth,
        y: rowHeight * 2,
      }

    case 3:
      // Level 3: Triggers, templates, restrictions
      if (parentPosition) {
        const childOffset = index * (config.nodeWidth * 0.9)
        return {
          x: parentPosition.x + childOffset,
          y: parentPosition.y + LAYOUT_CONSTANTS.CHILD_VERTICAL_OFFSET,
        }
      }
      return {
        x: index * columnWidth,
        y: rowHeight * 3,
      }

    default:
      return {
        x: index * columnWidth,
        y: level * rowHeight,
      }
  }
}

/**
 * Create an edge between two nodes with appropriate type and styling
 */
export function createLayoutEdge(
  sourceId: string,
  targetId: string,
  edgeType: ConfigEdgeType,
  label?: string
): ConfigEdge {
  return {
    id: `edge-${sourceId}-${targetId}`,
    source: sourceId,
    target: targetId,
    type: edgeType,
    animated: edgeType === 'dependency',
    data: {
      edgeType,
      label,
    },
    style: getEdgeStyle(edgeType),
  }
}

/**
 * Get edge style based on edge type
 */
export function getEdgeStyle(edgeType: ConfigEdgeType): React.CSSProperties {
  switch (edgeType) {
    case 'dependency':
      return {
        stroke: '#4CAF50',
        strokeWidth: 2,
      }
    case 'optional':
      return {
        stroke: '#9E9E9E',
        strokeWidth: 1.5,
        strokeDasharray: '4,4',
      }
    case 'contains':
      return {
        stroke: '#2196F3',
        strokeWidth: 2,
      }
    default:
      return {
        stroke: '#94a3b8',
        strokeWidth: 2,
      }
  }
}

/**
 * Hierarchical layout section definition
 */
interface LayoutSection {
  title: string
  nodeType: string
  startY: number
  itemsPerRow: number
}

/**
 * Calculate layout sections based on data counts
 */
export function calculateLayoutSections(
  channelCount: number,
  digestCount: number,
  botPersonaCount: number,
  config: GraphLayoutConfig
): LayoutSection[] {
  const rowHeight = config.nodeHeight + config.verticalGap
  const sections: LayoutSection[] = []

  // Calculate channel section height
  const channelRows = Math.ceil(channelCount / LAYOUT_CONSTANTS.ITEMS_PER_ROW)
  const channelSectionHeight = channelRows * rowHeight

  // Channels section
  sections.push({
    title: 'Channels',
    nodeType: 'channel',
    startY: 0,
    itemsPerRow: LAYOUT_CONSTANTS.ITEMS_PER_ROW,
  })

  // Digest personas section
  if (digestCount > 0) {
    sections.push({
      title: 'Digest Personas',
      nodeType: 'digestPersona',
      startY: channelSectionHeight + config.groupPadding * 3,
      itemsPerRow: LAYOUT_CONSTANTS.ITEMS_PER_ROW,
    })
  }

  // Bot personas section
  if (botPersonaCount > 0) {
    const digestRows = Math.ceil(digestCount / LAYOUT_CONSTANTS.ITEMS_PER_ROW)
    const digestSectionHeight = digestCount > 0 ? digestRows * rowHeight : 0
    sections.push({
      title: 'Bot Personas',
      nodeType: 'botPersona',
      startY: channelSectionHeight + digestSectionHeight + config.groupPadding * 4,
      itemsPerRow: LAYOUT_CONSTANTS.ITEMS_PER_ROW,
    })
  }

  return sections
}

/**
 * Position nodes within a section
 */
export function positionNodesInSection(
  nodes: ConfigNode[],
  sectionStartY: number,
  itemsPerRow: number,
  config: GraphLayoutConfig
): ConfigNode[] {
  const columnWidth = config.nodeWidth + config.horizontalGap
  const rowHeight = config.nodeHeight + config.verticalGap

  return nodes.map((node, index) => {
    const col = index % itemsPerRow
    const row = Math.floor(index / itemsPerRow)
    return {
      ...node,
      position: {
        x: col * columnWidth,
        y: sectionStartY + row * rowHeight,
      },
    }
  })
}

/**
 * Auto-layout all nodes based on their types and relationships
 */
export function autoLayoutNodes(
  nodes: ConfigNode[],
  config: GraphLayoutConfig
): ConfigNode[] {
  // Group nodes by type
  const nodesByType: Record<string, ConfigNode[]> = {}
  nodes.forEach((node) => {
    const nodeType = node.type ?? 'unknown'
    if (!nodesByType[nodeType]) {
      nodesByType[nodeType] = []
    }
    nodesByType[nodeType].push(node)
  })

  // Calculate section positions
  const channelCount = nodesByType['channel']?.length ?? 0
  const digestCount = nodesByType['digestPersona']?.length ?? 0
  const botPersonaCount = nodesByType['botPersona']?.length ?? 0

  const sections = calculateLayoutSections(channelCount, digestCount, botPersonaCount, config)

  // Position each section
  const layoutedNodes: ConfigNode[] = []

  sections.forEach((section) => {
    const sectionNodes = nodesByType[section.nodeType] ?? []
    const positionedNodes = positionNodesInSection(
      sectionNodes,
      section.startY,
      section.itemsPerRow,
      config
    )
    layoutedNodes.push(...positionedNodes)
  })

  // Add any remaining nodes not in sections
  const handledTypes = new Set(sections.map((s) => s.nodeType))
  Object.entries(nodesByType).forEach(([nodeType, typeNodes]) => {
    if (!handledTypes.has(nodeType)) {
      // Calculate Y position after all sections
      const lastSection = sections[sections.length - 1]
      const lastSectionNodes = nodesByType[lastSection?.nodeType ?? '']?.length ?? 0
      const lastSectionRows = Math.ceil(lastSectionNodes / LAYOUT_CONSTANTS.ITEMS_PER_ROW)
      const rowHeight = config.nodeHeight + config.verticalGap
      const startY = (lastSection?.startY ?? 0) + lastSectionRows * rowHeight + config.groupPadding * 2

      const positionedNodes = positionNodesInSection(
        typeNodes,
        startY,
        LAYOUT_CONSTANTS.ITEMS_PER_ROW,
        config
      )
      layoutedNodes.push(...positionedNodes)
    }
  })

  return layoutedNodes
}

/**
 * Create edges based on node relationships
 */
export function createEdgesFromRelationships(
  nodes: ConfigNode[],
  channelIds: Set<number>
): ConfigEdge[] {
  const edges: ConfigEdge[] = []

  nodes.forEach((node) => {
    const data = node.data

    // Digest persona -> target channel edge
    if (data.entityType === 'digestPersona' && 'persona' in data) {
      const persona = data.persona
      if (persona.targetChannelId && channelIds.has(persona.targetChannelId)) {
        edges.push(
          createLayoutEdge(
            node.id,
            `channel-${persona.targetChannelId}`,
            'dependency',
            'publishes to'
          )
        )
      }
    }

    // Bot persona -> target channel edge
    // Note: PersonaBundle doesn't have a direct channel reference in the current API
    // Bot personas are standalone entities, edges would need a different data source

    // Parent-child relationships via parentId
    if (data.parentId) {
      const parentNodeId = findParentNodeId(data.parentId, nodes)
      if (parentNodeId) {
        edges.push(
          createLayoutEdge(
            parentNodeId,
            node.id,
            'contains'
          )
        )
      }
    }
  })

  return edges
}

/**
 * Find parent node ID based on entity ID
 */
function findParentNodeId(parentId: number | string, nodes: ConfigNode[]): string | null {
  const parentNode = nodes.find((n) => {
    return n.data.entityId === parentId
  })
  return parentNode?.id ?? null
}

/**
 * Layout result containing positioned nodes and edges
 */
export interface LayoutResult {
  nodes: ConfigNode[]
  edges: ConfigEdge[]
}

/**
 * Perform complete layout of nodes and edges
 */
export function performLayout(
  nodes: ConfigNode[],
  existingEdges: ConfigEdge[],
  config: GraphLayoutConfig
): LayoutResult {
  // Auto-layout nodes
  const layoutedNodes = autoLayoutNodes(nodes, config)

  // Get channel IDs for edge creation
  const channelIds = new Set<number>()
  layoutedNodes.forEach((node) => {
    if (node.data.entityType === 'channel') {
      const entityId = node.data.entityId
      if (typeof entityId === 'number') {
        channelIds.add(entityId)
      }
    }
  })

  // Create edges from relationships
  const relationshipEdges = createEdgesFromRelationships(layoutedNodes, channelIds)

  // Merge with existing edges, avoiding duplicates
  const edgeIds = new Set(relationshipEdges.map((e) => e.id))
  const mergedEdges = [
    ...relationshipEdges,
    ...existingEdges.filter((e) => !edgeIds.has(e.id)),
  ]

  return {
    nodes: layoutedNodes,
    edges: mergedEdges,
  }
}
