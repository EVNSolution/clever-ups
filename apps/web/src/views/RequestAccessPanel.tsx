import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { formatPhone, profileProblems, requestAccess, type AccessProfileInput, type SignUpField } from "../api/access";
import { describeError } from "../api/http";
import { Notice } from "../ui/Controls";
import { TextField } from "../ui/TextField";

const EMPTY: AccessProfileInput = { fullName: "", phone: "" };

/**
 * An account that is signed in and has no access asks again, on the normal page. It is not a waiting screen:
 * the page behind it stays as it is, and the panel closes when the request is sent.
 */
export function RequestAccessPanel(props: { onAsked: () => void; onCancel: () => void }): JSX.Element {
  const [form, setForm] = useState<AccessProfileInput>(EMPTY);
  const [touched, setTouched] = useState<ReadonlySet<SignUpField>>(new Set());
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const problems = profileProblems(form);
  const touch = (...keys: SignUpField[]): void => setTouched(previous => new Set([...previous, ...keys]));
  const shown = (key: SignUpField): string | undefined => (touched.has(key) ? problems[key] : undefined);

  const set = <K extends keyof AccessProfileInput>(key: K, value: AccessProfileInput[K]): void =>
    setForm(previous => ({ ...previous, [key]: value }));

  const submit = async (event: Event): Promise<void> => {
    event.preventDefault();
    if (busy) return;
    const unmet = Object.keys(problems) as SignUpField[];
    if (unmet.length > 0) {
      touch(...unmet);
      setProblem("빨간색으로 표시된 항목을 확인하세요.");
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await requestAccess(form);
      if (outcome.kind === "waiting") setProblem("이미 대기 중인 신청이 있거나 접근 권한이 있습니다.");
      else if (outcome.kind === "closed") setProblem("오늘 신청이 가득 찼습니다. 내일 다시 시도하세요.");
      else if (outcome.kind === "invalid") setProblem(`입력을 확인하세요. ${outcome.detail}`.trim());
      else props.onAsked();
    } catch (error) {
      const { cause, action } = describeError(error);
      setProblem(`${cause} ${action}`);
    } finally {
      setBusy(false);
    }
  };

  const text = (id: string, label: string, key: "fullName" | "phone", hint?: string): JSX.Element => (
    <TextField
      id={id}
      label={label}
      value={form[key]}
      type={key === "phone" ? "tel" : "text"}
      hint={hint}
      error={shown(key)}
      onInput={value => set(key, key === "phone" ? formatPhone(value) : value)}
      onBlur={() => touch(key)}
    />
  );

  return (
    <form class="ask-panel" onSubmit={submit} aria-label="접근 신청" noValidate>
      {text("ask-name", "이름", "fullName")}
      {text("ask-phone", "휴대폰 번호", "phone")}
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
      <div class="approval-actions">
        <button type="submit" class="btn btn-primary" disabled={busy}>
          {busy ? "처리 중" : "신청"}
        </button>
        <button type="button" class="btn btn-soft" disabled={busy} onClick={props.onCancel}>
          닫기
        </button>
      </div>
    </form>
  );
}
