import { render, screen, waitFor, within } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { beforeEach, describe, expect, it, vi } from 'vitest';
import { CreateWebhookForm } from '@/app/me/developer/webhooks/create-webhook-form';
import { createWebhookAction, type WebhookActionResult } from '@/lib/actions/webhooks';

vi.mock('@/lib/actions/webhooks', () => ({ createWebhookAction: vi.fn() }));

const FIRMS = [
  { firmId: 12, displayName: 'Acme' },
  { firmId: 40, displayName: 'Globex' },
];
const URL_OK = 'https://hooks.example.com/treasury';
const DISCORD = 'https://discord.com/api/webhooks/123456/abcDEF';

// No visible label yet: the scope select is the only combobox, the URL box is found by placeholder.
function scope(): HTMLSelectElement {
  const el = screen.getByRole('combobox');
  if (!(el instanceof HTMLSelectElement)) throw new Error('scope is not a <select>');
  return el;
}

function urlInput(): HTMLElement {
  return screen.getByPlaceholderText(/your-endpoint/);
}

function submit(): HTMLElement {
  return screen.getByRole('button', { name: /create webhook|creating/i });
}

beforeEach(() => {
  vi.mocked(createWebhookAction).mockReset().mockResolvedValue({ ok: true, secret: 'whsec_abc123' });
});

describe('CreateWebhookForm scope options', () => {
  it('lists the personal account first, then each firm, defaulting to personal', () => {
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    expect(within(scope()).getAllByRole('option').map((o) => o.textContent)).toEqual([
      'My personal account',
      'Firm — Acme',
      'Firm — Globex',
    ]);
    expect(scope()).toHaveValue('account');
  });

  it('defaults to the first firm when there is no personal account', () => {
    render(<CreateWebhookForm hasPersonal={false} firms={FIRMS} />);
    expect(scope()).toHaveValue('firm:12');
  });

  it('can never submit with no scope available', async () => {
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal={false} firms={[]} />);
    expect(within(scope()).queryAllByRole('option')).toHaveLength(0);
    await user.type(urlInput(), URL_OK);
    expect(submit()).toBeDisabled();
  });
});

describe('CreateWebhookForm submission', () => {
  it('requires a URL', async () => {
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    expect(urlInput()).toBeRequired();
    expect(submit()).toBeDisabled();
    await user.type(urlInput(), '   ');
    expect(submit()).toBeDisabled();
  });

  it('lets the browser reject a malformed URL before calling the server', async () => {
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    await user.type(urlInput(), 'not a url');
    expect(urlInput()).toBeInvalid();
    await user.click(submit());
    expect(createWebhookAction).not.toHaveBeenCalled();
  });

  it('creates a personal-account webhook', async () => {
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    await user.type(urlInput(), URL_OK);
    await user.click(submit());
    expect(createWebhookAction).toHaveBeenCalledExactlyOnceWith({ scope: 'account', url: URL_OK });
  });

  it('creates a firm webhook with a numeric firm id', async () => {
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    await user.selectOptions(scope(), 'Firm — Globex');
    await user.type(urlInput(), URL_OK);
    await user.click(submit());
    expect(createWebhookAction).toHaveBeenCalledExactlyOnceWith({ scope: 'firm', firmId: 40, url: URL_OK });
  });

  it('shows the signing secret once, clears the URL, and replaces the secret on the next create', async () => {
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    await user.type(urlInput(), URL_OK);
    await user.click(submit());
    expect(await screen.findByText('whsec_abc123')).toBeInTheDocument();
    expect(screen.getByText('shown once — copy it now')).toBeInTheDocument();
    expect(urlInput()).toHaveValue('');

    let resolve: (r: WebhookActionResult) => void = () => {};
    vi.mocked(createWebhookAction).mockReturnValue(new Promise((r) => { resolve = r; }));
    await user.type(urlInput(), URL_OK);
    await user.click(submit());
    // The old secret is hidden as soon as a new create starts.
    expect(screen.queryByText('whsec_abc123')).toBeNull();
    expect(submit()).toHaveTextContent('Creating…');
    expect(submit()).toBeDisabled();
    resolve({ ok: true, secret: 'whsec_next' });
    expect(await screen.findByText('whsec_next')).toBeInTheDocument();
  });

  it('shows the server error and keeps the URL for correction', async () => {
    vi.mocked(createWebhookAction).mockResolvedValue({ ok: false, error: 'URL must resolve to a public address.' });
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    await user.type(urlInput(), URL_OK);
    await user.click(submit());
    expect(await screen.findByText('URL must resolve to a public address.')).toHaveClass('state-error');
    expect(urlInput()).toHaveValue(URL_OK);
    expect(screen.queryByText(/Signing secret/)).toBeNull();
  });

  it('falls back to a generic error message', async () => {
    vi.mocked(createWebhookAction).mockResolvedValue({ ok: false });
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    await user.type(urlInput(), URL_OK);
    await user.click(submit());
    expect(await screen.findByText('Failed')).toBeInTheDocument();
    await waitFor(() => expect(submit()).toBeEnabled());
  });
});

describe('CreateWebhookForm Discord hint', () => {
  it.each([
    [DISCORD, true],
    ['https://discordapp.com/api/v10/webhooks/1/tok', true],
    ['https://discord.com/channels/1/2', false],
    ['https://evil.example/api/webhooks/1/tok', false],
    [URL_OK, false],
  ])('for %s shows the rich-embed hint: %s', async (url, shown) => {
    const user = userEvent.setup();
    render(<CreateWebhookForm hasPersonal firms={FIRMS} />);
    await user.type(urlInput(), url);
    expect(screen.queryByText(/Looks like a Discord webhook/) !== null).toBe(shown);
  });
});
