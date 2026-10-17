import { describe, it, expect } from 'vitest';
import { isDiscordWebhookUrl } from '@/lib/util/discord';

describe('isDiscordWebhookUrl — allowlisted hosts + webhook path', () => {
  it('accepts every allowlisted host', () => {
    expect(isDiscordWebhookUrl('https://discord.com/api/webhooks/123/abcDEF-token')).toBe(true);
    expect(isDiscordWebhookUrl('https://discordapp.com/api/webhooks/123/tok')).toBe(true);
    expect(isDiscordWebhookUrl('https://canary.discord.com/api/webhooks/123/tok')).toBe(true);
    expect(isDiscordWebhookUrl('https://ptb.discord.com/api/webhooks/123/tok')).toBe(true);
  });

  it('accepts a versioned api path (/api/v10/webhooks/...)', () => {
    expect(isDiscordWebhookUrl('https://discord.com/api/v10/webhooks/123456/tok.en')).toBe(true);
    expect(isDiscordWebhookUrl('https://discord.com/api/v9/webhooks/1/t')).toBe(true);
  });

  it('is host case-insensitive but requires the host to be allowlisted', () => {
    expect(isDiscordWebhookUrl('https://DISCORD.COM/api/webhooks/123/tok')).toBe(true);
  });
});

describe('isDiscordWebhookUrl — rejections', () => {
  it('rejects non-allowlisted hosts (including look-alikes)', () => {
    expect(isDiscordWebhookUrl('https://evil.com/api/webhooks/123/tok')).toBe(false);
    expect(isDiscordWebhookUrl('https://discord.com.evil.com/api/webhooks/123/tok')).toBe(false);
    expect(isDiscordWebhookUrl('https://notdiscord.com/api/webhooks/123/tok')).toBe(false);
  });

  it('rejects wrong paths on an allowlisted host', () => {
    expect(isDiscordWebhookUrl('https://discord.com/api/webhooks/123')).toBe(false); // no token segment
    expect(isDiscordWebhookUrl('https://discord.com/api/webhooks/')).toBe(false);
    expect(isDiscordWebhookUrl('https://discord.com/webhooks/123/tok')).toBe(false); // missing /api
    expect(isDiscordWebhookUrl('https://discord.com/api/webhooks/abc/tok')).toBe(false); // non-numeric id
    expect(isDiscordWebhookUrl('https://discord.com/')).toBe(false);
  });

  it('rejects malformed / non-URL input', () => {
    expect(isDiscordWebhookUrl('not a url')).toBe(false);
    expect(isDiscordWebhookUrl('')).toBe(false);
  });
});
