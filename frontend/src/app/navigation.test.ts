import { describe, expect, it } from 'vitest';
import { MAX_BOTTOM_ITEMS, NAV_ITEMS, navigationFor, splitForMobile } from './navigation';

const labels = (roles: Parameters<typeof navigationFor>[0]) => navigationFor(roles).map((item) => item.label);

describe('navigationFor', () => {
  it('shows a member only member screens', () => {
    expect(labels(['MEMBER'])).toEqual(['Početna', 'Treninzi', 'Statistika', 'Moj profil']);
  });

  it('gives a trainer who is also a member one merged menu without duplicates', () => {
    const items = labels(['MEMBER', 'TRAINER']);
    expect(items).toEqual(['Početna', 'Treninzi', 'Prozivka', 'Statistika', 'Moj profil', 'Članovi', 'Izvještaji']);
    expect(new Set(items).size).toBe(items.length);
  });

  it('adds administration only for admins', () => {
    expect(labels(['TRAINER'])).not.toContain('Administracija');
    expect(labels(['ADMIN'])).toContain('Administracija');
    expect(labels(['ADMIN', 'MEMBER'])).toEqual(NAV_ITEMS.map((item) => item.label));
  });

  it('is independent of role order', () => {
    expect(labels(['TRAINER', 'MEMBER'])).toEqual(labels(['MEMBER', 'TRAINER']));
  });
});

describe('splitForMobile', () => {
  it('keeps at most four items in the bottom bar and puts the rest in "Više"', () => {
    const items = navigationFor(['ADMIN', 'MEMBER']);
    const { bar, more } = splitForMobile(items);

    expect(bar.length).toBeLessThanOrEqual(MAX_BOTTOM_ITEMS);
    expect([...bar, ...more].map((item) => item.to).sort()).toEqual(items.map((item) => item.to).sort());
    expect(more.map((item) => item.label)).toContain('Administracija');
  });
});
