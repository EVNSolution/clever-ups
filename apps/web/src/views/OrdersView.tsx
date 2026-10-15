import type { JSX } from "preact";
import type { Order } from "../api/resources";
import { DefinitionList, Section, type DetailContent } from "../detail/DetailPanel";
import { hasPosition } from "../geo";
import { orderOriginal, orderTitle } from "../order";
import type { Resource } from "../state/useResource";
import { Badge, IconButton, Notice } from "../ui/Controls";
import { Icon } from "../ui/Icon";
import { AddressKoEditor } from "./AddressKoEditor";
import { ResourceList, type ResourceFilter } from "./ResourceList";

/** An order with the zone it falls in and the driver of that zone. The driver is a suggestion: a person decides. */
export type OrderRow = Order & { zoneId: string | null; driverId: string | null };

const searchText = (order: OrderRow): string =>
  [
    orderTitle(order),
    order.address1,
    order.address2,
    order.detailKo,
    order.customerName,
    order.companyName,
    order.trackingNumber,
    order.postalCode,
    order.driverId,
    order.zoneId,
  ].join(" ");

export const ORDER_FILTERS: readonly ResourceFilter<OrderRow>[] = [
  { id: "unlocated", label: "위치 미확정", test: order => !hasPosition(order) },
  { id: "no-korean", label: "한글 주소 없음", test: order => !hasText(order.addressKo) },
  { id: "no-driver", label: "기사 미배정", test: order => hasPosition(order) && order.driverId === null },
];

const hasText = (value: string | null | undefined): value is string => value !== null && value !== undefined && value !== "";

function OrderOption(order: OrderRow): JSX.Element {
  const located = hasPosition(order);
  return (
    <>
      <span class="glyph" data-tone={located ? "order" : "muted"} aria-hidden="true">
        <Icon name={located ? "package" : "pin-off"} />
      </span>
      <span class="option-text">
        <span class="option-title">{orderTitle(order)}</span>
        {orderOriginal(order) !== "" && <span class="option-sub option-sub-muted">{orderOriginal(order)}</span>}
      </span>
      {order.driverId !== null && (
        <span class="option-meta">
          <Badge icon="user">{order.driverId}</Badge>
        </span>
      )}
    </>
  );
}

export function OrdersView(props: {
  resource: Resource<OrderRow>;
  selectedId: string | null;
  onSelect: (id: string | null) => void;
  scope?: string | undefined;
  /** Filters on when the list mounts; the status pill remounts the list with one. */
  initialFilters?: readonly string[] | undefined;
  /** Opens every order of the day as a table. */
  onOpenSheet: () => void;
}): JSX.Element {
  return (
    <ResourceList
      noun="주문"
      resource={props.resource}
      searchPlaceholder="주소·운송장·수취인 검색"
      searchText={searchText}
      filters={ORDER_FILTERS}
      initialFilters={props.initialFilters}
      tools={<IconButton icon="expand" label="주문 전체 셀 보기" onClick={props.onOpenSheet} />}
      renderOption={OrderOption}
      selectedId={props.selectedId}
      onSelect={props.onSelect}
      scope={props.scope}
      emptyHint="아직 데이터 출처가 연결되지 않았다면 정상입니다. 출처가 연결되면 이곳에 나타납니다."
    />
  );
}

export interface OrderDetailContext {
  flyTo: (lng: number, lat: number) => void;
  /** Set for an administrator or the owner: the Korean address may be typed. */
  onAddressSaved?: (() => void) | undefined;
}

export function orderDetail(order: OrderRow, context: OrderDetailContext): DetailContent {
  const { flyTo } = context;
  const title = orderTitle(order);
  return {
    title,
    icon: "package",
    tone: "order",
    body: (
      <>
        <Section title="센터">
          <DefinitionList entries={[["센터", hasText(order.centerCode) ? order.centerCode : "미지정"]]} />
        </Section>
        <Section title="배정">
          {order.zoneId === null ? (
            <Notice tone="warn" icon="pin-off" title="권역 없음">
              위치가 확정되어야 권역과 기사가 정해집니다.
            </Notice>
          ) : (
            <DefinitionList
              entries={[
                ["권역", order.zoneId],
                ["기사", order.driverId ?? "미배정"],
              ]}
            />
          )}
        </Section>
        <Section title="수취인">
          <DefinitionList
            entries={[
              ["고객명", order.customerName || "—"],
              ["상호", order.companyName || "—"],
            ]}
          />
        </Section>
        <Section title="배송지">
          <DefinitionList
            entries={[
              ["한글 주소", [order.addressKo, order.detailKo].filter(hasText).join(" ") || "없음"],
              ["주소1", order.address1 || "—"],
              ["주소2", order.address2 || "—"],
              ["우편번호", order.postalCode || "—"],
            ]}
          />
        </Section>
        {context.onAddressSaved !== undefined && hasText(order.addressKey) && (
          <Section title="한글 주소 입력">
            <AddressKoEditor
              key={order.addressKey}
              addressKey={order.addressKey}
              current={order.addressKo ?? ""}
              onSaved={context.onAddressSaved}
            />
          </Section>
        )}
        <Section title="위치">
          {hasPosition(order) ? (
            <>
              <DefinitionList entries={[["경도", order.lng.toFixed(6)], ["위도", order.lat.toFixed(6)]]} />
              <div class="section-actions">
                <button type="button" class="btn btn-outline" onClick={() => flyTo(order.lng, order.lat)}>
                  <Icon name="pin" size={16} />
                  지도에서 보기
                </button>
              </div>
            </>
          ) : (
            <Notice tone="warn" icon="pin-off" title="위치 미확정">
              주소를 정제해 좌표를 얻기 전에는 지도에 표시되지 않습니다.
            </Notice>
          )}
        </Section>
        <Section title="식별 정보">
          <DefinitionList
            entries={[
              ["운송장 번호", order.trackingNumber],
              ["Job", order.jobId || "—"],
            ]}
          />
        </Section>
      </>
    ),
  };
}
