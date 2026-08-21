import { EntityPanel, EntityPanelSection, DetailRow, Chip } from './EntityPanel'
import type { ChannelNodeData } from '../../../types/graph'

/**
 * Props for ChannelPanel component
 */
export interface ChannelPanelProps {
  data: ChannelNodeData
}

/**
 * Format date for display
 */
function formatDate(dateStr: string | null): string {
  if (!dateStr) return 'Never'
  try {
    const date = new Date(dateStr)
    return date.toLocaleString()
  } catch {
    return dateStr
  }
}

/**
 * Panel for displaying channel information
 * This is a read-only panel since channel info comes from Telegram
 */
export function ChannelPanel({ data }: ChannelPanelProps) {
  const channel = data.channel

  return (
    <EntityPanel
      title={data.label}
      entityType="Channel"
      status={data.status}
      editable={false}
    >
      <EntityPanelSection title="Channel Info">
        <DetailRow label="Chat ID">
          <Chip variant="outline">{channel.chatId}</Chip>
        </DetailRow>

        {channel.description && (
          <DetailRow label="Description">
            <span className="entity-panel__text-value">{channel.description}</span>
          </DetailRow>
        )}

        <DetailRow label="Join Status">
          <Chip variant={channel.joinStatus === 'JOINED' ? 'green' : 'outline'}>
            {channel.joinStatus ?? 'Unknown'}
          </Chip>
        </DetailRow>

        <DetailRow label="Mute Status">
          <Chip variant={channel.muteStatus === 'MUTED' ? 'amber' : 'outline'}>
            {channel.muteStatus ?? 'Unknown'}
          </Chip>
        </DetailRow>

        {channel.subscribers !== null && (
          <DetailRow label="Subscribers">
            <Chip variant="outline">{channel.subscribers.toLocaleString()}</Chip>
          </DetailRow>
        )}

        {channel.channelScore !== null && (
          <DetailRow label="Score">
            <Chip variant="violet">{channel.channelScore.toFixed(2)}</Chip>
          </DetailRow>
        )}

        <DetailRow label="Last Seen">
          <span className="entity-panel__text-value muted">
            {formatDate(channel.lastSeen)}
          </span>
        </DetailRow>
      </EntityPanelSection>

      <EntityPanelSection title="Configuration Status">
        <DetailRow label="Has Config">
          <Chip variant={channel.hasConfig ? 'green' : 'outline'}>
            {channel.hasConfig ? 'Yes' : 'No'}
          </Chip>
        </DetailRow>

        <DetailRow label="Enabled">
          <Chip variant={channel.enabled ? 'green' : 'outline'}>
            {channel.enabled ? 'Yes' : 'No'}
          </Chip>
        </DetailRow>

        <DetailRow label="Auto-Sync">
          <Chip variant={channel.autoSyncEnabled ? 'green' : 'outline'}>
            {channel.autoSyncEnabled ? 'Enabled' : 'Disabled'}
          </Chip>
        </DetailRow>

        {channel.language && (
          <DetailRow label="Language">
            <Chip variant="violet">{channel.language.toUpperCase()}</Chip>
          </DetailRow>
        )}

        {channel.processingPhase && (
          <DetailRow label="Phase">
            <Chip variant="outline">{channel.processingPhase}</Chip>
          </DetailRow>
        )}
      </EntityPanelSection>

      {(channel.triggerCount !== null || channel.restrictionCount !== null || channel.contextWindowSize !== null) && (
        <EntityPanelSection title="Configuration Details" collapsible defaultCollapsed>
          {channel.triggerCount !== null && (
            <DetailRow label="Triggers">
              <Chip variant={channel.triggerCount > 0 ? 'green' : 'outline'}>
                {channel.triggerCount}
              </Chip>
            </DetailRow>
          )}

          {channel.restrictionCount !== null && (
            <DetailRow label="Restrictions">
              <Chip variant={channel.restrictionCount > 0 ? 'amber' : 'outline'}>
                {channel.restrictionCount}
              </Chip>
            </DetailRow>
          )}

          {channel.contextWindowSize !== null && (
            <DetailRow label="Context Window">
              <Chip variant="outline">{channel.contextWindowSize}</Chip>
            </DetailRow>
          )}

          {channel.configChannelChatId !== null && (
            <DetailRow label="Config Channel">
              <Chip variant="outline">{channel.configChannelChatId}</Chip>
            </DetailRow>
          )}
        </EntityPanelSection>
      )}
    </EntityPanel>
  )
}
