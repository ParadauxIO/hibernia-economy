import '@democracycraft/design-system/styles.css';
import './explorer.css';
import './experience.css';
import './shell.css';
import type { Metadata } from 'next';
import Link from 'next/link';
import Script from 'next/script';
import { Suspense } from 'react';
import localFont from 'next/font/local';
import { GeistMono } from 'geist/font/mono';
import { Money } from '@democracycraft/design-system';
import { serverIdentity } from '@/lib/serverIdentity';
import { CURRENCY_SYMBOL } from '@/lib/currency';
import { parseMoney, abs } from '@/lib/money';
import { getViewer, type Viewer } from '@/lib/auth/viewer';
import { chromeViewer, personalBalance } from '@/lib/services/nav';
import { HeaderSearch } from '@/components/HeaderSearch';
import { HeaderNav, HeaderSectionNav } from '@/components/HeaderNav';
import { HeaderMenu } from '@/components/HeaderMenu';
import { HeaderAuth } from '@/components/HeaderAuth';
import { CommandPalette } from '@/components/CommandPalette';
import { ModeToggle } from '@/components/ModeToggle';
import { Footer } from '@/components/Footer';
import { ViewerProvider } from '@/components/ViewerContext';
import { ExplorerAnalytics } from '@/components/ExplorerAnalytics';
import { FaroAnalytics } from '@/components/FaroAnalytics';
import { brand, siteUrl } from '@/lib/metadata';

const dmSans = localFont({
  src: '../public/dm-sans.ttf',
  variable: '--font-dm-sans',
  display: 'swap',
  weight: '100 1000',
});

export async function generateMetadata(): Promise<Metadata> {
  const id = await brand();
  const site = `${id.name} Economy Explorer`;
  const description = `Ledger explorer for the ${id.name} economy — balances, transactions, firms and ChestShop market activity.`;
  const base = siteUrl();
  return {
    ...(base ? { metadataBase: new URL(base) } : {}),
    title: { default: site, template: `%s · ${site}` },
    description,
    applicationName: site,
    icons: { icon: id.icon },
    openGraph: {
      title: site,
      description,
      siteName: site,
      type: 'website',
      images: [{ url: id.icon, alt: `${id.name} emblem` }],
    },
    twitter: { card: 'summary', title: site, description, images: [id.icon] },
    // Whole explorer is noindex (per-page metadata may override, e.g. docs).
    robots: { index: false, follow: false },
  };
}

// Compact above $100k so the chip stays narrow; the exact value is in the tooltip.
const COMPACT_FROM_CENTS = 10_000_000n;

/** The account chip's balance, streamed so the header never waits for it. */
async function PersonalBalance({ viewer }: { viewer: Viewer }) {
  const balance = await personalBalance(viewer);
  if (balance === null) return null;
  return <Money value={balance} compact={abs(parseMoney(balance)) >= COMPACT_FROM_CENTS} />;
}

export default async function RootLayout({ children }: { children: React.ReactNode }) {
  // Every route is dynamic already, so resolving the viewer here costs nothing
  // extra: getViewer() is React-cached, and the page's own call reuses it. The
  // chrome then renders the right nav and account menu in the first HTML, with
  // no /api/viewer round trip.
  //
  // Theme and mode stay env-derived on the server. The `?theme=` / `mode`
  // cookie overrides are applied before first paint by the inline script below
  // and by ModeToggle.
  const viewer = await getViewer();
  const chrome = await chromeViewer(viewer);
  const theme = (process.env.THEME ?? 'blue').toLowerCase().trim() === 'red' ? 'red' : 'blue';
  const mode = 'dark';
  const server = serverIdentity(theme);
  // Umami analytics — one project (website id) per prod instance. Both vars are
  // set per overlay; when unset (e.g. dev), no script is emitted.
  const umamiSrc = process.env.UMAMI_SCRIPT_URL?.trim();
  const umamiId = process.env.UMAMI_WEBSITE_ID?.trim();
  // Grafana Faro RUM — web-vitals + JS errors → Loki, tenant-tagged via app.name.
  // Set per prod overlay (FARO_COLLECTOR_URL); unset in dev → no RUM emitted.
  const faroUrl = process.env.FARO_COLLECTOR_URL?.trim();
  const tenant = process.env.TENANT?.trim() || 'dev';
  const balance = chrome.linked ? <Suspense fallback={<span className="shell-balance-pending" />}><PersonalBalance viewer={viewer} /></Suspense> : undefined;
  return (
    <html
      lang="en"
      data-theme={theme}
      data-mode={mode}
      className={`${dmSans.variable} ${GeistMono.variable}`}
      // The pre-paint script may rewrite data-mode (system → resolved OS pref);
      // suppress the resulting attribute mismatch on hydration.
      suppressHydrationWarning
    >
      <body>
        {/* No-FOUC: when the saved choice is 'system', resolve the OS preference
            and set data-mode before first paint. Plain inline script (not
            next/script) so React 19 doesn't hoist it — that hoisting was the
            source of the old hydration error #418. */}
        <script
          dangerouslySetInnerHTML={{
            __html:
              "(function(){try{var m=document.cookie.match(/(?:^|; )mode=([^;]+)/);var c=m?decodeURIComponent(m[1]):'dark';if(c==='system'){c=window.matchMedia('(prefers-color-scheme: dark)').matches?'dark':'light';}document.documentElement.setAttribute('data-mode',c);var t=document.cookie.match(/(?:^|; )theme=([^;]+)/);if(t){var tv=decodeURIComponent(t[1]).toLowerCase();if(tv==='red'||tv==='blue')document.documentElement.setAttribute('data-theme',tv);}}catch(e){}})();",
          }}
        />
        <a href="#main-content" className="skip-link">Skip to content</a>
        <ViewerProvider viewer={chrome}>
          <header className="shell-header">
            <div className="shell-bar">
              <Link href="/" className="shell-brand" prefetch={false}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img src={server.icon} alt="" width={24} height={24} />
                <span className="shell-brand-name">{server.name}</span>
                <span className="shell-brand-product">Economy</span>
              </Link>
              <HeaderNav />
              <div className="shell-tools">
                <HeaderSearch />
                <ModeToggle className="shell-desktop-only" />
                <HeaderAuth balance={balance} />
                <HeaderMenu />
              </div>
            </div>
            <HeaderSectionNav />
          </header>
          <main id="main-content" className="explorer-main" tabIndex={-1}>{children}</main>
          <CommandPalette serverIp={server.ip} currency={CURRENCY_SYMBOL} />
        </ViewerProvider>
        <Footer server={server} />
        {umamiSrc && umamiId && (
          <>
            <Script src={umamiSrc} data-website-id={umamiId} strategy="afterInteractive" />
            <ExplorerAnalytics />
          </>
        )}
        {faroUrl && <FaroAnalytics appName={`economy-explorer-${tenant}`} url={faroUrl} />}
      </body>
    </html>
  );
}
