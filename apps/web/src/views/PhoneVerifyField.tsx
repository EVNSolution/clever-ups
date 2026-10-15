import type { JSX } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { toE164 } from "../api/access";
import { exchangeCode, solveChallenge, type FirebaseSettings } from "../api/firebasePhone";
import { describeError } from "../api/http";
import { sendProblem, sendSignUpCode } from "../api/phone";

const COOLDOWN_MS = 60_000;

const clock = (milliseconds: number): string => {
  const seconds = Math.max(0, Math.ceil(milliseconds / 1000));
  return `${Math.floor(seconds / 60)}:${String(seconds % 60).padStart(2, "0")}`;
};

/**
 * The phone number of the sign-up form, with its proof beside it: "인증번호 받기" sends one code (only the
 * person's own click does), a box and "확인" appear below, and "재전송" opens again after a minute.
 * The proof (a Firebase ID token) goes up to the form; the server checks it when the account is made.
 */
export function PhoneVerifyField(props: {
  settings: FirebaseSettings;
  value: string;
  proof: string;
  valid: boolean;
  /** The sign-up code typed above. It goes with every send; the server refuses a send without a valid one. */
  inviteCode: string;
  error: string | undefined;
  onInput: (value: string) => void;
  onBlur: () => void;
  onProof: (token: string) => void;
}): JSX.Element {
  const [reference, setReference] = useState<string | null>(null);
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [resendAt, setResendAt] = useState(0);
  const [now, setNow] = useState(Date.now());
  const holder = useRef<HTMLDivElement>(null);
  const verified = props.proof !== "";
  const waiting = resendAt > now;

  useEffect(() => {
    if (!waiting) return undefined;
    const timer = setInterval(() => setNow(Date.now()), 1000);
    return () => clearInterval(timer);
  }, [waiting]);

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
      if (holderElement === null || !props.valid) return;
      const challenge = await solveChallenge(props.settings, holderElement);
      const outcome = await sendSignUpCode(toE164(props.value), crypto.randomUUID(), challenge, props.inviteCode);
      if (outcome.kind !== "ok") {
        setProblem(sendProblem(outcome));
        return;
      }
      setReference(outcome.reference);
      setCode("");
      setNow(Date.now());
      setResendAt(Date.now() + COOLDOWN_MS);
    });

  const confirm = (): Promise<void> =>
    guard(async () => {
      if (reference === null) return;
      try {
        props.onProof(await exchangeCode(props.settings, reference, code.trim()));
        setReference(null);
      } catch (error) {
        const wrong = error instanceof Error && error.message.includes("invalid-verification-code");
        if (!wrong) throw error;
        setProblem("인증번호가 맞지 않습니다. 다시 확인하세요.");
      }
    });

  const change = (): void => {
    props.onProof("");
    setReference(null);
    setCode("");
    setProblem(null);
  };

  return (
    <div class="field">
      <label htmlFor="su-phone">휴대폰 번호</label>
      <div class="field-row">
        <input
          id="su-phone"
          class="text-input text-input-large"
          type="tel"
          autocomplete="tel"
          inputMode="numeric"
          value={props.value}
          readOnly={verified}
          aria-invalid={props.error !== undefined}
          aria-describedby="su-phone-note"
          onInput={event => props.onInput(event.currentTarget.value)}
          onBlur={props.onBlur}
        />
        {verified ? (
          <button type="button" class="btn btn-soft btn-field" onClick={change}>
            번호 변경
          </button>
        ) : (
          <button type="button" class="btn btn-soft btn-field" disabled={busy || !props.valid || reference !== null} onClick={send}>
            인증번호 받기
          </button>
        )}
      </div>
      {reference !== null && !verified && (
        <>
          <div class="field-row">
            <input
              class="text-input text-input-large"
              inputMode="numeric"
              autocomplete="one-time-code"
              aria-label="인증번호"
              maxLength={6}
              value={code}
              onInput={event => setCode(event.currentTarget.value.replace(/[^0-9]/g, ""))}
            />
            <button type="button" class="btn btn-soft btn-field" disabled={busy || code.length !== 6} onClick={confirm}>
              확인
            </button>
          </div>
          <div class="resend">
            <button type="button" class="text-link" disabled={busy || waiting} onClick={send}>
              재전송
            </button>
            {waiting && <span aria-live="off">({clock(resendAt - now)})</span>}
          </div>
        </>
      )}
      <div ref={holder} />
      <span id="su-phone-note" class={verified ? "field-ok" : props.error === undefined && problem === null ? "field-hint" : "field-error"}>
        {verified ? "인증되었습니다." : (problem ?? props.error ?? "")}
      </span>
    </div>
  );
}
