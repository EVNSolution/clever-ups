import assert from "node:assert/strict";
import { test } from "node:test";
import { formatAge, formatClock, positionAge, STALE_AFTER_MS } from "./format.ts";

test("formatAge uses the largest whole unit", () => {
  assert.equal(formatAge(10_000), "방금 전");
  assert.equal(formatAge(5 * 60_000), "5분 전");
  assert.equal(formatAge(3 * 3_600_000), "3시간 전");
  assert.equal(formatAge(2 * 86_400_000), "2일 전");
  assert.equal(formatAge(-5_000), "방금 전");
});

test("positionAge marks old positions as stale and missing ones as unknown", () => {
  const now = Date.parse("2026-10-08T01:00:00Z");
  assert.deepEqual(positionAge("2026-10-08T00:59:00Z", now), { known: true, ms: 60_000, stale: false });
  const old = positionAge("2026-10-08T00:30:00Z", now);
  assert.equal(old.known && old.stale, true);
  assert.equal(positionAge(new Date(now - STALE_AFTER_MS).toISOString(), now).known, true);
  assert.deepEqual(positionAge(null, now), { known: false });
  assert.deepEqual(positionAge("not a date", now), { known: false });
});

test("a planned arrival shows as hours and minutes in Korea", () => {
  assert.equal(formatClock(new Date("2026-10-14T00:35:08Z")), "09:35");
});
