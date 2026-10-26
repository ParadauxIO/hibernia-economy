import { ImageResponse } from 'next/og';

// The link-unfurl card for entity pages (spec §9.5): Discord and other chats
// show it when an account, player or firm link is pasted. Only public facts go
// on it: name, type, server brand, and a balance only where the page shows one
// to a logged-out visitor. Rendered without remote images so it never depends
// on a third party being up.

export const OG_SIZE = { width: 1200, height: 630 };

// Fixed per brand: an image can't read the CSS tokens. Chosen to match the
// dark theme's surface, text and accent for each server.
const PALETTE = {
  blue: { bg: '#0d1117', panel: '#161b22', fg: '#e6edf3', muted: '#8b949e', accent: '#4c8dff' },
  red: { bg: '#130d0d', panel: '#1d1414', fg: '#f3e6e6', muted: '#a39090', accent: '#e5484d' },
} as const;

export interface OgCardProps {
  theme: 'blue' | 'red';
  server: string;
  /** "Business account · #1204" */
  eyebrow: string;
  title: string;
  subtitle?: string | null;
  /** A public figure: "$84,210.55", with its label. */
  figure?: { label: string; value: string } | null;
}

export function ogCard({ theme, server, eyebrow, title, subtitle, figure }: OgCardProps): ImageResponse {
  const c = PALETTE[theme];
  return new ImageResponse(
    <div style={{ width: '100%', height: '100%', display: 'flex', flexDirection: 'column', justifyContent: 'space-between', background: c.bg, color: c.fg, padding: 72, fontFamily: 'sans-serif' }}>
      <div style={{ display: 'flex', alignItems: 'center', gap: 16, fontSize: 30, color: c.muted }}>
        <div style={{ width: 18, height: 18, borderRadius: 9, background: c.accent }} />
        <span style={{ color: c.fg, fontWeight: 600 }}>{server}</span>
        <span>Economy Explorer</span>
      </div>
      <div style={{ display: 'flex', flexDirection: 'column', gap: 18 }}>
        <div style={{ fontSize: 32, color: c.accent }}>{eyebrow}</div>
        <div style={{ fontSize: title.length > 28 ? 64 : 84, fontWeight: 700, lineHeight: 1.05, letterSpacing: -1 }}>{title}</div>
        {subtitle ? <div style={{ fontSize: 34, color: c.muted }}>{subtitle}</div> : null}
      </div>
      <div style={{ display: 'flex', alignItems: 'flex-end', justifyContent: 'space-between' }}>
        {figure ? (
          <div style={{ display: 'flex', flexDirection: 'column', gap: 6, background: c.panel, borderRadius: 16, padding: '20px 28px' }}>
            <span style={{ fontSize: 26, color: c.muted }}>{figure.label}</span>
            <span style={{ fontSize: 52, fontWeight: 700 }}>{figure.value}</span>
          </div>
        ) : <div style={{ display: 'flex' }} />}
      </div>
    </div>,
    OG_SIZE,
  );
}
