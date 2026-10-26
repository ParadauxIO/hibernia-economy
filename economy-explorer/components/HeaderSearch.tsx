'use client';
// The search affordance in the top bar. It looks like a field but is a button
// that opens the command palette (components/CommandPalette), which owns the
// typeahead, keyboard handling and results.

import { useSyncExternalStore } from 'react';
import { openPalette, usePaletteOpen } from '@/components/CommandPaletteStore';
import { SearchIcon } from '@/components/HeaderIcons';

const isMac = () => /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);

/** "⌘K" on Apple devices, "Ctrl K" elsewhere. The server renders "Ctrl K" and the client corrects it after hydration. */
function useShortcutLabel(): string {
  return useSyncExternalStore(() => () => {}, () => (isMac() ? '⌘K' : 'Ctrl K'), () => 'Ctrl K');
}

export function HeaderSearch() {
  const open = usePaletteOpen();
  const shortcut = useShortcutLabel();
  return (
    <button
      type="button"
      className="shell-search"
      aria-haspopup="dialog"
      aria-expanded={open}
      aria-keyshortcuts="Meta+K Control+K"
      onClick={openPalette}
    >
      <SearchIcon />
      <span className="shell-search-label">Search</span>
      <kbd className="shell-kbd" aria-hidden="true">{shortcut}</kbd>
    </button>
  );
}
