import type { ReactNode } from 'react'

interface Props {
  title: string
  description?: string
  accent?: string
  actions?: ReactNode
  children: ReactNode
}

export function Section({ title, description, accent = '', actions, children }: Props) {
  return (
    <section className="section">
      <header className="section__header">
        <div>
          <p className="section__eyebrow">{accent}</p>
          <h3>{title}</h3>
          {description ? <p className="muted">{description}</p> : null}
        </div>
        {actions}
      </header>
      <div className="section__body">{children}</div>
    </section>
  )
}
