# 티비룸 텔레그램 활성화 설정

## 새 텔레그램 봇 만들기

1. 텔레그램에서 공식 `@BotFather`를 엽니다.
2. `/newbot`을 보내고 표시 이름과 `bot`으로 끝나는 사용자명을 지정합니다.
3. BotFather가 발급한 토큰을 안전한 곳에 잠시 보관합니다.
4. 토큰은 비밀번호와 같으므로 채팅, 저장소 파일, 화면 캡처에 노출하지 않습니다.

## 관리자 채팅 ID 확인

1. 새로 만든 봇과의 개인 채팅을 열어 `/start`를 보냅니다.
2. 웹 브라우저에서 아래 주소의 `<BOT_TOKEN>`만 실제 토큰으로 바꿔 엽니다.

```text
https://api.telegram.org/bot<BOT_TOKEN>/getUpdates
```

3. 반환된 JSON에서 `result → message → chat → id` 숫자를 찾습니다.
4. 그 숫자가 `TELEGRAM_ADMIN_CHAT_ID`입니다. 그룹을 관리자 채팅으로 사용하면
   채팅 ID가 음수일 수 있으므로 앞의 `-`까지 그대로 저장합니다.
5. 토큰이 포함된 주소를 다른 사람에게 보내거나 화면 캡처하지 않습니다.

## GitHub Actions 비밀값 등록

GitHub 저장소에서 `Settings → Secrets and variables → Actions`로 이동한 뒤
`New repository secret`을 눌러 다음 두 값을 등록합니다.

- `TELEGRAM_BOT_TOKEN`: BotFather가 발급한 새 봇 토큰
- `TELEGRAM_ADMIN_CHAT_ID`: 활성화 명령을 보낼 관리자 텔레그램 채팅 ID

두 값 중 하나라도 없거나 형식이 올바르지 않으면 GitHub Actions의
`Validate Telegram activation secrets` 단계에서 APK 빌드가 중단됩니다.
실제 값은 소스 파일이나 Actions 로그에 출력하지 않습니다.

## 최초 활성화

1. GitHub Actions에서 새 APK를 빌드하고 설치합니다.
2. 앱을 처음 실행해 사용할 사용자명을 입력합니다.
3. 관리자가 설정한 텔레그램 관리자 채팅에서 다음 명령을 보냅니다.

```text
/티비룸 사용자명 (비번)
```

4. 앱에 암호 입력칸이 나타나면 괄호 안에 보낸 암호를 동일하게 입력합니다.
5. 인증되면 앱이 영구 활성화되고 메인 티비룸 화면이 열립니다.

활성화 상태는 앱 전용 설정에 저장됩니다. 같은 서명키로 빌드한 APK를
덮어써서 업데이트하면 유지되며, 앱 삭제 또는 앱 데이터 삭제 시 사라집니다.

같은 봇에 대해 다른 프로그램이 동시에 `getUpdates`를 실행하면 텔레그램
409 충돌이 생길 수 있습니다. 활성화 작업 중에는 같은 봇을 사용하는 다른
프로그램을 종료해야 합니다.

봇 토큰은 APK의 BuildConfig에 포함되므로 APK 분석을 완전히 막을 수는 없습니다.
토큰이 노출됐다고 판단되면 BotFather에서 즉시 폐기하고 새 토큰으로 교체해야 합니다.
