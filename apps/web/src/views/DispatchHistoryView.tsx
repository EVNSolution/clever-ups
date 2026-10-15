import type { JSX } from "preact";
import { useState } from "preact/hooks";
import type { ChangeOutcome, DispatchSummary, SavedRoute } from "../api/dispatches";
import { describeError } from "../api/http";
import { DefinitionList, Section, type DetailContent } from "../detail/DetailPanel";
import { formatClock } from "../format";
import { hasPosition } from "../geo";
import { orderTitle } from "../order";
import type { DispatchState } from "../state/useDispatch";
import type { Resource } from "../state/useResource";
import { Badge, Notice, type Tone } from "../ui/Controls";
import { Icon } from "../ui/Icon";
import { Skeleton } from "../ui/States";
import { km, minutes, Timeline } from "./DispatchView";
import type { OrderRow } from "./OrdersView";
import { ResourceList } from "./ResourceList";

const STATUS: Record<DispatchSummary["status"], { label: string; tone: Tone }> = {
  DRAFT: { label: "초안", tone: "neutral" },
  SENT: { label: "전송됨", tone: "ok" },
  CANCELLED: { label: "취소", tone: "danger" },
  REPLACED: { label: "대체됨", tone: "warn" },
};

const searchText = (run: DispatchSummary): string => STATUS[run.status].label;

function RunOption(run: DispatchSummary): JSX.Element {
  const status = STATUS[run.status];
  return (
    <>
      <span class="glyph" data-tone="route" aria-hidden="true">
        <Icon name="route" />
      </span>
      <span class="option-text">
        <span class="option-title">
          {formatClock(new Date(run.createdAt))} 저장 · {formatClock(new Date(run.departAt))} 출발
        </span>
        <span class="option-sub">
          기사 {run.routes} · 주문 {run.stops} · 미할당 {run.unassigned}
        </span>
      </span>
      <span class="option-meta">
        <Badge tone={status.tone}>{status.label}</Badge>
      </span>
    </>
  );
}

/** The dispatches saved for the chosen center and day, newest first. */
export function DispatchHistoryView(props: {
  resource: Resource<DispatchSummary>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  scope?: string | undefined;
}): JSX.Element {
  return (
    <ResourceList
      noun="배차"
      resource={props.resource}
      searchPlaceholder="상태 검색 (초안·대체됨)"
      searchText={searchText}
      renderOption={RunOption}
      selectedId={props.selectedId}
      onSelect={props.onSelect}
      scope={props.scope}
      emptyHint="배차에서 '배차 저장'을 누르면 이곳에 쌓입니다. 센터 하나를 골라야 보입니다."
    />
  );
}

/** One saved route as a button; the picked one opens its timeline, read from the saved values. */
function SavedRouteItem(props: {
  route: SavedRoute;
  orders: ReadonlyMap<string, OrderRow>;
  picked: boolean;
  onPick: () => void;
  origin: string | null;
  runId: string;
  /** Present while the run is a draft and the person may change it. */
  changer?: { free: readonly string[]; onChange: RunActions["onChangeDriver"] } | undefined;
}): JSX.Element {
  const { route } = props;
  const label = `${route.driverId}${route.routeCode === null ? "" : ` · ${route.routeCode}`}`;
  return (
    <li class="saved-route">
      <button type="button" class="saved-route-head" aria-expanded={props.picked} onClick={props.onPick}>
        <Icon name={props.picked ? "chevron-down" : "chevron-right"} size={16} />
        <span class="saved-route-name">{label}</span>
        <span class="saved-route-meta">
          {route.stops.length}건 · {km(route.distanceMeters)} · {minutes(route.durationSeconds)}
        </span>
      </button>
      {props.picked && props.changer !== undefined && (
        <DriverChanger from={route.driverId} free={props.changer.free} onChange={props.changer.onChange} />
      )}
      {props.picked && (
        <Timeline
          route={{ id: route.driverId, zoneIds: [], orders: route.stops.flatMap(stop => props.orders.get(stop.orderId) ?? []) }}
          state={{ status: "ok", sequence: { ...route, unassigned: [], stops: route.stops.map(stop => ({ id: stop.orderId, arrival: stop.arrival, distanceMeters: stop.distanceMeters })) } }}
          start={{ origin: route.start === null ? null : (props.origin ?? "출발지"), departAt: formatClock(new Date(route.departAt)) }}
          reported={{
            results: new Map(route.stops.flatMap(stop => (stop.result ? [[stop.orderId, stop.result] as const] : []))),
            photoUrl: id => `/api/dispatches/${encodeURIComponent(props.runId)}/photos/${encodeURIComponent(id)}`,
          }}
        />
      )}
    </li>
  );
}

export interface RunActions {
  onSend: () => Promise<ChangeOutcome>;
  onCancel: () => Promise<ChangeOutcome>;
  onChangeDriver: (from: string, to: string) => Promise<ChangeOutcome>;
  onAddOrders: (driverId: string, orderIds: string[]) => Promise<ChangeOutcome>;
  /** Driver codes of the center. */
  drivers: readonly string[];
}

const REFUSED: Record<number, string> = {
  403: "배차를 바꿀 권한이 없습니다.",
  404: "배차를 찾지 못했습니다. 목록을 다시 불러오세요.",
  409: "다른 사람이 먼저 바꿨습니다. 상태를 확인하세요.",
  503: "서버에 데이터베이스가 연결되지 않았습니다.",
};

/**
 * Send and cancel for a saved run. Sending a draft makes its routes the drivers'; cancelling asks for a second
 * press, since drivers may already hold a sent run.
 */
/** One change at a time, with the reason shown when the server refuses it. */
function useChange(): { busy: boolean; note: string | null; run: (action: () => Promise<ChangeOutcome>) => Promise<void> } {
  const [busy, setBusy] = useState(false);
  const [note, setNote] = useState<string | null>(null);
  const run = async (action: () => Promise<ChangeOutcome>): Promise<void> => {
    setBusy(true);
    setNote(null);
    try {
      const outcome = await action();
      if (outcome.kind === "refused") setNote(REFUSED[outcome.status] ?? `바꾸지 못했습니다 (HTTP ${outcome.status}).`);
    } catch (error) {
      setNote(`${describeError(error).cause} ${describeError(error).action}`);
    } finally {
      setBusy(false);
    }
  };
  return { busy, note, run };
}

function RefusedNote({ note }: { note: string | null }): JSX.Element | null {
  return note === null ? null : (
    <Notice tone="warn" icon="alert">
      {note}
    </Notice>
  );
}

function RunButtons({ status, actions }: { status: DispatchSummary["status"]; actions: RunActions }): JSX.Element | null {
  const { busy, note, run: change } = useChange();
  const [confirming, setConfirming] = useState(false);
  if (status !== "DRAFT" && status !== "SENT") return null;
  const run = async (action: () => Promise<ChangeOutcome>): Promise<void> => {
    await change(action);
    setConfirming(false);
  };
  return (
    <div class="run-actions">
      {status === "DRAFT" && (
        <button type="button" class="btn btn-primary" disabled={busy} onClick={() => void run(actions.onSend)}>
          노선 전송
        </button>
      )}
      {confirming ? (
        <button type="button" class="btn btn-outline" data-tone="danger" disabled={busy} onClick={() => void run(actions.onCancel)}>
          한 번 더 누르면 취소합니다
        </button>
      ) : (
        <button type="button" class="btn btn-outline" disabled={busy} onClick={() => setConfirming(true)}>
          배차 취소
        </button>
      )}
      <RefusedNote note={note} />
    </div>
  );
}

/** Gives a draft route to a driver of the center who has no route in the run. */
function DriverChanger(props: { from: string; free: readonly string[]; onChange: RunActions["onChangeDriver"] }): JSX.Element {
  const { busy, note, run } = useChange();
  const [to, setTo] = useState(props.free[0] ?? "");
  return (
    <div class="route-edit">
      <label class="route-edit-row">
        <span>기사 변경</span>
        <select class="text-input" value={to} disabled={props.free.length === 0} onChange={event => setTo(event.currentTarget.value)}>
          {props.free.length === 0 && <option value="">바꿀 수 있는 기사가 없습니다</option>}
          {props.free.map(code => (
            <option key={code} value={code}>
              {code}
            </option>
          ))}
        </select>
      </label>
      <button type="button" class="btn btn-outline" disabled={busy || to === ""} onClick={() => void run(() => props.onChange(props.from, to))}>
        변경
      </button>
      <RefusedNote note={note} />
    </div>
  );
}

/** The orders of the run no driver got; the chosen ones join a route, which is ordered again. */
function LooseOrders(props: {
  ids: readonly string[];
  orders: ReadonlyMap<string, OrderRow>;
  routes: readonly string[];
  onAdd: RunActions["onAddOrders"] | undefined;
}): JSX.Element {
  const { busy, note, run } = useChange();
  const [chosen, setChosen] = useState<ReadonlySet<string>>(new Set());
  const [target, setTarget] = useState(props.routes[0] ?? "");
  const toggle = (id: string): void =>
    setChosen(previous => {
      const next = new Set(previous);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  return (
    <details class="loose-orders">
      <summary>미할당 주문 {props.ids.length}건 보기</summary>
      <ul class="loose-list">
        {props.ids.map(id => {
          const order = props.orders.get(id);
          const placed = order !== undefined && hasPosition(order);
          return (
            <li key={id}>
              <label class="loose-item" data-disabled={!placed}>
                {props.onAdd !== undefined && <input type="checkbox" disabled={!placed} checked={chosen.has(id)} onChange={() => toggle(id)} />}
                <span>{order === undefined ? id : orderTitle(order)}</span>
                {!placed && <Badge icon="pin-off">위치 없음</Badge>}
              </label>
            </li>
          );
        })}
      </ul>
      {props.onAdd !== undefined && props.routes.length > 0 && (
        <div class="route-edit">
          <label class="route-edit-row">
            <span>추가할 노선</span>
            <select class="text-input" value={target} onChange={event => setTarget(event.currentTarget.value)}>
              {props.routes.map(code => (
                <option key={code} value={code}>
                  {code}
                </option>
              ))}
            </select>
          </label>
          <button
            type="button"
            class="btn btn-outline"
            disabled={busy || chosen.size === 0}
            onClick={() => void run(() => props.onAdd!(target, [...chosen])).then(() => setChosen(new Set()))}
          >
            선택한 {chosen.size}건 추가
          </button>
          <RefusedNote note={note} />
        </div>
      )}
    </details>
  );
}

/** A saved run in Detail: what it holds, then each driver's route as it was computed. */
export function runDetail(
  state: DispatchState,
  orders: ReadonlyMap<string, OrderRow>,
  picked: string | null,
  onPick: (driverId: string | null) => void,
  origin: string | null,
  actions?: RunActions,
): DetailContent | null {
  if (state.status === "idle" || state.status === "missing") return null;
  if (state.status === "loading") return { title: "저장된 배차", icon: "route", tone: "route", body: <Skeleton rows={4} /> };
  if (state.status === "error") {
    const { cause, action } = describeError(state.error);
    const body = (
      <Notice tone="warn" icon="alert" title="배차를 읽지 못했습니다">
        {cause} {action}
      </Notice>
    );
    return { title: "저장된 배차", icon: "route", tone: "route", body };
  }
  const { run } = state;
  const editable = actions !== undefined && run.summary.status === "DRAFT";
  const routed = new Set(run.routes.map(route => route.driverId));
  const free = actions?.drivers.filter(code => !routed.has(code)) ?? [];
  const title = `${formatClock(new Date(run.summary.createdAt))} 저장 배차`;
  const body = (
    <>
      <Section title="요약">
        {actions !== undefined && <RunButtons key={run.summary.id} status={run.summary.status} actions={actions} />}
        <DefinitionList
          entries={[
            ["상태", STATUS[run.summary.status].label],
            ["출발", formatClock(new Date(run.summary.departAt))],
            ["기사", `${run.routes.length}명`],
            ["주문", `${run.summary.stops}건 (미할당 ${run.unassigned.length}건)`],
          ]}
        />
      </Section>
      <Section title="노선">
        <ul class="saved-routes">
          {run.routes.map(route => (
            <SavedRouteItem
              key={route.driverId}
              route={route}
              orders={orders}
              picked={route.driverId === picked}
              onPick={() => onPick(route.driverId === picked ? null : route.driverId)}
              origin={origin}
              runId={run.summary.id}
              changer={editable ? { free, onChange: actions.onChangeDriver } : undefined}
            />
          ))}
        </ul>
      </Section>
      {run.unassigned.length > 0 && (
        <Section title="미할당">
          <LooseOrders
            key={run.summary.id}
            ids={run.unassigned}
            orders={orders}
            routes={run.routes.map(route => route.driverId)}
            onAdd={editable ? actions.onAddOrders : undefined}
          />
        </Section>
      )}
    </>
  );
  return { title, icon: "route", tone: "route", body };
}
