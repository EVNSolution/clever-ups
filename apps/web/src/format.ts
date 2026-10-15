/** A driver position older than this is shown as possibly outdated. Provisional until operations confirm it. */
export const STALE_AFTER_MS = 10 * 60 * 1000;

export function formatAge(ms: number): string {
  const seconds = Math.max(0, Math.floor(ms / 1000));
  if (seconds < 60) return "방금 전";
  const minutes = Math.floor(seconds / 60);
  if (minutes < 60) return `${minutes}분 전`;
  const hours = Math.floor(minutes / 60);
  if (hours < 24) return `${hours}시간 전`;
  return `${Math.floor(hours / 24)}일 전`;
}

export type PositionAge = { known: false } | { known: true; ms: number; stale: boolean };

/** Age of a recorded position. An unreadable or missing time is "unknown", never "fresh". */
export function positionAge(positionAt: string | null | undefined, now: number): PositionAge {
  if (positionAt === null || positionAt === undefined) return { known: false };
  const recorded = Date.parse(positionAt);
  if (Number.isNaN(recorded)) return { known: false };
  const ms = now - recorded;
  return { known: true, ms, stale: ms > STALE_AFTER_MS };
}

const timeFormat = new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", second: "2-digit" });
const dateTimeFormat = new Intl.DateTimeFormat("ko-KR", { dateStyle: "medium", timeStyle: "medium" });

export const formatTime = (date: Date): string => timeFormat.format(date);
export const formatDateTime = (date: Date): string => dateTimeFormat.format(date);

const clockFormat = new Intl.DateTimeFormat("ko-KR", { hour: "2-digit", minute: "2-digit", hourCycle: "h23", timeZone: "Asia/Seoul" });

/** Hours and minutes in Korea, such as 09:35, for planned arrivals. */
export const formatClock = (date: Date): string => clockFormat.format(date);
