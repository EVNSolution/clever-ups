import type { JSX } from "preact";
import { accountStatusLabel, avatarInitial } from "../account";
import { describeError } from "../api/http";
import type { MeState } from "../state/useMe";
import { IconButton } from "../ui/Controls";
import { Icon } from "../ui/Icon";

/** Who is signed in: the login ID as the nickname, and the role in small muted text. */
export function AccountBlock({
  me,
  onSignOut,
}: {
  me: MeState & { reload: () => void };
  onSignOut?: () => void;
}): JSX.Element {
  if (me.status === "loading") {
    return (
      <div class="account" aria-busy="true">
        <span class="avatar skeleton-block" />
        <span class="account-text">
          <span class="skeleton-block" style={{ width: 96, height: 12 }} />
          <span class="skeleton-block" style={{ width: 56, height: 10, marginTop: 6 }} />
        </span>
      </div>
    );
  }
  if (me.status === "signed-in") {
    const { loginId, role, membership } = me.account;
    return (
      <div class="account">
        <span class="avatar" aria-hidden="true">
          {avatarInitial(loginId)}
        </span>
        <span class="account-text">
          <span class="sr-only">계정</span>
          <strong class="account-id">{loginId}</strong>
          <span class="account-role">{accountStatusLabel(role, membership)}</span>
        </span>
        {onSignOut !== undefined && <IconButton icon="logout" label="로그아웃" onClick={onSignOut} />}
      </div>
    );
  }
  if (me.status === "signed-out") {
    return (
      <div class="account">
        <span class="avatar" data-tone="muted" aria-hidden="true">
          <Icon name="user" size={16} />
        </span>
        <span class="account-text">
          <strong class="account-id">로그인 필요</strong>
          <span class="account-role">로그인 후 센터 선택</span>
        </span>
      </div>
    );
  }
  return (
    <div class="account">
      <span class="avatar" data-tone="danger" aria-hidden="true">
        <Icon name="alert" size={16} />
      </span>
      <span class="account-text">
        <strong class="account-id">계정 확인 실패</strong>
        <span class="account-role">다시 시도해 주세요</span>
      </span>
      <IconButton icon="refresh" label={`계정 정보 다시 불러오기. ${describeError(me.error).cause}`} onClick={me.reload} />
    </div>
  );
}
