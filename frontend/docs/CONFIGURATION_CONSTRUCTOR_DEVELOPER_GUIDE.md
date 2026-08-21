# Configuration Constructor Developer Guide

## Architecture Overview

The Configuration Constructor is built using React 19 with TypeScript, React Flow for graph visualization, and custom hooks for state management.

```
┌─────────────────────────────────────────────────────────────┐
│                    ConstructorPage                          │
│  ┌─────────────────────────────────────────────────────┐   │
│  │              ConfigGraphProvider                     │   │
│  │  ┌──────────────────────┬────────────────────────┐  │   │
│  │  │  ConfigurationGraph  │    SelectedNodePanel   │  │   │
│  │  │  ┌────────────────┐  │    ┌────────────────┐  │  │   │
│  │  │  │  ReactFlow     │  │    │  Entity Panel  │  │  │   │
│  │  │  │  ├─ Nodes      │  │    │  (by type)     │  │  │   │
│  │  │  │  ├─ Edges      │  │    └────────────────┘  │  │   │
│  │  │  │  ├─ Controls   │  │                        │  │   │
│  │  │  │  └─ MiniMap    │  │                        │  │   │
│  │  │  └────────────────┘  │                        │  │   │
│  │  └──────────────────────┴────────────────────────┘  │   │
│  └─────────────────────────────────────────────────────┘   │
└─────────────────────────────────────────────────────────────┘
```

## Directory Structure

```
src/components/config-graph/
├── index.ts                    # Barrel exports
├── context.ts                  # React context definition
├── hooks.ts                    # Main custom hooks
├── ConfigGraphContext.tsx      # Context provider component
├── ConfigurationGraph.tsx      # Main graph component
├── controls/
│   ├── index.ts
│   └── GraphControls.tsx       # Zoom, search, fullscreen controls
├── edges/
│   ├── index.ts
│   ├── DependencyEdge.tsx      # Required dependency edges
│   ├── OptionalEdge.tsx        # Optional relationship edges
│   └── ContainsEdge.tsx        # Parent-child edges
├── hooks/
│   ├── useConfigData.ts        # Data loading hook
│   └── useValidation.ts        # Validation state hook
├── nodes/
│   ├── BaseNode.tsx            # Shared node styling
│   ├── ChannelNode.tsx         # Channel display
│   ├── ChatConfigNode.tsx      # Config node
│   ├── LlmNode.tsx             # LLM parameters
│   ├── RateLimitsNode.tsx      # Rate limits
│   ├── ContextSettingsNode.tsx # Context settings
│   ├── SearchConfigNode.tsx    # Search config
│   ├── TriggerNode.tsx         # Trigger conditions
│   ├── TemplateNode.tsx        # Response templates
│   ├── RestrictionNode.tsx     # Topic restrictions
│   ├── DigestPersonaNode.tsx   # Digest personas
│   └── BotPersonaNode.tsx      # Bot personas
├── panels/
│   ├── index.ts
│   ├── EntityPanel.tsx         # Base panel with sections
│   ├── LazyPanels.tsx          # Lazy-loaded panel wrappers
│   ├── ValidationNotice.tsx    # Validation display
│   ├── ChannelPanel.tsx        # Channel details (read-only)
│   ├── ChatConfigPanel.tsx     # Chat config form
│   ├── LlmPanel.tsx            # LLM parameters form
│   ├── RateLimitsPanel.tsx     # Rate limits form
│   ├── ContextSettingsPanel.tsx
│   ├── SearchConfigPanel.tsx
│   ├── TriggerPanel.tsx
│   ├── TemplatePanel.tsx
│   ├── RestrictionPanel.tsx
│   ├── DigestPersonaPanel.tsx
│   ├── BotPersonaPanel.tsx
│   └── DigestCreationWizard.tsx
└── utils/
    ├── transformData.ts        # API → Graph transformation
    ├── layoutEngine.ts         # Node positioning
    ├── statusCalculator.ts     # Status computation
    ├── dependencyResolver.ts   # Validation logic
    └── performance.ts          # Debounce/throttle utilities
```

## Core Components

### ConfigGraphProvider

The context provider that manages graph state.

```tsx
interface ConfigGraphContextValue {
  state: ConfigGraphState
  dispatch: React.Dispatch<ConfigGraphAction>
  refreshData: () => Promise<void>
  selectNode: (nodeId: string | null) => void
}

// Usage
<ConfigGraphProvider autoLoad={true}>
  <YourComponent />
</ConfigGraphProvider>
```

**Props:**
- `autoLoad?: boolean` - Automatically load data on mount (default: false)
- `children: ReactNode` - Child components

### ConfigurationGraph

The main graph visualization component.

```tsx
interface ConfigurationGraphProps {
  className?: string
}

// Usage
<ConfigurationGraph className="my-custom-class" />
```

**Features:**
- Wraps ReactFlow in ReactFlowProvider
- Handles node selection
- Keyboard navigation
- Fullscreen support
- Screen reader announcements

### GraphControls

Custom controls for graph interaction.

```tsx
interface GraphControlsProps {
  nodes: ConfigNode[]
  onNodeSelect: (id: string | null) => void
  selectedNodeId: string | null
  onFitView: () => void
  isFullscreen: boolean
  onToggleFullscreen: () => void
}
```

## Custom Hooks

### useConfigGraph

Access graph state and actions.

```tsx
const { state, dispatch, refreshData, selectNode } = useConfigGraph()

// state.nodes - Array of graph nodes
// state.edges - Array of graph edges
// state.selectedNodeId - Currently selected node ID
// state.isLoading - Loading state
// state.error - Error message if any
```

### useSelectedNode

Get the currently selected node.

```tsx
const selectedNode = useSelectedNode()
// Returns ConfigNode | null
```

### useNodesByType

Filter nodes by entity type.

```tsx
const channelNodes = useNodesByType('channel')
const triggerNodes = useNodesByType('trigger')
```

### useNodeById

Find a specific node.

```tsx
const node = useNodeById('channel-123')
```

### useConfigData

Load configuration data from the API.

```tsx
const { data, loading, error, refetch } = useConfigData()
```

### useValidation

Manage validation state.

```tsx
const {
  issues,
  validateNode,
  validateAll,
  clearIssues,
  hasErrors,
  hasWarnings,
  validateWithServer,
  applyServerValidation,
} = useValidation()
```

## Type System

### Node Data Types

```tsx
// Base interface all nodes extend
interface ConfigNodeDataBase {
  label: string
  status: ConfigStatus
  entityType: ConfigNodeType
  entityId: number | string
  parentId?: number | string
  isExpanded?: boolean
}

// Status values
type ConfigStatus =
  | 'configured'
  | 'partial'
  | 'warning'
  | 'unconfigured'
  | 'loading'
  | 'saved'

// Entity types
type ConfigNodeType =
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
```

### Specific Node Data

```tsx
interface ChannelNodeData extends ConfigNodeDataBase {
  entityType: 'channel'
  channel: ChannelOverview
}

interface ChatConfigNodeData extends ConfigNodeDataBase {
  entityType: 'chatConfig'
  config: EnhancedChatConfig
}

interface LlmNodeData extends ConfigNodeDataBase {
  entityType: 'llmParams'
  params: LlmParameters | null
}

// ... similar for all entity types
```

### Edge Types

```tsx
type ConfigEdgeType = 'dependency' | 'optional' | 'contains'

interface ConfigEdgeData {
  edgeType: ConfigEdgeType
  label?: string
}
```

## Data Flow

### Loading Data

```
1. ConfigGraphProvider mounts with autoLoad=true
2. useConfigData hook fetches from /api endpoints
3. transformToGraphData converts API data to nodes/edges
4. layoutEngine positions nodes hierarchically
5. State updates trigger React Flow re-render
```

### Selecting a Node

```
1. User clicks node in graph
2. handleNodeClick fires
3. selectNode(nodeId) dispatches SELECT_NODE action
4. useSelectedNode returns the node
5. SelectedNodePanel renders appropriate panel
6. Screen reader announces selection
```

### Saving Changes

```
1. User edits form in panel
2. Form validates locally
3. onSave handler calls API
4. On success: refreshData() reloads graph
5. On error: show error message in panel
```

## Creating Custom Nodes

### Node Component Structure

```tsx
import { Handle, Position, type NodeProps } from '@xyflow/react'
import { BaseNode, StatusBadge } from './BaseNode'
import type { MyNodeData } from '../../../types/graph'

export function MyNode({ data, selected }: NodeProps<MyNodeData>) {
  return (
    <BaseNode
      data={data}
      selected={selected}
      className="my-node"
      ariaLabel={`My entity: ${data.label}`}
    >
      {/* Node content */}
      <div className="my-node__content">
        <StatusBadge status={data.status} />
        <span className="my-node__label">{data.label}</span>
      </div>

      {/* Connection handles */}
      <Handle type="target" position={Position.Top} />
      <Handle type="source" position={Position.Bottom} />
    </BaseNode>
  )
}
```

### Register in nodeTypes

```tsx
// ConfigurationGraph.tsx
const nodeTypes = {
  // ... existing types
  myType: MyNode,
}
```

## Creating Custom Panels

### Panel Component Structure

```tsx
import { useState, useCallback } from 'react'
import { EntityPanel, EntityPanelSection, Field, DetailRow } from './EntityPanel'
import type { MyNodeData } from '../../../types/graph'

interface MyPanelProps {
  data: MyNodeData
  parentChatId?: number
}

export function MyPanel({ data, parentChatId }: MyPanelProps) {
  const [value, setValue] = useState(data.someValue)
  const [saving, setSaving] = useState(false)
  const [error, setError] = useState<string | null>(null)

  const handleSave = useCallback(async () => {
    setSaving(true)
    setError(null)
    try {
      await saveMyData(parentChatId, { value })
      // Optionally trigger refresh
    } catch (e) {
      setError(e instanceof Error ? e.message : 'Save failed')
    } finally {
      setSaving(false)
    }
  }, [value, parentChatId])

  return (
    <EntityPanel
      title={data.label}
      entityType={data.entityType}
      status={data.status}
    >
      <EntityPanelSection title="Settings">
        <Field label="My Field" hint="Description of field">
          <input
            type="text"
            value={value}
            onChange={(e) => setValue(e.target.value)}
          />
        </Field>
      </EntityPanelSection>

      <EntityPanelSection title="Information">
        <DetailRow label="ID" value={data.entityId} />
        <DetailRow label="Status" value={data.status} />
      </EntityPanelSection>

      {error && <p className="error">{error}</p>}

      <div className="panel-actions">
        <button onClick={handleSave} disabled={saving}>
          {saving ? 'Saving...' : 'Save'}
        </button>
      </div>
    </EntityPanel>
  )
}
```

### Export in panels/index.ts

```tsx
export { MyPanel } from './MyPanel'
export type { MyPanelProps } from './MyPanel'
```

### Add Lazy Version

```tsx
// LazyPanels.tsx
export const LazyMyPanel = lazy(() =>
  import('./MyPanel').then(m => ({ default: m.MyPanel }))
)
```

## Utility Functions

### Status Calculation

```tsx
import {
  calculateChatConfigStatus,
  calculateLlmStatus,
  getStatusColor,
  getStatusIcon,
  getStatusLabel,
} from './utils/statusCalculator'

// Get status for a config
const status = calculateChatConfigStatus(config)

// Get CSS color
const color = getStatusColor(status) // '#22c55e' for configured

// Get icon character
const icon = getStatusIcon(status) // '✅' for configured

// Get human label
const label = getStatusLabel(status) // 'Configured' for configured
```

### Layout Engine

```tsx
import {
  LAYOUT_CONSTANTS,
  performLayout,
  autoLayoutNodes,
} from './utils/layoutEngine'

// Full layout with edges
const { nodes, edges } = performLayout(rawNodes, relationships)

// Just position nodes
const positionedNodes = autoLayoutNodes(nodes)
```

### Data Transformation

```tsx
import {
  transformToGraphData,
  createChannelNode,
  createEdge,
  groupChannelsByStatus,
} from './utils/transformData'

// Transform API data to graph format
const { nodes, edges, summary } = transformToGraphData(overview)

// Create individual node
const node = createChannelNode(channelOverview)

// Create edge
const edge = createEdge('channel-1', 'config-1', 'contains')
```

### Validation

```tsx
import {
  validateChatConfig,
  validateLlmParameters,
  findDependentNodes,
  getAllIssues,
} from './utils/dependencyResolver'

// Validate single entity
const result = validateChatConfig(config)
// { isValid: boolean, issues: DependencyIssue[] }

// Find related nodes
const dependents = findDependentNodes(nodes, edges, 'channel-123')
```

### Performance

```tsx
import {
  debounce,
  throttle,
  useDebouncedCallback,
  useDebouncedValue,
  DEBOUNCE_DELAYS,
} from './utils/performance'

// Create debounced function
const debouncedSave = debounce(save, 300)

// Hook for debounced callback
const debouncedSearch = useDebouncedCallback(
  (term) => search(term),
  DEBOUNCE_DELAYS.SEARCH
)

// Hook for debounced value
const debouncedQuery = useDebouncedValue(query, 300)
```

## Styling

### CSS Classes

All components use BEM-style class naming:

```css
/* Block */
.config-graph { }

/* Element */
.config-graph__loader { }

/* Modifier */
.config-graph--loading { }
.config-graph--fullscreen { }
```

### Adding Styles

Styles are in `src/App.css`. Follow existing patterns:

```css
/* Node styles */
.config-node { }
.config-node--selected { }
.config-node__header { }
.config-node__body { }
.config-node__status { }

/* Panel styles */
.entity-panel { }
.entity-panel__section { }
.entity-panel__field { }

/* Control styles */
.graph-controls { }
.graph-controls__button { }
```

### Responsive Breakpoints

```css
/* Tablet */
@media (max-width: 1024px) { }

/* Mobile */
@media (max-width: 768px) { }

/* Small mobile */
@media (max-width: 480px) { }
```

### Accessibility Styles

```css
/* Screen reader only */
.sr-only { }

/* Focus visible */
.config-node:focus-visible { }

/* High contrast */
@media (prefers-contrast: high) { }

/* Reduced motion */
@media (prefers-reduced-motion: reduce) { }
```

## Testing

### Unit Testing Components

```tsx
import { render, screen } from '@testing-library/react'
import { ConfigGraphProvider } from '../ConfigGraphContext'
import { MyPanel } from './MyPanel'

describe('MyPanel', () => {
  const mockData = {
    label: 'Test',
    status: 'configured' as const,
    entityType: 'myType' as const,
    entityId: 1,
  }

  it('renders panel title', () => {
    render(
      <ConfigGraphProvider>
        <MyPanel data={mockData} />
      </ConfigGraphProvider>
    )
    expect(screen.getByText('Test')).toBeInTheDocument()
  })
})
```

### Testing Hooks

```tsx
import { renderHook, act } from '@testing-library/react'
import { useValidation } from './useValidation'
import { ConfigGraphProvider } from '../ConfigGraphContext'

describe('useValidation', () => {
  it('validates node correctly', () => {
    const { result } = renderHook(() => useValidation(), {
      wrapper: ConfigGraphProvider,
    })

    act(() => {
      result.current.validateNode(mockNode)
    })

    expect(result.current.hasErrors).toBe(false)
  })
})
```

## API Integration

### Adding New Endpoints

1. Add types to `src/types/api.ts`
2. Add fetch function to `src/api/client.ts`
3. Optionally add helper to `src/api/configClient.ts`

```tsx
// types/api.ts
export interface MyEntity {
  id: number
  name: string
}

// api/client.ts
export async function fetchMyEntity(id: number): Promise<MyEntity> {
  const res = await fetch(`${API_BASE}/api/my-entity/${id}`)
  if (!res.ok) throw new Error('Failed to fetch')
  return res.json()
}

// api/configClient.ts
export async function getMyEntityForGraph(id: number) {
  const entity = await fetchMyEntity(id)
  return transformMyEntity(entity)
}
```

## Performance Optimization

### Virtualization

React Flow automatically virtualizes nodes when `onlyRenderVisibleElements={true}` is set.

### Memoization

Key components are memoized:

```tsx
import { memo } from 'react'

export const BaseNode = memo(function BaseNode({ data, selected, children }) {
  // ...
})
```

### Lazy Loading

Panels are lazy-loaded for code splitting:

```tsx
const LazyChannelPanel = lazy(() =>
  import('./ChannelPanel').then(m => ({ default: m.ChannelPanel }))
)
```

### Debouncing

Validation and search are debounced:

```tsx
const DEBOUNCE_DELAYS = {
  VALIDATION: 150,
  SEARCH: 200,
  SAVE: 300,
}
```

## Common Patterns

### Type Guards

```tsx
function isChannelNodeData(data: AnyConfigNodeData): data is ChannelNodeData {
  return data.entityType === 'channel' && 'channel' in data
}
```

### Error Handling

```tsx
const [error, setError] = useState<string | null>(null)

try {
  await apiCall()
  setError(null)
} catch (e) {
  setError(e instanceof Error ? e.message : 'Unknown error')
}
```

### Loading States

```tsx
const [saving, setSaving] = useState(false)

const handleSave = async () => {
  setSaving(true)
  try {
    await save()
  } finally {
    setSaving(false)
  }
}

return (
  <button disabled={saving}>
    {saving ? 'Saving...' : 'Save'}
  </button>
)
```

## Troubleshooting

### Common Issues

**"Cannot read property of undefined"**
- Check that data is loaded before rendering
- Add null checks for optional properties

**"Maximum update depth exceeded"**
- Check for missing dependency arrays in useEffect/useCallback
- Ensure object references are stable

**"React Flow: No parent found"**
- Ensure ReactFlowProvider wraps the component using hooks

**Graph not updating**
- Call `refreshData()` after API changes
- Check that state updates are being dispatched

### Debug Mode

Enable React Flow debug overlay:

```tsx
<ReactFlow
  // ...
  proOptions={{ hideAttribution: false }}
/>
```

Check state in React DevTools by searching for "ConfigGraph".

## Contributing

1. Follow existing code patterns and naming conventions
2. Add TypeScript types for all new code
3. Include JSDoc comments for public APIs
4. Add unit tests for new functionality
5. Update this documentation for significant changes
6. Run `npm run lint` and `npm run build` before committing
