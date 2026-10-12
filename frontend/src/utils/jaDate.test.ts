import { describe, expect, it } from 'vitest';
import { jaToday, validDate } from './jaDate';

describe('日本時間の日付', () => {
  it.each([
    ['2026-10-11T14:59:59Z', '2026-10-11'],
    ['2026-10-11T15:00:00Z', '2026-10-12'],
  ])('[DATE-06] %sを日本時間の%sと判定する', (instant, expected) => {
    expect(jaToday(new Date(instant))).toBe(expected);
  });
  it.each([
    ['', false],
    ['2026-02-29', false],
    ['2028-02-29', true],
  ] as const)('[DATE-03] 日付 %s の妥当性は%s', (value, expected) => {
    expect(validDate(value)).toBe(expected);
  });
});
