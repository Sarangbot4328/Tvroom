# 티비룸 다운로더 Android

참조 APK의 하단 3탭 UI를 영상용으로 이식한 독립 Android 앱입니다.

## 기능

- 티비룸 WebView 탐색, 메인/뒤로/앞으로/새로고침 이동
- 영상 페이지에서 HLS 및 `segment_list` 주소와 AES 키·IV 감지
- 다운로드 탭에 썸네일·제목·상태·진행률 표시
- 다운로드 중단 및 중단/실패/완료 후 작업 파일 자동 삭제
- 설정에서 티비룸 주소 수동 변경(기본값 `https://tvroom20.org/`)
- 남은 작업 조각을 제거하는 임시 파일 삭제
- 완료 영상 재생, 전체화면 전환/복귀, Picture-in-Picture 팝업 재생
- GitHub Actions 서명 APK 빌드

## GitHub 빌드

1. 이 폴더의 내용이 GitHub 저장소 최상위가 되도록 업로드합니다.
2. [SIGNING_SETUP.md](SIGNING_SETUP.md)에 따라 네 개의 Repository Secret을 등록합니다.
3. `Actions → Build signed Android APK → Run workflow`를 실행합니다.
4. 완료된 실행의 `tvroom-downloader-apk` artifact를 내려받습니다.

## 실제 기기 첫 확인

1. 티비룸 탭에서 영상 페이지를 열고 재생합니다.
2. 하단 버튼이 `영상 분석 중…`에서 `다운로드`로 바뀌는지 확인합니다.
3. 다운로드를 누른 뒤 다운로드 탭과 알림의 진행률을 확인합니다.

사이트의 플레이어 도메인·암호화 구조가 변경되면 캡처 또는 복호화 로직도 갱신해야 합니다.
저장 권한이 있는 콘텐츠에만 사용하세요.
