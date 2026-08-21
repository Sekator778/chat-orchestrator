// Base components
export { EntityPanel, EntityPanelSection, DetailRow, Field, Chip } from './EntityPanel'
export type { EntityPanelProps, EntityPanelSectionProps, DetailRowProps, FieldProps, ChipProps } from './EntityPanel'

// Entity-specific panels (eager loading)
export { ChannelPanel } from './ChannelPanel'
export type { ChannelPanelProps } from './ChannelPanel'

export { ChatConfigPanel } from './ChatConfigPanel'
export type { ChatConfigPanelProps } from './ChatConfigPanel'

export { LlmPanel } from './LlmPanel'
export type { LlmPanelProps } from './LlmPanel'

export { RateLimitsPanel } from './RateLimitsPanel'
export type { RateLimitsPanelProps } from './RateLimitsPanel'

export { ContextSettingsPanel } from './ContextSettingsPanel'
export type { ContextSettingsPanelProps } from './ContextSettingsPanel'

export { SearchConfigPanel } from './SearchConfigPanel'
export type { SearchConfigPanelProps } from './SearchConfigPanel'

export { DigestPersonaPanel } from './DigestPersonaPanel'
export type { DigestPersonaPanelProps } from './DigestPersonaPanel'

export { DigestCreationWizard } from './DigestCreationWizard'
export type { DigestCreationWizardProps } from './DigestCreationWizard'

export { BotPersonaPanel } from './BotPersonaPanel'
export type { BotPersonaPanelProps } from './BotPersonaPanel'

export { TriggerPanel } from './TriggerPanel'
export type { TriggerPanelProps } from './TriggerPanel'

export { TemplatePanel } from './TemplatePanel'
export type { TemplatePanelProps } from './TemplatePanel'

export { RestrictionPanel } from './RestrictionPanel'
export type { RestrictionPanelProps } from './RestrictionPanel'

// Validation components
export { ValidationNotice, ValidationBadge } from './ValidationNotice'
export type { ValidationNoticeProps } from './ValidationNotice'

// Lazy-loaded panels (for code splitting - optional usage)
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
} from './LazyPanels'
