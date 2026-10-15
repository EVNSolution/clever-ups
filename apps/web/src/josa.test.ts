import assert from "node:assert/strict";
import { test } from "node:test";
import { subject } from "./josa.ts";

test("subject picks 이 or 가 by the final consonant", () => {
  assert.equal(subject("기사"), "기사가");
  assert.equal(subject("센터"), "센터가");
  assert.equal(subject("주문"), "주문이");
  assert.equal(subject("구역"), "구역이");
  assert.equal(subject("ABC"), "ABC이");
  assert.equal(subject(""), "이");
});
