import type { JSX } from "preact";
import { useEffect, useState } from "preact/hooks";
import { linkDriver, revokeMember, type MemberRow } from "../api/access";
import { listDrivers } from "../api/resources";
import { describeError } from "../api/http";
import { centerSummary, roleLabel } from "../account";
import { formatDateTime } from "../format";
import type { Resource } from "../state/useResource";
import { Badge, Notice } from "../ui/Controls";
import { EmptyState, Skeleton } from "../ui/States";

/**
 * Makes the member a driver account: one center, one driver code of it. The account then sees only its own route
 * in the driver app. The center list is the member's own; the drivers are read for the chosen center.
 */
function DriverLink({ member, onChanged, onClose }: { member: MemberRow; onChanged: () => void; onClose: () => void }): JSX.Element {
  const [center, setCenter] = useState(member.centerCodes[0] ?? "");
  const [codes, setCodes] = useState<string[] | null>(null);
  const [driver, setDriver] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  useEffect(() => {
    if (center === "") return;
    let live = true;
    setCodes(null);
    listDrivers(center).then(
      found => {
        if (!live) return;
        const ids = found.items.map(item => item.id);
        setCodes(ids);
        setDriver(ids[0] ?? "");
      },
      (error: unknown) => {
        if (live) setProblem(describeError(error).cause);
      },
    );
    return () => {
      live = false;
    };
  }, [center]);
  const save = async (): Promise<void> => {
    setBusy(true);
    setProblem(null);
    try {
      await linkDriver(member.accountId, center, driver);
      onChanged();
      onClose();
    } catch (error) {
      setProblem(describeError(error).cause);
    } finally {
      setBusy(false);
    }
  };
  return (
    <fieldset class="approval-confirm">
      <legend class="sr-only">{`${member.loginId} 기사 지정`}</legend>
      <p>기사로 지정하면 이 계정은 기사 앱에서 자기 노선만 봅니다. 센터 목록은 고른 센터 하나로 바뀝니다.</p>
      {member.centerCodes.length === 0 ? (
        <p>이 계정에 센터가 없습니다. 먼저 센터를 주세요.</p>
      ) : (
        <div class="route-edit">
          <label class="route-edit-row">
            <span>센터</span>
            <select class="text-input" value={center} onChange={event => setCenter(event.currentTarget.value)}>
              {member.centerCodes.map(code => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </label>
          <label class="route-edit-row">
            <span>기사</span>
            <select class="text-input" value={driver} disabled={codes === null || codes.length === 0} onChange={event => setDriver(event.currentTarget.value)}>
              {codes?.length === 0 && <option value="">이 센터에 기사가 없습니다</option>}
              {(codes ?? []).map(code => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </label>
        </div>
      )}
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
      <div class="approval-actions">
        <button type="button" class="btn btn-primary" disabled={busy || driver === ""} onClick={() => void save()}>
          {busy ? "처리 중" : "기사로 지정"}
        </button>
        <button type="button" class="btn btn-soft" disabled={busy} onClick={onClose}>
          취소
        </button>
      </div>
    </fieldset>
  );
}

function MemberCard({ member, onChanged }: { member: MemberRow; onChanged: () => void }): JSX.Element {
  const [linking, setLinking] = useState(false);
  const [confirming, setConfirming] = useState(false);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const isOwner = member.role === "owner";

  const revoke = async (): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      await revokeMember(member.accountId);
      onChanged();
    } catch (error) {
      setProblem(describeError(error).cause);
      onChanged();
    } finally {
      setBusy(false);
      setConfirming(false);
    }
  };

  return (
    <li class="approval">
      <div class="approval-head">
        <strong>{member.loginId}</strong>
        <Badge tone={isOwner ? "ok" : "neutral"}>{member.role === null || member.role === undefined ? "-" : roleLabel(member.role)}</Badge>
      </div>
      <dl class="approval-facts">
        <dt>센터</dt>
        <dd>{centerSummary(member.role, member.centerCodes)}</dd>
        {member.driverId !== null && member.driverId !== undefined && (
          <>
            <dt>기사</dt>
            <dd>{member.driverId}</dd>
          </>
        )}
        {member.decidedAt !== null && member.decidedAt !== undefined && (
          <>
            <dt>승인</dt>
            <dd>{formatDateTime(new Date(member.decidedAt))}</dd>
          </>
        )}
      </dl>
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
      {!isOwner && !confirming && !linking && (
        <div class="approval-actions">
          <button type="button" class="btn btn-outline" onClick={() => setLinking(true)}>
            {member.role === "driver" ? "기사 바꾸기" : "기사로 지정"}
          </button>
          <button type="button" class="btn btn-outline" onClick={() => setConfirming(true)}>
            권한 회수
          </button>
        </div>
      )}
      {!isOwner && linking && <DriverLink member={member} onChanged={onChanged} onClose={() => setLinking(false)} />}
      {!isOwner && confirming && (
        <fieldset class="approval-confirm">
          <legend class="sr-only">{`${member.loginId} 권한 회수 확인`}</legend>
          <p>회수하면 다음 요청부터 데이터가 닫히고 센터 권한이 모두 사라집니다.</p>
          <div class="approval-actions">
            <button type="button" class="btn btn-primary" disabled={busy} onClick={revoke}>
              {busy ? "처리 중" : "회수 확인"}
            </button>
            <button type="button" class="btn btn-soft" disabled={busy} onClick={() => setConfirming(false)}>
              취소
            </button>
          </div>
        </fieldset>
      )}
    </li>
  );
}

/** The accounts that can see data now, with the centers each may use. The owner is listed and cannot be revoked. */
export function MembersList(props: { resource: Resource<MemberRow>; onChanged: () => void }): JSX.Element {
  const { resource } = props;
  if (resource.items === null && resource.loading) return <Skeleton rows={2} />;
  if (resource.items === null) {
    return (
      <EmptyState icon="alert" title="멤버 목록을 불러오지 못했습니다" hint={describeError(resource.error).cause}>
        <button type="button" class="btn btn-outline" onClick={resource.reload}>
          다시 시도
        </button>
      </EmptyState>
    );
  }
  if (resource.items.length === 0) return <EmptyState icon="user" title="활성 멤버가 없습니다" />;
  return (
    <ul class="approvals">
      {resource.items.map(member => (
        <MemberCard key={member.id} member={member} onChanged={props.onChanged} />
      ))}
    </ul>
  );
}
