import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { describeError } from "../api/http";
import { redeemInvite } from "../api/invites";
import { Notice } from "../ui/Controls";
import { TextField } from "../ui/TextField";

/**
 * A signed-in account types a center entry code and gets that center. It is on the normal page for every
 * account except the owner, who has every center already.
 */
export function RedeemPanel(props: { onRedeemed: () => void; onCancel: () => void }): JSX.Element {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const submit = async (event: Event): Promise<void> => {
    event.preventDefault();
    if (busy || code.trim() === "") return;
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await redeemInvite(code.trim());
      if (outcome.kind === "ok") {
        props.onRedeemed();
        return;
      }
      if (outcome.kind === "locked") {
        setProblem(`잘못된 코드를 여러 번 입력했습니다. ${Math.max(1, Math.ceil(outcome.retryAfterSeconds / 60))}분 뒤에 다시 시도하세요.`);
      } else if (outcome.kind === "owner") {
        setProblem("개발 관리자는 모든 센터를 이미 쓸 수 있습니다.");
      } else {
        setProblem("코드가 맞지 않거나 이미 쓰였거나 기간이 지났습니다.");
      }
    } catch (error) {
      const { cause, action } = describeError(error);
      setProblem(`${cause} ${action}`);
    } finally {
      setBusy(false);
    }
  };

  return (
    <form class="ask-panel" onSubmit={submit} aria-label="센터 입장 코드" noValidate>
      <TextField
        id="redeem-code"
        label="센터 입장 코드"
        value={code}
        hint="센터 담당자에게 받은 코드를 입력하세요."
        error={undefined}
        onInput={setCode}
        onBlur={() => undefined}
      />
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
      <div class="approval-actions">
        <button type="submit" class="btn btn-primary" disabled={busy || code.trim() === ""}>
          {busy ? "처리 중" : "입력"}
        </button>
        <button type="button" class="btn btn-soft" disabled={busy} onClick={props.onCancel}>
          닫기
        </button>
      </div>
    </form>
  );
}
