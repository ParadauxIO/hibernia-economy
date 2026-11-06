import { afterEach, describe, expect, it, vi } from 'vitest';
import { parseDocNotes } from '@/lib/docsNotes';
import { GET } from '@/app/api/docs/notes/route';

const note = { title: 'Licence required', text: 'Visit the university.', link: { label: 'Guide', href: 'https://example.com/licence' } };
afterEach(() => { vi.unstubAllEnvs(); vi.restoreAllMocks(); });

describe('tenant docs notes', () => {
  it('has no defaults and preserves exact page keys', () => {
    expect(parseDocNotes(undefined)).toEqual({});
    expect(parseDocNotes(' ')).toEqual({});
    const notes = parseDocNotes(JSON.stringify({ '/docs/guides/api': note, '/docs': note }));
    expect(notes['/docs/guides/api']).toEqual(note);
    expect(notes['/docs']).toEqual(note);
    expect(notes['/docs/guides']).toBeUndefined();
  });

  it.each(['/docs/admin', '/docs/admin/operator-runbook', '/docs/guides/api/', '/docs/../api', '/other', '/docs?x=1'])('rejects non-public or non-canonical key %s', (path) => {
    expect(() => parseDocNotes(JSON.stringify({ [path]: note }))).toThrow();
  });

  it.each(['javascript:alert(1)', 'data:text/html,hello', '//example.com', '/docs'])('rejects unsafe or non-absolute link %s', (href) => {
    expect(() => parseDocNotes(JSON.stringify({ '/docs': { ...note, link: { ...note.link, href } } }))).toThrow();
  });

  it.each(['{', 'null', '[]', '{"/docs":{"title":"","text":"x"}}', '{"/docs":{"title":"x","text":"y","html":"<b>x</b>"}}'])('rejects invalid configuration %s', (raw) => {
    expect(() => parseDocNotes(raw)).toThrow();
  });

  it('reads runtime configuration and never caches tenant content', async () => {
    vi.stubEnv('DOCS_TOP_NOTES', JSON.stringify({ '/docs/guides/api': note }));
    const configured = await GET();
    expect(configured.headers.get('cache-control')).toBe('private, no-store');
    expect(await configured.json()).toEqual({ '/docs/guides/api': note });
    vi.stubEnv('DOCS_TOP_NOTES', '');
    expect(await (await GET()).json()).toEqual({});
  });

  it('reports invalid config without exposing its content', async () => {
    vi.stubEnv('DOCS_TOP_NOTES', 'private bad configuration');
    const log = vi.spyOn(console, 'error').mockImplementation(() => {});
    const response = await GET();
    expect(response.status).toBe(503);
    expect(await response.json()).toEqual({ error: 'unavailable' });
    expect(log).toHaveBeenCalledWith('Invalid DOCS_TOP_NOTES configuration');
  });
});
