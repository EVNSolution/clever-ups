/** Labels for the roles that are decided. Other roles show their raw name until a label is decided. */
const ROLE_LABELS = new Map([
  ["owner", "개발 관리자"],
  ["admin", "관리자"],
  ["driver", "기사"],
]);

export const roleLabel = (role: string): string => ROLE_LABELS.get(role) ?? role;

/**
 * The centers of a member, in words. The owner sees every center whatever rows it holds, so it reads "전체".
 * Any other role with no rows has no center at all and sees nothing, which is said plainly.
 */
export function centerSummary(role: string | null | undefined, centerCodes: readonly string[]): string {
  if (role === "owner") return "전체 센터";
  return centerCodes.length === 0 ? "센터 없음" : centerCodes.join(", ");
}

/** What to show under the login ID when the account has no active role yet. */
const MEMBERSHIP_LABELS = new Map([
  ["none", "접근 신청 전"],
  ["pending", "승인 대기"],
  ["rejected", "신청 거절됨"],
  ["revoked", "권한 회수됨"],
]);

/** The role label, or the membership state when there is no role. */
export function accountStatusLabel(role: string | null, membership: string): string {
  if (membership === "active" && role !== null) return roleLabel(role);
  return MEMBERSHIP_LABELS.get(membership) ?? membership;
}

/** First character of the login ID, for the round avatar. */
export function avatarInitial(loginId: string): string {
  const first = [...loginId.trim()][0];
  return first === undefined ? "?" : first.toUpperCase();
}
