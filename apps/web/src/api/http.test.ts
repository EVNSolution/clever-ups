import assert from "node:assert/strict";
import { test } from "node:test";
import { ApiError, describeError } from "./http.ts";

test("a 401 asks the person to sign in, not to check the address", () => {
  const { cause, action } = describeError(new ApiError("http", "서버가 오류를 반환했습니다 (HTTP 401).", 401));
  assert.equal(cause, "로그인이 필요합니다.");
  assert.match(action, /로그인/);
});

test("another 4xx keeps the permission and address hint", () => {
  const { action } = describeError(new ApiError("http", "x", 403));
  assert.match(action, /권한/);
});
