// Generic placeholder for pages whose SQL/visuals haven't been ported yet.
// Used during Phase 2/2.5 — every nav target gets a 200 with a clear message.
import { BackLink } from '@democracycraft/design-system/next';

interface ComingSoonProps {
  title: string;
  description: string;
  backHref?: string;
  backLabel?: string;
}

export function ComingSoon({ title, description, backHref, backLabel }: ComingSoonProps) {
  return (
    <>
      {backHref && backLabel && <BackLink href={backHref} label={backLabel} />}
      <div className="page-heading">
        <h1>{title}</h1>
        <span className="sub">port in progress</span>
      </div>
      <div className="card">
        <div className="card-title">Coming soon</div>
        <p style={{ padding: '4px 4px 8px', color: 'var(--fg-soft)', maxWidth: 640 }}>{description}</p>
      </div>
    </>
  );
}
