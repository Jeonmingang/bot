# DiscordBridge (Spigot 1.16.5 / Java 8) — v1.2.0

- 서버 ON/OFF 알림 + 양방향 채팅
- 플레이어 접속/퇴장 알림
- **신규**:
  - `/접속` (무권한): 서버 주소 + 디스코드 초대 링크 안내
  - `/디스코드 초대` (OP/관리자 명령): 초대 링크 출력
  - 기존 `/디스코드 리로드` 유지

## 설정
- `discord.invite-url` : 디스코드 초대 URL
- `server.address` : 서버 주소
- `messages.connect-*` : /접속 출력 문구 커스텀

## 플레이스홀더
- `%server%`, `%player%`, `%displayname%`, `%message%`, `%author%`
