package com.minkang.discordbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
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

import javax.security.auth.login.LoginException;
import java.util.logging.Level;

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

        // 서버 시작 알림
        sendDiscordSafe(applyCommonPlaceholders(startupMsg, null, false));
    }

    @Override
    public void onDisable() {
        // 서버 종료 알림
        sendDiscordSafe(applyCommonPlaceholders(shutdownMsg, null, false));

        if (jda != null) {
            jda.shutdownNow();
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
    }

    private void startJDA() throws LoginException {
        String token = getConfig().getString("discord.token", "");
        if (token == null || token.trim().isEmpty()) {
            getLogger().severe("config.yml의 discord.token이 비었습니다!");
            return;
        }

        JDABuilder builder = JDABuilder.createDefault(token);
        builder.setActivity(Activity.playing("서버 채팅 연동"));
        builder.addEventListeners(new DiscordListener());
        jda = builder.build();
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
        out = applyCommonPlaceholders(out, null, true); // adjust for quitting
        sendDiscordSafe(out);
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

    private void sendDiscordSafe(String content) {
        if (content == null || content.trim().isEmpty()) return;
        if (jda == null) return;
        if (channelId == null || channelId.isEmpty()) return;

        try {
            TextChannel ch = jda.getTextChannelById(channelId);
            if (ch != null) {
                ch.sendMessage(content).queue();
            }
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "디스코드 전송 실패: " + ex.getMessage());
        }
    }

    // Discord -> Minecraft
    private class DiscordListener extends ListenerAdapter {
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

    // Commands: /디스코드 [리로드|초대|인원], /접속
    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase();

        if (name.equals("디스코드")) {
            boolean isAdmin = sender.hasPermission("discordbridge.admin");
            if (args.length == 0) {
                sender.sendMessage(color("&e사용법: /디스코드 리로드 | /디스코드 초대 | /디스코드 인원"));
                return true;
            }
            if (args[0].equalsIgnoreCase("리로드")) {
                if (!isAdmin) { sender.sendMessage(color("&c이 명령은 OP 전용입니다.")); return true; }
                reloadConfig();
                loadConfigValues();
                sender.sendMessage(color("&a설정을 리로드했습니다."));

                if (jda != null) { jda.shutdownNow(); jda = null; }
                if (getConfig().getBoolean("discord.enabled", true)) {
                    try {
                        startJDA();
                        sender.sendMessage(color("&a디스코드 봇 재시작 완료."));
                    } catch (Exception e) {
                        sender.sendMessage(color("&c디스코드 봇 시작 실패: " + e.getMessage()));
                    }
                }
                return true;
            }
            if (args[0].equalsIgnoreCase("초대")) {
                if (!isAdmin) { sender.sendMessage(color("&c이 명령은 OP 전용입니다.")); return true; }
                sender.sendMessage(color("&b디스코드 초대: &f") + inviteUrl);
                return true;
            }
            if (args[0].equalsIgnoreCase("인원")) {
                if (!isAdmin) { sender.sendMessage(color("&c이 명령은 OP 전용입니다.")); return true; }
                String msg = ":busts_in_silhouette: 현재 인원 " +
                        Bukkit.getOnlinePlayers().size() + "/" + Bukkit.getMaxPlayers();
                sendDiscordSafe(msg);
                sender.sendMessage(color("&a디스코드로 현재 인원을 전송했습니다."));
                return true;
            }
            sender.sendMessage(color("&e사용법: /디스코드 리로드 | /디스코드 초대 | /디스코드 인원"));
            return true;
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
