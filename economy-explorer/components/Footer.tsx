import { CopyServer } from '@/components/CopyServer';
import type { ServerIdentity } from '@/lib/serverIdentity';
import { peekServerStatus } from '@/lib/serverStatus';

// Server-rendered footer. The player count comes from this pod's last
// server-list answer (lib/serverStatus peekServerStatus), so the footer never
// waits on the third-party API and can't hold the document open.
export function Footer({ server }: { server: ServerIdentity }) {
  const { name, ip, icon } = server;
  const year = process.env.BUILD_YEAR ?? '';
  const status = peekServerStatus(ip);
  const players = status?.online ? status.players : null;

  return (
    <footer className="shell-footer">
      <div className="shell-footer-inner">
        <div className="shell-footer-brand">
          {/* eslint-disable-next-line @next/next/no-img-element */}
          <img src={icon} alt="" width={20} height={20} />
          <span>{name} <span className="shell-footer-product">Economy Explorer</span></span>
        </div>

        <div className="shell-footer-play">
          {players != null && (
            <span className="shell-footer-online" title={`${players}${status?.max != null ? ` / ${status.max}` : ''} players online`}>
              <span className="live-dot" aria-hidden="true" />
              {players.toLocaleString('en-US')} online
            </span>
          )}
          <span className="shell-footer-play-label">Play at</span>
          <CopyServer ip={ip} />
        </div>

        <p className="shell-footer-meta">
          Developed with <span aria-hidden="true">❤️</span><span className="sr-only">love</span> by{' '}
          <a href="https://paradaux.io?ref=economy-explorer" target="_blank" rel="noopener noreferrer">Paradaux</a>. Part of
          the Minecraft Cities Network. Contains assets and references to trademarks belonging to
          Mojang Studios.{year ? ` © ${year}` : ''}
        </p>
      </div>
    </footer>
  );
}
