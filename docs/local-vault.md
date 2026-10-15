# 로컬 비밀번호 보관 (`scripts/ups_vault.py`)

개발 관리자 비밀번호를 평문 파일로 두지 않고, 읽지 않은 채로 API를 호출하기 위한 도구입니다.
Ours24 키 도구(`ours24_partner.py`)와 같은 방식입니다.

## 쓰는 법

```bash
python3 scripts/ups_vault.py init                        # 키 파일 만들기(한 번)
python3 scripts/ups_vault.py seal --source <비밀번호 파일>  # 암호화하고 평문 파일은 덮어써서 지움
python3 scripts/ups_vault.py login                       # 로그인. 세션도 암호화되어 저장됨
python3 scripts/ups_vault.py call GET /api/me            # API 호출
python3 scripts/ups_vault.py call GET "/api/orders?from=2026-10-11"
python3 scripts/ups_vault.py logout
```

## 어디에 무엇이 있는가

| 항목 | 위치 | 보호 |
| --- | --- | --- |
| 암호화된 비밀번호 | `secrets/<이름>.enc` | AES-256-CBC, PBKDF2 60만 회, 모드 600, Git 제외 |
| 암호화된 세션 | `secrets/.session-<이름>.enc` | 같음 |
| 키 파일 | `~/.config/ups/vault.key` (`UPS_VAULT_KEY_FILE`로 변경) | 모드 600, 프로젝트 밖, Documents 밖 |

## 출력 규칙

- 비밀번호, 접근 토큰, 갱신 토큰은 어떤 출력에도 나오지 않습니다.
- 배열 응답(주문, 기사 등)은 건수와 필드 이름만 나옵니다. 고객 정보가 화면에 나오지 않습니다.
- 본문 전체가 필요하면 `--raw`를 붙입니다. 사용자가 터미널에서 직접 실행할 때만 씁니다.
- 접근 토큰이 끝나면(401) 갱신 토큰으로 한 번 자동 갱신하고 같은 요청을 다시 보냅니다.

## 막아 주는 것과 막지 못하는 것

- 막는 것: 파일을 `@`로 붙이거나 복사·동기화·백업·커밋할 때 평문이 노출되는 것.
- 막지 못하는 것: 이 컴퓨터에서 같은 사용자로 실행되는 프로그램. 키 파일을 읽어 복호화할 수 있습니다.
- 더 강하게 하려면 키 파일을 암호화된 디스크 이미지(`hdiutil create -encryption AES-256`)에 두고 `UPS_VAULT_KEY_FILE`로 가리킵니다.
- 키 파일을 잃으면 `.enc`를 풀 수 없습니다. 비밀번호를 알고 있다면 `seal`을 다시 하면 됩니다.
