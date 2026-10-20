import type { ComponentProps, ReactNode } from 'react';

/** Native attributes (including ref, form and ARIA props) pass through. */
export function Button({ variant = 'default', type = 'button', className = '', ...props }: ComponentProps<'button'> & { variant?: 'default' | 'primary' }) {
  return <button type={type} className={`btn${variant === 'primary' ? ' btn-primary' : ''} ${className}`.trim()} {...props} />;
}

export function Input({ className = '', ...props }: ComponentProps<'input'>) {
  return <input className={`input ${className}`.trim()} {...props} />;
}

export function Select({ className = '', ...props }: ComponentProps<'select'>) {
  return <select className={`select ${className}`.trim()} {...props} />;
}

/** The host supplies search/filter behavior; this component only arranges it. */
export function ToolbarLayout({ search, filters, actions }: { search: ReactNode; filters?: ReactNode; actions?: ReactNode }) {
  return <div className="toolbar">
    {search}
    <div className="toolbar-filters">{filters}</div>
    <div className="toolbar-right">{actions}</div>
  </div>;
}
