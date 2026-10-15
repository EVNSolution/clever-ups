import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { approveRequest, rejectRequest, type AccessRequest, type GrantRole, type MemberRow } from "../api/access";
import { describeError } from "../api/http";
import { formatDateTime } from "../format";
import type { Resource } from "../state/useResource";
import { Badge, Notice } from "../ui/Controls";
import { Skeleton, EmptyState } from "../ui/States";
import { InviteMaker } from "./InviteMaker";
import { MembersList } from "./MembersList";

type Row = AccessRequest & { id: string };

const ROLE_OPTIONS: readonly { value: GrantRole; label: string }[] = [
  { value: "member", label: "일반 사용자(member)" },
  { value: "viewer", label: "조회 전용(viewer)" },
  { value: "admin", label: "관리자(admin)" },
];

function RequestCard({ item, onDecided }: { item: Row; onDecided: () => void }): JSX.Element {
  const [role, setRole] = useState<GrantRole>("member");
  const [center, setCenter] = useState(item.centerCode ?? "");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const run = async (action: () => Promise<void>): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      await action();
      onDecided();
    } catch (error) {
      setProblem(describeError(error).cause);
      onDecided();
    } finally {
      setBusy(false);
    }
  };

  const centers = center.trim() === "" ? [] : [center.trim().toUpperCase()];
  return (
    <li class="approval">
      <div class="approval-head">
        <strong>{item.loginId}</strong>
        <Badge tone={item.phoneVerified ? "ok" : "warn"}>{item.phoneVerified ? "휴대폰 인증됨" : "휴대폰 미인증"}</Badge>
      </div>
      <dl class="approval-facts">
        <dt>이름</dt>
        <dd>{item.fullName ?? "-"}</dd>
        <dt>신청 센터</dt>
        <dd>{item.centerCode ?? "-"}</dd>
        <dt>기사 ID</dt>
        <dd>{item.driverId ?? "-"}</dd>
        <dt>휴대폰</dt>
        <dd>{item.phone ?? "-"}</dd>
        <dt>신청</dt>
        <dd>{formatDateTime(new Date(item.createdAt))}</dd>
        <dt>만료</dt>
        <dd>{formatDateTime(new Date(item.expiresAt))}</dd>
      </dl>
      <div class="approval-form">
        <label class="field">
          <span>역할</span>
          <select class="text-input" value={role} onChange={event => setRole(event.currentTarget.value as GrantRole)}>
            {ROLE_OPTIONS.map(option => (
              <option key={option.value} value={option.value}>
                {option.label}
              </option>
            ))}
          </select>
        </label>
        <label class="field">
          <span>허용 센터 코드</span>
          <input class="text-input" value={center} onInput={event => setCenter(event.currentTarget.value)} />
        </label>
      </div>
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
      <div class="approval-actions">
        <button type="button" class="btn btn-primary" disabled={busy || centers.length === 0} onClick={() => run(() => approveRequest(item.requestId, role, centers))}>
          승인
        </button>
        <button type="button" class="btn btn-outline" disabled={busy} onClick={() => run(() => rejectRequest(item.requestId))}>
          거절
        </button>
      </div>
    </li>
  );
}

/** The 개발 관리자's side: the waiting requests, then the accounts that can see data now. */
export function ApprovalsView(props: {
  resource: Resource<Row>;
  members: Resource<MemberRow>;
  onMembersChanged: () => void;
}): JSX.Element {
  return (
    <div class="manage">
      <h2 class="manage-title">대기 중인 신청</h2>
      <Requests resource={props.resource} onDecided={() => { props.resource.reload(); props.onMembersChanged(); }} />
      <h2 class="manage-title">센터 입장 코드</h2>
      <InviteMaker />
      <h2 class="manage-title">활성 멤버</h2>
      <MembersList resource={props.members} onChanged={props.onMembersChanged} />
    </div>
  );
}

/** The decision is final for a request; the list reloads after each one. */
function Requests(props: { resource: Resource<Row>; onDecided: () => void }): JSX.Element {
  const { resource } = props;
  if (resource.items === null && resource.loading) return <Skeleton rows={3} />;
  if (resource.items === null) {
    return (
      <EmptyState icon="alert" title="신청 목록을 불러오지 못했습니다" hint={describeError(resource.error).cause}>
        <button type="button" class="btn btn-outline" onClick={resource.reload}>
          다시 시도
        </button>
      </EmptyState>
    );
  }
  if (resource.items.length === 0) {
    return <EmptyState icon="inbox" title="대기 중인 신청이 없습니다" hint="가입 신청이 들어오면 이곳에 나타납니다. 신청은 14일 뒤 만료됩니다." />;
  }
  return (
    <ul class="approvals">
      {resource.items.map(item => (
        <RequestCard key={item.id} item={item} onDecided={props.onDecided} />
      ))}
    </ul>
  );
}
