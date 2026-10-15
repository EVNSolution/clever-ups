import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { saveAddressKo } from "../api/addresses";
import { describeError } from "../api/http";
import { Notice } from "../ui/Controls";
import { Icon } from "../ui/Icon";

const MAX_LENGTH = 200;

const NOT_PLACED: Readonly<Record<string, string>> = {
  NOT_FOUND: "주소를 확인하고 다시 입력하세요. 도로명과 번호까지 쓰면 잘 찾습니다.",
  OUTSIDE_KOREA: "한국 밖의 위치가 나왔습니다. 주소를 확인하세요.",
  UNAVAILABLE: "지금은 위치를 찾지 못했습니다. 나중에 지오코딩을 다시 실행하면 찾습니다.",
};

const PROBLEMS = {
  invalid: "한글 주소를 1~200자로 입력하세요.",
  forbidden: "한글 주소는 관리자만 입력할 수 있습니다.",
  missing: "이 주소를 찾지 못했습니다. 목록을 새로고침하세요.",
} as const;

/**
 * Where an administrator types the Korean address of an order. The text is saved as the Korean address and
 * the address waits for its place again, so the pin shows once the place is found.
 */
export function AddressKoEditor(props: {
  addressKey: string;
  current: string;
  onSaved: () => void;
}): JSX.Element {
  const [open, setOpen] = useState(props.current === "");
  const [text, setText] = useState(props.current);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const [saved, setSaved] = useState<{ placed: boolean; reason: string | null } | null>(null);

  const save = async (): Promise<void> => {
    if (busy) return;
    const value = text.trim();
    if (value === "" || value.length > MAX_LENGTH) {
      setProblem(PROBLEMS.invalid);
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await saveAddressKo(props.addressKey, value);
      if (outcome.kind === "ok") {
        setSaved({ placed: outcome.placed, reason: outcome.reason });
        setOpen(false);
        props.onSaved();
      } else {
        setProblem(PROBLEMS[outcome.kind]);
      }
    } catch (error) {
      setProblem(`${describeError(error).cause} ${describeError(error).action}`);
    } finally {
      setBusy(false);
    }
  };

  if (!open) {
    return (
      <div class="section-actions">
        {saved !== null && saved.placed && (
          <Notice tone="ok" icon="check" title="저장하고 위치를 찾았습니다">
            지도에 표시됩니다.
          </Notice>
        )}
        {saved !== null && !saved.placed && (
          <Notice tone="warn" icon="pin-off" title="저장했지만 위치를 찾지 못했습니다">
            {NOT_PLACED[saved.reason ?? ""] ?? NOT_PLACED["UNAVAILABLE"]}
          </Notice>
        )}
        <button type="button" class="btn btn-outline" onClick={() => setOpen(true)}>
          <Icon name="edit" size={16} />
          한글 주소 수정
        </button>
      </div>
    );
  }
  return (
    <form
      class="address-ko-form"
      onSubmit={event => {
        event.preventDefault();
        void save();
      }}
    >
      <label class="sr-only" htmlFor="address-ko-input">
        한글 주소
      </label>
      <textarea
        id="address-ko-input"
        class="text-input"
        rows={3}
        maxLength={MAX_LENGTH}
        value={text}
        placeholder="예: 경기도 성남시 수정구 수정로 123"
        aria-invalid={problem !== null}
        onInput={event => setText(event.currentTarget.value)}
      />
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert" title="저장하지 못했습니다">
          {problem}
        </Notice>
      )}
      <div class="section-actions">
        <button type="submit" class="btn btn-primary" disabled={busy}>
          저장
        </button>
        {props.current !== "" && (
          <button type="button" class="btn btn-outline" disabled={busy} onClick={() => setOpen(false)}>
            취소
          </button>
        )}
      </div>
    </form>
  );
}
