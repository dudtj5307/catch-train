# Catch Train

코레일(KTX) 조회 결과 화면을 감시하다가, 사용자가 체크해 둔 칸이 열리면 알리고
그 칸을 골라 **[예매] 를 눌러 주는 것까지** 하는 도구.

```
코레일 사이트에서 사용자가 직접 조회
  → 결과 목록을 DOM 으로 읽어 [열차 선택] 목록 생성
  → 사용자가 (열차 × 좌석등급) 칸 체크
  → 감시 시작: 페이지 새로고침 → DOM 분석 → 체크한 칸이 AVAILABLE?
  → 알림 → 그 칸 터치(1단계) → 하단 바 [예매] 터치(2단계) → RESERVED
     (여기서 끝. 좌석 선택과 결제는 사용자)
```

**자동 결제·CAPTCHA 우회·로그인 자동화는 구현하지 않는다.**
조회 조건(구간/날짜/시간)도 갖고 있지 않다 — 사이트에 있는 그대로 쓴다.

---

## 저장소 구조

한 레포에 클라이언트 둘이 들어 있다. 나누는 기준은 **"코레일이 바뀌면 같이 깨지는가"** 다.
같이 깨지는 것은 공통, 플랫폼 사정은 각자.

| 위치 | 내용 |
|---|---|
| [`docs/`](docs/) | **공통.** 설계 규칙과 코레일 사이트 실측. 양쪽 코드가 `§번호` 로 참조한다 |
| [`shared/`](shared/) | **공통.** 두 클라이언트가 같이 쓰는 selector 등 (계획 단계) |
| [`CLAUDE.md`](CLAUDE.md) | **공통.** 대원칙과 작업 지침. 코드를 건드리기 전에 읽는다 |
| [`android/`](android/) | 안드로이드 앱 (WebView). Gradle 루트가 여기다 |
| [`extension/`](extension/) | 크롬 확장 (MV3). **작업 시작 단계 — 아직 골격뿐이다** |

Android Studio 로 열 때는 저장소 루트가 아니라 **`android/` 폴더**를 연다.

## 문서

| 문서 | 내용 | 언제 |
|---|---|---|
| [`CLAUDE.md`](CLAUDE.md) | 대원칙 · 코드 지도 · 작업 지침 | 코드를 건드리기 전 |
| [`docs/DESIGN.md`](docs/DESIGN.md) | 설계 규칙과 실측 메모. 코드 KDoc 이 `§번호` 로 가리킨다 | 동작을 바꾸기 전 |
| [`docs/KTX-MIGRATION.md`](docs/KTX-MIGRATION.md) | KTX 전환 진행 상황과 남은 일 | 작업을 시작할 때 |
| [`docs/HISTORY.md`](docs/HISTORY.md) | 원설계 의도, 폐기된 모델 | 거의 안 읽어도 된다 |
| [`android/README.md`](android/README.md) | 안드로이드 빌드와 쓰는 흐름 | |
| [`android/RELEASE.md`](android/RELEASE.md) | 서명 키 / 버전 올리기 / APK 배포 | 배포할 때만 |
| [`android/INSTALL.md`](android/INSTALL.md) | 최종 사용자에게 APK 와 함께 주는 안내문 | 배포할 때만 |
| [`extension/README.md`](extension/README.md) | 확장에서 갈리는 지점과 정해야 할 것 | |

## 주의

재조회 간격을 짧게 두면 요청이 그만큼 많아지고 **접속이 차단된다.**
SRT 대응 시절에 실제로 겪었다. 사이트 이용정책과 요청 제한을 준수할 것.

SRT 대응 마지막 버전은 태그 **`v0.1.1-srt`** 에 있다 (`git show v0.1.1-srt:<경로>`).
