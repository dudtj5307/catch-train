# 배포 (APK 직접 전달)

스토어에 올리지 않고 **서명된 release APK 를 직접 전달**한다. Play 스토어는 타사 사이트를
사용자 계정으로 자동 조작하는 앱을 `Device and Network Abuse` 로 걸러내므로 애초에 대상이 아니다.

받는 사람에게 줄 안내문은 [`../INSTALL.md`](../INSTALL.md) 에 따로 있다. APK 와 함께 전달한다.

---

## 서명 키

`applicationId` 는 `dev.yslee.srtwatcher`. release 빌드는 루트의 `keystore.properties` 를
읽어 서명한다. 이 파일이 없으면 **서명 없는 APK** 가 나오고(경고 출력) 기기에 설치되지 않는다.

| 파일 | 역할 |
|---|---|
| `srtwatcher-release.jks` | 서명 키 (PKCS12, RSA 2048, 유효기간 10000일) |
| `keystore.properties` | 위 파일 경로와 비밀번호 |
| `keystore.properties.example` | 다른 환경에서 채워 쓰는 템플릿 |

> **이 두 파일을 잃어버리면 같은 앱의 업데이트를 영영 낼 수 없다.**
> 다른 키로 서명한 APK 는 기존 앱 위에 설치되지 않고, 사용자가 지우고 다시 깔아야 한다.
> 저장소 밖(비밀번호 관리자, 외장 백업 등)에 따로 보관할 것. 둘 다 `.gitignore` 에 있다.

키를 새로 만들어야 하면:

```bash
keytool -genkeypair -v -keystore srtwatcher-release.jks -storetype PKCS12 -keyalg RSA -keysize 2048 -validity 10000 -alias srtwatcher
```

그 뒤 `keystore.properties.example` 을 `keystore.properties` 로 복사해 값을 채운다.

## 빌드

```bash
./gradlew :app:assembleRelease
```

**`--offline` 은 쓰지 않는다.** release 빌드의 `lintVital` 이 `lint-gradle` 을 받아와야 해서
오프라인에서는 실패한다. (debug 빌드와 단위 테스트는 `--offline` 로 된다)

결과물: `app/build/outputs/apk/release/app-release.apk`

전달 전에 서명을 확인한다 (`Verifies` 가 나와야 한다):

```bash
apksigner verify --print-certs app/build/outputs/apk/release/app-release.apk
```

배포용 사본은 버전이 드러나는 이름으로 `dist/` 에 둔다: `dist/SRT-Watcher-0.1.0.apk`

## 새 버전을 낼 때

1. `app/build.gradle.kts` 의 **`versionCode` 를 1 올린다.** 이 값이 그대로면 사용자 기기가
   업데이트로 인식하지 않는다.
2. `versionName` 을 사람이 읽는 값으로 올린다 (`0.1.0` → `0.2.0`).
3. `assembleRelease` → 서명 확인 → `dist/` 에 새 이름으로 복사 → 전달.

같은 키로 서명했으면 사용자는 **덮어 설치**만 하면 되고 설정은 유지된다.

## R8 을 켜지 않는 이유

`isMinifyEnabled = false` 는 의도적이다. 이 앱의 핵심은 WebView 안의 DOM 을 읽는 것이라
난독화로 얻을 것이 없고, 규칙 누락으로 파서가 깨질 위험만 생긴다.
