'use client';

import { Select, ToolbarLayout } from '@democracycraft/design-system';
import { useHydrated } from '@democracycraft/design-system/use-hydrated';
import { useEffect } from 'react';

/** Preview only: changes the document without changing the user's saved preference. */
export function ThemePreview() {
  const ready = useHydrated();
  useEffect(() => {
    const root = document.documentElement;
    const mode = root.getAttribute('data-mode');
    const theme = root.getAttribute('data-theme');
    return () => {
      if (mode === null) root.removeAttribute('data-mode');
      else root.setAttribute('data-mode', mode);
      if (theme === null) root.removeAttribute('data-theme');
      else root.setAttribute('data-theme', theme);
    };
  }, []);
  return <ToolbarLayout search={<label>Appearance{' '}<Select aria-label="Preview appearance" disabled={!ready} defaultValue="" onChange={event => { document.documentElement.dataset.mode = event.target.value; }}><option value="" disabled>Current appearance</option><option value="dark">Dark</option><option value="light">Light</option></Select></label>} filters={<label>Brand{' '}<Select aria-label="Preview brand" disabled={!ready} defaultValue="" onChange={event => { document.documentElement.dataset.theme = event.target.value; }}><option value="" disabled>Current brand</option><option value="blue">DemocracyCraft</option><option value="red">StateCraft</option></Select></label>} />;
}
