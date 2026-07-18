# GitHub APK 서명 시크릿 등록

안드로이드 앱은 **같은 applicationId와 같은 서명 키**를 계속 사용해야 기존 설치본 위에 업데이트할 수 있습니다.
처음 만든 키스토어 파일과 비밀번호는 분실하면 복구할 수 없으므로 별도 안전한 위치에도 보관하세요.

## 1. 영구 서명 키 만들기

Java가 설치된 PC의 PowerShell에서 실행합니다. 아래 별칭은 그대로 사용해도 됩니다.

```powershell
keytool -genkeypair -v -keystore tvroom-release.jks -alias tvroom -keyalg RSA -keysize 4096 -validity 10000
```

## 2. 키스토어를 Base64 문자열로 변환

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes((Resolve-Path .\tvroom-release.jks))) | Set-Clipboard
```

## 3. GitHub Repository Secrets 등록

GitHub 저장소의 `Settings → Secrets and variables → Actions → New repository secret`에서 다음 네 개를 등록합니다.

| Secret | 값 |
|---|---|
| `ANDROID_KEYSTORE_BASE64` | 위 명령으로 복사한 긴 Base64 문자열 |
| `ANDROID_KEYSTORE_PASSWORD` | 키스토어 생성 때 입력한 비밀번호 |
| `ANDROID_KEY_ALIAS` | `tvroom` |
| `ANDROID_KEY_PASSWORD` | 키 비밀번호 |

키스토어 원본은 Git에 커밋하지 마세요. `.gitignore`에서 차단되어 있습니다.

## 4. 이후 업데이트

`app/build.gradle.kts`의 `versionCode`를 반드시 이전보다 크게 올리고 `versionName`도 변경한 뒤 푸시합니다.
동일한 네 개의 시크릿을 유지하면 GitHub Actions가 업데이트 설치 가능한 서명 APK를 생성합니다.
