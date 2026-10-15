import type { JSX } from "preact";
import type { DispatchDetail, SavedRoute } from "../api/dispatches";
import { describeError } from "../api/http";
import type { Driver } from "../api/resources";
import { DefinitionList, Section, type DetailContent } from "../detail/DetailPanel";
import { formatAge, formatClock, positionAge } from "../format";
import type { DispatchState } from "../state/useDispatch";
import type { Resource } from "../state/useResource";
import { Badge, Notice } from "../ui/Controls";
import { Icon } from "../ui/Icon";
import { EmptyState, Skeleton } from "../ui/States";
import { km, Timeline } from "./DispatchView";
import type { OrderRow } from "./OrdersView";
import { ResourceList } from "./ResourceList";

/** One driver of the sent run, with how far the route has come and where the phone last was. */
export interface LiveRoute {
  id: string;
  route: SavedRoute;
  done: number;
  exceptions: number;
  driver: Driver | undefined;
}

/** The routes of the run, each with its progress and the driver's last known place. */
export function liveRoutes(run: DispatchDetail, drivers: readonly Driver[]): LiveRoute[] {
  const byId = new Map(drivers.map(driver => [driver.id, driver] as const));
  return run.routes.map(route => ({
    id: route.driverId,
    route,
    done: route.stops.filter(stop => stop.result?.outcome === "DELIVERED").length,
    exceptions: route.stops.filter(stop => stop.result?.outcome === "EXCEPTION").length,
    driver: byId.get(route.driverId),
  }));
}

const searchText = (live: LiveRoute): string => `${live.id} ${live.route.routeCode ?? ""}`;

function PlaceBadge({ driver }: { driver: Driver | undefined }): JSX.Element {
  const age = positionAge(driver?.positionAt, Date.now());
  if (driver?.lat === null || driver?.lat === undefined || !age.known) return <Badge icon="pin-off">위치 없음</Badge>;
  return age.stale ? (
    <Badge tone="warn" icon="clock">
      {formatAge(age.ms)}
    </Badge>
  ) : (
    <Badge tone="ok" icon="truck">
      {formatAge(age.ms)}
    </Badge>
  );
}

function LiveOption(live: LiveRoute): JSX.Element {
  const total = live.route.stops.length;
  return (
    <>
      <span class="glyph" data-tone="driver" aria-hidden="true">
        <Icon name="truck" />
      </span>
      <span class="option-text">
        <span class="option-title">
          {live.id}
          {live.route.routeCode === null ? "" : ` · ${live.route.routeCode}`}
        </span>
        <span class="option-sub">
          완료 {live.done}/{total}
          {live.exceptions > 0 ? ` · 예외 ${live.exceptions}` : ""}
        </span>
      </span>
      <span class="option-meta">
        <PlaceBadge driver={live.driver} />
      </span>
    </>
  );
}

/**
 * 운행 현황: the run sent for the chosen center and day, driver by driver. Each row says how many stops are done and
 * how old the driver's last place is; an old place is marked, so it is not read as live.
 */
export function MonitorView(props: {
  run: DispatchState;
  resource: Resource<LiveRoute>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  onRefresh: () => void;
  scope?: string | undefined;
}): JSX.Element {
  const { run } = props;
  if (run.status === "idle") {
    return <EmptyState icon="route" title="오늘 전송된 배차가 없습니다" hint="배차 내역에서 노선을 전송하면 이곳에서 운행을 봅니다." />;
  }
  if (run.status === "loading") return <Skeleton rows={4} />;
  if (run.status !== "ok") {
    return (
      <Notice tone="warn" icon="alert" title="운행을 읽지 못했습니다">
        {run.status === "error" ? describeError(run.error).cause : "배차를 찾지 못했습니다."}
      </Notice>
    );
  }
  const stops = run.run.routes.flatMap(route => route.stops);
  const done = stops.filter(stop => stop.result?.outcome === "DELIVERED").length;
  const exceptions = stops.filter(stop => stop.result?.outcome === "EXCEPTION").length;
  return (
    <div class="dispatch">
      <div class="dispatch-head">
        <div class="stats">
          <div class="stat">
            <span class="stat-value">{run.run.routes.length}대</span>
            <span class="stat-label">운행 차량</span>
          </div>
          <div class="stat">
            <span class="stat-value">
              {done}/{stops.length}
            </span>
            <span class="stat-label">배송 완료</span>
          </div>
          <div class="stat">
            <span class="stat-value">{exceptions}건</span>
            <span class="stat-label">예외</span>
          </div>
        </div>
        <button type="button" class="btn btn-outline" onClick={props.onRefresh}>
          새로 고침
        </button>
      </div>
      <ResourceList
        noun="차량"
        resource={props.resource}
        searchPlaceholder="기사 ID·구역 검색"
        searchText={searchText}
        renderOption={LiveOption}
        selectedId={props.selectedId}
        onSelect={props.onSelect}
        scope={props.scope}
        emptyHint="전송된 배차에 노선이 없습니다."
      />
    </div>
  );
}

/** One driver in Detail: the last place, then the route as a timeline with what was reported at each stop. */
export function liveDetail(live: LiveRoute, runId: string, orders: ReadonlyMap<string, OrderRow>, origin: string | null): DetailContent {
  const { route, driver } = live;
  const placed = driver?.positionAt ? new Date(driver.positionAt) : null;
  return {
    title: route.routeCode === null ? live.id : `${live.id} · ${route.routeCode}`,
    icon: "truck",
    tone: "driver",
    body: (
      <>
        <Section title="지금">
          <DefinitionList
            entries={[
              ["완료", `${live.done}/${route.stops.length}건${live.exceptions > 0 ? ` · 예외 ${live.exceptions}건` : ""}`],
              ["마지막 위치", placed === null ? "기사 앱에서 온 위치가 없습니다" : `${formatClock(placed)} 기록`],
              ["노선", `${km(route.distanceMeters)} · ${formatClock(new Date(route.departAt))} 출발`],
            ]}
          />
        </Section>
        <Section title="진행 순서">
          <Timeline
            route={{ id: route.driverId, zoneIds: [], orders: route.stops.flatMap(stop => orders.get(stop.orderId) ?? []) }}
            state={{
              status: "ok",
              sequence: {
                ...route,
                unassigned: [],
                stops: route.stops.map(stop => ({ id: stop.orderId, arrival: stop.arrival, distanceMeters: stop.distanceMeters })),
              },
            }}
            start={{ origin: route.start === null ? null : (origin ?? "출발지"), departAt: formatClock(new Date(route.departAt)) }}
            reported={{
              results: new Map(route.stops.flatMap(stop => (stop.result ? [[stop.orderId, stop.result] as const] : []))),
              photoUrl: id => `/api/dispatches/${encodeURIComponent(runId)}/photos/${encodeURIComponent(id)}`,
            }}
          />
        </Section>
      </>
    ),
  };
}
