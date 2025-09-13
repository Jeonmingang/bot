# DiscordBridge (Spigot 1.16.5 / Java 8) — v1.3.2

- 양방향 채팅 (MC ↔ Discord)
- 서버 시작/종료 + 접속/퇴장 알림
- 인원수 플레이스홀더 `%online%` / `%max%`
- `/디스코드 인원` 명령
- **JDA 5 + MESSAGE_CONTENT 인텐트 적용** (디스코드 → MC 메시지 내용 표시)

## GitHub Actions 예시 (Java 8, Maven)
```yaml
name: build
on: [push]
jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      - name: Set up JDK 8
        uses: actions/setup-java@v4
        with:
          distribution: 'temurin'
          java-version: '8'
      - name: Build with Maven
        run: mvn -B -DskipTests package
      - name: Upload artifact
        uses: actions/upload-artifact@v4
        with:
          name: DiscordBridge-jar
          path: target/*-shaded.jar
```
