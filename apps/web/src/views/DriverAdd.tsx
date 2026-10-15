import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { describeError } from "../api/http";
import { createDriver, refusalText } from "../api/masters";
import { Notice } from "../ui/Controls";

/** Registers a driver of the chosen center by DRIVER ID code. Names and phone numbers are never asked. */
export function DriverAdd(props: { centerId: string; onDone: () => void }): JSX.Element {
  const [code, setCode] = useState("");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const add = async (): Promise<void> => {
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await createDriver(props.centerId, code.trim());
      if (outcome.kind === "refused") {
        setProblem(refusalText(outcome.status, "이 센터에 이미 있는 기사 ID입니다."));
        return;
      }
      setCode("");
      props.onDone();
    } catch (error) {
      setProblem(describeError(error).cause);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div class="driver-add">
      <form
        class="route-edit-row"
        onSubmit={event => {
          event.preventDefault();
          void add();
        }}
      >
        <input
          class="text-input"
          value={code}
          maxLength={30}
          aria-label={`${props.centerId} 기사 ID`}
          placeholder="기사 ID (DRIVER ID)"
          onInput={event => setCode(event.currentTarget.value)}
        />
        <button type="submit" class="btn btn-soft" disabled={busy || !/^[A-Za-z0-9_-]{1,30}$/.test(code.trim())}>
          기사 추가
        </button>
      </form>
      {problem !== null && <Notice tone="danger" icon="alert" role="alert" title={problem} />}
    </div>
  );
}
