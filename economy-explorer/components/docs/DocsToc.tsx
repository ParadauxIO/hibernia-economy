'use client';
// Right-rail "On this page" nav. Highlights the heading currently in view via
// IntersectionObserver (scrollspy). Always renders the column (even when empty)
// so the 3-column grid stays stable.

import { useEffect, useState } from 'react';
import type { DocHeading } from '@/lib/docs';

export function DocsToc({ headings }: { headings: DocHeading[] }) {
  const [active, setActive] = useState('');

  useEffect(() => {
    if (headings.length < 2) return;
    const observer = new IntersectionObserver(
      (entries) => {
        const first = entries.find((e) => e.isIntersecting);
        if (first) setActive((first.target as HTMLElement).id);
      },
      // Fire as a heading nears the top of the viewport.
      { rootMargin: '0px 0px -70% 0px', threshold: 0 },
    );
    headings.forEach((h) => {
      const el = document.getElementById(h.id);
      if (el) observer.observe(el);
    });
    return () => observer.disconnect();
  }, [headings]);

  if (headings.length < 2) return <aside className="docs-toc" aria-hidden />;

  return (
    <aside className="docs-toc">
      <div className="docs-toc-title">On this page</div>
      <nav aria-label="On this page">
        <ul>
          {headings.map((h) => (
            <li key={h.id} className={`toc-d${h.depth}${active === h.id ? ' active' : ''}`}>
              <a href={`#${h.id}`}>{h.text}</a>
            </li>
          ))}
        </ul>
      </nav>
    </aside>
  );
}
