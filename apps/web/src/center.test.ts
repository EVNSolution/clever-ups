import assert from "node:assert/strict";
import { test } from "node:test";
import { pickCenter, readCenterParam, withCenterParam } from "./center.ts";

const centers = [{ id: "c1" }, { id: "c2" }, { id: "c3" }];

test("pickCenter keeps a usable request and falls back to the first center", () => {
  assert.equal(pickCenter(centers, "c2"), "c2");
  assert.equal(pickCenter(centers, "gone"), "c1");
  assert.equal(pickCenter(centers, null), "c1");
  assert.equal(pickCenter([], "c1"), null);
});

test("withCenterParam sets and removes the center and keeps the rest of the URL", () => {
  const base = "http://localhost:5173/?x=1#/settings";
  const set = withCenterParam(base, "c2");
  assert.equal(readCenterParam(set), "c2");
  assert.ok(set.includes("x=1") && set.endsWith("#/settings"));
  assert.equal(readCenterParam(withCenterParam(set, null)), null);
  assert.equal(readCenterParam(withCenterParam(base, "한 센터&1")), "한 센터&1");
});
