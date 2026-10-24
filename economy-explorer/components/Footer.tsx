import { CopyServer } from '@/components/CopyServer';
import { serverIdentity } from '@/lib/serverIdentity';
import { serverStatus } from '@/lib/serverStatus';
import { Suspense } from 'react';

// Server-rendered footer. Server name / address / emblem come from the
// serverIdentity helper (env overrides with theme-derived defaults).
export function Footer({ theme }: { theme: 'blue' | 'red' }) {
  const { name, ip, icon } = serverIdentity(theme);
  const year = process.env.BUILD_YEAR ?? '';

  return (
    <footer className="explorer-footer">
      <div className="explorer-footer-inner">
        <div className="footer-brand">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img className="footer-mark" src={icon} alt={`${name} emblem`} width={22} height={22} />
          <span>
            <strong>Economy Explorer</strong>
            <span className="footer-sub"> · {name}</span>
          </span>
        </div>

        <div className="footer-join">
          <Suspense fallback={null}><OnlinePlayers ip={ip} /></Suspense>
          <span className="footer-join-label">Play now</span>
          <CopyServer ip={ip} />
        </div>

        <div className="footer-meta">
          Developed with <span aria-hidden="true" className="footer-heart">❤️</span> by{' '}
          <a href="https://paradaux.io?ref=economy-explorer" target="_blank" rel="noopener noreferrer">Paradaux</a>. Part of
          the Minecraft Cities Network — contains assets and references to trademarks belonging to
          Mojang Studios.{year ? ` · ${year}` : ''}
        </div>
      </div>
    </footer>
  );
}

// An external server-list API must never block the root layout or navigation.
async function OnlinePlayers({ ip }: { ip: string }) {
  const status = await serverStatus(ip);
  if (!status.online || status.players == null) return null;
  return (
    <span className="footer-online" title={`${status.players}${status.max != null ? ` / ${status.max}` : ''} players online`}>
      <span className="online-dot" />
      {status.players.toLocaleString()} online
    </span>
  );
}
