/**
 * Lazy Loading Wrappers for Entity Panels
 *
 * Provides code-splitting for entity panels to reduce initial bundle size
 * and improve performance. Panels are loaded on-demand when selected.
 */

import { lazy, Suspense, type ComponentType } from 'react'

/**
 * Loading fallback for lazy-loaded panels
 */
function PanelLoadingFallback() {
  return (
    <div className="entity-panel entity-panel--loading">
      <div className="entity-panel__loading">
        <div className="entity-panel__spinner" />
        <span className="muted">Loading panel...</span>
      </div>
    </div>
  )
}

/**
 * Error boundary fallback for failed panel loads
 */
function PanelErrorFallback() {
  return (
    <div className="entity-panel entity-panel--error">
      <div className="entity-panel__error">
        <span className="muted">Failed to load panel</span>
      </div>
    </div>
  )
}

/**
 * Creates a lazy-loaded version of a panel component with suspense boundary
 */
function createLazyPanel<P extends object>(
  importFn: () => Promise<{ default: ComponentType<P> } | { [key: string]: ComponentType<P> }>,
  exportName?: string
): ComponentType<P> {
  const LazyComponent = lazy(() =>
    importFn().then((module) => {
      if (exportName && exportName in module) {
        return { default: (module as Record<string, ComponentType<P>>)[exportName] }
      }
      return module as { default: ComponentType<P> }
    })
  )
  return function LazyPanelWrapper(props: P) {
    return (
      <Suspense fallback={<PanelLoadingFallback />}>
        <LazyComponent {...props} />
      </Suspense>
    )
  }
}

/**
 * Lazy-loaded Channel Panel
 */
export const LazyChannelPanel = createLazyPanel(
  () => import('./ChannelPanel'),
  'ChannelPanel'
)

/**
 * Lazy-loaded Chat Config Panel
 */
export const LazyChatConfigPanel = createLazyPanel(
  () => import('./ChatConfigPanel'),
  'ChatConfigPanel'
)

/**
 * Lazy-loaded LLM Panel
 */
export const LazyLlmPanel = createLazyPanel(
  () => import('./LlmPanel'),
  'LlmPanel'
)

/**
 * Lazy-loaded Rate Limits Panel
 */
export const LazyRateLimitsPanel = createLazyPanel(
  () => import('./RateLimitsPanel'),
  'RateLimitsPanel'
)

/**
 * Lazy-loaded Context Settings Panel
 */
export const LazyContextSettingsPanel = createLazyPanel(
  () => import('./ContextSettingsPanel'),
  'ContextSettingsPanel'
)

/**
 * Lazy-loaded Search Config Panel
 */
export const LazySearchConfigPanel = createLazyPanel(
  () => import('./SearchConfigPanel'),
  'SearchConfigPanel'
)

/**
 * Lazy-loaded Digest Persona Panel
 */
export const LazyDigestPersonaPanel = createLazyPanel(
  () => import('./DigestPersonaPanel'),
  'DigestPersonaPanel'
)

/**
 * Lazy-loaded Bot Persona Panel
 */
export const LazyBotPersonaPanel = createLazyPanel(
  () => import('./BotPersonaPanel'),
  'BotPersonaPanel'
)

/**
 * Lazy-loaded Trigger Panel
 */
export const LazyTriggerPanel = createLazyPanel(
  () => import('./TriggerPanel'),
  'TriggerPanel'
)

/**
 * Lazy-loaded Template Panel
 */
export const LazyTemplatePanel = createLazyPanel(
  () => import('./TemplatePanel'),
  'TemplatePanel'
)

/**
 * Lazy-loaded Restriction Panel
 */
export const LazyRestrictionPanel = createLazyPanel(
  () => import('./RestrictionPanel'),
  'RestrictionPanel'
)

/**
 * Lazy-loaded Digest Creation Wizard
 */
export const LazyDigestCreationWizard = createLazyPanel(
  () => import('./DigestCreationWizard'),
  'DigestCreationWizard'
)

export { PanelLoadingFallback, PanelErrorFallback }
