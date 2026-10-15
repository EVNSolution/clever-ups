import type { JSX } from "preact";
import { useState } from "preact/hooks";
import { unitKeyOf, unitsOf, type UnitLevel } from "../assign";
import { DefinitionList, Section, type DetailContent } from "../detail/DetailPanel";
import { hasPosition } from "../geo";
import type { MapOutline, MapShape } from "../map/MapView";
import { outerEdges } from "../map/outline";
import { orderTitle } from "../order";
import type { ZoneRow } from "../zones";
import type { OrderRow } from "./OrdersView";

/** 배정: hand out the zones no driver has yet. 조정: see everyone's share and move zones or single orders. */
export type DispatchMode = "assign" | "adjust";

// ponytail: eight colours repeat past eight drivers; a center with more needs a longer palette.
const PALETTE = ["#1f77b4", "#d62728", "#2ca02c", "#9467bd", "#ff7f0e", "#17becf", "#8c564b", "#e377c2"];
const NO_DRIVER = "#8c8c8c";

export const colorOf = (driverId: string, driverIds: readonly string[]): string =>
  PALETTE[Math.max(0, driverIds.indexOf(driverId)) % PALETTE.length] ?? NO_DRIVER;

interface UnitStat {
  row: ZoneRow;
  orders: OrderRow[];
  drivers: Set<string | null>;
}

/** Each unit of the level with its positioned orders of the day and the drivers they go to now. */
export function unitStats(rows: readonly ZoneRow[], orders: readonly OrderRow[], level: UnitLevel): UnitStat[] {
  const byKey = new Map<string, OrderRow[]>();
  for (const order of orders) {
    if (!hasPosition(order)) continue;
    const key = unitKeyOf(order, rows, level);
    if (key === null) continue;
    byKey.set(key, [...(byKey.get(key) ?? []), order]);
  }
  return unitsOf(rows, level).map(row => {
    const mine = byKey.get(row.id) ?? [];
    return { row, orders: mine, drivers: new Set(mine.map(order => order.driverId)) };
  });
}

const driverText = (drivers: Set<string | null>): string =>
  [...drivers].map(driver => driver ?? "미배정").join(", ") || "주문 없음";

/**
 * The map of the dispatch modes. 배정 shows only units with orders no driver has, with that count. 조정 shows
 * every unit with orders in its driver's colour, grey where an order has no driver.
 */
export function assignShapes(
  stats: readonly UnitStat[],
  mode: DispatchMode,
  picked: ReadonlySet<string>,
  driverIds: readonly string[],
): MapShape[] {
  return stats.flatMap(({ row, orders, drivers }) => {
    if (row.shape === null || orders.length === 0) return [];
    const open = orders.filter(order => order.driverId === null).length;
    if (mode === "assign" && open === 0) return [];
    const only = drivers.size === 1 ? [...drivers][0] : undefined;
    const color = mode === "assign" ? undefined : drivers.has(null) ? NO_DRIVER : only ? colorOf(only, driverIds) : undefined;
    return [
      {
        id: `unit:${row.id}`,
        role: picked.has(row.id) ? ("picked" as const) : ("zone" as const),
        geometry: row.shape,
        label: mode === "assign" ? `${row.id} ${open}` : row.id,
        color: picked.has(row.id) ? undefined : color,
        card: [
          ["권역", row.parentName === null ? row.id : `${row.parentName} › ${row.id}`],
          ["배송지", `${orders.length}곳`],
          ["미배정", `${open}곳`],
          ["기사", driverText(drivers)],
        ],
      },
    ];
  });
}

/**
 * The lines of 조정: each driver's outer edge in the driver's colour, so one share reads as one area while the
 * zones inside stay faint; and, at the 권역 level, the 세부권역 edges that the map shows once zoomed in.
 */
export function assignOutlines(
  stats: readonly UnitStat[],
  rows: readonly ZoneRow[],
  level: UnitLevel,
  driverIds: readonly string[],
): MapOutline[] {
  const byDriver = new Map<string, NonNullable<ZoneRow["shape"]>[]>();
  for (const { row, orders, drivers } of stats) {
    const only = drivers.size === 1 ? [...drivers][0] : undefined;
    if (row.shape === null || orders.length === 0 || only === undefined || only === null) continue;
    byDriver.set(only, [...(byDriver.get(only) ?? []), row.shape]);
  }
  const drivers = [...byDriver].map(([driverId, shapes]) => ({
    kind: "driver" as const,
    color: colorOf(driverId, driverIds),
    segments: outerEdges(shapes),
  }));
  if (level === "sub") return drivers;
  const inner = rows.flatMap(row => (row.parentName !== null && row.shape !== null ? [row.shape] : []));
  const rings = inner.flatMap(shape => ((shape.type === "Polygon" ? [shape.coordinates] : shape.coordinates) as [number, number][][][]).flat());
  return [...drivers, { kind: "detail" as const, segments: rings }];
}

/** A driver list and one action button. */
function DriverPick(props: { drivers: readonly string[]; current: string | null; action: string; onPick: (driverId: string | null) => void }): JSX.Element {
  const [choice, setChoice] = useState(props.current ?? "");
  return (
    <div class="route-edit">
      <label class="route-edit-row">
        <span>기사</span>
        <select class="text-input" value={choice} onChange={event => setChoice(event.currentTarget.value)}>
          <option value="">미배정</option>
          {props.drivers.map(id => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>
      <button type="button" class="btn btn-primary" onClick={() => props.onPick(choice === "" ? null : choice)}>
        {props.action}
      </button>
    </div>
  );
}

/** The Detail for picked units: their numbers and the driver to give them to. */
export function unitsDetail(
  stats: readonly UnitStat[],
  picked: ReadonlySet<string>,
  mode: DispatchMode,
  driverIds: readonly string[],
  onAssign: (driverId: string | null) => void,
): DetailContent {
  const chosen = stats.filter(stat => picked.has(stat.row.id));
  const orders = chosen.reduce((sum, stat) => sum + stat.orders.length, 0);
  const drivers = new Set(chosen.flatMap(stat => [...stat.drivers]));
  const only = drivers.size === 1 ? ([...drivers][0] ?? null) : null;
  return {
    title: chosen.length === 1 ? (chosen[0]?.row.id ?? "") : `권역 ${chosen.length}개`,
    icon: "map",
    tone: "route",
    body: (
      <>
        <Section title="고른 권역">
          <DefinitionList
            entries={[
              ["권역", chosen.map(stat => stat.row.id).join(", ")],
              ["배송지", `${orders}곳`],
              ["지금 기사", driverText(drivers)],
            ]}
          />
        </Section>
        <Section title={mode === "assign" ? "배정" : "다시 주기"}>
          <DriverPick key={[...picked].join()} drivers={driverIds} current={only} action={mode === "assign" ? "배정" : "다시 주기"} onPick={onAssign} />
          <p class="dispatch-basis">권역 안의 주문이 모두 이 기사에게 갑니다. 기사의 담당 권역은 바뀌지 않습니다.</p>
        </Section>
      </>
    ),
  };
}

/** The Detail for one order picked on the map in 조정: move it to another driver. */
export function orderMoveDetail(order: OrderRow, driverIds: readonly string[], onMove: (driverId: string | null) => void): DetailContent {
  return {
    title: orderTitle(order),
    icon: "package",
    tone: "order",
    body: (
      <>
        <Section title="주문">
          <DefinitionList
            entries={[
              ["운송장", order.trackingNumber],
              ["권역", order.zoneId ?? "없음"],
              ["지금 기사", order.driverId ?? "미배정"],
            ]}
          />
        </Section>
        <Section title="옮기기">
          <DriverPick key={order.id} drivers={driverIds} current={order.driverId} action="옮기기" onPick={onMove} />
          <p class="dispatch-basis">이 주문만 옮깁니다. 되돌리기로 취소할 수 있습니다.</p>
        </Section>
      </>
    ),
  };
}

/** The two switches over the map on 배차: the mode and the unit one click assigns. */
export function DispatchSwitches(props: {
  mode: DispatchMode;
  onMode: (mode: DispatchMode) => void;
  level: UnitLevel;
  onLevel: (level: UnitLevel) => void;
}): JSX.Element {
  return (
    <div class="map-switches">
      <fieldset class="seg map-toggle-inline" aria-label="배차 모드">
        {(["assign", "adjust"] as const).map(mode => (
          <button type="button" key={mode} class="seg-tab" aria-pressed={props.mode === mode} onClick={() => props.onMode(mode)}>
            {mode === "assign" ? "배정" : "조정"}
          </button>
        ))}
      </fieldset>
      <fieldset class="seg map-toggle-inline" aria-label="배정 단위">
        {(["zone", "sub"] as const).map(level => (
          <button type="button" key={level} class="seg-tab" aria-pressed={props.level === level} onClick={() => props.onLevel(level)}>
            {level === "zone" ? "권역" : "세부권역"}
          </button>
        ))}
      </fieldset>
    </div>
  );
}
