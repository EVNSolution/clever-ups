import type { JSX } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { exchangeCode, settingsOf, solveChallenge } from "../api/firebasePhone";
import { describeError } from "../api/http";
import { getPhoneStatus, sendPhoneCode, sendProblem, verifyPhone, type PhoneStatus } from "../api/phone";
import { Notice } from "../ui/Controls";

/**
 * A person who asked for access proves the phone number they gave. It sits on the normal page: no waiting
 * screen. A message is sent only by the person's own click on "인증번호 받기", never by opening the page.
 */
export function PhoneProofPanel(): JSX.Element | null {
  const [status, setStatus] = useState<PhoneStatus | null>(null);
  const [reference, setReference] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const holder = useRef<HTMLDivElement>(null);

  useEffect(() => {
    let live = true;
    getPhoneStatus().then(
      next => live && setStatus(next),
      () => live && setStatus(null),
    );
    return () => {
      live = false;
    };
  }, []);

  if (status === null || !status.configured) return null;
  if (status.verified) {
    return (
      <Notice tone="ok" icon="check">
        <p>휴대폰 인증이 끝났습니다. 승인을 기다리는 중입니다.</p>
      </Notice>
    );
  }
  const settings = settingsOf(status);
  if (settings === null) return null;

  const guard = async (work: () => Promise<void>): Promise<void> => {
    if (busy) return;
    setBusy(true);
    setProblem(null);
    try {
      await work();
    } catch (error) {
      const { cause, action } = describeError(error);
      setProblem(`${cause} ${action}`);
    } finally {
      setBusy(false);
    }
  };

  const send = (): Promise<void> =>
    guard(async () => {
      const holderElement = holder.current;
      if (holderElement === null) return;
      const challenge = await solveChallenge(settings, holderElement);
      const outcome = await sendPhoneCode(crypto.randomUUID(), challenge);
      if (outcome.kind === "ok") setReference(outcome.reference);
      else setProblem(sendProblem(outcome));
    });

  const confirm = (): Promise<void> =>
    guard(async () => {
      if (reference === null) return;
      const token = await exchangeCode(settings, reference, code.trim());
      if (await verifyPhone(token)) setStatus({ ...status, verified: true });
      else setProblem("인증을 확인하지 못했습니다. 번호와 코드를 확인하세요.");
    });

  return (
    <section class="ask-panel" aria-label="휴대폰 인증">
      <p>
        휴대폰 인증{status.phoneHint !== null && status.phoneHint !== undefined ? ` · ${status.phoneHint}` : ""}
      </p>
      {reference === null ? (
        <button type="button" class="btn btn-primary" disabled={busy} onClick={send}>
          {busy ? "처리 중" : "인증번호 받기"}
        </button>
      ) : (
        <div class="field">
          <label htmlFor="phone-code">인증번호</label>
          <input
            id="phone-code"
            class="text-input"
            inputMode="numeric"
            autocomplete="one-time-code"
            maxLength={6}
            value={code}
            onInput={event => setCode(event.currentTarget.value.replace(/[^0-9]/g, ""))}
          />
          <div class="approval-actions">
            <button type="button" class="btn btn-primary" disabled={busy || code.length !== 6} onClick={confirm}>
              {busy ? "처리 중" : "확인"}
            </button>
            <button type="button" class="btn btn-soft" disabled={busy} onClick={() => setReference(null)}>
              다시 받기
            </button>
          </div>
        </div>
      )}
      <div ref={holder} />
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
    </section>
  );
}
