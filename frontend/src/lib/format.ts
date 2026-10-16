/** Club time zone (spec: Europe/Zagreb). All instants from the API are UTC and are displayed in this zone. */
export const CLUB_TIME_ZONE = 'Europe/Zagreb';

const dateTimeFormat = new Intl.DateTimeFormat('hr-HR', {
  dateStyle: 'medium',
  timeStyle: 'short',
  timeZone: CLUB_TIME_ZONE,
});

export const formatDateTime = (iso: string) => dateTimeFormat.format(new Date(iso));

const longDateFormat = new Intl.DateTimeFormat('hr-HR', {
  weekday: 'long',
  day: 'numeric',
  month: 'long',
  year: 'numeric',
  timeZone: CLUB_TIME_ZONE,
});
const timeFormat = new Intl.DateTimeFormat('hr-HR', {
  hour: '2-digit',
  minute: '2-digit',
  hourCycle: 'h23',
  timeZone: CLUB_TIME_ZONE,
});
const partsFormat = new Intl.DateTimeFormat('en-CA', {
  year: 'numeric',
  month: '2-digit',
  day: '2-digit',
  hour: '2-digit',
  minute: '2-digit',
  second: '2-digit',
  hourCycle: 'h23',
  timeZone: CLUB_TIME_ZONE,
});

/** "utorak, 20. listopada 2026." in the club zone. */
export const formatLongDate = (iso: string) => longDateFormat.format(new Date(iso));

/** "18:30" in the club zone. */
export const formatTime = (iso: string) => timeFormat.format(new Date(iso));

/** "18:30–20:00" in the club zone. */
export const formatTimeRange = (startIso: string, endIso: string) => `${formatTime(startIso)}–${formatTime(endIso)}`;

/** Splits `YYYY-MM-DD` (or `YYYY-MM`) into numbers; missing parts are 1. */
export function dateParts(date: string): [number, number, number] {
  const [y = 1970, m = 1, d = 1] = date.split('-').map(Number);
  return [y, m, d];
}

/** Local date (`YYYY-MM-DD`) of a calendar date string, formatted like {@link formatLongDate}. */
export function formatLocalDate(date: string, options: Intl.DateTimeFormatOptions = {}) {
  const [y, m, d] = dateParts(date);
  return new Intl.DateTimeFormat('hr-HR', {
    weekday: 'long',
    day: 'numeric',
    month: 'long',
    year: 'numeric',
    timeZone: 'UTC',
    ...options,
  }).format(new Date(Date.UTC(y, m - 1, d)));
}

interface ZonedParts {
  date: string;
  time: string;
}

/** Wall-clock date (`YYYY-MM-DD`) and time (`HH:mm`) of an instant in the club zone. */
export function toClubParts(iso: string | Date): ZonedParts {
  const parts = Object.fromEntries(
    partsFormat.formatToParts(typeof iso === 'string' ? new Date(iso) : iso).map((p) => [p.type, p.value]),
  );
  return { date: `${parts.year}-${parts.month}-${parts.day}`, time: `${parts.hour}:${parts.minute}` };
}

/** Offset of the club zone from UTC at `utcMillis`, in milliseconds (e.g. +2 h in summer). */
function clubOffset(utcMillis: number): number {
  const parts: Record<string, string> = Object.fromEntries(
    partsFormat.formatToParts(new Date(utcMillis)).map((p) => [p.type, p.value]),
  );
  const n = (key: string) => Number(parts[key]);
  const asUtc = Date.UTC(n('year'), n('month') - 1, n('day'), n('hour'), n('minute'), n('second'));
  return asUtc - Math.floor(utcMillis / 1000) * 1000;
}

/**
 * UTC ISO instant of a wall-clock date and time in the club zone, DST-aware ("2026-10-27", "18:30" →
 * "2026-10-27T17:30:00.000Z"). Returns null for malformed input.
 */
export function clubDateTimeToIso(date: string, time: string): string | null {
  const dm = /^(\d{4})-(\d{2})-(\d{2})$/.exec(date);
  const tm = /^(\d{2}):(\d{2})$/.exec(time);
  if (!dm || !tm) return null;
  const wall = Date.UTC(Number(dm[1]), Number(dm[2]) - 1, Number(dm[3]), Number(tm[1]), Number(tm[2]));
  if (Number.isNaN(wall)) return null;
  let utc = wall - clubOffset(wall);
  utc = wall - clubOffset(utc); // second pass settles the offset around DST changes
  const result = new Date(utc);
  const check = toClubParts(result);
  if (check.date !== date) return null; // e.g. 2026-02-31
  return result.toISOString();
}

/** Today's date (`YYYY-MM-DD`) in the club zone. */
export const clubToday = (now: Date = new Date()) => toClubParts(now).date;

/** Adds days to a `YYYY-MM-DD` date. */
export function addDays(date: string, days: number): string {
  const [y, m, d] = dateParts(date);
  return new Date(Date.UTC(y, m - 1, d + days)).toISOString().slice(0, 10);
}
