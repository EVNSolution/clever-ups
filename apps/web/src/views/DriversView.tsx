import type { JSX } from "preact";
import type { Driver } from "../api/resources";
import { DefinitionList, Section, type DetailContent } from "../detail/DetailPanel";
import { formatAge, formatDateTime, positionAge } from "../format";
import { hasPosition } from "../geo";
import { useNow } from "../state/useNow";
import type { Resource } from "../state/useResource";
import { Badge, Notice } from "../ui/Controls";
import { Icon } from "../ui/Icon";
import { ResourceList, type ResourceFilter } from "./ResourceList";

/** A driver with the zones given to the driver on the zone tab, and the orders in them. */
export type DriverRow = Driver & { zoneIds: readonly string[]; orders: number };

const searchText = (driver: DriverRow): string => `${driver.id} ${driver.zoneIds.join(" ")} ${driver.centerId}`;

const FILTERS: readonly ResourceFilter<DriverRow>[] = [
  { id: "no-zone", label: "권역 미배정", test: driver => driver.zoneIds.length === 0 },
  { id: "no-position", label: "위치 없음", test: driver => !hasPosition(driver) },
];

/** How old the position is. An old position is marked, so it is not read as live. */
function PositionBadge({ driver }: { driver: Driver }): JSX.Element {
  if (!hasPosition(driver)) return <Badge icon="pin-off">위치 없음</Badge>;
  const age = positionAge(driver.positionAt, Date.now());
  if (!age.known) {
    return (
      <Badge tone="warn" icon="clock">
        시각 불명
      </Badge>
    );
  }
  return age.stale ? (
    <Badge tone="warn" icon="clock">
      오래된 위치 · {formatAge(age.ms)}
    </Badge>
  ) : (
    <Badge icon="clock">{formatAge(age.ms)}</Badge>
  );
}

function DriverOption(driver: DriverRow): JSX.Element {
  return (
    <>
      <span class="glyph" data-tone="driver" aria-hidden="true">
        <Icon name="user" />
      </span>
      <span class="option-text">
        <span class="option-title">{driver.id}</span>
        <span class="option-sub">
          {driver.zoneIds.length > 0 ? `권역 ${driver.zoneIds.length}개 · 주문 ${driver.orders}건` : "권역 미배정"}
        </span>
      </span>
      <span class="option-meta">
        <PositionBadge driver={driver} />
      </span>
    </>
  );
}

export function DriversView(props: {
  resource: Resource<DriverRow>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  scope?: string | undefined;
}): JSX.Element {
  useNow(); // re-draws the rows now and then, so the ages keep moving
  return (
    <ResourceList
      noun="기사"
      resource={props.resource}
      searchPlaceholder="기사 ID·권역 검색"
      searchText={searchText}
      filters={FILTERS}
      renderOption={DriverOption}
      selectedId={props.selectedId}
      onSelect={props.onSelect}
      scope={props.scope}
      emptyHint="아직 데이터 출처가 연결되지 않았다면 정상입니다. 출처가 연결되면 이곳에 나타납니다."
    />
  );
}

export function driverDetail(driver: DriverRow, centerName: string | undefined): DetailContent {
  const age = positionAge(driver.positionAt, Date.now());
  const recorded = typeof driver.positionAt === "string" ? new Date(driver.positionAt) : null;
  return {
    title: driver.id,
    icon: "user",
    tone: "driver",
    body: (
      <>
        <Section title="센터">
          <DefinitionList
            entries={[
              ["이름", centerName ?? "—"],
              ["코드", driver.centerId],
            ]}
          />
        </Section>
        <Section title="배정">
          {driver.zoneIds.length > 0 ? (
            <DefinitionList
              entries={[
                ["권역", driver.zoneIds.join(", ")],
                ["주문", `${driver.orders}건`],
              ]}
            />
          ) : (
            <Notice icon="info" title="권역 미배정">
              권역 탭에서 권역을 고르고 이 기사를 배정하면 표시됩니다.
            </Notice>
          )}
        </Section>
        <Section title="위치">
          {hasPosition(driver) ? (
            <>
              <DefinitionList
                entries={[
                  ["경도", driver.lng.toFixed(6)],
                  ["위도", driver.lat.toFixed(6)],
                  [
                    "기록 시각",
                    age.known && recorded !== null ? `${formatDateTime(recorded)} (${formatAge(age.ms)})` : "알 수 없음",
                  ],
                ]}
              />
              {age.known && age.stale && (
                <Notice tone="warn" icon="clock" title="오래된 위치">
                  마지막 위치가 오래되어 지금 위치가 아닐 수 있습니다.
                </Notice>
              )}
            </>
          ) : (
            <Notice icon="pin-off" title="위치 없음">
              기사 앱에서 위치가 오면 표시됩니다.
            </Notice>
          )}
        </Section>
        <Section title="식별 정보">
          <DefinitionList entries={[["기사 ID", driver.id]]} />
        </Section>
      </>
    ),
  };
}
