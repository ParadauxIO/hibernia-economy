import { fireEvent, render, screen } from '@testing-library/react';
import { describe, expect, it } from 'vitest';
import { ItemIcon } from '@/components/market/ItemIcon';

describe('ItemIcon', () => {
  it.each([null, '', 'javascript:alert(1)', 'data:image/png;base64,AAAA'])('uses a letter tile for an unavailable or unsafe icon (%s)', (icon) => {
    const { container } = render(<ItemIcon icon={icon} name="diamond" size={48} />);
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('D')).toHaveAttribute('aria-hidden', 'true');
    expect(screen.getByText('D')).toHaveStyle({ width: '48px', height: '48px' });
  });

  it('uses a question mark when neither an icon nor a name is available', () => {
    render(<ItemIcon icon={null} name="" />);
    expect(screen.getByText('?')).toBeInTheDocument();
  });

  it.each(['/items/diamond.png', 'https://example.com/diamond.png', 'http://example.com/diamond.png'])('renders a valid texture (%s)', (icon) => {
    render(<ItemIcon icon={icon} name="diamond" />);
    const image = screen.getByRole('presentation');
    expect(image).toHaveAttribute('src', icon);
    expect(image).toHaveAttribute('alt', '');
    expect(image).toHaveAttribute('loading', 'lazy');
    expect(image).toHaveStyle({ width: '36px', height: '36px' });
  });

  it('falls back on load failure and tries a different icon on the same instance', () => {
    const { rerender, container } = render(<ItemIcon icon="/items/diamond.png" name="diamond" />);
    fireEvent.error(screen.getByRole('presentation'));
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('D')).toBeInTheDocument();

    rerender(<ItemIcon icon="/items/diamond.png" name="diamond block" />);
    expect(container.querySelector('img')).toBeNull();

    rerender(<ItemIcon icon="/items/emerald.png" name="emerald" />);
    expect(screen.getByRole('presentation')).toHaveAttribute('src', '/items/emerald.png');
    expect(screen.queryByText('D')).toBeNull();
    fireEvent.error(screen.getByRole('presentation'));
    expect(container.querySelector('img')).toBeNull();
    expect(screen.getByText('E')).toBeInTheDocument();
  });
});
