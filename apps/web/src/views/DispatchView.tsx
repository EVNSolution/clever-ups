import type { JSX } from "preact";
import { useState } from "preact/hooks";
import type { DeliveryResult, DispatchSummary, SaveOutcome } from "../api/dispatches";
import { describeError } from "../api/http";
import { DefinitionList, Section, type DetailContent } from "../detail/DetailPanel";
import type { Plan, Route } from "../dispatch";
import { formatClock } from "../format";
import { orderTitle } from "../order";
import type { Resource } from "../state/useResource";
import type { SequenceState } from "../state/useSequence";
import { Badge, Notice } from "../ui/Controls";
import { Icon } from "../ui/Icon";
import { Skeleton } from "../ui/States";
import type { OrderRow } from "./OrdersView";
import { ResourceList } from "./ResourceList";

export type RouteRow = Route<OrderRow>;

const searchText = (route: RouteRow): string => `${route.id} ${route.zoneIds.join(" ")}`;

function RouteOption(route: RouteRow): JSX.Element {
  return (
    <>
      <span class="glyph" data-tone="route" aria-hidden="true">
        <Icon name="route" />
      </span>
      <span class="option-text">
        <span class="option-title">{route.id}</span>
        <span class="option-sub">
          권역 {route.zoneIds.length}개 · 주문 {route.orders.length}건
        </span>
      </span>
    </>
  );
}

function Stat({ label, value }: { label: string; value: string }): JSX.Element {
  return (
    <div class="stat">
      <span class="stat-value">{value}</span>
      <span class="stat-label">{label}</span>
    </div>
  );
}

/** Dispatch by zone: each order goes to the driver of its zone. The result follows the day, the center and the zones. */
export function DispatchView(props: {
  plan: Plan<OrderRow> | null;
  resource: Resource<RouteRow>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  scope?: string | undefined;
  /** When the drivers leave, as HH:mm on the chosen day. */
  departAt: string;
  onDepartAt: (value: string) => void;
  /** The depot drivers leave from, or null when the center has no place: then each leaves from its first order. */
  origin: string | null;
  /** Saves the dispatch. Left out when the person may not save, or no single center is chosen. */
  onSave?: (() => Promise<SaveOutcome>) | undefined;
  /** Takes back the last change made on the map. Left out when there is nothing to take back. */
  onUndo?: (() => void) | undefined;
  /** Drops every change: the day goes back to the zones' drivers. */
  onReset: () => void;
  /** The saved run the day was opened from, if any. */
  base: DispatchSummary | null;
}): JSX.Element {
  const { plan } = props;
  const [saving, setSaving] = useState(false);
  const [saved, setSaved] = useState<{ tone: "ok" | "warn"; text: string } | null>(null);
  const save = async (): Promise<void> => {
    if (props.onSave === undefined) return;
    setSaving(true);
    setSaved(null);
    try {
      setSaved(saveMessage(await props.onSave()));
    } catch (error) {
      setSaved({ tone: "warn", text: `${describeError(error).cause} ${describeError(error).action}` });
    } finally {
      setSaving(false);
    }
  };
  const assigned = plan?.routes.reduce((sum, route) => sum + route.orders.length, 0) ?? 0;
  const average = plan === null || plan.routes.length === 0 ? 0 : assigned / plan.routes.length;
  return (
    <div class="dispatch">
      <div class="dispatch-head">
        <p class="dispatch-basis">
          <strong>권역 배차</strong> · 권역의 담당 기사가 기본이고, 지도의 배정·조정으로 이 날만 바꿉니다.
        </p>
        <p class="dispatch-basis">
          {props.base === null
            ? "저장된 배차가 없어 담당 기사로 시작했습니다."
            : `저장된 배차(${props.base.status === "SENT" ? "전송됨" : "초안"}, ${formatClock(new Date(props.base.createdAt))})에서 시작했습니다.`}
        </p>
        <div class="approval-actions">
          <button type="button" class="btn btn-soft" disabled={props.onUndo === undefined} onClick={props.onUndo}>
            되돌리기
          </button>
          <button type="button" class="btn btn-soft" onClick={props.onReset}>
            초기화
          </button>
        </div>
        <label class="depart">
          <span>출발 시각</span>
          <input
            type="time"
            class="text-input"
            value={props.departAt}
            onChange={event => {
              const value = event.currentTarget.value;
              if (value !== "") props.onDepartAt(value);
            }}
          />
        </label>
        <p class="dispatch-basis">
          출발지 · {props.origin ?? "센터 위치가 없어 기사마다 첫 주문에서 출발합니다."}
        </p>
        {plan !== null && (
          <>
            <div class="stats">
              <Stat label="배차 기사" value={`${plan.routes.length}명`} />
              <Stat label="배정 주문" value={`${assigned}건`} />
              <Stat label="기사당 평균" value={`${average.toFixed(1)}건`} />
            </div>
            {(plan.unassigned.length > 0 || plan.idleDriverIds.length > 0) && (
              <Notice tone="warn" icon="alert" title={`미할당 업무 ${plan.unassigned.length}건 · 미할당 기사 ${plan.idleDriverIds.length}명`}>
                위치가 없는 주문은 관리 &gt; 주문에서, 기사 없는 권역은 관리 &gt; 권역에서 정리합니다.
              </Notice>
            )}
            {props.onSave !== undefined && (
              <button type="button" class="btn btn-primary" disabled={saving || plan.routes.length === 0} onClick={() => void save()}>
                {saving ? "순서 계산하고 저장하는 중…" : "배차 저장"}
              </button>
            )}
            {saved !== null && (
              <output aria-live="polite">
                <Notice tone={saved.tone} icon={saved.tone === "ok" ? "check" : "alert"}>
                  {saved.text}
                </Notice>
              </output>
            )}
          </>
        )}
      </div>
      <ResourceList
        noun="배차"
        resource={props.resource}
        searchPlaceholder="기사 ID·권역 검색"
        searchText={searchText}
        renderOption={RouteOption}
        selectedId={props.selectedId}
        onSelect={props.onSelect}
        scope={props.scope}
        emptyHint="권역에 경계와 담당 기사가 있거나, 지도에서 배정하면 이곳에 기사별 배차가 나타납니다."
      />
    </div>
  );
}

/** What a save means to the person: the counts, or why it was refused. */
function saveMessage(outcome: SaveOutcome): { tone: "ok" | "warn"; text: string } {
  if (outcome.kind === "saved") {
    const { routes, stops, unassigned } = outcome.summary;
    return { tone: "ok", text: `저장했습니다. 노선 ${routes}개 · 주문 ${stops}건 · 미할당 ${unassigned}건. 배차 내역에서 볼 수 있습니다.` };
  }
  const reasons: Record<number, string> = {
    400: "주문이나 기사가 서버와 맞지 않습니다. 날짜를 다시 불러온 뒤 저장하세요.",
    403: "이 센터의 배차를 저장할 권한이 없습니다. 관리자에게 문의하세요.",
    503: "서버에 데이터베이스나 경로 엔진이 연결되지 않았습니다.",
  };
  return { tone: "warn", text: reasons[outcome.status] ?? `저장하지 못했습니다 (HTTP ${outcome.status}).` };
}

export const km = (meters: number): string => `${(meters / 1000).toFixed(1)}km`;

export function minutes(seconds: number): string {
  const total = Math.round(seconds / 60);
  return total < 60 ? `${total}분` : `${Math.floor(total / 60)}시간 ${total % 60}분`;
}

/** The orders of the route as they come, for when there is no visiting order. */
function Unordered({ route }: { route: RouteRow }): JSX.Element {
  return (
    <ul class="route-orders">
      {route.orders.map(order => (
        <li key={order.id}>{orderTitle(order)}</li>
      ))}
    </ul>
  );
}

/** One driver's stops from top to bottom in visiting order: number, arrival, address, road distance so far. */
export interface Start {
  origin: string | null;
  departAt: string;
}

/** What the driver reported at a stop, and where its photos are, for a saved run. */
export interface Reported {
  results: ReadonlyMap<string, DeliveryResult>;
  photoUrl: (photoId: string) => string;
}

/** One line of what happened at a stop: arrival, then delivered or the exception, with the photos taken there. */
function Outcome({ result, photoUrl }: { result: DeliveryResult; photoUrl: Reported["photoUrl"] }): JSX.Element {
  const at = (value: string | null | undefined): string => (value ? formatClock(new Date(value)) : "");
  const done =
    result.outcome === "DELIVERED"
      ? `배송 완료(KB) ${at(result.completedAt)}${result.podMethod === "FACE" ? ` · 대면 ${result.recipientName ?? ""}` : " · 비대면"}`
      : result.outcome === "EXCEPTION"
        ? `예외 ${result.code ?? ""} ${at(result.completedAt)}`
        : null;
  return (
    <span class="timeline-outcome">
      {result.arrivedAt && <span>도착 {at(result.arrivedAt)}</span>}
      {done !== null && (
        <Badge tone={result.outcome === "DELIVERED" ? "ok" : "warn"} icon={result.outcome === "DELIVERED" ? "check" : "alert"}>
          {done}
        </Badge>
      )}
      {result.photoIds.length > 0 && (
        <span class="timeline-photos" aria-label={`현장 확인 사진 ${result.photoIds.length}장`}>
          {result.photoIds.map((id, index) => (
            <a key={id} href={photoUrl(id)} target="_blank" rel="noreferrer">
              <img src={photoUrl(id)} alt={`현장 확인 ${index + 1}`} loading="lazy" />
            </a>
          ))}
        </span>
      )}
    </span>
  );
}

export function Timeline({
  route,
  state,
  start,
  reported,
}: {
  route: RouteRow;
  state: SequenceState;
  start: Start;
  reported?: Reported | undefined;
}): JSX.Element {
  if (state.status === "idle" || state.status === "loading") return <Skeleton rows={4} />;
  if (state.status !== "ok") {
    const hint =
      state.status === "unavailable"
        ? "서버에 경로 엔진이 연결되지 않았거나 응답하지 않습니다."
        : `${describeError(state.error).cause} ${describeError(state.error).action}`;
    return (
      <>
        <Notice tone="warn" icon="alert" title="방문 순서를 계산하지 못했습니다">
          {hint}
        </Notice>
        <Unordered route={route} />
      </>
    );
  }
  const { sequence } = state;
  const byId = new Map(route.orders.map(order => [order.id, order] as const));
  const last = sequence.stops.at(-1);
  return (
    <>
      <DefinitionList
        entries={[
          ["총 거리", km(sequence.distanceMeters)],
          ["총 소요", minutes(sequence.durationSeconds)],
          ["마지막 도착", last === undefined ? "—" : formatClock(new Date(last.arrival))],
        ]}
      />
      <ol class="timeline">
        {start.origin !== null && (
          <li class="timeline-step">
            <span class="timeline-number" data-kind="start" aria-hidden="true">
              <Icon name="building" size={12} />
            </span>
            <div class="timeline-body">
              <span class="timeline-time">{start.departAt} 출발</span>
              <span class="timeline-title">{start.origin}</span>
            </div>
          </li>
        )}
        {sequence.stops.map((stop, index) => {
          const order = byId.get(stop.id);
          return (
            <li key={stop.id} class="timeline-step">
              <span class="timeline-number" aria-hidden="true">
                {index + 1}
              </span>
              <div class="timeline-body">
                <span class="timeline-time">
                  <span class="sr-only">{index + 1}번째, </span>
                  {formatClock(new Date(stop.arrival))} 도착 예정 · {km(stop.distanceMeters)}
                </span>
                <span class="timeline-title">{order === undefined ? stop.id : orderTitle(order)}</span>
                {reported?.results.get(stop.id) !== undefined && (
                  <Outcome result={reported.results.get(stop.id)!} photoUrl={reported.photoUrl} />
                )}
              </div>
            </li>
          );
        })}
      </ol>
      {sequence.unassigned.length > 0 && (
        <Notice tone="warn" icon="alert" title={`경로에 넣지 못한 주문 ${sequence.unassigned.length}건`}>
          도로로 갈 수 없는 위치일 수 있습니다. 주문의 위치를 확인하세요.
        </Notice>
      )}
    </>
  );
}

export function routeDetail(route: RouteRow, sequence: SequenceState, start: Start): DetailContent {
  return {
    title: route.id,
    icon: "route",
    tone: "route",
    body: (
      <>
        <Section title="배정">
          <DefinitionList
            entries={[
              ["권역", route.zoneIds.join(", ")],
              ["주문", `${route.orders.length}건`],
            ]}
          />
        </Section>
        <Section title="방문 순서">
          <Timeline route={route} state={sequence} start={start} />
        </Section>
      </>
    ),
  };
}
