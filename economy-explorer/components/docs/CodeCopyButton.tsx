'use client';
// Progressive enhancement: decorate every doc code block with a hover
// copy-to-clipboard button. The content itself stays static server HTML — this
// just appends a button on the client. Re-runs on navigation (keyed on path)
// since the docs layout persists across page changes.

import { usePathname } from 'next/navigation';
import { useEffect } from 'react';

export function CodeCopyButton() {
  const path = usePathname();
  useEffect(() => {
    const blocks = Array.from(document.querySelectorAll<HTMLElement>('.doc-prose pre'));
    const added: HTMLButtonElement[] = [];
    for (const pre of blocks) {
      if (pre.querySelector('.doc-copy')) continue;
      pre.style.position = 'relative';
      const btn = document.createElement('button');
      btn.type = 'button';
      btn.className = 'doc-copy';
      btn.textContent = 'Copy';
      btn.addEventListener('click', () => {
        const text = pre.querySelector('code')?.textContent ?? pre.textContent;
        // navigator.clipboard is absent outside secure contexts (plain-http hosts).
        if (!('clipboard' in navigator)) return;
        navigator.clipboard.writeText(text).then(
          () => {
            btn.textContent = 'Copied';
            window.setTimeout(() => (btn.textContent = 'Copy'), 1500);
          },
          () => undefined, // permission denied: leave the button as it was
        );
      });
      pre.appendChild(btn);
      added.push(btn);
    }
    return () => added.forEach((b) => b.remove());
  }, [path]);
  return null;
}
