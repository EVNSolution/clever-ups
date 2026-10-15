import type { JSX } from "preact";
import { useCallback, useEffect, useMemo, useRef, useState } from "preact/hooks";
import { listAccessRequests, listMembers } from "./api/access";
import { addRouteOrders, changeDispatch, changeRouteDriver, listDispatches, saveDispatch, type ChangeOutcome } from "./api/dispatches";
import { clearOrderCache, listDrivers, listOrders, warmOrders, type Driver, type Order } from "./api/resources";
import { describeError, isForbidden } from "./api/http";
import { listCenters, logout, type Account, type Center } from "./api/session";
import { DetailPanel, type DetailContent } from "./detail/DetailPanel";
import { planByZone } from "./dispatch";
import { decodePolyline } from "./polyline";
import { formatTime } from "./format";
import { hasPosition } from "./geo";
import { buildZones } from "./zones";
import { assignOrder, assignUnits, driverOf, type UnitLevel } from "./assign";
import { useAssignment } from "./state/useAssignment";
import { assignOutlines, assignShapes, DispatchSwitches, orderMoveDetail, unitStats, unitsDetail, type DispatchMode } from "./views/AssignPanel";
import { AccountBlock } from "./header/AccountBlock";
import { CenterSelect } from "./header/CenterSelect";
import { MapView, type CameraCommand, type MapPoint, type MapShape, type PointKind } from "./map/MapView";
import { useCenterSelection } from "./state/useCenterSelection";
import { addDays, today } from "./api/day";
import { DayPicker } from "./ui/DayPicker";
import { useHealth, type HealthState } from "./state/useHealth";
import { useMe, type MeState } from "./state/useMe";
import { hiddenResource } from "./state/hidden";
import { useResource } from "./state/useResource";
import { useDispatch } from "./state/useDispatch";
import { useSequence } from "./state/useSequence";
import { EmptyState, Skeleton } from "./ui/States";
import { Icon } from "./ui/Icon";
import { MenuRail, type MenuGroup } from "./ui/MenuRail";
import { Tabs } from "./ui/Tabs";
import { ApprovalsView } from "./views/ApprovalsView";
import { PhoneProofPanel } from "./views/PhoneProofPanel";
import { RedeemPanel } from "./views/RedeemPanel";
import { RequestAccessPanel } from "./views/RequestAccessPanel";
import { DispatchHistoryView, runDetail } from "./views/DispatchHistoryView";
import { liveDetail, liveRoutes, MonitorView } from "./views/MonitorView";
import { DispatchView, routeDetail } from "./views/DispatchView";
import { DriversView, driverDetail, type DriverRow } from "./views/DriversView";
import { ZonesView, zoneDetail } from "./views/ZonesView";
import { listZones } from "./api/zones";
import { LoginView } from "./views/LoginView";
import { OrderSheet } from "./views/OrderSheet";
import { CentersView } from "./views/CentersView";
import { DriverAdd } from "./views/DriverAdd";
import { useZoneEditor } from "./views/ZoneEditor";
import { ORDER_FILTERS, OrdersView, orderDetail, type OrderRow } from "./views/OrdersView";

type TabId = "drivers" | "orders" | "zones";
type MapViewMode = "zones" | "orders";
type MenuId = "prep" | "dispatch" | "history" | "monitor" | "approvals" | "centers";
/** What the sidebar shows: one of the prep tabs, or the dispatch list. Each keeps its own selection. */
type PaneId = TabId | "dispatch" | "history" | "monitor";
type Selection = Record<PaneId, string | null>;

const NO_SELECTION: Selection = { drivers: null, orders: null, zones: null, dispatch: null, history: null, monitor: null };
const KIND_TAB: Record<PointKind, TabId> = { order: "orders", driver: "drivers" };

/** Shown only when the API does not answer. */
function ApiDown({ health }: { health: HealthState }): JSX.Element {
  return (
    <output class="status-chip" data-tone="danger" title={health.checkedAt === null ? "" : `마지막 확인 ${formatTime(health.checkedAt)}`}>
      <Icon name="alert" size={12} />
      API 연결 실패
    </output>
  );
}

/** The orders of the day in one line. Each part opens the order list with its filter on. */
function DayStatus(props: { orders: readonly OrderRow[]; onOpen: (filter: string | null) => void }): JSX.Element {
  const parts = ORDER_FILTERS.filter(filter => filter.id !== "no-korean")
    .map(filter => ({ id: filter.id, label: filter.label, count: props.orders.filter(filter.test).length }))
    .filter(part => part.count > 0);
  return (
    <fieldset class="day-status" aria-label="그날 주문 요약">
      <button type="button" onClick={() => props.onOpen(null)}>
        주문 <b>{props.orders.length}</b>
      </button>
      {parts.map(part => (
        <button type="button" key={part.id} data-tone="warning" onClick={() => props.onOpen(part.id)}>
          {part.label} <b>{part.count}</b>
        </button>
      ))}
    </fieldset>
  );
}

type MeResult = MeState & { reload: () => void; refresh: () => void };

/**
 * Nothing of the work area is loaded before a person is signed in with an active membership, so the screens
 * before that make no data requests at all. Signing out unmounts the workspace and drops what it held.
 */
export function App(): JSX.Element {
  const me = useMe();
  const signOut = useCallback((): void => {
    clearOrderCache();
    void logout().finally(me.reload);
  }, [me.reload]);

  if (me.status === "loading") {
    return (
      <main class="auth-screen">
        <div class="login-form">
          <Skeleton rows={2} />
        </div>
      </main>
    );
  }
  if (me.status === "error") {
    return (
      <main class="auth-screen">
        <div class="login-form">
          <EmptyState icon="alert" title="계정을 확인하지 못했습니다" hint={`${describeError(me.error).cause} ${describeError(me.error).action}`}>
            <button type="button" class="btn btn-outline" onClick={me.reload}>
              다시 시도
            </button>
          </EmptyState>
        </div>
      </main>
    );
  }
  if (me.status === "signed-out") return <LoginView onSignedIn={me.reload} />;
  return <Workspace me={me} account={me.account} onSignOut={signOut} />;
}

/**
 * The normal page for every signed-in account. An account whose membership is not active gets the same page
 * with every list empty and no data request sent: only the information is hidden, there is no extra screen.
 */
function Workspace({ me, account, onSignOut }: { me: MeResult; account: Account; onSignOut: () => void }): JSX.Element {
  const canSee = account.membership === "active";
  const health = useHealth();
  const liveCenters = useResource(listCenters, canSee);
  const centers = canSee ? liveCenters : hiddenResource<Center>();
  const center = useCenterSelection(centers);
  const centerId = center.selectedId ?? undefined;
  const selectedCenter = centers.items?.find(item => item.id === center.selectedId);
  const centerName = selectedCenter?.name;
  // A driver leaves from the depot of the center when the server could place it, else from the first order.
  const depot =
    typeof selectedCenter?.lng === "number" && typeof selectedCenter.lat === "number"
      ? { lng: selectedCenter.lng, lat: selectedCenter.lat }
      : null;
  const origin = depot === null ? null : (selectedCenter?.address ?? centerName ?? null);

  // The lists wait for the center list, then follow the selected center. A new center starts them over.
  const loadDrivers = useCallback(() => listDrivers(centerId), [centerId]);
  const liveDrivers = useResource(loadDrivers, canSee && center.settled);
  // The operating day is chosen on the page; orders follow the day and the selected center.
  const [day, setDay] = useState(today);
  const loadOrders = useCallback(() => listOrders(day, centerId), [day, centerId]);
  const liveOrders = useResource(loadOrders, canSee && center.settled);
  const drivers = canSee ? liveDrivers : hiddenResource<Driver>();
  const orders = canSee ? liveOrders : hiddenResource<Order>();

  // Zones are the ones drawn in the 권역 editor. An order falls in the zone its position is in, and gets the
  // zone's driver: a suggestion that dispatch confirms.
  const loadZones = useCallback(
    () => (centerId === undefined ? Promise.resolve({ items: [], incomplete: false }) : listZones(centerId).then(items => ({ items, incomplete: false }))),
    [centerId],
  );
  const liveZones = useResource(loadZones, canSee && center.settled);
  const positioned = useMemo(() => (orders.items ?? []).filter(hasPosition), [orders.items]);
  const built = useMemo(
    () => buildZones(liveZones.items ?? [], drivers.items ?? [], positioned),
    [liveZones.items, drivers.items, positioned],
  );
  const zoneRows = built.rows;
  const zones = useMemo(
    () => ({ ...liveZones, items: liveZones.items === null ? null : zoneRows }),
    [liveZones, zoneRows],
  );
  const orderRows = useMemo<OrderRow[] | null>(() => {
    if (orders.items === null) return null;
    const driverOf = new Map(zoneRows.map(row => [row.id, row.driverId] as const));
    return orders.items.map(order => {
      const zoneId = hasPosition(order) ? built.zoneOf(order.lng, order.lat) : null;
      return { ...order, zoneId, driverId: zoneId === null ? null : (driverOf.get(zoneId) ?? null) };
    });
  }, [orders.items, zoneRows, built]);
  const orderList = useMemo(() => ({ ...orders, items: orderRows }), [orders, orderRows]);
  const driverRows = useMemo<DriverRow[] | null>(
    () =>
      drivers.items?.map(driver => ({
        ...driver,
        zoneIds: zoneRows.filter(row => row.driverId === driver.id).map(row => row.id),
        orders: (orderRows ?? []).filter(order => order.driverId === driver.id).length,
      })) ?? null,
    [drivers.items, zoneRows, orderRows],
  );
  const driverList = useMemo(() => ({ ...drivers, items: driverRows }), [drivers, driverRows]);
  // The day's dispatch: each order's driver after the changes made on 배차 (배정 and 조정) over the zone default.
  const assignment = useAssignment(centerId, day, canSee && center.settled);
  const assignedRows = useMemo(
    () => orderRows?.map(order => ({ ...order, driverId: driverOf(order, zoneRows, assignment.changes) })) ?? null,
    [orderRows, zoneRows, assignment.changes],
  );
  const plan = useMemo(
    () => {
      if (assignedRows === null || driverRows === null) return null;
      const result = planByZone(assignedRows, driverRows);
      // A route's zones are the zones of the orders it got, which 배정 and 조정 may change for the day.
      const routes = result.routes.map(route => ({
        ...route,
        zoneIds: [...new Set(route.orders.flatMap(order => (order.zoneId === null ? [] : [order.zoneId])))],
      }));
      return { ...result, routes };
    },
    [assignedRows, driverRows],
  );
  const [dispatchMode, setDispatchMode] = useState<DispatchMode>("assign");
  const [unitLevel, setUnitLevel] = useState<UnitLevel>("zone");
  const [pickedUnits, setPickedUnits] = useState<ReadonlySet<string>>(new Set());
  const [pickedOrder, setPickedOrder] = useState<string | null>(null);
  const clearPicks = useCallback((): void => {
    setPickedUnits(new Set());
    setPickedOrder(null);
  }, []);
  useEffect(clearPicks, [dispatchMode, unitLevel, centerId, day, clearPicks]);
  const unitRows = useMemo(() => unitStats(zoneRows, assignedRows ?? [], unitLevel), [zoneRows, assignedRows, unitLevel]);
  const driverIds = useMemo(() => (drivers.items ?? []).map(driver => driver.id), [drivers.items]);
  const routeList = useMemo(
    () => ({
      ...orders,
      items: plan?.routes ?? null,
      loading: orders.loading || drivers.loading,
      error: orders.error ?? drivers.error,
      reload: () => {
        orders.reload();
        drivers.reload();
      },
    }),
    [orders, drivers, plan],
  );

  // Only the owner decides access requests (see ApprovalPolicy on the server). The tab is hidden for others.
  const canDecide = canSee && account.role === "owner";
  const liveRequests = useResource(listAccessRequests, canDecide);
  const requests = canDecide ? liveRequests : hiddenResource<Awaited<ReturnType<typeof listAccessRequests>>["items"][number]>();
  const liveMembers = useResource(listMembers, canDecide);
  const members = canDecide ? liveMembers : hiddenResource<Awaited<ReturnType<typeof listMembers>>["items"][number]>();

  // An account that sees nothing and has no waiting request may ask, on this same page.
  const canAsk = !canSee && account.membership !== "pending";
  const [asking, setAsking] = useState(false);
  const canRedeem = account.role !== "owner";
  const [redeeming, setRedeeming] = useState(false);

  // A 403 means the membership changed since the page loaded. Ask who this is again, and the lists hide.
  const forbidden = [liveCenters, liveDrivers, liveOrders, liveRequests, liveMembers].some(item => isForbidden(item.error));
  useEffect(() => {
    if (forbidden) me.refresh();
  }, [forbidden]);

  // An account that sees nothing yet checks again when the person comes back to the tab, so an approval
  // shows up without a sign-out.
  useEffect(() => {
    if (canSee) return;
    const onVisible = (): void => {
      if (document.visibilityState === "visible") me.refresh();
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => document.removeEventListener("visibilitychange", onVisible);
  }, [canSee, me.refresh]);

  const [tab, setTab] = useState<TabId>("orders");
  // What the 관리 map draws: zones or order pins. The 권역 and 주문 tabs set it; the map toggle changes it alone.
  const [mapView, setMapView] = useState<MapViewMode>("orders");
  const changeTab = useCallback((next: TabId): void => {
    setTab(next);
    if (next !== "drivers") setMapView(next);
  }, []);
  // The rail picks what the sidebar shows. Approvals exist for the owner only; anyone else stays on prep.
  const [menu, setMenu] = useState<MenuId>("prep");
  const current: MenuId = (menu === "approvals" || menu === "centers") && !canDecide ? "prep" : menu;
  const onPage = current === "approvals" || current === "centers";
  const pane: PaneId = current === "dispatch" || current === "history" || current === "monitor" ? current : tab;
  const groups = useMemo<MenuGroup<MenuId>[]>(
    () => [
      { label: "관리", items: [{ id: "prep", label: "기사·주문·권역", icon: "package" }] },
      {
        label: "배차",
        items: [
          { id: "dispatch", label: "배차", icon: "route" },
          { id: "history", label: "배차 내역", icon: "legend" },
        ],
      },
      { label: "모니터링", items: [{ id: "monitor", label: "운행 현황", icon: "truck" }] },
      ...(canDecide
        ? [
            {
              label: "계정",
              items: [
                { id: "approvals" as const, label: "승인", icon: "user-check" as const },
                { id: "centers" as const, label: "센터", icon: "building" as const },
              ],
            },
          ]
        : []),
    ],
    [canDecide],
  );
  const [selection, setSelection] = useState<Selection>(NO_SELECTION);
  // The status pill opens the order list with one filter: a new key remounts the list with that filter on.
  const [orderJump, setOrderJump] = useState<{ n: number; filters: string[] }>({ n: 0, filters: [] });
  const openOrders = (filter: string | null): void => {
    setMenu("prep");
    setTab("orders");
    setOrderJump(jump => ({ n: jump.n + 1, filters: filter === null ? [] : [filter] }));
  };
  const [command, setCommand] = useState<CameraCommand | null>(null);
  const commandCount = useRef(0);
  const plotRef = useRef<HTMLElement>(null);
  const trigger = useRef<HTMLElement | null>(null);

  // When the API comes back, retry what failed while it was down, so nobody has to click each retry.
  const wasDown = useRef(false);
  useEffect(() => {
    if (health.status === "down") wasDown.current = true;
    if (health.status !== "up" || !wasDown.current) return;
    wasDown.current = false;
    if (me.status === "error") me.reload();
    if (centers.items === null && !centers.loading) centers.reload();
    for (const resource of [drivers, orders]) if (resource.error !== null) resource.reload();
  }, [health.status]);

  // A selection belongs to one center. Drop it, and so close Detail, when the center changes.
  useEffect(() => setSelection(NO_SELECTION), [centerId]);

  const select = useCallback((target: PaneId, id: string | null): void => {
    const active = document.activeElement;
    if (id !== null && active instanceof HTMLElement && plotRef.current?.contains(active)) trigger.current = active;
    setSelection(previous => ({ ...previous, [target]: id }));
  }, []);

  const fitTo = useCallback((coordinates: [number, number][]): void => {
    commandCount.current += 1;
    setCommand({ nonce: commandCount.current, kind: "fit", coordinates });
  }, []);
  const zoneEditor = useZoneEditor(centerId, fitTo, liveZones.reload);
  const editingZones = zoneEditor.open && pane === "zones";

  const closeDetail = useCallback((): void => {
    if (editingZones) {
      zoneEditor.close();
      return;
    }
    if (pane === "dispatch" && (pickedUnits.size > 0 || pickedOrder !== null)) {
      clearPicks();
      return;
    }
    select(pane, null);
    if (trigger.current?.isConnected) trigger.current.focus();
  }, [pane, select, editingZones, zoneEditor, pickedUnits, pickedOrder, clearPicks]);

  const flyTo = useCallback((lng: number, lat: number): void => {
    commandCount.current += 1;
    setCommand({ nonce: commandCount.current, kind: "fly", lng, lat });
  }, []);

  // An administrator or the owner may type the Korean address of an order. The day is read again after a save.
  const canEditAddress = canSee && (account.role === "owner" || account.role === "admin");

  // When drivers leave on the chosen day, for the visiting order and for a saved dispatch.
  const [departAt, setDepartAt] = useState("09:00");

  // Saved dispatches of the chosen center and day. Read only while the history is open, and again after a save.
  const loadHistory = useCallback(
    () => (centerId === undefined ? Promise.resolve({ items: [], incomplete: false }) : listDispatches(centerId, day)),
    [centerId, day],
  );
  const history = useResource(loadHistory, canSee && center.settled && (current === "history" || current === "monitor"));
  const reloadHistory = history.reload;
  const saveCurrent = useMemo(() => {
    if (!canEditAddress || centerId === undefined || plan === null) return undefined;
    return async () => {
      const outcome = await saveDispatch({
        centerId,
        day,
        departAt,
        routes: plan.routes.map(route => ({ driverId: route.id, orderIds: route.orders.map(order => order.id) })),
        unassigned: plan.unassigned.map(order => order.id),
      });
      if (outcome.kind === "saved") reloadHistory();
      return outcome;
    };
  }, [canEditAddress, centerId, plan, day, departAt, reloadHistory]);
  const [sheetOpen, setSheetOpen] = useState(false);
  const reloadOrders = liveOrders.reload;
  const afterAddressSaved = useCallback((): void => {
    clearOrderCache();
    reloadOrders();
  }, [reloadOrders]);

  // The visiting order of the driver picked on dispatch, from the route engine.
  const pickedRoute = pane === "dispatch" ? (plan?.routes.find(item => item.id === selection.dispatch) ?? null) : null;
  const sequenceRequest = useMemo(
    () =>
      pickedRoute === null
        ? null
        : {
            startAt: `${day}T${departAt}:00+09:00`,
            start: depot,
            stops: pickedRoute.orders.filter(hasPosition).map(order => ({ id: order.id, lng: order.lng, lat: order.lat })),
          },
    [pickedRoute, day, departAt, depot?.lng, depot?.lat],
  );
  const sequence = useSequence(sequenceRequest);

  // A saved run opened from the history, and the driver whose route is opened in it.
  const [runVersion, setRunVersion] = useState(0);
  const savedRun = useDispatch(pane === "history" ? selection.history : null, runVersion);
  // Send and cancel, for those who may save. After a change the run and the day's list are read again.
  const runActions = useMemo(() => {
    const id = selection.history;
    if (!canEditAddress || id === null) return undefined;
    const after = (outcome: ChangeOutcome): ChangeOutcome => {
      if (outcome.kind === "changed") {
        setRunVersion(version => version + 1);
        reloadHistory();
      }
      return outcome;
    };
    return {
      onSend: () => changeDispatch(id, "send").then(after),
      onCancel: () => changeDispatch(id, "cancel").then(after),
      onChangeDriver: (from: string, to: string) => changeRouteDriver(id, from, to).then(after),
      onAddOrders: (driverId: string, orderIds: string[]) => addRouteOrders(id, driverId, orderIds).then(after),
      drivers: (drivers.items ?? []).map(driver => driver.id),
    };
  }, [canEditAddress, selection.history, reloadHistory, drivers.items]);
  const [historyRoute, setHistoryRoute] = useState<string | null>(null);
  useEffect(() => setHistoryRoute(null), [selection.history]);
  const savedRoutes = useMemo(
    () =>
      savedRun.status === "ok" ? savedRun.run.routes.filter(route => historyRoute === null || route.driverId === historyRoute) : null,
    [savedRun, historyRoute],
  );
  const orderById = useMemo(() => new Map((orderRows ?? []).map(order => [order.id, order] as const)), [orderRows]);
  // The order card on the map: 고객명 | 회사명 | 주소1(원문) | 주소2(한글), as the 2026-10-14 meeting asked.
  const describeMarker = useCallback(
    (kind: PointKind, id: string): [string, string][] | null => {
      const order = kind === "order" ? orderById.get(id) : undefined;
      if (order === undefined) return null;
      const join = (...parts: (string | null | undefined)[]): string => parts.filter(part => part?.trim()).join(" ") || "—";
      return [
        ["고객명", join(order.customerName)],
        ["회사명", join(order.companyName)],
        ["주소1", join(order.address1, order.address2)],
        ["주소2", join(order.addressKo, order.detailKo)],
      ];
    },
    [orderById],
  );

  // 운행 현황: the run sent for the day, with drivers' last places. It is read again every minute while open.
  const [liveVersion, setLiveVersion] = useState(0);
  const sentId = history.items?.find(run => run.status === "SENT")?.id ?? null;
  const liveRun = useDispatch(current === "monitor" ? sentId : null, liveVersion);
  const reloadDrivers = liveDrivers.reload;
  const refreshLive = useCallback((): void => {
    setLiveVersion(version => version + 1);
    reloadDrivers();
    reloadHistory();
  }, [reloadDrivers, reloadHistory]);
  useEffect(() => {
    if (current !== "monitor") return;
    const timer = setInterval(refreshLive, 60_000);
    return () => clearInterval(timer);
  }, [current, refreshLive]);
  const liveRows = useMemo(
    () => (liveRun.status === "ok" ? liveRoutes(liveRun.run, drivers.items ?? []) : null),
    [liveRun, drivers.items],
  );
  const liveList = useMemo(
    () => ({ items: liveRows, loading: false, error: null, updatedAt: null, incomplete: false, reload: refreshLive }),
    [liveRows, refreshLive],
  );
  const pickedLive = pane === "monitor" ? (liveRows?.find(row => row.id === selection.monitor) ?? null) : null;

  const routeLine = useMemo(() => {
    const geometry =
      pane === "dispatch" && sequence.status === "ok"
        ? sequence.sequence.geometry
        : pane === "history" && historyRoute !== null
          ? savedRoutes?.[0]?.geometry
          : pane === "monitor"
            ? pickedLive?.route.geometry
            : null;
    return geometry ? decodePolyline(geometry) : [];
  }, [pane, sequence, historyRoute, savedRoutes, pickedLive]);
  // Show the whole route once its line arrives.
  useEffect(() => {
    if (routeLine.length < 2) return;
    commandCount.current += 1;
    setCommand({ nonce: commandCount.current, kind: "fit", coordinates: routeLine });
  }, [routeLine]);

  const detail = useMemo<DetailContent | null>(() => {
    if (pane === "dispatch" && pickedUnits.size > 0) {
      return unitsDetail(unitRows, pickedUnits, dispatchMode, driverIds, driverId => {
        assignment.apply(changes => assignUnits(changes, [...pickedUnits], driverId, assignedRows ?? [], zoneRows));
        clearPicks();
      });
    }
    const moving = pane === "dispatch" && pickedOrder !== null ? assignedRows?.find(order => order.id === pickedOrder) : undefined;
    if (moving !== undefined) {
      return orderMoveDetail(moving, driverIds, driverId => {
        assignment.apply(changes => assignOrder(changes, moving.id, driverId));
        clearPicks();
      });
    }
    const id = selection[pane];
    if (id === null) return null;
    if (pane === "dispatch") {
      const route = plan?.routes.find(item => item.id === id);
      return route === undefined ? null : routeDetail(route, sequence, { origin, departAt });
    }
    if (pane === "monitor") return pickedLive === null || sentId === null ? null : liveDetail(pickedLive, sentId, orderById, origin);
    if (pane === "history") return runDetail(savedRun, orderById, historyRoute, setHistoryRoute, origin, runActions);
    if (pane === "orders") {
      const order = orderRows?.find(item => item.id === id);
      return order === undefined ? null : orderDetail(order, { flyTo, onAddressSaved: canEditAddress ? afterAddressSaved : undefined });
    }
    if (pane === "drivers") {
      const driver = driverRows?.find(item => item.id === id);
      return driver === undefined ? null : driverDetail(driver, centerName);
    }
    const row = zoneRows.find(item => item.id === id);
    if (row === undefined) return null;
    const onZoneChanged = (): void => {
      liveDrivers.reload();
      liveZones.reload();
    };
    return zoneDetail(row, canEditAddress && centerId !== undefined ? { drivers: driverIds, onChanged: onZoneChanged } : undefined);
  }, [pickedUnits, pickedOrder, unitRows, dispatchMode, driverIds, assignment, assignedRows, clearPicks, pane, plan, sequence, savedRun, orderById, historyRoute, runActions, pickedLive, sentId, selection, orderRows, driverRows, drivers.items, zoneRows, flyTo, centerName, canEditAddress, afterAddressSaved, liveDrivers, liveZones, centerId]);

  // Each pane draws only its own things: orders draws every order, drivers and dispatch the orders in assigned
  // zones (one driver's when one is picked), zones only the zone squares and their numbers.
  const onPrep = pane === "drivers" || pane === "orders" || pane === "zones";
  const zonesOnMap = onPrep && mapView === "zones";
  const points = useMemo<MapPoint[]>(() => {
    if (zonesOnMap) return [];
    // 배차 without an opened route: 배정 shows zones only, 조정 every order's pin to move one.
    if (pane === "dispatch" && selection.dispatch === null) {
      if (dispatchMode === "assign") return [];
      return (assignedRows ?? []).flatMap(order =>
        hasPosition(order) ? [{ kind: "order" as const, id: order.id, lng: order.lng, lat: order.lat, icon: "order" as const, priority: true }] : [],
      );
    }
    if (pane === "monitor") {
      // Drivers where their phones last were, and the opened driver's stops, numbered.
      const placed = (drivers.items ?? []).flatMap(driver =>
        typeof driver.lng === "number" && typeof driver.lat === "number"
          ? [{ kind: "driver" as const, id: driver.id, lng: driver.lng, lat: driver.lat, icon: "driver" as const, label: driver.id, priority: true }]
          : [],
      );
      const stops = (pickedLive?.route.stops ?? []).flatMap(stop => {
        const order = orderById.get(stop.orderId);
        return order && hasPosition(order)
          ? [{ kind: "order" as const, id: order.id, lng: order.lng, lat: order.lat, icon: "order" as const, label: String(stop.seq) }]
          : [];
      });
      return [...stops, ...placed];
    }
    let shown = positioned;
    // A driver's pins carry their visiting number once the order is known. Never an address.
    let numbers = new Map<string, string>();
    if (pane === "history" && savedRoutes !== null) {
      // An opened run draws the orders of its routes; an opened route only its own, numbered.
      const ids = new Set(savedRoutes.flatMap(route => route.stops.map(stop => stop.orderId)));
      shown = positioned.filter(order => ids.has(order.id));
      if (historyRoute !== null) numbers = new Map(savedRoutes[0]?.stops.map(stop => [stop.orderId, String(stop.seq)] as const));
    } else if (pane === "drivers" || pane === "dispatch" || pane === "history") {
      const picked = pane === "history" ? null : selection[pane];
      const zoneIds = new Set(zoneRows.filter(row => row.driverId !== null && (picked === null || row.driverId === picked)).map(row => row.id));
      shown = positioned.filter(order => zoneIds.has(orderById.get(order.id)?.zoneId ?? ""));
      if (pane === "dispatch" && sequence.status === "ok") {
        numbers = new Map(sequence.sequence.stops.map((stop, index) => [stop.id, String(index + 1)] as const));
      }
    }
    return shown.map(order => ({
      kind: "order" as const,
      id: order.id,
      lng: order.lng,
      lat: order.lat,
      icon: "order" as const,
      label: numbers.get(order.id),
      priority: true,
    }));
  }, [pane, zonesOnMap, dispatchMode, assignedRows, positioned, zoneRows, selection, sequence, savedRoutes, historyRoute, drivers.items, pickedLive, orderById]);

  // Read the days next to the one on screen, so moving a day forward or back shows at once.
  useEffect(() => {
    if (orders.items === null || !canSee) return;
    warmOrders(addDays(day, -1), centerId);
    if (day < today()) warmOrders(addDays(day, 1), centerId);
  }, [orders.items, day, centerId, canSee]);

  // Show every pin once when a new day or center loads; the list starts empty then. A reload of the same day
  // keeps its list, so the camera stays where the person put it.
  const fitPending = useRef(true);
  useEffect(() => {
    if (orders.items === null) {
      fitPending.current = true;
      return;
    }
    if (!fitPending.current) return;
    fitPending.current = false;
    const coordinates = orders.items.flatMap(order => (hasPosition(order) ? [[order.lng, order.lat] as const] : []));
    if (coordinates.length === 0) return;
    commandCount.current += 1;
    setCommand({ nonce: commandCount.current, kind: "fit", coordinates });
  }, [orders.items]);

  // 권역 보기: the drawn zones, with the day's order count. The picked zone, or the picked driver's, stands out.
  const zoneShapes = useMemo<MapShape[]>(
    () =>
      !zonesOnMap
        ? []
        : zoneRows.flatMap(row => {
            if (row.shape === null) return [];
            const picked = pane === "drivers" ? row.driverId !== null && row.driverId === selection.drivers : row.id === selection.zones;
            const label = row.orders > 0 ? `${row.id} ${row.orders}` : row.id;
            return [{ id: `zone:${row.id}`, role: picked ? ("picked" as const) : ("zone" as const), geometry: row.shape, label }];
          }),
    [pane, zonesOnMap, zoneRows, selection.zones, selection.drivers],
  );
  const dispatchMap = pane === "dispatch" && selection.dispatch === null;
  const dispatchOutlines = useMemo(
    () => (dispatchMap && dispatchMode === "adjust" ? assignOutlines(unitRows, zoneRows, unitLevel, driverIds) : []),
    [dispatchMap, dispatchMode, unitRows, zoneRows, unitLevel, driverIds],
  );
  const dispatchShapes = useMemo(
    () => assignShapes(unitRows, dispatchMode, pickedUnits, driverIds),
    [unitRows, dispatchMode, pickedUnits, driverIds],
  );
  const onSelectShape = useCallback(
    (id: string): void => {
      if (editingZones) {
        zoneEditor.onSelectShape(id);
        return;
      }
      if (pane === "dispatch" && id.startsWith("unit:")) {
        // 배정 picks several units at once; 조정 works on one.
        const unit = id.slice("unit:".length);
        setPickedOrder(null);
        setPickedUnits(previous => {
          const next = new Set(dispatchMode === "assign" ? previous : []);
          if (previous.has(unit)) next.delete(unit);
          else next.add(unit);
          return next;
        });
        return;
      }
      if (!id.startsWith("zone:")) return;
      changeTab("zones");
      select("zones", id.slice("zone:".length));
    },
    [editingZones, zoneEditor, select, changeTab, pane, dispatchMode],
  );

  const selectedMarker = pane === "orders" && selection.orders !== null ? { kind: "order" as const, id: selection.orders } : null;

  const onSelectMarker = useCallback(
    (kind: PointKind, id: string): void => {
      // On 운행 현황 a driver badge or a stop picks that driver; on dispatch a pin picks its route.
      if (pane === "monitor") {
        if (kind === "driver") select("monitor", id);
        return;
      }
      if (pane === "dispatch" && selection.dispatch === null && dispatchMode === "adjust") {
        setPickedUnits(new Set());
        setPickedOrder(id);
        return;
      }
      if (pane === "dispatch") {
        const driverId = assignedRows?.find(order => order.id === id)?.driverId ?? null;
        if (driverId !== null) select("dispatch", driverId);
        return;
      }
      const target = KIND_TAB[kind];
      changeTab(target);
      select(target, id);
    },
    [pane, assignedRows, select, changeTab, selection.dispatch, dispatchMode],
  );

  return (
    <div class="workspace" data-detail={!onPage && (editingZones ? zoneEditor.detail : detail) !== null}>
      <MenuRail groups={groups} active={current} onChange={setMenu} />
      <aside class="plot" aria-label="작업 목록" ref={plotRef}>
        <header class="plot-header">
          <div class="account-row">
            <AccountBlock me={me} onSignOut={onSignOut} />
            {health.status === "down" && <ApiDown health={health} />}
          </div>
          {canAsk && (
            <button type="button" class="btn btn-outline ask-toggle" aria-expanded={asking} onClick={() => setAsking(open => !open)}>
              접근 신청
            </button>
          )}
          {canRedeem && (
            <button type="button" class="btn btn-outline ask-toggle" aria-expanded={redeeming} onClick={() => setRedeeming(open => !open)}>
              센터 입장 코드
            </button>
          )}
          {canRedeem && redeeming && (
            <RedeemPanel
              onRedeemed={() => {
                setRedeeming(false);
                me.refresh();
                liveCenters.reload();
              }}
              onCancel={() => setRedeeming(false)}
            />
          )}
          {account.membership === "pending" && <PhoneProofPanel />}
          {canAsk && asking && (
            <RequestAccessPanel
              onAsked={() => {
                setAsking(false);
                me.refresh();
              }}
              onCancel={() => setAsking(false)}
            />
          )}
          {!onPage && (
            <>
              <CenterSelect centers={centers} selectedId={center.selectedId} onSelect={center.select} />
              <DayPicker day={day} onChange={setDay} />
              {orderRows !== null && orderRows.length > 0 && <DayStatus orders={orderRows} onOpen={openOrders} />}
            </>
          )}
        </header>
        {current === "centers" ? (
          <section class="menu-page" aria-labelledby="centers-title">
            <header class="menu-page-head">
              <h2 id="centers-title">센터</h2>
            </header>
            <div class="menu-page-body">
              <CentersView resource={centers} onChanged={liveCenters.reload} />
            </div>
          </section>
        ) : current === "approvals" ? (
          <section class="menu-page" aria-labelledby="approvals-title">
            <header class="menu-page-head">
              <h2 id="approvals-title">승인</h2>
            </header>
            <div class="menu-page-body">
              <ApprovalsView resource={requests} members={members} onMembersChanged={liveMembers.reload} />
            </div>
          </section>
        ) : current === "dispatch" ? (
          <DispatchView
            plan={plan}
            resource={routeList}
            selectedId={selection.dispatch}
            onSelect={id => select("dispatch", id)}
            scope={centerName}
            departAt={departAt}
            origin={origin}
            onDepartAt={setDepartAt}
            onSave={saveCurrent}
            onUndo={assignment.canUndo ? assignment.undo : undefined}
            onReset={assignment.reset}
            base={assignment.base}
          />
        ) : current === "monitor" ? (
          <MonitorView
            run={liveRun}
            resource={liveList}
            selectedId={selection.monitor}
            onSelect={id => select("monitor", id)}
            onRefresh={refreshLive}
            scope={centerName}
          />
        ) : current === "history" ? (
          <DispatchHistoryView
            resource={history}
            selectedId={selection.history}
            onSelect={id => select("history", id)}
            scope={centerName}
          />
        ) : (
        <>
        <Tabs
          label="작업 구분"
          idPrefix="plot"
          active={tab}
          onChange={changeTab}
          tabs={[
            { id: "drivers", label: "기사", count: drivers.items?.length },
            { id: "orders", label: "주문", count: orders.items?.length },
            { id: "zones", label: "권역", count: zones.items?.length },
          ]}
        />
        <div class="tabpanels">
          <div class="tabpanel" role="tabpanel" id="plot-panel-drivers" aria-labelledby="plot-tab-drivers" hidden={tab !== "drivers"}>
            {canEditAddress && centerId !== undefined && <DriverAdd centerId={centerId} onDone={liveDrivers.reload} />}
            <DriversView resource={driverList} selectedId={selection.drivers} onSelect={id => select("drivers", id)} scope={centerName} />
          </div>
          <div class="tabpanel" role="tabpanel" id="plot-panel-orders" aria-labelledby="plot-tab-orders" hidden={tab !== "orders"}>
            <OrdersView key={orderJump.n} initialFilters={orderJump.filters} resource={orderList} selectedId={selection.orders} onSelect={id => select("orders", id)} scope="전체 센터" onOpenSheet={() => setSheetOpen(true)} />
          </div>
          <div class="tabpanel" role="tabpanel" id="plot-panel-zones" aria-labelledby="plot-tab-zones" hidden={tab !== "zones"}>
            <ZonesView resource={zones} selectedId={selection.zones} onSelect={id => select("zones", id)} scope={centerName} onEdit={canEditAddress ? zoneEditor.start : undefined} />
          </div>
        </div>
        </>
        )}
      </aside>
      <DetailPanel content={editingZones ? zoneEditor.detail : detail} onClose={closeDetail} />
      {sheetOpen && orderRows !== null && (
        <OrderSheet day={day} orders={orderRows} canEdit={canEditAddress} onClose={() => setSheetOpen(false)} onSaved={afterAddressSaved} />
      )}
      <main class="map-area">
        {editingZones && zoneEditor.attribution !== null && <p class="map-credit">{zoneEditor.attribution}</p>}
        {current === "dispatch" && selection.dispatch === null && (
          <DispatchSwitches mode={dispatchMode} onMode={setDispatchMode} level={unitLevel} onLevel={setUnitLevel} />
        )}
        {onPrep && !editingZones && (
          <fieldset class="seg map-toggle" aria-label="지도 보기">
            {(["zones", "orders"] as const).map(mode => (
              <button type="button" key={mode} class="seg-tab" aria-pressed={mapView === mode} onClick={() => setMapView(mode)}>
                {mode === "zones" ? "권역 보기" : "주문 보기"}
              </button>
            ))}
          </fieldset>
        )}
        <MapView
          points={editingZones ? [] : points}
          shapes={editingZones ? zoneEditor.shapes : dispatchMap ? dispatchShapes : zoneShapes}
          outlines={editingZones ? [] : dispatchOutlines}
          onSelectShape={onSelectShape}
          onView={zoneEditor.onView}
          draw={editingZones ? zoneEditor.draw : undefined}
          line={routeLine}
          selected={selectedMarker}
          markerScale={1}
          autoCenter
          command={command}
          hidden={false}
          onSelect={onSelectMarker}
          describe={describeMarker}
        />
      </main>
    </div>
  );
}
