import { render, screen } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, expect, it } from 'vitest';
import { InfoTip } from '@democracycraft/design-system';

describe('InfoTip', () => {
  it('opens on keyboard focus, dismisses with Escape and retains focus', async () => {
    const user = userEvent.setup();
    render(<InfoTip label="About stock">Stock can change after observation.</InfoTip>);
    expect(screen.queryByRole('tooltip')).toBeNull();
    await user.tab();
    expect(screen.getByRole('tooltip')).toHaveTextContent('Stock can change after observation.');
    expect(screen.getByRole('button', { name: 'About stock' })).toHaveAccessibleDescription('Stock can change after observation.');
    await user.keyboard('{Escape}');
    expect(screen.queryByRole('tooltip')).toBeNull();
    expect(screen.getByRole('button')).toHaveFocus();
  });

  it('opens by tapping and closes when focus moves away', async () => {
    const user = userEvent.setup();
    render(<><InfoTip>Helpful context</InfoTip><button>Continue</button></>);
    await user.click(screen.getByRole('button', { name: 'More information' }));
    expect(screen.getByRole('tooltip')).toBeVisible();
    await user.click(screen.getByRole('button', { name: 'Continue' }));
    expect(screen.queryByRole('tooltip')).toBeNull();
  });
});
