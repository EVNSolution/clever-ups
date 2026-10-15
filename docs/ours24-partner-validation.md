# Ours24 파트너 API 검증

키는 `secrets/ours24-api-key`에 저장합니다.
`scripts/ours24_partner.py`가 키를 읽고 `x-api-key` 헤더에 넣습니다.
키 값을 대화에 붙여넣거나 명령 인자로 전달하지 않습니다.

## 키 입력

macOS의 로컬 터미널에서 실행합니다.

```bash
cd /Users/jiin/Documents/Files/03_Work_EVnSolution/01_Repos/08_UPS/clever-ups
python3 scripts/ours24_partner.py set-key
```

프롬프트에 키를 붙여넣고 Enter를 누릅니다.
입력 내용은 화면에 나타나지 않습니다.
기존 키가 있으면 새 키로 교체합니다.
키를 교체할 때도 같은 명령을 사용합니다.

- 디렉터리 권한: `700`.
- 파일 권한: `600`.
- Git 제외: 기존 `.gitignore`의 `secrets/` 규칙.
- 초기 구성은 빈 파일로 생성했습니다. 사용자가 입력한 키는 Git에 포함하지 않습니다.
- `AGENTS.md`는 에이전트의 비밀 파일 읽기·검색·출력을 금지합니다.

이 구성은 키 값이 모델의 도구 결과에 나타나지 않도록 합니다.
모델과 실행 프로그램은 같은 사용자 권한을 사용합니다.
따라서 파일 권한과 지침은 모델의 접근 권한을 기술적으로 분리하지 않습니다.
접근 권한 자체를 분리하려면 다른 사용자나 별도 비밀정보 서비스가 필요합니다.
현재 구성은 그 수준의 격리를 제공하지 않습니다.

파일 상태는 내용을 읽지 않고 확인할 수 있습니다.

```bash
python3 scripts/ours24_partner.py check-key
```

이 명령은 파일 권한과 비어 있는지 확인합니다.
키의 유효성은 API 인증 응답으로 확인합니다.

## 최초 조회 검증

조회할 날짜를 지정하고 한 페이지만 요청합니다.
아래 날짜는 2026-10-07 KST입니다.
다른 날짜를 검증할 때 두 날짜를 변경합니다.

```bash
python3 scripts/ours24_partner.py jobs --start-date 2026-10-07 --end-date 2026-10-07 --size 1
```

요청: `GET https://api.ours-24.com/jobs?start_date=2026-10-07&end_date=2026-10-07&size=1&page=1`.
운영 서버의 조회입니다. 명세에 별도 공개 테스트 서버가 없습니다.
배송 완료·배송 예외·주소 변경 요청은 이 도구가 지원하지 않습니다.

출력에는 HTTP 상태, JSON 여부, 필수 구조 충족 여부와 건수만 포함합니다.
키, 요청 헤더, 응답 원문, 고객 주소, 전화번호는 출력하거나 저장하지 않습니다.
외부 응답의 오류 메시지도 출력하지 않습니다.
응답 구조가 맞고 HTTP 2xx이면 종료 코드는 `0`입니다.
인증 실패, 네트워크 실패, 응답 구조 불일치는 종료 코드 `1`입니다.
HTTP 200의 빈 목록은 데이터 연동 완료를 증명하지 않습니다.

키를 보내지 않는 인증 검증도 가능합니다.
명세의 기대값은 `401`이며 종료 코드는 `1`입니다.
2026-10-07 수정한 도구로 키 미포함 `401`, 키 포함 `200`을 확인했습니다.
키 포함 응답은 필수 구조 검사를 통과했습니다. 위 날짜 조건의 결과는 `jobs: []`, `total: 0`입니다.
인증과 `/jobs` 접근은 확인했습니다. 날짜 경계와 실제 업무 필드는 별도 검증이 필요합니다.
프로그램이 로컬 키를 내부에서 읽어 헤더에 넣었습니다. 에이전트는 키 값을 출력하거나 직접 열지 않았습니다.

```bash
python3 scripts/ours24_partner.py jobs --start-date 2026-10-07 --end-date 2026-10-07 --size 1 --without-key
```

### User-Agent 확인

초기 HTTP 403의 차이는 User-Agent였습니다.
같은 키·URL·조회 조건에서 User-Agent 하나만 변경해 확인했습니다.

| 조건 | HTTP 결과 |
| --- | --- |
| Python urllib 기본 User-Agent | 403 |
| `Mozilla/5.0` User-Agent | 200 |
| `CLEVER-UPS-Partner-Validation/1.0` User-Agent | 200 |
| 기본 User-Agent에 Origin만 추가 | 403 |
| 기본 User-Agent에 Referer만 추가 | 403 |

도구는 `User-Agent: CLEVER-UPS-Partner-Validation/1.0`을 명시합니다.
브라우저의 쿠키를 가져오지 않고 자체 클라이언트 이름을 사용합니다.
어느 서버 계층의 규칙이 기본 User-Agent를 거부하는지는 확인하지 않았습니다.
제공자에게 키 오류로 판단하기 전에 요청의 User-Agent를 확인합니다.

다음 조회에는 제공받은 검증용 식별자를 사용합니다.

```bash
python3 scripts/ours24_partner.py tasks --job-id TEST_JOB_ID --limit 1
python3 scripts/ours24_partner.py tracking --tracking-number TEST_TRACKING_NUMBER
```

운송장 조회는 `carrier=UPS`, `x-api-version: 1`, `x-carrier-environment: dev`를 사용합니다.
`dev`는 운송사 연동 환경 헤더입니다. BFF 서버는 같은 운영 URL입니다.
실제 테스트 데이터와 키의 접근 범위는 API 제공자에게 확인해야 합니다.
임의의 운송장이나 Job ID를 유효한 검증 데이터로 취급하지 않습니다.

## 값 없이 구조 확인

`--shape`는 필드 경로와 형식, 그리고 `status` 같은 몇 개 키의 대문자 코드만 출력합니다.
이름·주소·전화번호·운송장 번호 같은 값은 출력하지 않습니다.
`tasks`와 `tracking`의 `--from-date`는 그날 첫 Job(과 첫 행)을 프로그램 안에서 골라 씁니다. Job ID와 운송장 번호는 출력하지 않습니다.

```bash
python3 scripts/ours24_partner.py jobs --start-date 2026-10-07 --end-date 2026-10-07 --size 5 --shape
python3 scripts/ours24_partner.py tasks --from-date 2026-10-07 --shape
python3 scripts/ours24_partner.py tracking --from-date 2026-10-07 --shape
```

2026-10-08에 확인한 실제 응답입니다. 모두 GET이며 운영 서버를 조회했습니다.

| 대상 | 확인한 필드 | 관찰한 코드 |
| --- | --- | --- |
| Job 목록 | `jobs[]`: `id`, `jobId`, `status`, `createdAt`, `totalTasksCount`. 페이지: `total`, `page`, `limit`, `totalPages` | `READY` |
| 행(task) | `rowNo`, `status`, `trackingNumber`, `customerName`, `companyName`, `address1`, `address2`, `postalCode` | `PENDING` |
| 추적(dev) | `success`, `carrier`, `trackingNumber`, `status`, `statusDescription`, `timeline`, `carrierMetadata.logCount` | `RECEIVED`, 타임라인 비어 있음 |

- 행에는 좌표, 연락처, 센터, 구역(loop), 기사, 계획 일자·순번이 없습니다. 주소는 정제 전 문자열입니다.
- Job 하나가 하나의 배치입니다. 확인한 Job 하나에 행이 100건 이상 있었습니다.
- `/jobs`의 조회 기간은 양 끝을 포함해 7일까지 받습니다. 8일 이상은 HTTP 400입니다. 명세에는 없고 조회로 확인했습니다.
- `/tasks`는 `limit`과 `page`를 무시하고 항상 첫 50행을 돌려줍니다(응답 `page: 1`, `limit: 50`). `limit=5`, `page=2`로 확인했습니다.
  명세는 `limit` 최대 200을 정의합니다. 이 동작은 Ours24 확인이 필요합니다.
- 이 확인 시점에는 2026-10-07에 Job이 여러 건 있었습니다. 앞의 "2026-10-07 `total: 0`" 결과는 그날 데이터가 들어오기 전의 것입니다.

## 문서 확인 결과

2026-10-07에 [공식 문서](https://api.ours-24.com/docs/partner#description/introduction)와 [OpenAPI 원본](https://api.ours-24.com/openapi-partner.json)을 확인했습니다.
OpenAPI는 `3.1.0`, API 문서 버전은 `1.1.0`입니다.
원본 스냅샷은 공개 저장소에 두지 않습니다. 필요하면 위 주소에서 받아 `docs/references/ours24/`(Git 제외)에 둡니다.
원본 SHA-256: `f15961c4aa45b0e4f6797baf3b1592bdb6bcc0a4150455e54f58d29c12a46d75`.

| 메서드 | 경로 | 문서의 기능 |
| --- | --- | --- |
| GET | `/jobs` | Job 목록 |
| GET | `/jobs/{id}/tasks` | 업로드 원본 행 목록 |
| GET | `/carriers/trackings/{trackingNumber}` | 배송 추적 |
| POST | `/carriers/trackings/delivery` | 배송 완료 보고 |
| POST | `/carriers/trackings/exception` | 배송 예외 보고 |
| POST | `/addresses/update` | 주소 정제 결과 반영 |

이 명세만으로 UPS 배차 시스템 전체 연동을 확정할 수 없습니다.

- `/jobs`의 각 Job 객체는 명세에 업무 필드가 정의되어 있지 않습니다. 실제 필드는 위 표를 따릅니다.
- `/jobs`는 관리자·파트너 목록을 공유합니다. 파라미터에 따라 응답 형태가 바뀐다고 명시합니다.
- `/tasks`는 명세에 `rowNo`, `status` 외의 원본 행 필드를 고정하지 않습니다. 실제 필드는 위 표를 따릅니다.
- 차량, 기사 목록, 배차 계획, 경로, 실시간 GPS API는 이 명세에 없습니다.
- 페이지 반복 조회, 날짜 경계·타임존, 호출 제한, 키 만료·권한 범위는 추가 확인이 필요합니다.
- 변경 API의 중복 방지·재시도 계약과 주소 변경의 고정 응답 구조는 추가 확인이 필요합니다.

먼저 인증과 최소 응답 구조를 확인합니다.
다음으로 제공받은 실제 샘플과 UPS의 필요한 업무 필드를 대조합니다.
이 도구의 검사는 필수 구조의 일부를 확인합니다. 전체 OpenAPI 검증기는 아닙니다.

## API에서 읽기

`GET /api/orders?from=YYYY-MM-DD&to=YYYY-MM-DD`는 Ours24의 Job과 행을 읽어 주문으로 돌려줍니다. GET만 사용합니다.
환경 변수 `OURS24_KEY_FILE`에 키 파일의 경로를 주면 연결이 켜집니다. 값은 키가 아니라 경로입니다.
설정하지 않으면 Ours24에 연결하지 않고 빈 목록을 돌려줍니다.

```bash
cd services/api
OURS24_KEY_FILE="$PWD/../../secrets/ours24-api-key" ./gradlew bootRun
```

- 인증이 아직 없어 API는 이 컴퓨터(127.0.0.1)에서만 받습니다. 다른 곳에서 열려면 환경 변수 `SERVER_ADDRESS`를 지정하되, 인증을 만든 뒤에 합니다.
- 키 파일은 일반 파일이고, 현재 사용자 소유이며, 권한이 600이어야 합니다. 맞지 않으면 API가 시작하지 않습니다.
- 키는 요청마다 파일에서 읽습니다. 로그와 오류에 쓰지 않습니다.
- 날짜는 받은 그대로 Ours24에 보냅니다. 기간 제한은 코드에 두지 않았습니다. 8일 이상은 Ours24가 HTTP 400을 주고 API는 502를 돌려줍니다.
- 502 본문은 고정 문구입니다. Ours24가 보낸 내용은 전달하지 않습니다.
- 한 번에 읽는 Job은 최대 200건입니다. 넘으면 응답 헤더 `X-Data-Incomplete: true`를 보냅니다.
- 위 `/tasks` 제한 때문에 Job마다 첫 50행만 읽습니다. 이때도 `X-Data-Incomplete: true`를 보내고, 화면이 경고를 보입니다. Ours24가 `page`를 지원하면 같은 코드가 모든 페이지를 읽습니다.
- 운송장 번호가 없는 행은 주문으로 만들지 않습니다. 건수는 서버 로그의 `skippedRows`에 남습니다.
- 로그에는 건수만 남깁니다. 이름·주소·운송장 번호는 쓰지 않습니다.
- 주문 ID는 `<Job ID>:<rowNo>`입니다. 주문에는 아직 센터가 없습니다.

2026-10-08에 실제 키로 이 API를 통해 읽은 결과입니다(건수만 기록).

| 조회일 | Job | 주문 | 비고 |
| --- | --- | --- | --- |
| 2026-10-07 | 7건 | 348건 | 모든 Job이 정확히 50행. 운송장 없는 2행 제외. `X-Data-Incomplete: true` |
| 2026-10-08 | 2건 | 100건 | 모든 Job이 정확히 50행. `X-Data-Incomplete: true` |

- 2026-10-01~2026-10-08(8일)을 요청하면 Ours24가 HTTP 400을 주고 API는 502를 돌려줍니다.
- 2026-10-07의 서로 다른 Job에 같은 운송장 번호가 88건 있었습니다(최대 2회). 한 Job 안의 중복은 없었습니다. 재업로드인지 확인이 필요합니다.
- Ours24 호출 1회가 약 0.7초입니다. Job을 하나씩 읽으므로 Job 7건에 호출 약 15회, 응답 9~11초였습니다.

## 로컬 검사

```bash
python3 -B -m unittest discover -s tests -v
```

테스트는 임시 디렉터리의 가짜 키만 사용합니다.
실제 키 파일과 외부 API를 사용하지 않습니다.
