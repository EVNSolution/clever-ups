import assert from "node:assert/strict";
import { test } from "node:test";
import { ApiError, isForbidden } from "../api/http.ts";
import { hiddenResource } from "./hidden.ts";

test("a hidden list is empty, settled and quiet", () => {
  const list = hiddenResource<{ id: string }>();
  assert.deepEqual(list.items, []);
  assert.equal(list.loading, false);
  assert.equal(list.error, null);
  assert.equal(list.incomplete, false);
  assert.doesNotThrow(() => list.reload());
});

test("only a 403 counts as forbidden", () => {
  assert.equal(isForbidden(new ApiError("http", "x", 403)), true);
  assert.equal(isForbidden(new ApiError("http", "x", 401)), false);
  assert.equal(isForbidden(new Error("x")), false);
});
