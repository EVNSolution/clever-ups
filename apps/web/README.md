# apps/web

TypeScript + Vite 운영 화면. 지도 중심 UI는 [DESIGN.md](../../DESIGN.md)를 따릅니다.

```bash
npm install
npm run check   # typecheck + lint(Oxlint, jsx-a11y, Anti Slop) + test + build
npm run dev
```

Node 22.18 이상이 필요합니다.

## API 연결

개발 서버는 `/actuator`, `/api`를 `http://127.0.0.1:8080`(로컬 `services/api`)으로 전달합니다. 응답 타입은 [계약](../../contract/openapi.yaml)에서 생성한 `src/api/schema.d.ts`를 씁니다.

```bash
npm run gen:api     # 계약 변경 후 타입 재생성
npm run check:api   # 생성 결과가 커밋된 파일과 다르면 실패
```

`openapi-typescript`는 TypeScript 5를 요구해 의존성에 넣지 않고 `npx`로 고정 버전을 실행합니다.

## 화면 구성

Preact + TypeScript(JSX)입니다. [DESIGN.md](../../DESIGN.md)의 구조(좌측 Plot 탭 · 확장 Detail · 지도)입니다. 데모 데이터는 없습니다. 색·글꼴·간격은 [src/styles/tokens.css](src/styles/tokens.css)의 토큰만 씁니다.

| 위치 | 역할 |
| --- | --- |
| `src/App.tsx` | 상태(탭·선택·센터)를 들고 화면을 조립 |
| `src/header/` | 좌측 상단. `AccountBlock`(아이디를 닉네임으로, 역할은 작고 흐린 글씨), `CenterSelect`(센터 선택) |
| `src/views/` | 기사·주문·권역 목록(`ResourceList`가 검색·필터·로딩·빈 상태·오류를 공통 처리), 로그인·가입(`LoginView`, `SignUpForm`, `PhoneVerifyField`), 승인·멤버(`ApprovalsView`, `MembersList`), 재신청·휴대폰 인증 패널 |
| `src/detail/` | 선택 항목의 Detail. 필드는 API 이름 그대로 표시(필드 확정 전) |
| `src/map/` | MapLibre 지도와 마커 그림. 좌표가 있는 항목만 마커로 표시 |
| `src/ui/` | 아이콘(`Icon`은 lucide-preact의 이름 매핑이며 직접 그린 SVG는 `no-handmade-svg.test.ts`가 막음), 탭, 목록(방향키 이동), 팝오버, 운영일 선택(`DayPicker`: 직접 만든 달력, 전날·다음날) 등 공용 부품 |
| `src/state/` | `useResource`(목록), `useMe`(계정), `useCenterSelection`(센터), `useHealth`(API 상태), `hidden`(승인 전 목록 숨김) |
| `src/api/` | 계약에서 생성한 타입과 호출. 응답 형태를 실행 중에도 검사 |

**용어** (UPS 센터 시트와 Ours24를 따릅니다)
- 센터 = 영업소. id는 LOCATION NAME(예: `KRHWN`)이며 목록에 이름과 함께 보입니다.
- 기사 = 배송원. id는 DRIVER ID이고 그대로 이름으로 씁니다. 형식을 해석하지 않습니다.
- 구역 = 구역 코드(ROUTE ID). 센터 안에서만 유일하며 같은 코드를 기사 여러 명이 쓸 수 있습니다(중복 허용). 코드는 항상 문자열입니다.
- 주문 = Ours24 Job의 행(운송장, 수취인, 상호, 주소1·2, 우편번호). 좌표는 주소를 정제한 뒤에 생깁니다.
- 구역 경계(폴리곤·행정동 조합)는 아직 없습니다. 웹에서 만들 예정입니다.
- 권역 탭은 시험용입니다. `src/grid.ts`가 전국 고정 원점에서 위도 0.02°·경도 0.025°(성남에서 약 2.2km) 칸을 만들어 권역 ID가 날짜·센터와 관계없이 같습니다. 이 브라우저가 본 권역과 권역별 기사 배정은 `localStorage`에만 저장하며 DB 테이블은 없습니다.

**계정과 센터**
- 한 아이디로 센터 여러 개를 쓸 수 있습니다. 센터 목록은 역할이 아니라 계정마다 정해지며 API(`GET /api/centers`)가 줍니다.
- 선택한 센터는 URL(`?center=`)에만 기억합니다. 브라우저 저장소와 서버 저장은 쓰지 않습니다.
- 센터를 바꾸면 목록이 새로 불러와지고, 선택과 Detail은 닫힙니다.
- 로그인하지 않으면(`GET /api/me`가 401) 지도 없는 로그인 화면만 보입니다. 로그인한 계정은 모두 같은 작업 화면으로 들어가고, 승인되기 전에는 목록이 비어 보이며 데이터 요청을 보내지 않습니다. 접근 제한은 API가 합니다.
- 역할 표시: `owner`는 "개발 관리자", `admin`은 "관리자"입니다. 나머지 역할은 이름 그대로 보여 줍니다.
- 개발 관리자에게만 "승인" 탭이 보입니다. 주문 목록과 상세에는 센터 코드 배지가 있고, 센터가 없으면 "센터 미지정"으로 보입니다.

주문은 Ours24가 연결되면 채워지고, 기사·구역은 출처가 연결되기 전까지 "표시할 ○○이 없습니다"를 보여 줍니다. 우측 보조 탭은 없습니다. 업로드는 두지 않습니다(주문은 Ours24에서 옵니다).

## 지도 성능 검증 (합성 데이터)

| 항목 | 결과 |
| --- | --- |
| 마커 100개 표시 | 통과 (개발·프로덕션 빌드 모두) |
| 마커·목록 클릭 → Detail | 통과. 선택 마커 강조와 지도 이동 확인 |
| 한국어 검색 | 통과 (입력 `송파구` → 8건, 독립 계산과 일치). IME 조합 이벤트(시작·갱신·종료)도 통과 |
| 마커 5,000개 | 이동 58.6fps(p95 17.6ms), 확대·축소 58.1fps (심볼 레이어·마커 그림 적용 후 측정) |
| 마커 20,000개 | 이동 43.1fps(최대 600ms 정지 1회), 확대·축소 54.8fps |
| 번들 크기 | JS 1,040 kB(gzip 277 kB) + worker 508 kB(gzip 144 kB) + CSS 84 kB(gzip 11 kB) |

주의:
- maplibre-gl 6.x는 Vite에서 기본 worker 경로가 깨집니다. `setWorkerUrl`로 직접 지정합니다([src/map/MapView.tsx](src/map/MapView.tsx)).
- 측정 조건: Apple M2, Chrome 154(보이는 창, 새 프로필), 프로덕션 빌드, 창 1280×773, 실제 마우스 드래그·휠 입력(DevTools 프로토콜). 60fps는 화면 주사율 상한이라 여유는 알 수 없습니다.
- 브라우저 창이 숨겨진 상태에서는 지도가 그려지지 않으므로 측정은 보이는 창에서만 유효합니다.
- IME는 `Input.imeSetComposition`으로 조합 이벤트를 보냈습니다. 사람이 macOS 한글 입력기로 직접 입력한 것은 아닙니다.
