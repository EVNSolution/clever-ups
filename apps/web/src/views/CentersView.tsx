import type { JSX } from "preact";
import { useState } from "preact/hooks";
import type { Center } from "../api/session";
import { createCenter, refusalText, updateCenter, type WriteOutcome } from "../api/masters";
import { describeError } from "../api/http";
import type { Resource } from "../state/useResource";
import { Notice } from "../ui/Controls";

/** One form for a new center or a change to one: code (new only), name, depot address. */
function CenterForm(props: { center?: Center; onDone: () => void; onCancel?: () => void }): JSX.Element {
  const editing = props.center !== undefined;
  const [code, setCode] = useState(props.center?.id ?? "");
  const [name, setName] = useState(props.center?.name.replace(/^[A-Z0-9]+ · /, "") ?? "");
  const [address, setAddress] = useState(props.center?.address ?? "");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const save = async (): Promise<void> => {
    setBusy(true);
    setProblem(null);
    try {
      const outcome: WriteOutcome = editing
        ? await updateCenter(code, name.trim(), address.trim())
        : await createCenter(code.trim(), name.trim(), address.trim());
      if (outcome.kind === "refused") {
        setProblem(refusalText(outcome.status, "이미 있는 센터 코드입니다."));
        return;
      }
      if (!editing) {
        setCode("");
        setName("");
        setAddress("");
      }
      props.onDone();
    } catch (error) {
      setProblem(describeError(error).cause);
    } finally {
      setBusy(false);
    }
  };
  const valid = /^[A-Za-z0-9]{3,12}$/.test(code.trim()) && name.trim() !== "";
  return (
    <div class="route-edit">
      {!editing && (
        <label class="route-edit-row">
          <span>코드</span>
          <input class="text-input" value={code} maxLength={12} placeholder="KRSNA" onInput={event => setCode(event.currentTarget.value.toUpperCase())} />
        </label>
      )}
      <label class="route-edit-row">
        <span>이름</span>
        <input class="text-input" value={name} maxLength={200} placeholder="성남" onInput={event => setName(event.currentTarget.value)} />
      </label>
      <label class="route-edit-row">
        <span>차고지</span>
        <input class="text-input" value={address} maxLength={200} placeholder="한글 주소" onInput={event => setAddress(event.currentTarget.value)} />
      </label>
      {problem !== null && <Notice tone="danger" icon="alert" role="alert" title={problem} />}
      <div class="approval-actions">
        {props.onCancel && (
          <button type="button" class="btn btn-soft" disabled={busy} onClick={props.onCancel}>
            취소
          </button>
        )}
        <button type="button" class="btn btn-primary" disabled={busy || !valid} onClick={() => void save()}>
          {editing ? "저장" : "센터 만들기"}
        </button>
      </div>
    </div>
  );
}

/**
 * The owner's center page: every center, a form for a new one, and changes to name and depot address. A new
 * center reaches other people when the owner gives it to them on approval.
 */
export function CentersView(props: { resource: Resource<Center>; onChanged: () => void }): JSX.Element {
  const [editing, setEditing] = useState<string | null>(null);
  const items = props.resource.items ?? [];
  return (
    <div class="manage centers-page">
      <section>
        <h3 class="section-title">새 센터</h3>
        <CenterForm onDone={props.onChanged} />
        <p class="dispatch-basis">코드는 LOCATION NAME(예: KRSNA)입니다. 차고지 주소에서 기사가 출발합니다.</p>
      </section>
      <section>
        <h3 class="section-title">센터 {items.length}곳</h3>
        <ul class="zone-edit-list">
          {items.map(center =>
            editing === center.id ? (
              <li key={center.id}>
                <CenterForm
                  center={center}
                  onDone={() => {
                    setEditing(null);
                    props.onChanged();
                  }}
                  onCancel={() => setEditing(null)}
                />
              </li>
            ) : (
              <li key={center.id} class="zone-edit-row">
                <span>
                  {center.name}
                  {center.address ? ` · ${center.address}` : " · 차고지 없음"}
                </span>
                <button type="button" class="btn btn-soft" onClick={() => setEditing(center.id)}>
                  고치기
                </button>
              </li>
            ),
          )}
        </ul>
      </section>
    </div>
  );
}
