import assert from "node:assert/strict";
import { test } from "node:test";
import { accountStatusLabel, avatarInitial, centerSummary, roleLabel } from "./account.ts";

test("roleLabel shows the decided labels and the raw name for the rest", () => {
  assert.equal(roleLabel("owner"), "개발 관리자");
  assert.equal(roleLabel("admin"), "관리자");
  assert.equal(roleLabel("member"), "member");
  assert.equal(roleLabel("constructor"), "constructor");
});

test("avatarInitial takes the first character, also for Korean and empty IDs", () => {
  assert.equal(avatarInitial("gunpo01"), "G");
  assert.equal(avatarInitial("  dev01"), "D");
  assert.equal(avatarInitial("군포01"), "군");
  assert.equal(avatarInitial(""), "?");
});

test("accountStatusLabel shows the role, or the membership state when there is none", () => {
  assert.equal(accountStatusLabel("owner", "active"), "개발 관리자");
  assert.equal(accountStatusLabel(null, "pending"), "승인 대기");
  assert.equal(accountStatusLabel(null, "none"), "접근 신청 전");
  // A role left over from an earlier membership is not shown once the membership is not active.
  assert.equal(accountStatusLabel("viewer", "revoked"), "권한 회수됨");
});

test("the owner reads as every center, others list theirs or say they have none", () => {
  assert.equal(centerSummary("owner", ["KRHWN", "KRSNA"]), "전체 센터");
  assert.equal(centerSummary("owner", []), "전체 센터");
  assert.equal(centerSummary("admin", []), "센터 없음");
  assert.equal(centerSummary("member", ["KRHWN", "KRSNA"]), "KRHWN, KRSNA");
  assert.equal(centerSummary(null, []), "센터 없음");
});
