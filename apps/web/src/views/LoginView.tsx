import type { JSX } from "preact";
import { useEffect, useRef, useState } from "preact/hooks";
import { describeError } from "../api/http";
import { login } from "../api/session";
import { BrandTitle } from "../ui/Brand";
import { Notice } from "../ui/Controls";
import { SignUpForm } from "./SignUpForm";

/** How the wait of a locked ID reads: whole minutes, at least one. */
export const lockedMessage = (seconds: number): string =>
  `로그인 시도가 너무 많습니다. ${Math.max(1, Math.ceil(seconds / 60))}분 뒤에 다시 시도하세요.`;

/**
 * The sign-in screen. It is a full page without the map: nothing of the work area is shown before a person
 * is signed in. A wrong ID and a wrong password get the same sentence, as the server gives the same answer.
 */
export function LoginView({ onSignedIn }: { onSignedIn: () => void }): JSX.Element {
  const [loginId, setLoginId] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [help, setHelp] = useState(false);
  const idField = useRef<HTMLInputElement>(null);

  useEffect(() => idField.current?.focus(), []);

  const submit = async (event: Event): Promise<void> => {
    event.preventDefault();
    if (busy || loginId.trim() === "" || password === "") return;
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await login(loginId.trim(), password);
      if (outcome.kind === "ok") {
        setPassword("");
        onSignedIn();
        return;
      }
      setPassword("");
      setProblem(outcome.kind === "locked" ? lockedMessage(outcome.retryAfterSeconds) : "로그인 ID 또는 비밀번호가 맞지 않습니다.");
    } catch (error) {
      const { cause, action } = describeError(error);
      setProblem(`${cause} ${action}`);
    } finally {
      setBusy(false);
    }
  };

  if (mode === "signup") {
    return (
      <main class="auth-screen">
        <SignUpForm onSignedIn={onSignedIn} onBack={() => setMode("login")} />
      </main>
    );
  }

  return (
    <main class="auth-screen">
      <form class="login-form" onSubmit={submit} aria-labelledby="auth-title">
        <BrandTitle id="auth-title">CLEVER UPS</BrandTitle>
        <div class="field">
          <label htmlFor="auth-login-id">로그인 ID</label>
          <input
            id="auth-login-id"
            ref={idField}
            class="text-input text-input-large"
            type="text"
            name="username"
            autocomplete="username"
            autocapitalize="none"
            spellcheck={false}
            value={loginId}
            onInput={event => setLoginId(event.currentTarget.value)}
          />
        </div>
        <div class="field">
          <label htmlFor="auth-password">비밀번호</label>
          <input
            id="auth-password"
            class="text-input text-input-large"
            type="password"
            name="password"
            autocomplete="current-password"
            value={password}
            onInput={event => setPassword(event.currentTarget.value)}
          />
        </div>
        {problem !== null && (
          <Notice tone="danger" icon="alert" role="alert">
            <p>{problem}</p>
          </Notice>
        )}
        <button type="submit" class="btn btn-primary btn-large" disabled={busy || loginId.trim() === "" || password === ""}>
          {busy ? "확인 중" : "로그인"}
        </button>
        <div class="login-links">
          <button type="button" class="text-link" onClick={() => setMode("signup")} disabled={busy}>
            회원가입
          </button>
          <span aria-hidden="true">|</span>
          <button type="button" class="text-link" aria-expanded={help} onClick={() => setHelp(open => !open)}>
            아이디/비밀번호 찾기
          </button>
        </div>
        {help && (
          <output class="login-help">
            아이디·비밀번호 찾기는 아직 준비 중입니다. 개발 관리자에게 문의하세요.
          </output>
        )}
      </form>
    </main>
  );
}
