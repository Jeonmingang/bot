package com.minkang.discordbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.events.session.ReadyEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import net.dv8tion.jda.api.requests.GatewayIntent;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Server;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import javax.security.auth.login.LoginException;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.List;
import java.util.ArrayList;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.regex.Pattern;

public class Main extends JavaPlugin implements Listener {

    private JDA jda;
    private String guildId;
    private String channelId;
    private String inviteUrl;
    private String serverAddress;

    private String fmtMcToDc;
    private String fmtDcToMc;
    private String startupMsg;
    private String shutdownMsg;
    private boolean joinEnabled;
    private boolean quitEnabled;
    private String joinFormat;
    private String quitFormat;
    private boolean ignoreBots;
    private boolean stripMentions;
    private boolean allowMcColorInbound;

    // messages
    private String msgConnectHeader;
    private String msgConnectServer;
    private String msgConnectDiscord;

    // startup/shutdown
    private boolean startupAnnounced;

    // pixelmon detection
    private boolean pxDetectEnabled;
    private boolean pxUseFileTail;
    private String pxDiscordFormat;
    private String pxChannelId;
    private List<Pattern> pxPatterns = new ArrayList<>();
    private Handler consoleHook;
    private RandomAccessFile pxRaf;
    private long pxLastPos = 0L;
    private BukkitTask pxTailTask;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadConfigValues();

        Bukkit.getPluginManager().registerEvents(this, this);

        if (getConfig().getBoolean("discord.enabled", true)) {
            try {
                startJDA();
            } catch (Exception e) {
                getLogger().log(Level.SEVERE, "JDA 시작 실패: " + e.getMessage(), e);
            }
        }

        // attach console hook if enabled
        if (pxDetectEnabled) attachConsoleHook();
        // start file tail if enabled
        startPixelmonTailTask();
    }

    @Override
    public void onDisable() {
        // send shutdown synchronously (best-effort)
        sendDiscordSync(applyCommonPlaceholders(shutdownMsg, null, false));

        detachConsoleHook();
        stopPixelmonTailTask();

        if (jda != null) {
            jda.shutdown(); // graceful
            jda = null;
        }
    }

    private void loadConfigValues() {
        FileConfiguration c = getConfig();
        guildId = c.getString("discord.guild-id", "");
        channelId = c.getString("discord.channel-id", "");
        inviteUrl = c.getString("discord.invite-url", "https://discord.gg/");
        serverAddress = c.getString("server.address", "play.example.com");

        fmtMcToDc = c.getString("format.minecraft-to-discord", "**<%player%>**: %message%");
        fmtDcToMc = c.getString("format.discord-to-minecraft", "&9[디스코드] &b%author%&7: &f%message%");
        startupMsg = c.getString("events.startup-message", ":green_circle: 서버 열림 | 인원 %online%/%max%");
        shutdownMsg = c.getString("events.shutdown-message", ":red_circle: 서버 종료 | 인원 %online%/%max%");
        joinEnabled = c.getBoolean("events.join-enabled", true);
        quitEnabled = c.getBoolean("events.quit-enabled", true);
        joinFormat = c.getString("events.join-format", ":arrow_right: **%player%** 접속 | 현재 %online%/%max%");
        quitFormat = c.getString("events.quit-format", ":arrow_left: **%player%** 퇴장 | 현재 %online%/%max%");
        ignoreBots = c.getBoolean("options.ignore-discord-bots", true);
        stripMentions = c.getBoolean("options.strip-discord-mentions", true);
        allowMcColorInbound = c.getBoolean("options.allow-minecraft-color-inbound", false);

        msgConnectHeader = c.getString("messages.connect-header", "&a[접속 안내]");
        msgConnectServer = c.getString("messages.connect-server", "&f서버 주소: &b%server_address%");
        msgConnectDiscord = c.getString("messages.connect-discord", "&f디스코드: &b%invite_url%");

        // pixelmon
        pxDetectEnabled = c.getBoolean("pixelmon.legendary-broadcast-detection.enabled", true);
        pxUseFileTail = c.getBoolean("pixelmon.legendary-broadcast-detection.use-file-tail", true);
        pxDiscordFormat = c.getString("pixelmon.legendary-broadcast-detection.discord-format", ":sparkles: 전설 스폰 감지! %raw%");
        pxChannelId = c.getString("pixelmon.legendary-broadcast-detection.channel-id", "");

        pxPatterns.clear();
        List<String> list = c.getStringList("pixelmon.legendary-broadcast-detection.patterns");
        if (list != null) {
            for (String s : list) {
                try { pxPatterns.add(Pattern.compile(s)); }
                catch (Exception ex) { getLogger().warning("패턴 파싱 실패: " + s); }
            }
        }
    }

    private void startJDA() throws LoginException {
        String token = getConfig().getString("discord.token", "");
        if (token == null || token.trim().isEmpty()) {
            getLogger().severe("config.yml의 discord.token이 비었습니다!");
            return;
        }

        JDABuilder builder = JDABuilder.createDefault(token);
        builder.enableIntents(GatewayIntent.MESSAGE_CONTENT);
        builder.setActivity(Activity.playing("서버 채팅 연동"));
        builder.addEventListeners(new DiscordListener());
        jda = builder.build();
    }

    // Ready -> send startup once
    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onReady(ReadyEvent event) {
            if (!startupAnnounced) {
                String msg = applyCommonPlaceholders(startupMsg, null, false);
                sendDiscordSafe(msg);
                startupAnnounced = true;
            }
        }

        // Discord -> Minecraft
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (channelId == null || channelId.isEmpty()) return;
            if (event.getChannel() == null) return;
            if (!event.getChannel().getId().equals(channelId)) return;

            if (ignoreBots && (event.getAuthor().isBot() || event.isWebhookMessage())) {
                return;
            }

            final String author = event.getAuthor().getName();
            String raw = event.getMessage().getContentDisplay();

            if (stripMentions) {
                raw = raw.replace("@everyone", "(everyone)")
                         .replace("@here", "(here)");
            }

            String formatted = fmtDcToMc
                    .replace("%author%", author)
                    .replace("%message%", raw);

            final String mcOut;
            if (allowMcColorInbound) {
                mcOut = ChatColor.translateAlternateColorCodes('&', formatted);
            } else {
                mcOut = ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', formatted));
            }

            Bukkit.getScheduler().runTask(Main.this, () -> {
                for (Player pl : Bukkit.getOnlinePlayers()) {
                    pl.sendMessage(mcOut);
                }
                getServer().getConsoleSender().sendMessage(mcOut);
            });
        }
    }

    private String applyCommonPlaceholders(String content, Player context, boolean quitting) {
        if (content == null) return null;
        Server sv = getServer();
        int online = sv.getOnlinePlayers().size();
        if (quitting) online = Math.max(0, online - 1);
        int max = sv.getMaxPlayers();

        String out = content;
        out = out.replace("%server%", sv.getName());
        out = out.replace("%online%", String.valueOf(online));
        out = out.replace("%max%", String.valueOf(max));
        if (context != null) {
            out = out.replace("%player%", context.getName());
            out = out.replace("%displayname%", context.getDisplayName());
        }
        return out;
    }

    private void sendDiscordTo(String channelOverrideId, String content) {
        if (content == null || content.trim().isEmpty()) return;
        if (jda == null) return;
        String targetId = (channelOverrideId != null && !channelOverrideId.isEmpty()) ? channelOverrideId : channelId;
        if (targetId == null || targetId.isEmpty()) return;
        try {
            TextChannel ch = jda.getTextChannelById(targetId);
            if (ch != null) {
                ch.sendMessage(content).queue();
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "디스코드 전송 실패: " + ex.getMessage());
        }
    }

    private void sendDiscordSafe(String content) {
        sendDiscordTo(null, content);
    }

    private void sendDiscordSync(String content) {
        if (content == null || content.trim().isEmpty()) return;
        if (jda == null) return;
        String targetId = channelId;
        if (targetId == null || targetId.isEmpty()) return;
        try {
            TextChannel ch = jda.getTextChannelById(targetId);
            if (ch != null) {
                ch.sendMessage(content).complete(); // block until sent
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "디스코드 동기 전송 실패: " + ex.getMessage());
        }
    }

    private void attachConsoleHook() {
        if (consoleHook != null) return;
        consoleHook = new Handler() {
            @Override
            public void publish(LogRecord record) {
                if (record == null) return;
                String msg = record.getMessage();
                if (msg == null) return;
                for (Pattern p : pxPatterns) {
                    if (p.matcher(msg).find()) {
                        String content = pxDiscordFormat.replace("%raw%", msg);
                        sendDiscordTo(pxChannelId, content);
                        break;
                    }
                }
            }
            @Override public void flush() {}
            @Override public void close() throws SecurityException {}
        };
        try {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.addHandler(consoleHook);
            getLogger().info("Pixelmon 전설 감지용 콘솔 훅 장착 완료.");
        } catch (Exception ex) {
            getLogger().warning("콘솔 훅 설치 실패: " + ex.getMessage());
        }
    }

    private void detachConsoleHook() {
        if (consoleHook == null) return;
        try {
            java.util.logging.Logger root = java.util.logging.Logger.getLogger("");
            root.removeHandler(consoleHook);
        } catch (Exception ignored) {}
        consoleHook = null;
    }

    private File resolveLatestLog() {
        try {
            File logsDir = new File("logs");
            if (!logsDir.exists()) return null;
            File f = new File(logsDir, "latest.log");
            return f.exists() ? f : null;
        } catch (Exception e) { return null; }
    }

    private void startPixelmonTailTask() {
        if (!pxDetectEnabled || !pxUseFileTail || pxTailTask != null) return;
        pxTailTask = new BukkitRunnable() {
            @Override public void run() {
                try {
                    File f = resolveLatestLog();
                    if (f == null) return;
                    if (pxRaf == null) {
                        pxRaf = new RandomAccessFile(f, "r");
                        pxLastPos = f.length(); // start at end to avoid flooding old lines
                        pxRaf.seek(pxLastPos);
                    }
                    long len = f.length();
                    if (len < pxLastPos) { // rotation or truncation
                        try { pxRaf.close(); } catch (Exception ignored) {}
                        pxRaf = new RandomAccessFile(f, "r");
                        pxLastPos = 0L;
                    }
                    if (len > pxLastPos) {
                        pxRaf.seek(pxLastPos);
                        String line;
                        while ((line = pxRaf.readLine()) != null) {
                            String utf = new String(line.getBytes("ISO-8859-1"), "UTF-8"); // console encoding safety
                            for (Pattern p : pxPatterns) {
                                if (p.matcher(utf).find()) {
                                    String content = pxDiscordFormat.replace("%raw%", utf);
                                    sendDiscordTo(pxChannelId, content);
                                    break;
                                }
                            }
                        }
                        pxLastPos = pxRaf.getFilePointer();
                    }
                } catch (Exception ex) {
                    // swallow, will retry next tick
                }
            }
        }.runTaskTimerAsynchronously(this, 40L, 40L); // start after 2s, run every 2s
        getLogger().info("Pixelmon 전설 감지용 파일 tail 시작 (logs/latest.log).");
    }

    private void stopPixelmonTailTask() {
        try { if (pxTailTask != null) pxTailTask.cancel(); } catch (Exception ignored) {}
        pxTailTask = null;
        try { if (pxRaf != null) pxRaf.close(); } catch (Exception ignored) {}
        pxRaf = null;
    }

    // AsyncPlayerChatEvent -> Discord
    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        Player p = e.getPlayer();
        String message = e.getMessage();

        String out = fmtMcToDc
                .replace("%player%", p.getName())
                .replace("%displayname%", p.getDisplayName())
                .replace("%message%", message);

        sendDiscordSafe(out);
    }

    // PlayerJoin -> Discord
    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!joinEnabled) return;
        Player p = e.getPlayer();
        String out = joinFormat
                .replace("%player%", p.getName())
                .replace("%displayname%", p.getDisplayName());
        out = applyCommonPlaceholders(out, null, false);
        sendDiscordSafe(out);
    }

    // PlayerQuit -> Discord
    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!quitEnabled) return;
        Player p = e.getPlayer();
        String out = quitFormat
                .replace("%player%", p.getName())
                .replace("%displayname%", p.getDisplayName());
        out = applyCommonPlaceholders(out, null, true);
        sendDiscordSafe(out);
    }

    // Commands: /디스코드 [...], /접속
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("디스코드")) {
            boolean isAdmin = sender.hasPermission("discordbridge.admin");

            if (args.length == 0) {
                sender.sendMessage(color("&e사용법: /디스코드 리로드 | /디스코드 초대 | /디스코드 인원 | /디스코드 테스트레전드"));
                return true;
            }

            String sub = args[0];

            if (sub.equalsIgnoreCase("리로드")) {
                if (!isAdmin) { sender.sendMessage(color("&c이 명령은 OP 전용입니다.")); return true; }
                reloadConfig();
                loadConfigValues();
                sender.sendMessage(color("&a설정을 리로드했습니다."));

                if (jda != null) { jda.shutdown(); jda = null; }
                if (getConfig().getBoolean("discord.enabled", true)) {
                    try {
                        startJDA();
                        sender.sendMessage(color("&a디스코드 봇 재시작 완료."));
                    } catch (Exception e) {
                        sender.sendMessage(color("&c디스코드 봇 시작 실패: " + e.getMessage()));
                    }
                }
                detachConsoleHook();
                if (pxDetectEnabled) attachConsoleHook();
                stopPixelmonTailTask();
                startPixelmonTailTask();
                return true;
            } else if (sub.equalsIgnoreCase("초대")) {
                if (!isAdmin) { sender.sendMessage(color("&c이 명령은 OP 전용입니다.")); return true; }
                sender.sendMessage(color("&b디스코드 초대: &f") + inviteUrl);
                return true;
            } else if (sub.equalsIgnoreCase("인원")) {
                if (!isAdmin) { sender.sendMessage(color("&c이 명령은 OP 전용입니다.")); return true; }
                String msg = ":busts_in_silhouette: 현재 인원 " +
                        Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers();
                sendDiscordTo(pxChannelId, msg);
                sender.sendMessage(color("&a디스코드로 현재 인원을 전송했습니다."));
                return true;
            } else if (sub.equalsIgnoreCase("테스트레전드")) {
                if (!isAdmin) { sender.sendMessage(color("&c이 명령은 OP 전용입니다.")); return true; }
                String msg = pxDiscordFormat.replace("%raw%", "A Legendary has spawned in a nearby biome!");
                sendDiscordTo(pxChannelId, msg);
                sender.sendMessage(color("&a레전더리 테스트 메시지를 디스코드로 전송했습니다."));
                return true;
            } else {
                sender.sendMessage(color("&e사용법: /디스코드 리로드 | /디스코드 초대 | /디스코드 인원 | /디스코드 테스트레전드"));
                return true;
            }
        }

        if (name.equals("접속")) {
            sender.sendMessage(color(msgConnectHeader));
            sender.sendMessage(color(msgConnectServer
                    .replace("%server_address%", serverAddress)));
            sender.sendMessage(color(msgConnectDiscord
                    .replace("%invite_url%", inviteUrl)));
            return true;
        }

        return false;
    }

    private String color(String s) {
        return ChatColor.translateAlternateColorCodes('&', s);
    }
}
