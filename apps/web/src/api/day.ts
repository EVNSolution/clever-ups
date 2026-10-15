/** Days are `YYYY-MM-DD` in Korea, the way the API reads them. */
const KOREA = "Asia/Seoul";

const pattern = /^\d{4}-\d{2}-\d{2}$/;

export const isDay = (value: string): boolean => pattern.test(value) && !Number.isNaN(Date.parse(`${value}T00:00:00Z`));

/** Today in Korea, whatever the computer's zone is. */
export function today(now: Date = new Date()): string {
  const parts = new Intl.DateTimeFormat("en-CA", { timeZone: KOREA, year: "numeric", month: "2-digit", day: "2-digit" })
    .formatToParts(now);
  const get = (type: string): string => parts.find(part => part.type === type)?.value ?? "";
  return `${get("year")}-${get("month")}-${get("day")}`;
}

/** The day before (-1) or after (+1) [day]. Calendar arithmetic in UTC, so no zone or daylight-saving slip. */
export function addDays(day: string, delta: number): string {
  const moved = new Date(`${day}T00:00:00Z`);
  moved.setUTCDate(moved.getUTCDate() + delta);
  return moved.toISOString().slice(0, 10);
}

/** `YYYY-MM` of a day. */
export const monthOf = (day: string): string => day.slice(0, 7);

/** The first day of the month before (-1) or after (+1) the month of [day]. */
export function addMonths(day: string, delta: number): string {
  const [year, month] = day.split("-").map(Number) as [number, number];
  const moved = new Date(Date.UTC(year, month - 1 + delta, 1));
  return moved.toISOString().slice(0, 10);
}

/** Six weeks, Sunday first, that cover the month of [day]: 42 days, the edges from the months next to it. */
export function monthGrid(day: string): string[] {
  const first = `${monthOf(day)}-01`;
  const start = addDays(first, -new Date(`${first}T00:00:00Z`).getUTCDay());
  return Array.from({ length: 42 }, (_, index) => addDays(start, index));
}

