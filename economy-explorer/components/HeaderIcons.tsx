// Stroke icons for the chrome. Decorative: every control that uses one has a text label.
import type { SVGProps } from 'react';

function Icon({ size = 16, children, ...rest }: SVGProps<SVGSVGElement> & { size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth={1.75} strokeLinecap="round" strokeLinejoin="round" aria-hidden="true" focusable="false" {...rest}>
      {children}
    </svg>
  );
}

export function SearchIcon(props: { size?: number }) {
  return <Icon {...props}><circle cx="11" cy="11" r="6.5" /><path d="m20 20-4.2-4.2" /></Icon>;
}

export function ChevronDownIcon(props: { size?: number }) {
  return <Icon {...props}><path d="m6 9 6 6 6-6" /></Icon>;
}

export function MenuIcon(props: { size?: number }) {
  return <Icon {...props}><path d="M4 7h16M4 12h16M4 17h16" /></Icon>;
}

export function CloseIcon(props: { size?: number }) {
  return <Icon {...props}><path d="M6 6l12 12M18 6 6 18" /></Icon>;
}

export function ClockIcon(props: { size?: number }) {
  return <Icon {...props}><circle cx="12" cy="12" r="8.5" /><path d="M12 7.5V12l3 2" /></Icon>;
}
