import type { ReactionStatus } from '../../types/reaction'
import { REACTION_STATUS_MAP } from '../../types/reaction'

interface Props {
  status: ReactionStatus
}

export function ReactionStatusBadge({ status }: Props) {
  const meta = REACTION_STATUS_MAP[status]
  return <span className={`chip ${meta.chipClass} tiny`}>{meta.label}</span>
}
