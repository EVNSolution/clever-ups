# services/api

Kotlin + Spring Boot 단일 애플리케이션. 외부 API 통로는 `com.evnsolution.ups.partner` 패키지에 있습니다(Ours24, GET만).

```bash
./gradlew test detekt bootJar
```

JDK 21이 필요합니다. 헬스 확인: `GET /actuator/health`.

## 구조

자원마다 패키지 하나(`drivers`, `orders`, `routes`, `centers`, `accounts`, `addresses`, `phone`)이며 같은 모양입니다. `security`는 문(접근 필터), `partner`는 외부 API입니다. 용어는 UPS 센터 시트와 Ours24를 따릅니다: 센터(영업소, id는 LOCATION NAME), 기사(배송원, id는 DRIVER ID), 구역코드(ROUTE ID, 중복 허용), 주문(Ours24 Job의 행). 목록 자원은 `centerId` 쿼리를 받아 `*Store`에 넘깁니다. 한 계정이 여러 센터를 쓸 수 있고, 센터 목록(`GET /api/centers`)은 역할이 아니라 계정마다 정해집니다. `GET /api/me`는 로그인 정보가 없으면 401입니다.

| 파일 | 역할 |
| --- | --- |
| `<Resource>Controller.kt` | HTTP 경로. 계약([contract/openapi.yaml](../../contract/openapi.yaml))의 경로와 같아야 합니다. |
| `<Resources>.kt` | 모델, `*Store`(데이터 출처 인터페이스), 기본 구현 `Empty*Store` |

DB는 계정·세션, 주소, 휴대폰 발송 장부, 센터·구역·기사·원본 행의 스키마(Flyway V1~V7)에 씁니다. 기사·구역·센터 목록은 데이터 출처가 연결되기 전까지 `Empty*Store`가 빈 목록을 반환합니다. 주문은 Ours24에서 읽습니다. 테이블을 늘릴 때는 먼저 합의합니다.

테스트: `DefaultsTest`(출처가 없을 때 빈 목록·401), `ContractTest`(테스트 전용 항목으로 모든 GET 경로를 계약에 대조), `CenterScopeTest`(`centerId`가 저장소에 전달되는지). 테스트 전용 항목은 `FilledStores`에만 있습니다.

## 누가 무엇을 호출하는가

호출자는 두 종류이고 문이 다릅니다. 코드는 `security/AccessGate.kt`입니다.

| 호출자 | 인증 | 갈 수 있는 곳 |
| --- | --- | --- |
| 웹 사용자(사람) | 로그인 세션(쿠키) | `/api/orders`, `/api/drivers`, `/api/routes`, `/api/centers`, `/api/me`, 승인·멤버십·휴대폰 경로 |
| 우리 서비스(주소 엔진 등) | `Authorization: Bearer` 서비스 토큰 | `/api/addresses*` 만 |
| 누구나 | 없음 | `/actuator/health`, 로그인·가입·갱신, 가입 전 휴대폰 발송 |

- 로그인하지 않은 호출은 401입니다. 위 "누구나" 경로만 열려 있습니다.
- 서비스 토큰은 `ups.service-token-file`(환경 변수 `UPS_SERVICE_TOKEN_FILE`)이 가리키는 파일에 둡니다. 모드 600이어야 하고, 파일이 없으면 `/api/addresses`는 닫힙니다.
- Ours24 키와 서비스 토큰은 서버 밖으로 나가지 않습니다.
- 로그인, 세션, CSRF는 아래 "로그인과 데이터 접근"을 따릅니다. 데이터베이스가 꺼져 있으면 로그인이 없어 데이터 목록은 모두 401입니다.

## 경로 엔진

`POST /api/stop-sequence`는 기사 한 명의 정차지(최대 150곳)를 VROOM에 보내 방문 순서, 도착 예상 시각, 도로 선(polyline)을 받습니다. 코드는 `sequence/`입니다.

- `ups.vroom.url`(환경 변수 `UPS_VROOM_URL`)이 있을 때만 켜집니다. 없거나 엔진이 응답하지 않으면 503입니다.
- 엔진 실행은 [infra/route/README.md](../../infra/route/README.md)를 따릅니다. 밖으로 나가는 것은 좌표뿐이며, 엔진은 로컬에서 돕니다.
- 출발지가 없으면 요청의 첫 정차지에서 출발합니다(VROOM은 출발지나 도착지가 없는 차량을 거절합니다).
- 웹은 센터 차고지를 출발지로 보냅니다. `GET /api/centers`가 `depot_address_ko`를 V-World로 찾아 `lng`·`lat`을 붙입니다. 찾은 결과는 서버가 도는 동안 기억하고, DB에는 쓰지 않습니다. 결과는 저장하지 않습니다.

## 기사 계정

기사는 일반 계정으로 로그인하고, 역할이 `driver`입니다(V13). 코드는 `driverapp/`입니다.

- `POST /api/memberships/{accountId}/driver`(개발 관리자만): 활성 계정을 센터 하나의 기사 하나에 묶습니다. 역할은 `driver`, 센터 목록은 그 센터 하나가 됩니다. 기사 하나에 계정 하나이며, 이전 묶음은 바뀝니다. `access_decision`에 `LINK_DRIVER`로 남습니다.
- 문(`AccessGate`): `driver` 계정은 `/api/driver` 아래, `/api/me`, 로그아웃, 휴대폰 경로만 씁니다. 그 밖은 403 `role-driver`입니다.
- `GET /api/driver/route?day=`: 그 센터·날짜에 전송(`SENT`)된 배차에서 자기 노선을 돌려줍니다. 정차마다 주소·운송장·위치가 있고, 고객 이름·전화번호는 없습니다. 없으면 404입니다.

## 기사 위치

- `POST /api/driver/position`: 기사 앱이 화면에 있는 동안 1분마다 보냅니다. `driver_position`(V16)에 기사마다 마지막 위치 한 행만 두고, 지난 위치는 남기지 않습니다.
- `GET /api/drivers`가 그 위치(`lng`, `lat`, `positionAt`)를 돌려줍니다.

## 배송 결과

기사 앱이 정차마다 보고합니다(V15 `delivery_result`, `delivery_photo`). 코드는 `driverapp/`입니다.

- `POST /api/driver/route/{planId}/stops/{orderId}/arrive`, `.../result`, `.../photos`: 자기 노선의 전송된(`SENT`) 배차의 정차에만 씁니다. 아니면 404입니다.
- 결과는 `DELIVERED`(코드 KB, `FACE`는 수령인 이름 필수, `DOOR`는 사진) 또는 `EXCEPTION`(UPS 예외 코드)입니다. 코드는 UPS 시트의 41개(`UpsCodes`, `GET /api/driver/codes`)입니다.
- 사진은 `ups.photos.dir`(`UPS_PHOTOS_DIR`) 폴더에 ID 이름의 파일로, JPEG·PNG 5MB까지입니다. 폴더가 없으면 503입니다. 웹은 `GET /api/dispatches/{id}/photos/{photoId}`로 봅니다.
- 실적은 계획(`dispatch_stop`)과 따로 둡니다. 재배차해도 기사가 한 일은 지워지지 않습니다.
- Ours24로 결과를 보내는 POST는 아직 없습니다.

## 배차 저장

배차 1건은 센터 1곳 × 운영일 1일 × 1회 실행입니다. 코드는 `dispatch/`, 표는 V11입니다.

| 표 | 한 행 |
| --- | --- |
| `dispatch_plan` | 배차 1건. 상태 `DRAFT`·`SENT`·`CANCELLED`·`REPLACED` |
| `dispatch_route` | 기사 1명의 노선. 대표 구역코드(보고서 `loop`), 출발 시각·출발지, 총 거리·소요, 경로선 |
| `dispatch_stop` | 주문 1건. 순번, 도착 예정, 누적 거리. 노선이 없으면 미할당 |

- `POST /api/dispatches`는 관리자·개발 관리자만 부릅니다. 본인의 센터가 아니면 403입니다.
- `GET /api/dispatches/{id}`는 저장된 배차 하나를 노선·정차 순서 그대로 돌려줍니다. 주문은 ID만 주고, 주소는 그날의 주문 목록에서 찾습니다. 없는 ID와 남의 센터는 같은 404입니다.
- 주문은 `GET /api/orders`로 한 번 읽힌 것이어야 합니다. 읽을 때 `delivery_task`에 남습니다(task_key = Job ID와 행 번호).
- 순서와 시각은 서버가 경로 엔진으로 계산해 그대로 저장합니다. 엔진이 없으면 503입니다.
- 같은 센터·운영일을 다시 저장하면 이전 `DRAFT`가 `REPLACED`가 됩니다.
- `POST /api/dispatches/{id}/send`(초안만): 그 배차가 `SENT`, 노선이 `SENT`가 되고, 같은 센터·운영일의 이전 `SENT`는 `REPLACED`가 됩니다. 센터 행을 잠그고 바꾸므로 동시에 보내도 `SENT`는 하나입니다.
- `POST /api/dispatches/{id}/routes/{driverId}/driver`(초안만): 노선을 그 센터의 다른 기사에게 넘깁니다. 이미 노선이 있는 기사는 409, 센터 밖 기사는 400입니다. 순서는 그대로이고 대표 구역코드는 새 기사의 메인 권역입니다.
- `POST /api/dispatches/{id}/routes/{driverId}/orders`(초안만): 그 배차의 미할당 주문(위치 있음)을 노선에 더하고 노선 전체 순서를 경로 엔진으로 다시 계산합니다.
- `POST /api/dispatches/{id}/cancel`(초안·전송분): `CANCELLED`. 다른 상태는 409입니다. 노선 상태는 그대로이므로, 기사 앱은 배차 상태를 함께 봐야 합니다.
- 실제 도착·완료·현장 확인은 이 표에 넣지 않습니다. 기사 앱 단계에서 별도 표로 만듭니다.

## 주소 저장소

Ours24는 영문 주소만 주고 좌표를 주지 않습니다. 주소 번역과 지오코딩은 이 API 밖의 작업이 맡고, 이 API는 **저장과 문**만 맡습니다.

- 영문 원본과 한글 결과는 `address_location`(`V1`, `V7`)에 있습니다. 주소 한 건이 한 행이며, 같은 주소가 여러 Job에 와도 한 행입니다. 주문 행(`delivery_task`)은 스키마만 있고 아직 채우지 않습니다.
- `POST /api/addresses/refresh?from=YYYY-MM-DD[&to=]`(서비스 문)가 그 날짜의 주문을 읽어 새 주소를 `PENDING`으로 쌓습니다. 로그인이 필요 없고 Ours24는 GET만 호출합니다. `GET /api/orders`도 읽은 주문의 새 주소를 같은 방식으로 쌓습니다. 이미 `DONE`인 주소의 `lat`·`lng`·`addressKo`는 응답에 채워집니다.
- 사람(관리자·개발 관리자)은 `PUT /api/address-ko/{key}`로 한글 주소를 직접 입력합니다. 주문 응답의 `addressKey`가 키입니다. 출처는 `MANUAL`로 남습니다. `ups.vworld.key-file`(`UPS_VWORLD_KEY_FILE`)이 있으면 서버가 V-World로 바로 위치를 찾아 `DONE`으로 쓰고(`geocoding/VWorldGeocoder.kt`, DSV의 방식), 못 찾거나 키가 없으면 위치 없이 `PENDING`입니다. 밖으로 나가는 것은 주소 글자(괄호 안 제외)뿐입니다. member·viewer는 403입니다.
- 주문 셀 편집: `PUT /api/orders/edits`(관리자·개발 관리자)는 주문표에서 고친 칸만 `order_edit`(V17)에 저장합니다. 칸은 고객명·회사명·주소1·주소2·우편번호입니다. Ours24에는 쓰지 않고, `GET /api/orders`가 고친 값을 Ours24 값 위에 보여 주며 `edited`에 고친 필드를 적습니다. 빈 값은 Ours24 값으로 되돌립니다. 자기 센터 주문이 아니거나 읽은 적 없는 주문은 404입니다. 고친 고객명·회사명은 운영일로부터 90일 뒤 매일 03:30(서울)에 지웁니다. 누가·언제 고쳤는지 남깁니다.
- 서비스는 `GET /api/addresses?status=PENDING`으로 대기열을 읽고, `PUT /api/addresses/{key}`로 `DONE`(좌표 필수) 또는 `FAILED`를 씁니다. 한글 주소·상세·출처·비교 결과 쓰기는 아래 "주소 엔진을 위한 서비스 문"을 따릅니다.
- DB는 `ups.db.url`이 있을 때만 켜집니다(환경 변수 `UPS_DB_URL`, `UPS_DB_USER`, 비밀번호는 `UPS_DB_PASSWORD_FILE`의 모드 600 파일). 꺼져 있으면 주문은 그대로 통과합니다.
- 테스트는 H2(PostgreSQL 모드)로 돌고, `UPS_TEST_DB_URL`을 주면 같은 테스트가 실제 PostgreSQL에서 돕니다.
- 마이그레이션은 병합 후 수정하지 않고 다음 번호를 추가합니다.

## 행정구역 경계

- `admin_boundary`(V18)에 행정동(`DONG`)·시군구(`SGG`)·일반구가 있는 시(`CITY`)의 경계를 GeoJSON 글자와 범위 상자로 둡니다.
- 터미널 명령 `--ups.import-boundaries=<파일>`이 모든 행을 한 트랜잭션으로 바꿉니다. 웹 서버는 띄우지 않습니다.
- 출처·라이선스·받는 방법은 [infra/boundaries/README.md](../../infra/boundaries/README.md)에 있습니다.
- `GET /api/boundaries?level=SI|DONG&bbox=서,남,동,북`: 상자에 걸친 경계. SI는 일반구가 있는 시와 일반구가 아닌 시·군·구입니다. 600개가 넘으면 `X-Data-Incomplete`.

## 센터·기사·담당 권역

- `POST /api/centers`(개발 관리자): 코드(LOCATION NAME, 대문자 3~12자)·이름·차고지 주소로 센터를 만듭니다. 같은 코드는 409. `PUT /api/centers/{id}`로 이름·차고지 주소를 고칩니다.
- 개발 관리자의 센터 목록에는 `center` 마스터의 모든 센터가 들어갑니다. 다른 사람에게는 승인 때 센터를 줍니다.
- `POST /api/drivers`(관리자·개발 관리자, 자기 센터): 기사 ID(DRIVER ID)만 받습니다. 이름·전화번호는 저장하지 않습니다.
- `PUT /api/zones/{id}/driver`: 권역의 담당 기사를 한 명으로 정하거나(`driverId`) 비웁니다(null). 그 기사에게 메인 권역이 없으면 이 권역이 메인 권역이 됩니다. 날짜별 변경은 배차에서 합니다.

## 권역 도형

- `zone`(V5)에 상위 권역(`parent_zone_id`)과 도형·범위 상자(V19)를 더했습니다. 상위가 있는 권역이 세부권역입니다.
- `GET /api/zones?centerId=`: 센터의 권역과 도형. `POST /api/zones`: 권역 또는 세부권역 만들기(관리자·개발 관리자, 같은 센터 이름 중복은 409).
- `PUT /api/zones/{id}/shape`: `boundaryCodes`의 합 또는 `polygon`으로 도형을 정합니다. 둘 다 없으면 지웁니다.
  - 같은 센터·같은 단계의 다른 권역이 가진 부분을 잘라 냅니다. 세부권역은 상위 권역 안으로 자릅니다.
  - 남는 부분이 없거나 상위 권역에 도형이 없으면 409입니다.
  - 권역 도형을 바꿔도 이미 있는 세부권역은 다시 자르지 않습니다.

## 휴대폰 인증 발송 장부

휴대폰 확인용 문자는 서버가 **발송 전에** 장부(`phone_send`)에 예약해야만 보낼 수 있습니다. 코드는 `phone/`입니다.

- 상한: 24시간 이동 창 안에서 전체 10건(기본). 계정당 3건, 같은 번호는 60초 간격입니다. 값은 `SendLimits`입니다.
- 이동 창인 이유: 제공업체의 하루 경계를 모릅니다. 어떤 24시간에도 10건을 넘지 않으면 어떤 제공업체의 하루도 넘지 않습니다.
- 중복 방지: 한 번의 사용자 행동은 하나의 `request_key`입니다. 키는 유일하므로 두 번째 행이 생기지 않고, 반복 호출은 `Duplicate`로 답합니다.
- 동시 호출: 예약은 단일 행 잠금(`phone_send_gate`) 안에서 개수를 세고 넣습니다. 슬롯 둘이 같은 DB를 써도 순서대로 처리됩니다.
- 발송 결과를 모르면(시간 초과, 장애) `UNKNOWN`으로 남고 개수에 포함됩니다. 자동 재시도는 없습니다. 한 건이 사라지는 쪽이 두 건이 나가는 쪽보다 낫습니다.
- 제공업체가 "보내지 않음"이라고 분명히 거절한 경우만 `FAILED`로 풀려 개수에서 빠집니다.
- 검증기(`FirebasePhoneProof`)는 Firebase ID 토큰의 서명, 프로젝트, 휴대폰 로그인, 한국 휴대폰 번호, 신선도(기본 15분)를 확인합니다. `UPS_FIREBASE_PROJECT_ID`가 있을 때만 켜집니다.
- 발송 어댑터는 Identity Platform REST(`IdentityPlatformSender`)입니다. 사용 방법은 아래 "휴대폰 인증"에 있습니다.

## 로그인과 데이터 접근

코드는 `accounts/`와 `security/AccessGate.kt`입니다. 데이터베이스가 켜져 있을 때(`ups.db.url`)만 동작합니다.

| 요청 | 결과 |
| --- | --- |
| 로그인하지 않음 | 401 |
| 로그인했고 멤버십이 `active`가 아님 | 403, `detail`이 이유(`membership-none`, `-pending`, `-rejected`, `-revoked`) |
| 로그인했고 멤버십이 `active` | 데이터 조회 가능. POST는 `X-CSRF-Token` 필요 |
| AT 만료, RT 유효 | 401 → `/api/auth/refresh` → 새 AT·RT 발급 |

- 멤버십 상태는 **요청마다** 읽습니다. 권한을 회수하면 다음 요청부터 막힙니다. 세션을 따로 끊지 않아도 됩니다.
- 로그인: `POST /api/auth/login`(`loginId`, `password`). 틀린 비밀번호와 없는 ID는 구분하지 않고 401입니다. 같은 ID에 15분 안에 5번 실패하면 잠기고 429입니다. 없는 ID도 같은 방식으로 잠깁니다.
- 토큰 두 개(AT/RT). 둘 다 HttpOnly, SameSite=Strict 쿠키이고 서버에는 SHA-256 해시만 저장합니다.
  - 접근 토큰(AT) `UPS_SESSION`: 15분. 모든 API 요청에 실립니다. 요청마다 DB로 확인하므로 권한 회수가 바로 반영됩니다.
  - 갱신 토큰(RT) `UPS_REFRESH`: 30일, 쓸 때마다 새것으로 바뀝니다(롤링). `/api/auth/*`에만 전송됩니다. 절대 한도 90일.
  - 페이지는 API가 401을 주면 `POST /api/auth/refresh`(헤더 `X-UPS-Refresh`)를 한 번 호출하고 원래 요청을 반복합니다. 이것이 자동 로그인입니다.
  - 이미 바뀐 RT가 10초 뒤에 다시 오면 복사본으로 보고 세션 전체를 끝냅니다. 10초 안이면 탭 두 개의 경합이라 거절만 하고 세션은 유지합니다.
  - 값은 `ups.session.access-minutes`, `refresh-days`, `absolute-days`입니다.
- 비밀번호: 8~128자, 영문 대문자·숫자·특수문자를 각각 1개 이상, 로그인 ID를 포함하지 않음. 저장은 JDK PBKDF2-HMAC-SHA256 60만 회이며 형식에 횟수가 들어 있어 올려도 이전 해시가 통합니다.
- 첫 개발 관리자(owner)는 터미널에서만 만듭니다. 비밀번호는 화면에 보이지 않게 두 번 입력합니다. 인자·환경 변수·파일로 받지 않습니다.

```bash
UPS_DB_URL=jdbc:postgresql://127.0.0.1:5432/ups_dev UPS_DB_USER=$(whoami) \
java -jar services/api/build/libs/ups-api-0.0.1-SNAPSHOT.jar --ups.bootstrap-owner=<로그인 ID>
```

- 이 명령은 웹 서버를 띄우지 않으므로 실행 중인 API와 포트가 겹치지 않습니다.
- 개발 관리자를 다른 계정으로 옮기는 것도 터미널에서만 합니다. 옮긴 뒤에도 owner는 한 명이고, 이전 owner는 admin이 됩니다. 새 owner의 센터 목록은 이 명령에서 정합니다. 승인 화면과 API로는 owner를 줄 수 없습니다.

```bash
UPS_DB_URL=jdbc:postgresql://127.0.0.1:5432/ups_dev UPS_DB_USER=$(whoami) \
java -jar services/api/build/libs/ups-api-0.0.1-SNAPSHOT.jar \
  --ups.transfer-owner=<새 owner의 로그인 ID> --ups.transfer-centers=KRHWN,KRSNA
```

- ID 형식과 비밀번호 규칙은 입력 중에 확인하고, 어기면 한 줄로 알리고 아무것도 만들지 않습니다.
- owner는 하나만 만들 수 있습니다. 두 번째는 거절합니다.
- 아직 없는 것: 로그인 시도의 IP별 제한, 비밀번호 변경·복구.

## 역할별 권한

| 할 수 있는 것 | owner (개발 관리자) | admin (관리자) | member | viewer |
| --- | --- | --- | --- | --- |
| 데이터 조회(주문, 기사, 구역, 센터) | 모든 센터 | 부여된 센터만 | 부여된 센터만 | 부여된 센터만 |
| 신청 목록 보기, 승인, 거절 | 예 | 아니오 | 아니오 | 아니오 |
| 활성 멤버 보기, 권한 회수 | 예 | 아니오 | 아니오 | 아니오 |
| 다른 계정에 줄 수 있는 역할 | admin, member, viewer | 없음 | 없음 | 없음 |
| 센터 입장 코드 만들기 | 예 | 아니오 | 아니오 | 아니오 |
| 센터 입장 코드 쓰기 | 불가(이미 전체) | 예 | 예 | 예 |
| 회수당할 수 있는가 | **아니오** | 예 | 예 | 예 |
| 자기 자신을 회수 | 불가 | 불가 | 불가 | 불가 |

- owner는 하나입니다. 승인 화면과 API로는 만들 수도, 회수할 수도 없습니다. 만들기와 이전은 터미널 명령만 합니다.
- admin, member, viewer는 지금 코드에서 데이터 접근 범위가 같습니다. 센터 범위(`account_center`)로만 갈립니다. 역할이 다른 동작을 하게 되는 기능이 생기면 이 표를 먼저 고칩니다.
- 센터가 하나도 없는 admin, member, viewer는 데이터가 비어 보입니다. 멤버 목록에는 "센터 없음"으로 표시합니다.
- owner의 센터 목록은 모든 계정에 부여된 센터의 합집합입니다. 멤버 목록에는 "전체 센터"로 표시합니다.

## 센터 입장 코드와 가입 코드

코드는 `accounts/CenterInvites.kt`, 테이블은 `center_invite`, `center_invite_center`(V8, V9)입니다. 같은 코드가 가입 열쇠이면서 센터 입장 열쇠입니다.

- 개발 관리자가 센터 1~10개와 역할(member 또는 viewer)을 정해 만듭니다(`POST /api/center-invites`). 코드는 응답에서 한 번만 보이고 서버에는 해시만 있습니다.
- 임의 12자(혼동되는 문자 제외), 한 번만 쓸 수 있고 7일 뒤 만료됩니다.
- **가입에는 코드가 필요합니다**(`ups.signup.code-required`, 기본 켜짐). 가입 화면은 `GET /api/auth/signup`으로 이를 알아냅니다.
  - 코드를 입력하기 전에는 문자 발송(`POST /api/auth/phone/send`)이 열리지 않습니다. 틀린 코드는 번호마다 15분에 5번까지이고, 그 사이 어떤 문자도 나가지 않습니다.
  - 가입이 끝나면 계정은 바로 활성이며 코드의 역할과 센터를 가집니다. 신청서는 `APPROVED`로 남고 감사 기록은 `center-invite`입니다.
  - 코드는 가입과 같은 트랜잭션에서 소모됩니다. 바로 직전에 다른 사람이 쓴 코드면 가입 전체가 취소됩니다.
- 이미 계정이 있는 사람은 코드를 입력해(`POST /api/center-invites/redeem`) 센터를 더합니다. 접근이 없던 계정은 코드의 역할로 활성이 되고, 접근이 있는 계정은 역할을 그대로 두고 센터만 늘어납니다. 여러 코드를 입력하면 센터가 계속 늘어납니다.
- 개발 관리자는 코드를 쓸 수 없습니다. 코드로는 admin과 owner를 줄 수 없습니다.
- 틀린 코드, 쓴 코드, 만료된 코드는 같은 400입니다. 로그인한 계정의 입력도 계정마다 15분에 5번까지입니다.
- 센터 관리자 이관 때는 `create`의 소유자 검사를 "자기 센터의 코드만"으로 넓힙니다(`HANDOFF` 주석).

## 가입과 승인

코드는 `accounts/SignUp.kt`, `accounts/AccessRequests.kt`, `accounts/AccessController.kt`입니다.

- 가입(`POST /api/auth/signup`)은 가입 코드가 있는 사람만 합니다(위 절). 코드가 필요 없게 켠 서버에서는 누구나 하고, 계정·대기 멤버십·접근 신청을 한 번에 만들며 **접근은 주지 않습니다.**
- 가입자가 입력하는 것: 로그인 ID, 비밀번호, 이름, 휴대폰 번호(와 인증). 동의 항목은 개인정보처리방침·위치기반서비스 약관이 생길 때까지 없습니다. 센터 코드와 기사 ID는 묻지 않습니다(API는 선택 항목으로 받고 비어 있으면 null). 역할·조직·센터 권한은 입력받지 않습니다.
- 센터는 개발 관리자가 승인할 때 직접 정합니다. 신청에 센터가 없고 승인에서도 정하지 않으면 400입니다.
- 휴대폰은 `+82…` 형식으로 정리해 저장합니다. 휴대폰 인증이 켜져 있으면 가입 본문에 `phoneProof`(같은 번호의 Firebase ID 토큰)가 있어야 하고, 신청서는 `phone_verified=true`로 만들어집니다. 꺼져 있으면 `false`로 시작합니다.
- 개발 관리자(owner)만 신청을 봅니다(`GET /api/access-requests`), 승인(`approve`)·거절(`reject`)·회수(`revoke`)합니다.
- 승인으로 줄 수 있는 역할은 `admin`, `member`, `viewer`입니다. `owner`는 승인으로 줄 수 없습니다.
- 승인자 판단은 `ApprovalPolicy` 한 곳입니다. 센터 관리자에게 이관하는 방법이 `HANDOFF` 주석에 있습니다.
- 신청은 14일 뒤 만료됩니다. 만료되면 대기 멤버십이 지워져 다시 신청할 수 있고, 이름·기사 ID·휴대폰은 만료·거절 30일 뒤 지웁니다.
- 모든 결정은 한 트랜잭션이며 `access_decision`에 한 줄이 추가됩니다(수정·삭제하지 않음). 증거 열에는 개인정보가 아니라 `phone_verified=false` 같은 값만 둡니다.
- 하루 가입 신청 상한은 `ups.signup.daily-limit`(기본 100)입니다. 넘으면 429입니다.
- 활성 멤버 목록(`GET /api/memberships`)은 owner만 봅니다. 센터는 멤버별로 묶어 돌려줍니다.
- 회수는 멤버십을 `REVOKED`로 바꾸고 `account_center`를 모두 지웁니다. 다시 승인하면 새로 정한 센터만 줍니다.
- 승인은 센터를 합치지 않고 **바꿉니다.** 이전 센터가 남지 않습니다.
- 재신청(`POST /api/access-requests`)은 로그인한 계정이 합니다. 멤버십이 없거나 거절·회수·만료일 때만 열리고, 대기·활성이면 409입니다. 입력 규칙과 하루 상한은 가입과 같습니다.
- 센터 목록(`GET /api/centers`)은 승인으로 허용된 센터입니다. 개발 관리자는 모든 센터를 봅니다. 센터 이름 출처가 아직 없어 코드가 이름입니다.

## 휴대폰 인증

코드는 `phone/PhoneVerification.kt`, `phone/IdentityPlatformSender.kt`, `phone/FirebasePhoneProof.kt`입니다.

- 켜는 조건: `ups.db.url`, `ups.firebase.project-id`(`UPS_FIREBASE_PROJECT_ID`), `ups.firebase.api-key-file`(`UPS_FIREBASE_API_KEY_FILE`) 세 가지입니다. 하나라도 없으면 `GET /api/phone`이 `configured:false`이고 문자는 나가지 않습니다.
- 웹 API 키는 공개용이지만 설정 한 곳에서만 읽도록 파일(mode 600)에 둡니다. 서버가 `GET /api/phone`으로 웹에 알려 줍니다.
- 가입 화면의 흐름(계정이 아직 없음): `GET /api/auth/phone`(켜져 있는지와 웹 설정) → 브라우저가 reCAPTCHA 토큰을 얻음 → `POST /api/auth/phone/send`(번호, `requestKey`, 토큰) → 서버가 장부(`phone_send`)에 먼저 예약하고 허락되면 한 번만 Firebase에 보냄 → 사람이 코드를 입력하면 브라우저가 Firebase ID 토큰을 얻어 `POST /api/auth/signup`의 `phoneProof`로 보냄. 서버가 서명·발급자·휴대폰 제공자·한국 번호·신선도를 확인하고, 같은 번호일 때만 `phone_verified=true`로 만듭니다. 토큰은 저장하지 않습니다.
- 이미 계정이 있는 대기 계정은 `POST /api/phone/send`, `POST /api/phone/verify`(로그인 필요)로 인증합니다. 번호는 호출자가 정할 수 없고 본인의 열린 신청서에 있는 번호입니다.
- 한도는 서버에서 지킵니다. 서비스 전체 24시간 10건(`ups.phone.daily-limit`), 계정(번호)당 3건, 같은 번호 60초 쿨다운입니다.
- 응답이 없거나 5xx면 "갔을 수 있음"으로 세고 다시 보내지 않습니다. 제공자가 4xx로 거절하면 세지 않습니다.
- 로그인한 대기 계정은 멤버십이 없어도 인증 경로를 쓸 수 있습니다. 세션과 CSRF 토큰은 똑같이 요구합니다.
- 인증은 접근을 주지 않습니다. 개발 관리자가 승인 화면에서 "인증됨" 표시를 보고 결정합니다.

로컬에서 켜기(웹 API 키는 터미널에서만 입력합니다. 대화·명령 인자에 넣지 않습니다):

```bash
umask 077; read -rs "K?Firebase web API key: "; printf %s "$K" > secrets/firebase-web-api-key; unset K
```

```bash
UPS_FIREBASE_PROJECT_ID=<Firebase 프로젝트 ID> \
UPS_FIREBASE_API_KEY_FILE="$PWD/secrets/firebase-web-api-key" \
UPS_DB_URL=jdbc:postgresql://127.0.0.1:5432/ups_dev UPS_DB_USER=$(whoami) \
UPS_SERVICE_TOKEN_FILE="$PWD/secrets/ups-service-token" \
java -jar services/api/build/libs/ups-api-0.0.1-SNAPSHOT.jar
```

- Firebase 콘솔에서 Authentication → 로그인 방법 → 전화를 켜고, 설정 → SMS 지역 정책을 한국만 허용으로 둡니다.
- 테스트는 가짜 제공자만 씁니다(`PhoneVerifyFlowTest`, `IdentityPlatformSenderTest`). 어떤 테스트도 실제 문자를 보내지 않습니다.
- 가입 전 발송도 같은 장부를 지나갑니다. 번호 단위로 3건·60초 쿨다운이고 전체 24시간 10건입니다. 인증 증명은 15분 안의 것만 받습니다(`ups.firebase.proof-max-minutes`).

## 센터·주문·한글 주소 스키마 (V5~V7)

값은 마이그레이션이 아니라 관제 웹이나 가져오기 도구가 넣습니다.

| 구분 | 테이블 | 채우는 곳 |
| --- | --- | --- |
| 센터 설정 | `center` (출발지, 수송량, 작업시간, 발신자, 알림) | 관제 웹 |
| 구역·기사 | `zone`, `driver`, `driver_zone` | 관제 웹 (최초 1회는 업로드 파일에서 가져옴) |
| Job의 센터 | `job_center` (Job당 한 행, 두 번째 지정은 DB가 거절) | 관제 웹 / 기본 센터 |
| 원본 행 | `delivery_task` (운송장, Job, 행 번호, 영문 주소로 가는 키) | Ours24 읽기 |
| 영문 원본·한글 주소 | `address_location` (+ `detail_ko`, `ko_source`, `verify_status`) | 읽기 / 번역 작업 / 관제 웹 교정 |
| 배송지표(구역) | `address_zone` | 동 매칭 자동 / 관제 웹 수동 |
| 정답지 | `address_reference` (업로드 파일의 한글 주소) | 가져오기 도구, 1회 |

- 고객 이름, 고객 연락처, 기사 이름·연락처는 어떤 테이블에도 두지 않습니다.
- 운송장 번호는 `delivery_task`에만 있고, 정답지에는 해시만 둡니다.
- 한글 열은 번역 전에 null입니다. 번역 작업이 `PUT /api/addresses/{key}`로 채웁니다.
- 주문의 센터: 그 Job의 `job_center`입니다. 지정된 센터가 없는 Job은 기본 센터(`ups.default-center`, `UPS_DEFAULT_CENTER`)를 따르고, 기본 센터도 없으면 `centerCode`는 null이며 화면은 "센터 미지정"을 보여 줍니다. `GET /api/orders?centerId=`는 그 센터의 주문만 돌려줍니다.
- Ours24 읽기는 같은 범위를 다시 읽지 않도록 서버가 기억합니다(`CachingOrderStore`): 오늘이 들어 있으면 60초, 과거만이면 1시간. 불완전한 답과 실패는 기억하지 않고, 같은 범위를 동시에 묻는 호출은 한 번의 읽기를 나눠 씁니다.
- 센터를 지정해 물으면(`centerId`) Job 목록만 먼저 읽고 그 센터의 Job 행만 읽습니다. 센터가 맡은 Job이 없으면 Ours24의 행은 읽지 않습니다.
- 같은 파일이 두 번 올라가 같은 운송장이 여러 Job에 있어도 `GET /api/orders`는 운송장마다 한 건만 돌려줍니다.
- Job의 센터 지정은 Job당 한 번입니다. 센터로 나누는 규칙(`method=RULE`)은 아직 없습니다.
- 기사(`driver`)는 메인 구역(`main_zone_id`) 하나와 담당 구역(`driver_zone`)을 가집니다. `GET /api/drivers`의 `routeCode`는 메인 구역 이름, `zones`는 메인 구역을 맨 앞에 둔 담당 구역입니다. `GET /api/routes`는 구역을 그 구역을 맡은 기사와 함께 돌려줍니다.
- 센터 이름은 `center.name_ko`가 있으면 `KRSNA · 성남`처럼 코드와 함께 보입니다.

### 정답지 가져오기

정답지는 비교용이며 확정된 답이 아닙니다. `scripts/import_reference.py`가 Fleetune 업로드 파일(xlsx)의 한글 주소를 `address_reference`에 한 번 넣습니다.

```bash
python3 scripts/import_reference.py --xlsx "<업로드 파일>" --date 2026-10-05 --dry-run
python3 scripts/import_reference.py --xlsx "<업로드 파일>" --date 2026-10-05 --database ups_dev
```

- 파일은 로컬에서만 읽고 저장소에 올리지 않습니다.
- 영문 원본은 Ours24에서 운송장 번호로 찾습니다(GET만). 그래서 주소 키가 서버가 만드는 키와 같습니다.
- 한글 주소가 없거나 영문이 섞인 행은 넣지 않고 건수만 셉니다.
- 같은 키에 한글 주소가 다르면 쓰지 않고 `conflict`로 셉니다. 다시 실행해도 기존 행은 바뀌지 않습니다.
- 출력은 건수뿐입니다. 이름, 연락처, 주소, 운송장 번호는 출력하지도 저장하지도 않습니다(운송장은 해시만).

## 주소 엔진을 위한 서비스 문 (확장)

`PUT /api/addresses/{key}`는 `detailKo`, `koSource`, `verifyStatus`도 받습니다.

- `addressKo`가 있으면 `koSource`(`TRANSLATOR`/`REFERENCE`/`MANUAL`)가 필요합니다.
- `detailKo`와 `koSource`는 `addressKo` 없이 보낼 수 없습니다(400).
- `verifyStatus`를 생략하면 저장된 값을 그대로 둡니다.
- `GET /api/addresses/references?limit=&after=`는 정답지를 키 순서로 한 페이지씩 읽습니다. 확정된 답이 아니라 비교용입니다.
- 이 문은 서비스 토큰만 열립니다. 엔진의 위치와 구조는 `engines/`에 고정되어 있습니다.

### 성남 센터 데모 설정

`scripts/demo_seongnam.py`가 업로드 파일(xlsx)에서 한 센터를 데이터베이스에 설정합니다. 먼저 정답지를 가져옵니다.

```bash
python3 scripts/import_reference.py --xlsx "<업로드 파일>" --date 2026-10-05
python3 scripts/demo_seongnam.py --xlsx "<업로드 파일>" --date 2026-10-05 --dry-run
python3 scripts/demo_seongnam.py --xlsx "<업로드 파일>" --date 2026-10-05 --database ups_dev
```

- 센터(출발지, 수송량, 작업시간, 발신자, 알림), 구역, 기사(차량번호가 기사 코드), 기사별 메인 구역(파일에 먼저 적힌 구역)과 담당 구역, 파일의 운송장을 가진 Job의 센터를 넣습니다.
- 기사 이름, 기사 연락처, 고객 정보는 읽지도 저장하지도 않습니다. 출력은 건수뿐입니다.
- 정답지의 한글 주소를 해당 주소의 `address_ko`에 `ko_source=REFERENCE`로 채웁니다. 이미 한글 주소가 있는 행은 바꾸지 않습니다.
- 메인 구역은 파일에 표시가 없어 "먼저 적힌 구역"이라는 가정입니다. 틀리면 관제 웹의 구역 관리가 생길 때 고칩니다.
- 다시 실행해도 같은 결과입니다.

### 좌표 입력 도구

`scripts/geocode_addresses.py`가 한글 주소는 있고 좌표가 없는 주소에 V-World로 좌표를 넣습니다(`status=DONE`).

```bash
python3 scripts/geocode_addresses.py --dry-run
python3 scripts/geocode_addresses.py --database ups_dev
```

- 방식은 DSV 경로 서버의 V-World 클라이언트를 따릅니다(`EVNSolution/clever-route-server`, `apps/delivery-api/src/modules/geocoding/vworld-geocoding.client.ts`, 리비전 `55c1a72b`). 도로명 번호가 있으면 ROAD 먼저, 아니면 PARCEL 먼저 찾고, 답의 시·도가 물은 시·도와 다르면 버립니다. Python으로 옮겼고 캐시 테이블과 장소 검색은 가져오지 않았습니다.
- 키는 UPS 전용 V-World 키입니다. DSV의 키는 쓰지 않습니다. `secrets/vworld-api-key`(mode 600)에서 도구가 읽고 출력하지 않습니다.
- 나가는 것은 괄호를 뺀 한글 주소 문자열뿐입니다. 이름, 번호, 운송장은 나가지 않습니다. `--dry-run`은 아무것도 보내지 않습니다.
- 한국 범위(위도 33~39, 경도 124~132) 밖의 좌표는 쓰지 않습니다. 찾지 못한 주소는 그대로 두고 건수만 셉니다. 호출 한도 응답을 받으면 멈춥니다.
- 출력은 건수뿐입니다.
