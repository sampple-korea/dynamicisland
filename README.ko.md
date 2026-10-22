[![English](https://img.shields.io/badge/English-555?style=flat-square)](README.md) [![한국어](https://img.shields.io/badge/%ED%95%9C%EA%B5%AD%EC%96%B4-2F80ED?style=flat-square)](README.ko.md)

# Dynamic Island

삼성 One UI용 다이나믹 아일랜드입니다. 상태바의 Now Bar 캡슐을 알약과 카드로 바꾸고, 일반 알림도 라이브 카드로 보여 줍니다.

![Android](https://img.shields.io/badge/Android-15%2B-3DDC84?logo=android&logoColor=white) ![LSPosed](https://img.shields.io/badge/LSPosed-Xposed%20API%2093-6f42c1) ![License: MIT](https://img.shields.io/badge/License-MIT-blue)

## 주요 기능

- **라이브 알림 카드:** 시스템 UI의 모든 진행 중 활동에 작은 알약과 펼친 카드를 제공합니다. 헤더, 스톱워치, 설명, 일반·구간·불확정 진행바, 아이콘·텍스트 버튼, 커스텀 칩, 업무 프로필 배지를 지원합니다. 통화, 캐스트, 화면 녹화, 타이머, 음악 플레이어(시크바, 앨범 색 글로우, 파형, 출력 위치)도 포함합니다.
- **일반 알림 카드:**
  - 메시지와 배너를 기본 헤드업 배너 대신 아일랜드 카드로 보여 줍니다(전화와 알람은 기본 배너 유지).
  - 인증번호 카드에 복사 버튼이 있습니다(자동 복사 옵션, 민감 정보 표시).
  - 다운로드·복사·업데이트를 진행 카드로 보여 줍니다. "73%", "12 MB / 40 MB"처럼 글자로만 적힌 진행도 처리하며, 카드가 떠 있는 동안 알림창의 원본을 숨길 수 있습니다.
  - 음성 채팅·VoIP 통화는 통화 시간과 종료 버튼이 있는 통화 카드로 보여 줍니다.
- **데이터로 된 규칙:** 배달, 픽업, 택배, 택시, 길안내, 웨어러블, 배터리 카드는 LiveBridge 사전 형식의 규칙 파일에서 옵니다. 1년 반 동안 기록한 알림으로 만든 한국어 규칙 파일이 내장되어 있어 다운로드가 없고 네트워크 권한도 없습니다. 클립보드로 내 규칙을 추가할 수 있습니다.
- **시스템 이벤트:** 충전, 배터리 부족, 소리 모드, 배터리가 보이는 블루투스 오디오, 카메라·마이크·위치 사용, 빠른 설정 피드백(방해금지, 비행기, 절전, 손전등, 핫스팟, 자동 회전)을 보여 줍니다.
- **앱 관리:** 일반 알림 기능에 대해 앱 블랙리스트 또는 화이트리스트를 지정합니다. 라이브 알림, 미디어, 통화, 시스템 이벤트는 걸러지지 않습니다.
- **모션:** 스프링 기반 애니메이션, 찌그러짐·늘어남 효과, 알약과 카드 사이의 액체 연결, 알약에서 자라나는 카드.
- **One UI 설정 앱:** 묶음 카드와 하위 페이지로 구성된 허브이며 한국어와 영어를 지원합니다.
- **안전장치:** 시스템 UI가 반복해서 죽으면 크래시 가드가 모듈을 끄고, 후크는 그룹별로 격리되며, 진단 페이지에서 모듈이 시스템 UI 안에서 한 일을 볼 수 있습니다.

## 요구 사항

- One UI 8 / 9(Android 16 / 17)의 삼성 기기와 기본 시스템 UI.
- LSPosed(모듈 최소 SDK 35).
- 루트는 선택 기능인 **시스템 UI 재시작** 버튼에만 필요합니다.

## 설치

1. 프로젝트 **Releases**에 서명된 APK가 있으면 설치합니다. 없으면 아래 안내대로 직접 빌드합니다.
2. LSPosed에서 **Dynamic Island**를 활성화하고 범위를 **시스템 UI**(`com.android.systemui`)로 지정합니다.
3. 시스템 UI를 재시작합니다(앱의 **시스템 UI 재시작** 버튼 또는 재부팅).
4. 런처에서 Dynamic Island를 엽니다. 설정은 몇 초 안에 적용되며 재시작이 필요 없습니다.

## 설정

| 페이지 | 내용 |
|---|---|
| 표시와 모션 | 카드 헤더, 오디오 파형, 햅틱, 자동 접힘·일시정지 음악 시간 |
| 알림 카드 | 알림을 아일랜드로, 진행 카드, 인증번호, 음성 채팅 통화 |
| 시스템 이벤트 | 충전, 소리 모드, 블루투스, 설정 변경, 개인정보 표시 |
| 앱 관리 | 블랙리스트 / 화이트리스트와 앱 목록 |
| 규칙 | 카드 규칙별 스위치, 규칙마다 단계·앱·문구(직접 추가 가능), 알림 글이 어떤 카드가 되는지 확인하는 시험, 가져오기·내보내기 |
| 도구 | 시스템 UI 재시작, 진단 |

## 동작하지 않을 때

시스템 UI를 재시작한 뒤 앱의 **도구 → 진단**을 엽니다. 시스템 UI 버전, 시작 단계, 붙지 못한 후크, 오류, 이전 크래시가 나옵니다. **리포트 복사**로 복사해서 이슈에 첨부하세요.

## 소스에서 빌드

JDK 17 이상과 Android SDK(compile SDK 36)가 필요합니다.

```bash
./gradlew assembleRelease     # 서명되지 않은 APK: app/build/outputs/apk/release
KEYSTORE_PASSWORD=... scripts/sign.sh   # 빌드, 정렬, 서명 -> release/DynamicIsland-<버전>.apk
./gradlew testReleaseUnitTest # 단위 테스트와 헤드리스 렌더 테스트
```

`scripts/sign.sh`는 키스토어 비밀번호를 환경 변수 `KEYSTORE_PASSWORD`로 받고, 처음 실행할 때 `release-keys/`에 자체 서명 키를 만듭니다(git 제외). 업데이트는 같은 키로 서명해야 하므로 보관하세요. 소스 구조, 후크, 규칙 형식, 테스트 설정은 [`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md)를 참고하세요.

## 릴리스

서명된 APK는 [Releases](../../releases) 페이지에 올라옵니다. 빌드는 자동화되어 있습니다([`docs/DEVELOPMENT.md`](docs/DEVELOPMENT.md) 참고). 모든 릴리스는 같은 키로 서명되므로 새 버전이 기존 앱 위에 업데이트로 설치됩니다.

## 개인정보

모듈은 시스템 UI 안에서 동작하며 시스템 UI가 이미 가진 알림을 읽습니다. 네트워크 권한이 없고 어디에도 전송하지 않습니다. 설정과 진단 리포트는 기기에만 남고, 리포트에는 알림 내용이 들어 있지 않습니다.

## 면책

Dynamic Island는 삼성과 무관한 독립 비공식 프로젝트입니다. 비공개 시스템 UI 클래스를 후크하므로 업데이트로 언제든 동작이 바뀔 수 있고, 실제 기기가 아닌 헤드리스 테스트·렌더 환경에서만 검증했습니다. 사용에 따른 책임은 사용자에게 있습니다.

## 크레딧

- [LiveBridge](https://github.com/appsfolder/livebridge): 이 프로젝트가 읽는 규칙 파일 형식 (LiveBridge 파일은 포함하지 않습니다)
- @cryyear의 DynamicIsland ([github.com/Spottq](https://github.com/Spottq)): 이 프로젝트에 영감을 준 프로젝트이며, 여기의 코드는 새로 구현한 것입니다
- [Xposed API](https://api.xposed.info/)(Apache-2.0, `app/libs/xposed-api-82.jar`)와 [LSPosed](https://github.com/LSPosed/LSPosed)

## 라이선스

[MIT](LICENSE) © 2026 sampple-korea.

## Made by [sampple-korea](https://github.com/sampple-korea)
