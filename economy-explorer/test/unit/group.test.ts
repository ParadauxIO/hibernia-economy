import { describe, it, expect, vi, beforeEach } from 'vitest';

// group.ts (the service) imports the pure DAL and lib/db. Mock the DAL so
// resolvePlayerUuid's name-fallback branch is observable without a database,
// and stub lib/db (only touched by the transaction path we don't exercise here).
const findPlayerUuidByName = vi.fn<(name: string) => Promise<string | null>>();
vi.mock('@/lib/sql/group', () => ({
  findPlayerUuidByName: (name: string) => findPlayerUuidByName(name),
  // service re-exports / pass-throughs it imports at module load:
  listGroups: vi.fn(),
  selectGroupById: vi.fn(),
  createGroup: vi.fn(),
  deleteGroup: vi.fn(),
  deleteGroupCapabilities: vi.fn(),
  insertGroupCapability: vi.fn(),
  setLuckpermsNode: vi.fn(),
  listGroupMembers: vi.fn(),
  addManualMember: vi.fn(),
  removeManualMember: vi.fn(),
}));
vi.mock('@/lib/db', () => ({ db: {} }));

import { resolvePlayerUuid } from '@/lib/services/group';

const UUID = '25a9c52a-6af0-45d8-a52d-e4d5cfcbbab5';

beforeEach(() => findPlayerUuidByName.mockReset());

describe('resolvePlayerUuid — canonical UUID input', () => {
  it('accepts a UUID verbatim, lower-cased, without a name lookup', async () => {
    await expect(resolvePlayerUuid(UUID)).resolves.toBe(UUID);
    expect(findPlayerUuidByName).not.toHaveBeenCalled();
  });

  it('lower-cases an upper-case UUID', async () => {
    await expect(resolvePlayerUuid(UUID.toUpperCase())).resolves.toBe(UUID);
    expect(findPlayerUuidByName).not.toHaveBeenCalled();
  });

  it('trims surrounding whitespace before matching the UUID shape', async () => {
    await expect(resolvePlayerUuid(`  ${UUID}  `)).resolves.toBe(UUID);
    expect(findPlayerUuidByName).not.toHaveBeenCalled();
  });
});

describe('resolvePlayerUuid — name fallback', () => {
  it('looks up a non-UUID identifier by (trimmed) name', async () => {
    findPlayerUuidByName.mockResolvedValue(UUID);
    await expect(resolvePlayerUuid('  Paradaux  ')).resolves.toBe(UUID);
    expect(findPlayerUuidByName).toHaveBeenCalledWith('Paradaux');
  });

  it('returns null when the name resolves to nothing', async () => {
    findPlayerUuidByName.mockResolvedValue(null);
    await expect(resolvePlayerUuid('NoSuchPlayer')).resolves.toBeNull();
    expect(findPlayerUuidByName).toHaveBeenCalledWith('NoSuchPlayer');
  });

  it('treats a UUID-shaped-but-partial string as a name (falls through to lookup)', async () => {
    findPlayerUuidByName.mockResolvedValue(null);
    await expect(resolvePlayerUuid('25a9c52a')).resolves.toBeNull();
    expect(findPlayerUuidByName).toHaveBeenCalledWith('25a9c52a');
  });
});
