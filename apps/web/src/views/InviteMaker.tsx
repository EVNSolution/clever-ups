import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { describeError } from "../api/http";
import { createInvite, type InviteRole } from "../api/invites";
import { formatDateTime } from "../format";
import { Notice } from "../ui/Controls";
import { TextField } from "../ui/TextField";

type Made = { code: string; centerCodes: string[]; role: string; expiresAt: string };

/** The owner makes a center entry code. The code is shown once here; the server keeps only its hash. */
export function InviteMaker(): JSX.Element {
  const [center, setCenter] = useState("");
  const [role, setRole] = useState<InviteRole>("member");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [made, setMade] = useState<Made | null>(null);
  const [copied, setCopied] = useState(false);

  const submit = async (event: Event): Promise<void> => {
    event.preventDefault();
    if (busy || center.trim() === "") return;
    setBusy(true);
    setProblem(null);
    setMade(null);
    setCopied(false);
    try {
      const centers = center.split(/[,\s]+/).map(c => c.trim().toUpperCase()).filter(c => c !== "");
      const outcome = await createInvite(centers, role);
      if (outcome.kind === "ok") setMade(outcome);
      else setProblem("센터 코드는 영문·숫자 2~20자이고 1~10개까지 입력할 수 있습니다.");
    } catch (error) {
      setProblem(describeError(error).cause);
    } finally {
      setBusy(false);
    }
  };

  const copy = async (): Promise<void> => {
    if (made === null) return;
    try {
      await navigator.clipboard.writeText(made.code);
      setCopied(true);
    } catch {
      setCopied(false);
    }
  };

  return (
    <form class="ask-panel" onSubmit={submit} aria-label="센터 입장 코드 만들기" noValidate>
      <TextField
        id="invite-center"
        label="센터 코드"
        value={center}
        hint="여러 개는 쉼표로 구분합니다. 예: KRSNA, KRHWN"
        error={undefined}
        onInput={setCenter}
        onBlur={() => undefined}
      />
      <div class="field">
        <label htmlFor="invite-role">역할</label>
        <select id="invite-role" class="text-input" value={role} onChange={event => setRole(event.currentTarget.value as InviteRole)}>
          <option value="member">일반 사용자(member)</option>
          <option value="viewer">조회 전용(viewer)</option>
        </select>
        <span class="field-hint">관리자(admin)는 코드로 줄 수 없고 승인 화면에서만 줍니다.</span>
      </div>
      <button type="submit" class="btn btn-primary" disabled={busy || center.trim() === ""}>
        {busy ? "만드는 중" : "코드 만들기"}
      </button>
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
      {made !== null && (
        <Notice tone="ok" icon="check" title={`${made.centerCodes.join(", ")} · ${made.role}`}>
          <p class="invite-code">{made.code}</p>
          <p>한 번만 쓸 수 있고 {formatDateTime(new Date(made.expiresAt))}까지 유효합니다. 이 화면을 닫으면 다시 볼 수 없습니다.</p>
          <button type="button" class="btn btn-outline" onClick={copy}>
            {copied ? "복사됨" : "코드 복사"}
          </button>
        </Notice>
      )}
    </form>
  );
}
