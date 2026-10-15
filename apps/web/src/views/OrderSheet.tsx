import type { JSX } from "preact";
import { useEffect, useMemo, useRef, useState } from "preact/hooks";
import { saveAddressKo } from "../api/addresses";
import { saveOrderEdits, type EditableField, type OrderEdit } from "../api/orderEdits";
import type { Order } from "../api/resources";
import { Notice } from "../ui/Controls";

type Column = EditableField | "addressKo";
const COLUMNS: readonly { id: Column; label: string }[] = [
  { id: "customerName", label: "고객명" },
  { id: "companyName", label: "회사명" },
  { id: "address1", label: "주소1" },
  { id: "address2", label: "주소2" },
  { id: "postalCode", label: "우편번호" },
  { id: "addressKo", label: "한글 주소" },
];
type Draft = Record<string, Partial<Record<Column, string>>>;

const valueOf = (order: Order, column: Column): string => order[column] ?? "";

/**
 * Every order of the day as a table, full screen. Administrators and the owner change cells and save at once;
 * others only read. Ours24 is not written: the API keeps the changed cells. An emptied cell goes back to Ours24.
 * The Korean address is saved per address, so every order at that address gets it.
 */
export function OrderSheet(props: {
  day: string;
  orders: readonly Order[];
  canEdit: boolean;
  onClose: () => void;
  onSaved: () => void;
}): JSX.Element {
  const dialog = useRef<HTMLDialogElement>(null);
  const [draft, setDraft] = useState<Draft>({});
  const [saving, setSaving] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    dialog.current?.showModal();
  }, []);

  const changes = useMemo(() => {
    const byId = new Map(props.orders.map(order => [order.id, order] as const));
    return Object.entries(draft).flatMap(([id, cells]) => {
      const order = byId.get(id);
      if (order === undefined) return [];
      return Object.entries(cells)
        .filter(([column, value]) => value.trim() !== valueOf(order, column as Column))
        .map(([column, value]) => ({ order, column: column as Column, value: value.trim() }));
    });
  }, [draft, props.orders]);

  const edit = (id: string, column: Column, value: string): void =>
    setDraft(previous => ({ ...previous, [id]: { ...previous[id], [column]: value } }));

  const save = async (): Promise<void> => {
    setSaving(true);
    setProblem(null);
    try {
      const cells: OrderEdit[] = changes
        .filter(change => change.column !== "addressKo")
        .map(change => ({ orderId: change.order.id, field: change.column as EditableField, value: change.value === "" ? null : change.value }));
      if (cells.length > 0) {
        const outcome = await saveOrderEdits(props.day, cells);
        if (outcome.kind !== "ok") throw new Error(outcome.kind);
      }
      // One Korean address per address: the last typed value of an address wins.
      const korean = new Map(
        changes
          .filter(change => change.column === "addressKo" && change.value !== "" && change.order.addressKey)
          .map(change => [change.order.addressKey as string, change.value] as const),
      );
      for (const [key, text] of korean) {
        const outcome = await saveAddressKo(key, text);
        if (outcome.kind !== "ok") throw new Error(outcome.kind);
      }
      props.onSaved();
      props.onClose();
    } catch (error) {
      const kind = error instanceof Error ? error.message : "";
      setProblem(
        kind === "forbidden"
          ? "관리자와 개발 관리자만 저장할 수 있습니다."
          : kind === "missing"
            ? "목록에 없는 주문이 있습니다. 새로고침한 뒤 다시 고쳐 주세요."
            : "저장하지 못했습니다. 잠시 뒤 다시 시도하세요.",
      );
      setSaving(false);
    }
  };

  return (
    <dialog class="sheet" ref={dialog} aria-labelledby="sheet-title" onCancel={props.onClose}>
      <header class="sheet-head">
        <h2 id="sheet-title">{props.day} 주문 {props.orders.length}건</h2>
        <span class="sheet-note">
          {props.canEdit ? `고친 칸 ${changes.length} · 비운 칸은 Ours24 값으로 돌아갑니다. Ours24에는 쓰지 않습니다.` : "보기 전용"}
        </span>
        <div class="sheet-actions">
          <button type="button" class="btn btn-outline" onClick={props.onClose} disabled={saving}>
            취소
          </button>
          {props.canEdit && (
            <button type="button" class="btn btn-primary" onClick={() => void save()} disabled={saving || changes.length === 0}>
              {saving ? "저장 중" : "저장"}
            </button>
          )}
        </div>
      </header>
      {problem !== null && (
        <Notice tone="danger" icon="alert" role="alert" title={problem}>
          <p>고친 내용은 그대로 남아 있습니다.</p>
        </Notice>
      )}
      <div class="sheet-body">
        <table>
          <thead>
            <tr>
              <th scope="col">운송장</th>
              {COLUMNS.map(column => (
                <th scope="col" key={column.id}>{column.label}</th>
              ))}
            </tr>
          </thead>
          <tbody>
            {props.orders.map(order => (
              <tr key={order.id}>
                <th scope="row">{order.trackingNumber}</th>
                {COLUMNS.map(column => {
                  const typed = draft[order.id]?.[column.id];
                  const value = typed ?? valueOf(order, column.id);
                  const changed = typed !== undefined && typed.trim() !== valueOf(order, column.id);
                  const edited = (order.edited ?? []).includes(column.id as EditableField);
                  return (
                    <td key={column.id} data-changed={changed} data-edited={edited}>
                      {props.canEdit ? (
                        <input
                          value={value}
                          maxLength={200}
                          disabled={column.id === "addressKo" && !order.addressKey}
                          aria-label={`${order.trackingNumber} ${column.label}`}
                          onInput={event => edit(order.id, column.id, event.currentTarget.value)}
                        />
                      ) : (
                        value
                      )}
                    </td>
                  );
                })}
              </tr>
            ))}
          </tbody>
        </table>
      </div>
    </dialog>
  );
}
