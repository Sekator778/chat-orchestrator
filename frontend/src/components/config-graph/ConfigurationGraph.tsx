import { useCallback, useEffect, useMemo, useState, useRef } from 'react'
import {
  ReactFlow,
  Controls,
  MiniMap,
  Background,
  BackgroundVariant,
  useNodesState,
  useEdgesState,
  type Node,
  type Edge,
  MarkerType,
  ReactFlowProvider,
  useReactFlow,
} from '@xyflow/react'
import '@xyflow/react/dist/style.css'
import { useConfigGraph } from './hooks'
import { ChannelNode } from './nodes/ChannelNode'
import { ChatConfigNode } from './nodes/ChatConfigNode'
import { LlmNode } from './nodes/LlmNode'
import { DigestPersonaNode } from './nodes/DigestPersonaNode'
import { RateLimitsNode } from './nodes/RateLimitsNode'
import { ContextSettingsNode } from './nodes/ContextSettingsNode'
import { SearchConfigNode } from './nodes/SearchConfigNode'
import { BotPersonaNode } from './nodes/BotPersonaNode'
import { TriggerNode } from './nodes/TriggerNode'
import { TemplateNode } from './nodes/TemplateNode'
import { RestrictionNode } from './nodes/RestrictionNode'
import { DependencyEdge, OptionalEdge, ContainsEdge } from './edges'
import { GraphControls } from './controls'
import { getStatusColor } from './utils/statusCalculator'
import type { ConfigStatus, ConfigNode, AnyConfigNodeData } from '../../types/graph'

/**
 * Custom node types for React Flow
 * Each entity type has a specialized node component for optimal display
 */
const nodeTypes = {
  channel: ChannelNode,
  chatConfig: ChatConfigNode,
  llmParams: LlmNode,
  rateLimits: RateLimitsNode,
  contextSettings: ContextSettingsNode,
  trigger: TriggerNode,
  template: TemplateNode,
  restriction: RestrictionNode,
  searchConfig: SearchConfigNode,
  digestPersona: DigestPersonaNode,
  botPersona: BotPersonaNode,
}

/**
 * Custom edge types for React Flow
 * Each relationship type has a distinct visual style
 */
const edgeTypes = {
  dependency: DependencyEdge,
  optional: OptionalEdge,
  contains: ContainsEdge,
}

/**
 * Props for ConfigurationGraph component
 */
interface ConfigurationGraphProps {
  className?: string
}

/**
 * Live region for screen reader announcements
 */
function LiveRegion({ message }: { message: string }) {
  return (
    <div
      role="status"
      aria-live="polite"
      aria-atomic="true"
      className="sr-only"
    >
      {message}
    </div>
  )
}

/**
 * Inner graph component that uses React Flow hooks
 */
function ConfigurationGraphInner({ className }: ConfigurationGraphProps) {
  const { state, selectNode } = useConfigGraph()
  const [nodes, setNodes, onNodesChange] = useNodesState<Node>([])
  const [edges, setEdges, onEdgesChange] = useEdgesState<Edge>([])
  const [isFullscreen, setIsFullscreen] = useState(false)
  const [announcement, setAnnouncement] = useState('')
  const containerRef = useRef<HTMLDivElement>(null)
  const reactFlow = useReactFlow()

  const stateNodes = useMemo(() => state.nodes as Node[], [state.nodes])
  const stateEdges = useMemo(() => state.edges as Edge[], [state.edges])

  useEffect(() => {
    setNodes(stateNodes)
  }, [stateNodes, setNodes])

  useEffect(() => {
    setEdges(stateEdges)
  }, [stateEdges, setEdges])

  const handleNodeClick = useCallback(
    (_event: React.MouseEvent, node: Node) => {
      selectNode(node.id)
      // Announce selection to screen readers
      const nodeData = node.data as AnyConfigNodeData | undefined
      const label = nodeData?.label ?? node.id
      setAnnouncement(`Selected ${nodeData?.entityType ?? 'node'}: ${label}`)
    },
    [selectNode]
  )

  const handlePaneClick = useCallback(() => {
    selectNode(null)
    setAnnouncement('Selection cleared')
  }, [selectNode])

  /**
   * Fit view to show all nodes
   */
  const handleFitView = useCallback(() => {
    reactFlow.fitView({ padding: 0.2, duration: 300 })
  }, [reactFlow])

  /**
   * Toggle fullscreen mode
   */
  const handleToggleFullscreen = useCallback(() => {
    if (!containerRef.current) return

    if (!isFullscreen) {
      if (containerRef.current.requestFullscreen) {
        containerRef.current.requestFullscreen()
      }
    } else {
      if (document.exitFullscreen) {
        document.exitFullscreen()
      }
    }
  }, [isFullscreen])

  /**
   * Listen for fullscreen changes
   */
  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(!!document.fullscreenElement)
    }

    document.addEventListener('fullscreenchange', handleFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange)
  }, [])

  /**
   * Keyboard shortcut for node navigation
   * Supports Tab cycling, arrow keys, Enter/Space for selection, Escape to deselect
   */
  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      // Don't handle if typing in an input
      if (e.target instanceof HTMLInputElement || e.target instanceof HTMLTextAreaElement) {
        return
      }

      // Tab: Cycle through nodes
      if (e.key === 'Tab' && !e.ctrlKey && !e.altKey) {
        e.preventDefault()
        const currentIndex = state.selectedNodeId
          ? stateNodes.findIndex((n) => n.id === state.selectedNodeId)
          : -1
        const nextIndex = e.shiftKey
          ? (currentIndex <= 0 ? stateNodes.length - 1 : currentIndex - 1)
          : (currentIndex + 1) % stateNodes.length

        if (stateNodes[nextIndex]) {
          selectNode(stateNodes[nextIndex].id)
          const node = stateNodes[nextIndex]
          const nodeData = node.data as AnyConfigNodeData | undefined
          setAnnouncement(
            `Node ${nextIndex + 1} of ${stateNodes.length}: ${nodeData?.entityType ?? 'unknown'}, ${nodeData?.label ?? node.id}`
          )
          if (node.position) {
            reactFlow.setCenter(node.position.x + 120, node.position.y + 60, {
              zoom: reactFlow.getZoom(),
              duration: 200,
            })
          }
        }
      }

      // Arrow keys for spatial navigation within the graph
      if (['ArrowUp', 'ArrowDown', 'ArrowLeft', 'ArrowRight'].includes(e.key) && !e.ctrlKey) {
        const currentNode = stateNodes.find((n) => n.id === state.selectedNodeId)
        if (!currentNode?.position) return

        // Find the nearest node in the direction pressed
        const currentPos = currentNode.position
        let bestCandidate: Node | null = null
        let bestDistance = Infinity

        for (const node of stateNodes) {
          if (node.id === currentNode.id || !node.position) continue

          const dx = node.position.x - currentPos.x
          const dy = node.position.y - currentPos.y

          // Check if node is in the correct direction
          let isInDirection = false
          switch (e.key) {
            case 'ArrowUp':
              isInDirection = dy < -20 && Math.abs(dx) < Math.abs(dy) * 2
              break
            case 'ArrowDown':
              isInDirection = dy > 20 && Math.abs(dx) < Math.abs(dy) * 2
              break
            case 'ArrowLeft':
              isInDirection = dx < -20 && Math.abs(dy) < Math.abs(dx) * 2
              break
            case 'ArrowRight':
              isInDirection = dx > 20 && Math.abs(dy) < Math.abs(dx) * 2
              break
          }

          if (isInDirection) {
            const distance = Math.sqrt(dx * dx + dy * dy)
            if (distance < bestDistance) {
              bestDistance = distance
              bestCandidate = node
            }
          }
        }

        if (bestCandidate) {
          e.preventDefault()
          selectNode(bestCandidate.id)
          const nodeData = bestCandidate.data as AnyConfigNodeData | undefined
          setAnnouncement(`Navigated to ${nodeData?.entityType ?? 'node'}: ${nodeData?.label ?? bestCandidate.id}`)
          if (bestCandidate.position) {
            reactFlow.setCenter(bestCandidate.position.x + 120, bestCandidate.position.y + 60, {
              zoom: reactFlow.getZoom(),
              duration: 200,
            })
          }
        }
      }

      // Home: Go to first node
      if (e.key === 'Home' && stateNodes.length > 0) {
        e.preventDefault()
        const firstNode = stateNodes[0]
        selectNode(firstNode.id)
        const nodeData = firstNode.data as AnyConfigNodeData | undefined
        setAnnouncement(`First node: ${nodeData?.entityType ?? 'node'}, ${nodeData?.label ?? firstNode.id}`)
        if (firstNode.position) {
          reactFlow.setCenter(firstNode.position.x + 120, firstNode.position.y + 60, {
            zoom: reactFlow.getZoom(),
            duration: 200,
          })
        }
      }

      // End: Go to last node
      if (e.key === 'End' && stateNodes.length > 0) {
        e.preventDefault()
        const lastNode = stateNodes[stateNodes.length - 1]
        selectNode(lastNode.id)
        const nodeData = lastNode.data as AnyConfigNodeData | undefined
        setAnnouncement(`Last node: ${nodeData?.entityType ?? 'node'}, ${nodeData?.label ?? lastNode.id}`)
        if (lastNode.position) {
          reactFlow.setCenter(lastNode.position.x + 120, lastNode.position.y + 60, {
            zoom: reactFlow.getZoom(),
            duration: 200,
          })
        }
      }
    }

    window.addEventListener('keydown', handleKeyDown)
    return () => window.removeEventListener('keydown', handleKeyDown)
  }, [state.selectedNodeId, stateNodes, selectNode, reactFlow])

  if (state.isLoading) {
    return (
      <div
        className={`config-graph config-graph--loading ${className ?? ''}`}
        role="status"
        aria-busy="true"
        aria-label="Loading configuration graph"
      >
        <div className="config-graph__loader">
          <div className="config-graph__spinner" aria-hidden="true" />
          <p className="muted">Loading configuration data...</p>
        </div>
      </div>
    )
  }

  if (state.error) {
    return (
      <div
        className={`config-graph config-graph--error ${className ?? ''}`}
        role="alert"
        aria-live="assertive"
      >
        <div className="config-graph__error">
          <h3>Failed to load configuration</h3>
          <p className="muted">{state.error}</p>
        </div>
      </div>
    )
  }

  return (
    <div
      ref={containerRef}
      className={`config-graph ${isFullscreen ? 'config-graph--fullscreen' : ''} ${className ?? ''}`}
      role="application"
      aria-label="Configuration dependency graph"
      aria-describedby="graph-instructions"
    >
      {/* Screen reader instructions */}
      <div id="graph-instructions" className="sr-only">
        Use Tab or arrow keys to navigate between nodes.
        Press Enter or Space to select a node and view its details.
        Press Escape to deselect. Press Home for first node, End for last node.
        Use Ctrl+F to search nodes, Ctrl+0 to fit view, F11 for fullscreen.
      </div>

      {/* Live region for announcements */}
      <LiveRegion message={announcement} />

      <ReactFlow
        nodes={nodes}
        edges={edges}
        onNodesChange={onNodesChange}
        onEdgesChange={onEdgesChange}
        onNodeClick={handleNodeClick}
        onPaneClick={handlePaneClick}
        nodeTypes={nodeTypes}
        edgeTypes={edgeTypes}
        fitView
        fitViewOptions={{ padding: 0.2 }}
        minZoom={0.1}
        maxZoom={2}
        defaultEdgeOptions={{
          type: 'smoothstep',
          animated: false,
          markerEnd: {
            type: MarkerType.ArrowClosed,
            width: 16,
            height: 16,
            color: '#94a3b8',
          },
        }}
        proOptions={{ hideAttribution: true }}
        nodesDraggable={false}
        nodesConnectable={false}
        elementsSelectable={true}
        selectNodesOnDrag={false}
        panOnScroll={true}
        zoomOnScroll={true}
        zoomOnDoubleClick={false}
        onlyRenderVisibleElements={true}
      >
        {/* Custom controls in top-right */}
        <GraphControls
          nodes={state.nodes as ConfigNode[]}
          onNodeSelect={selectNode}
          selectedNodeId={state.selectedNodeId}
          onFitView={handleFitView}
          isFullscreen={isFullscreen}
          onToggleFullscreen={handleToggleFullscreen}
        />

        {/* Default zoom controls */}
        <Controls
          position="bottom-left"
          showZoom={true}
          showFitView={true}
          showInteractive={false}
          aria-label="Graph zoom and navigation controls"
        />

        {/* Minimap with status colors */}
        <MiniMap
          position="bottom-right"
          nodeColor={(node) => {
            const data = node.data as { status?: ConfigStatus } | undefined
            return getStatusColor(data?.status ?? 'unconfigured')
          }}
          maskColor="rgba(15, 23, 42, 0.1)"
          pannable
          zoomable
          aria-label="Graph minimap overview"
        />

        {/* Background dots pattern */}
        <Background variant={BackgroundVariant.Dots} gap={20} size={1} color="#cbd5e1" />
      </ReactFlow>

      {/* Hint for shortcuts - hidden from screen readers as instructions are provided in sr-only div */}
      <div className="config-graph__hint" aria-hidden="true">
        Press <kbd>?</kbd> for keyboard shortcuts
      </div>
    </div>
  )
}

/**
 * Main configuration graph component using React Flow
 * Wrapped in ReactFlowProvider for hook access
 */
export function ConfigurationGraph({ className }: ConfigurationGraphProps) {
  return (
    <ReactFlowProvider>
      <ConfigurationGraphInner className={className} />
    </ReactFlowProvider>
  )
}
