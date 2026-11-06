import { afterEach, expect, it, vi } from 'vitest';
import { render, screen, waitFor } from '@testing-library/react';
import { DocTopNote } from '@/components/docs/DocTopNote';

afterEach(() => vi.unstubAllGlobals());

it('renders plain text and the configured link only on the exact page, including navigation', async () => {
  vi.stubGlobal('fetch', vi.fn().mockResolvedValue({ ok: true, json: async () => ({
    '/docs/guides/api': { title: 'Licence required', text: '<img src=x onerror=alert(1)>', link: { label: 'Licence guide', href: 'https://example.com/licence' } },
  }) }));
  const { container, rerender } = render(<DocTopNote path="/docs/guides/api" />);
  expect(await screen.findByRole('complementary', { name: 'Licence required' })).toBeVisible();
  expect(screen.getByText('<img src=x onerror=alert(1)>')).toBeVisible();
  expect(container.querySelector('img')).toBeNull();
  expect(screen.getByRole('link', { name: 'Licence guide' })).toHaveAttribute('href', 'https://example.com/licence');
  rerender(<DocTopNote path="/docs/guides" />);
  expect(screen.queryByRole('complementary')).toBeNull();
  rerender(<DocTopNote path="/docs/guides/api" />);
  expect(screen.getByRole('complementary')).toBeVisible();
});

it.each([{}, null])('leaves the guide without a note when config is absent or unavailable (%s)', async (data) => {
  const fetcher = vi.fn().mockResolvedValue({ ok: data !== null, json: async () => data });
  vi.stubGlobal('fetch', fetcher);
  const { container } = render(<DocTopNote path="/docs/guides/api" />);
  await waitFor(() => expect(fetcher).toHaveBeenCalled());
  expect(container).toBeEmptyDOMElement();
});
