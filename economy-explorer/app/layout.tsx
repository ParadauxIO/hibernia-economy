import '@democracycraft/design-system/styles.css';
import './explorer.css';
import './experience.css';
import type { Metadata } from 'next';
import Link from 'next/link';
import Script from 'next/script';
import { GeistSans } from 'geist/font/sans';
import { GeistMono } from 'geist/font/mono';
import { serverIdentity } from '@/lib/serverIdentity';
import { HeaderSearch } from '@/components/HeaderSearch';
import { HeaderNav } from '@/components/HeaderNav';
import { HeaderAuth } from '@/components/HeaderAuth';
import { ModeToggle } from '@/components/ModeToggle';
import { Footer } from '@/components/Footer';
import { ViewerProvider } from '@/components/ViewerContext';
import { FaroAnalytics } from '@/components/FaroAnalytics';
import { brand, siteUrl } from '@/lib/metadata';

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

export default function RootLayout({ children }: { children: React.ReactNode }) {
  // Theme/mode are derived from env (static, per-deployment) so the root layout
  // reads NO cookies and resolves NO session — which is what lets it render
  // statically and keeps getViewer() off the universal layout path. The
  // `?theme=` / `mode` cookie overrides are applied client-side by the pre-paint
  // script below (theme + mode) and the ModeToggle. Net effect: /docs and other
  // static-eligible routes serve as static HTML, and authenticated pages no
  // longer pay an auth round-trip in the layout on every request.
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
  return (
    <html
      lang="en"
      data-theme={theme}
      data-mode={mode}
      className={`${GeistSans.variable} ${GeistMono.variable}`}
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
        <div className="brand-stripe" aria-hidden />
        <ViewerProvider>
          <header className="explorer-header">
            <div className="explorer-header-top">
              <Link href="/" className="explorer-logo" prefetch={false}>
                {/* eslint-disable-next-line @next/next/no-img-element */}
                <img className="mark" src={server.icon} alt={`${server.name} emblem`} width={34} height={34} />
                <span className="explorer-logo-text">Economy <span className="explorer-logo-accent">Explorer</span></span>
              </Link>
              <div className="explorer-spacer"></div>
              <HeaderSearch />
              <ModeToggle />
              <HeaderAuth />
            </div>
            <div className="explorer-header-nav">
              <HeaderNav />
            </div>
          </header>
          <main id="main-content" className="explorer-main" tabIndex={-1}>{children}</main>
        </ViewerProvider>
        <Footer theme={theme} />
        {umamiSrc && umamiId && (
          <Script src={umamiSrc} data-website-id={umamiId} strategy="afterInteractive" />
        )}
        {faroUrl && <FaroAnalytics appName={`economy-explorer-${tenant}`} url={faroUrl} />}
      </body>
    </html>
  );
}
