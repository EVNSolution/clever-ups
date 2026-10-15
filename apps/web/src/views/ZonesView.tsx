import type { JSX } from "preact";
import { useMemo, useState } from "preact/hooks";
import { describeError } from "../api/http";
import { refusalText, setZoneDriver } from "../api/masters";
import { DefinitionList, Section, type DetailContent } from "../detail/DetailPanel";
import type { Resource } from "../state/useResource";
import { Badge, IconButton, Notice } from "../ui/Controls";
import { Icon } from "../ui/Icon";
import type { ZoneRow } from "../zones";
import { ResourceList, type ResourceFilter } from "./ResourceList";

export type { ZoneRow };

const searchText = (row: ZoneRow): string => `${row.id} ${row.parentName ?? ""} ${row.driverId ?? ""}`;

const FILTERS: readonly ResourceFilter<ZoneRow>[] = [
  { id: "open", label: "주문 있고 기사 없음", test: row => row.orders > 0 && row.driverId === null },
  { id: "has-orders", label: "주문 있음", test: row => row.orders > 0 },
  { id: "no-shape", label: "경계 없음", test: row => row.shape === null },
];

function ZoneOption(row: ZoneRow): JSX.Element {
  return (
    <>
      <span class="glyph" data-tone={row.orders > 0 ? "route" : "muted"} aria-hidden="true">
        <Icon name="map" />
      </span>
      <span class="option-text">
        <span class="option-title">{row.parentName === null ? row.id : `${row.parentName} › ${row.id}`}</span>
        <span class="option-sub">{row.driverId ?? "기사 미배정"}</span>
      </span>
      <span class="option-meta">
        {row.shape === null ? (
          <Badge tone="warn">경계 없음</Badge>
        ) : (
          <Badge tone={row.orders > 0 && row.driverId === null ? "warn" : "neutral"}>주문 {row.orders}</Badge>
        )}
      </span>
    </>
  );
}

function ZoneSummary({ rows }: { rows: readonly ZoneRow[] }): JSX.Element | null {
  if (rows.length === 0) return null;
  const busy = rows.filter(row => row.orders > 0);
  // A 권역 already counts the orders of its 세부권역, so the sums use 권역 only.
  const tops = busy.filter(row => row.parentName === null);
  const open = tops.filter(row => row.driverId === null);
  return (
    <div class="zone-summary">
      <DefinitionList
        entries={[
          ["권역", `${rows.length}개 · 주문 있음 ${busy.length}개`],
          ["주문", `${tops.reduce((sum, row) => sum + row.orders, 0)}건 · 권역당 최대 ${Math.max(0, ...tops.map(row => row.orders))}건`],
          ["기사 배정", `${tops.length - open.length} / ${tops.length}개`],
          ["미배정 주문", `${open.reduce((sum, row) => sum + row.orders, 0)}건`],
        ]}
      />
    </div>
  );
}

/** Zones with their numbers. Picking one opens its Detail. */
export function ZonesView(props: {
  resource: Resource<ZoneRow>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  scope?: string | undefined;
  /** Opens the 권역 editor. Set for an administrator or the owner. */
  onEdit?: (() => void) | undefined;
}): JSX.Element {
  const rows = useMemo(() => props.resource.items ?? [], [props.resource.items]);
  return (
    <>
      <ZoneSummary rows={rows} />
      <ResourceList
        noun="권역"
        resource={props.resource}
        searchPlaceholder="권역·기사 ID 검색"
        searchText={searchText}
        filters={FILTERS}
        renderOption={ZoneOption}
        selectedId={props.selectedId}
        onSelect={props.onSelect}
        scope={props.scope}
        emptyHint="센터를 하나 고르면 권역이 보입니다. 권역은 권역 편집에서 만듭니다."
        tools={props.onEdit && <IconButton icon="edit" label="권역 편집" onClick={props.onEdit} />}
      />
    </>
  );
}

/** Picks the zone's 담당 기사 among the center's drivers, or none. */
function ZoneDriverPick(props: { row: ZoneRow; drivers: readonly string[]; onChanged: () => void }): JSX.Element {
  const [choice, setChoice] = useState(props.row.driverId ?? "");
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const save = async (): Promise<void> => {
    setBusy(true);
    setProblem(null);
    try {
      const outcome = await setZoneDriver(props.row.zoneId, choice === "" ? null : choice);
      if (outcome.kind === "refused") setProblem(refusalText(outcome.status, ""));
      else props.onChanged();
    } catch (error) {
      setProblem(describeError(error).cause);
    } finally {
      setBusy(false);
    }
  };
  return (
    <div class="route-edit">
      <label class="route-edit-row">
        <span>담당 기사</span>
        <select class="text-input" value={choice} onChange={event => setChoice(event.currentTarget.value)}>
          <option value="">없음</option>
          {props.drivers.map(id => (
            <option key={id} value={id}>
              {id}
            </option>
          ))}
        </select>
      </label>
      <button type="button" class="btn btn-primary" disabled={busy} onClick={() => void save()}>
        지정
      </button>
      {problem !== null && <Notice tone="danger" icon="alert" role="alert" title={problem} />}
    </div>
  );
}

/** [assign] is set for an administrator or the owner: the center's drivers and what to do after a change. */
export function zoneDetail(
  row: ZoneRow,
  assign?: { drivers: readonly string[]; onChanged: () => void },
): DetailContent {
  return {
    title: row.id,
    icon: "map",
    tone: "route",
    body: (
      <>
        <Section title="통계">
          <DefinitionList
            entries={[
              ["상위 권역", row.parentName ?? "없음"],
              ["주문", `${row.orders}건`],
              ["기사", row.driverId ?? "미배정"],
            ]}
          />
        </Section>
        {row.shape === null && (
          <Notice tone="warn" icon="alert" title="경계 없음">
            권역 편집에서 경계를 정해야 주문이 이 권역에 들어옵니다.
          </Notice>
        )}
        {assign !== undefined && (
          <Section title="담당 기사 지정">
            {assign.drivers.length === 0 ? (
              <p class="dispatch-basis">이 센터에 기사가 없습니다. 기사 탭에서 먼저 추가하세요.</p>
            ) : (
              <ZoneDriverPick key={`${row.zoneId}:${row.driverId ?? ""}`} row={row} drivers={assign.drivers} onChanged={assign.onChanged} />
            )}
          </Section>
        )}
        <Notice icon="info" title="담당 기사">
          기사의 담당 권역(메인 권역 우선)입니다. 세부권역에 담당 기사가 없으면 상위 권역의 기사를 따릅니다. 날짜별 변경은 배차에서 합니다.
        </Notice>
      </>
    ),
  };
}
