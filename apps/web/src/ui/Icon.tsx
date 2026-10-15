import {
  Building2,
  Calendar,
  Check,
  ChevronDown,
  ChevronLeft,
  ChevronRight,
  Clock,
  Pencil,
  Grid3x3,
  Maximize2,
  Inbox,
  Info,
  Layers,
  List,
  LogOut,
  Map as MapIcon,
  MapPin,
  MapPinOff,
  Package,
  RefreshCw,
  Route,
  Search,
  Truck,
  TriangleAlert,
  User,
  UserCheck,
  X,
  ArrowLeft,
} from "lucide-preact";
import type { JSX } from "preact";

/**
 * Every icon of the page comes from lucide (lucide-preact). Nothing here is drawn by hand: to change an
 * icon, change which lucide icon a name points at. Decorative: the label lives in adjacent text or aria-label.
 */
const ICONS = {
  truck: Truck,
  package: Package,
  route: Route,
  search: Search,
  close: X,
  layers: Layers,
  legend: List,
  map: MapIcon,
  back: ArrowLeft,
  check: Check,
  calendar: Calendar,
  alert: TriangleAlert,
  info: Info,
  refresh: RefreshCw,
  "pin-off": MapPinOff,
  pin: MapPin,
  clock: Clock,
  logout: LogOut,
  user: User,
  "user-check": UserCheck,
  building: Building2,
  "chevron-down": ChevronDown,
  "chevron-left": ChevronLeft,
  "chevron-right": ChevronRight,
  inbox: Inbox,
  edit: Pencil,
  grid: Grid3x3,
  expand: Maximize2,
} as const;

export type IconName = keyof typeof ICONS;

export function Icon({ name, size = 18 }: { name: IconName; size?: number }): JSX.Element {
  const Glyph = ICONS[name];
  return <Glyph class="icon" size={size} strokeWidth={1.75} aria-hidden="true" focusable="false" />;
}
