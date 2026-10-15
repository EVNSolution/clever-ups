import assert from "node:assert/strict";
import { test } from "node:test";
import { addDays, addMonths, isDay, monthGrid, monthOf, today } from "./day.ts";

test("today is the Korean date even when the computer's day is another", () => {
  // 2026-10-05T16:00Z is already 2026-10-06 in Korea.
  assert.equal(today(new Date("2026-10-05T16:00:00Z")), "2026-10-06");
  assert.equal(today(new Date("2026-10-05T14:59:00Z")), "2026-10-05");
});

test("days move across month and year ends", () => {
  assert.equal(addDays("2026-10-31", 1), "2026-11-01");
  assert.equal(addDays("2026-01-01", -1), "2025-12-31");
  assert.equal(addDays("2024-02-28", 1), "2024-02-29");
});

test("only a real YYYY-MM-DD is a day", () => {
  assert.equal(isDay("2026-10-05"), true);
  assert.equal(isDay("2026-13-05"), false);
  assert.equal(isDay("20261005"), false);
  assert.equal(isDay(""), false);
});

test("a month grid is six Sunday-first weeks that hold the whole month", () => {
  const grid = monthGrid("2026-10-15");
  assert.equal(grid.length, 42);
  assert.equal(grid[0], "2026-09-27"); // 2026-10-01 is a Thursday
  assert.equal(new Date(`${grid[0]}T00:00:00Z`).getUTCDay(), 0);
  assert.ok(grid.includes("2026-10-01") && grid.includes("2026-10-31"));
  assert.equal(grid[41], "2026-11-07");
});

test("months move across a year end and keep the first of the month", () => {
  assert.equal(addMonths("2026-12-20", 1), "2027-01-01");
  assert.equal(addMonths("2026-01-31", -1), "2025-12-01");
  assert.equal(monthOf("2026-10-05"), "2026-10");
});
