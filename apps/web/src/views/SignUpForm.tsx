import type { JSX } from "preact";
import { useEffect, useState } from "preact/hooks";
import { formatPhone, getSignUpOptions, signUp, signUpProblems, type SignUpField, type SignUpInput } from "../api/access";
import { settingsOf, type FirebaseSettings } from "../api/firebasePhone";
import { getPublicPhone } from "../api/phone";
import { describeError } from "../api/http";
import { login } from "../api/session";
import { BrandTitle } from "../ui/Brand";
import { Notice } from "../ui/Controls";
import { TextField } from "../ui/TextField";
import { PhoneVerifyField } from "./PhoneVerifyField";

const EMPTY: SignUpInput = {
  loginId: "",
  password: "",
  fullName: "",
  phone: "",
  phoneProof: "",
  inviteCode: "",
};

/**
 * Sign-up asks for access in the same step. It gives none: a person who signs up sees the normal page with the
 * information hidden until the 개발 관리자 approves. After the account is made the person is signed in at once.
 */
export function SignUpForm(props: { onSignedIn: () => void; onBack: () => void }): JSX.Element {
  const [form, setForm] = useState<SignUpInput>(EMPTY);
  const [confirm, setConfirm] = useState("");
  const [touched, setTouched] = useState<ReadonlySet<SignUpField>>(new Set());
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  /** Non-null when the server wants the number proved before an account is made. */
  const [verify, setVerify] = useState<FirebaseSettings | null>(null);
  /** True while the server only lets a person with a sign-up code make an account. */
  const [codeRequired, setCodeRequired] = useState(false);

  useEffect(() => {
    let live = true;
    getSignUpOptions().then(
      options => live && setCodeRequired(options.codeRequired),
      () => undefined,
    );
    getPublicPhone().then(
      config => {
        if (live && config.configured) setVerify(settingsOf({ verified: false, ...config }));
      },
      () => undefined,
    );
    return () => {
      live = false;
    };
  }, []);

  const problems = signUpProblems(form, confirm, verify !== null, codeRequired);
  const set = <K extends keyof SignUpInput>(key: K, value: SignUpInput[K]): void => setForm(previous => ({ ...previous, [key]: value }));
  const touch = (...keys: SignUpField[]): void => setTouched(previous => new Set([...previous, ...keys]));
  /** A problem shows once the person has left the box, and stays live after that. */
  const [submitted, setSubmitted] = useState(false);
  const shown = (key: SignUpField): string | undefined => {
    const problem = touched.has(key) ? problems[key] : undefined;
    // "Prove the number" is not scolded while the person is still on the way to the button beside it.
    return key === "phone" && problem === "휴대폰 인증을 완료하세요." && !submitted ? undefined : problem;
  };

  const submit = async (event: Event): Promise<void> => {
    event.preventDefault();
    if (busy) return;
    setSubmitted(true);
    const unmet = Object.keys(problems) as SignUpField[];
    if (unmet.length > 0) {
      touch(...unmet);
      setProblem("빨간색으로 표시된 항목을 확인하세요.");
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await signUp({ ...form, loginId: form.loginId.trim() });
      if (outcome.kind === "taken") return setProblem("이미 사용 중인 로그인 ID입니다.");
      if (outcome.kind === "closed") return setProblem("오늘 가입 신청이 가득 찼습니다. 내일 다시 시도하세요.");
      if (outcome.kind === "invalid" && /phone/i.test(outcome.detail)) {
        set("phoneProof", "");
        return setProblem("휴대폰 인증이 만료되었거나 맞지 않습니다. 다시 인증하세요.");
      }
      if (outcome.kind === "invalid") return setProblem(`입력을 확인하세요. ${outcome.detail}`.trim());
      const signedIn = await login(form.loginId.trim(), form.password);
      setForm(EMPTY);
      setConfirm("");
      if (signedIn.kind === "ok") props.onSignedIn();
      else props.onBack();
    } catch (error) {
      const { cause, action } = describeError(error);
      setProblem(`${cause} ${action}`);
    } finally {
      setBusy(false);
    }
  };

  const text = (
    id: string,
    label: string,
    key: SignUpField,
    value: string,
    onInput: (value: string) => void,
    extra: { type?: string; autocomplete?: string; hint?: string } = {},
  ): JSX.Element => (
    <TextField id={id} label={label} value={value} large onInput={onInput} onBlur={() => touch(key)} error={shown(key)} {...extra} />
  );

  return (
    <form class="login-form" onSubmit={submit} aria-labelledby="signup-title" noValidate>
      <BrandTitle id="signup-title">가입 신청</BrandTitle>
      {codeRequired &&
        text("su-code", "가입 코드", "inviteCode", form.inviteCode, value => set("inviteCode", value), {
          hint: "개발 관리자에게 받은 코드를 입력하세요.",
        })}
      {text("su-login", "로그인 ID", "loginId", form.loginId, value => set("loginId", value), {
        autocomplete: "username",
        hint: "4~32자의 영문, 숫자, 특수문자(. - _)만 쓸 수 있습니다.",
      })}
      {text("su-password", "비밀번호", "password", form.password, value => set("password", value), {
        type: "password",
        autocomplete: "new-password",
        hint: "8~128자, 영문 대문자·숫자·특수문자를 각각 1자 이상 포함합니다.",
      })}
      {text("su-password-confirm", "비밀번호 확인", "passwordConfirm", confirm, setConfirm, {
        type: "password",
        autocomplete: "new-password",
      })}
      {text("su-name", "이름", "fullName", form.fullName, value => set("fullName", value), { autocomplete: "name" })}
      {verify === null
        ? text("su-phone", "휴대폰 번호", "phone", form.phone, value => set("phone", formatPhone(value)), {
            type: "tel",
            autocomplete: "tel",
          })
        : (
          <PhoneVerifyField
            settings={verify}
            value={form.phone}
            proof={form.phoneProof}
            valid={
              (problems.phone === undefined || problems.phone === "휴대폰 인증을 완료하세요.") &&
              (!codeRequired || form.inviteCode.trim() !== "")
            }
            inviteCode={form.inviteCode}
            error={shown("phone")}
            onInput={value => set("phone", formatPhone(value))}
            onBlur={() => touch("phone")}
            onProof={token => {
              set("phoneProof", token);
              touch("phone");
            }}
          />
        )}
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert">
          <p>{problem}</p>
        </Notice>
      )}
      <button type="submit" class="btn btn-primary btn-large" disabled={busy}>
        {busy ? "처리 중" : "가입 신청"}
      </button>
      <div class="login-links">
        <button type="button" class="text-link" onClick={props.onBack} disabled={busy}>
          로그인으로 돌아가기
        </button>
      </div>
    </form>
  );
}
