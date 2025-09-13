package com.minkang.discordbridge;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.Activity;
import net.dv8tion.jda.api.entities.TextChannel;
import net.dv8tion.jda.api.events.message.MessageReceivedEvent;
import net.dv8tion.jda.api.hooks.ListenerAdapter;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
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
import org.bukkit.scheduler.BukkitTask;

import javax.security.auth.login.LoginException;
import java.util.logging.Level;

public class Main extends JavaPlugin implements Listener {

    private JDA jda;
    private String guildId;
    private String channelId;
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

    private boolean activityEnabled;
    private String activityFormat;
    private int activityInterval;
    private boolean postEnabled;
    private String postFormat;
    private int postInterval;
    private BukkitTask activityTask;
    private BukkitTask postTask;

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

        sendDiscordSafe(startupMsg.replace("%server%", Bukkit.getServer().getName()));
        startSchedulers();
    }

    @Override
    public void onDisable() {
        sendDiscordSafe(shutdownMsg.replace("%server%", Bukkit.getServer().getName()));

        cancelSchedulers();

        if (jda != null) {
            jda.shutdownNow();
            jda = null;
        }
    }

    private void loadConfigValues() {
        FileConfiguration c = getConfig();
        guildId = c.getString("discord.guild-id", "");
        channelId = c.getString("discord.channel-id", "");
        fmtMcToDc = c.getString("format.minecraft-to-discord", "**<%player%>**: %message%");
        fmtDcToMc = c.getString("format.discord-to-minecraft", "&9[디스코드] &b%author%&7: &f%message%");
        startupMsg = c.getString("events.startup-message", ":green_circle: 서버 열림: %server%");
        shutdownMsg = c.getString("events.shutdown-message", ":red_circle: 서버 종료: %server%");
        joinEnabled = c.getBoolean("events.join-enabled", true);
        quitEnabled = c.getBoolean("events.quit-enabled", true);
        joinFormat = c.getString("events.join-format", ":arrow_right: **%player%** 접속");
        quitFormat = c.getString("events.quit-format", ":arrow_left: **%player%** 퇴장");
        ignoreBots = c.getBoolean("options.ignore-discord-bots", true);
        stripMentions = c.getBoolean("options.strip-discord-mentions", true);
        allowMcColorInbound = c.getBoolean("options.allow-minecraft-color-inbound", false);

        activityEnabled = c.getBoolean("online-status.activity-enabled", true);
        activityFormat = c.getString("online-status.activity-format", "%online%명 접속 중 (%max%)");
        activityInterval = c.getInt("online-status.activity-interval-seconds", 60);
        postEnabled = c.getBoolean("online-status.post-enabled", false);
        postFormat = c.getString("online-status.post-format", "현재 온라인: %online%/%max%");
        postInterval = c.getInt("online-status.post-interval-seconds", 300);
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

    private void startSchedulers() {
        cancelSchedulers();
        if (activityEnabled) {
            long ticks = Math.max(20L, activityInterval * 20L);
            activityTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                if (jda == null) return;
                String text = replaceStatusPlaceholders(activityFormat);
                try {
                    jda.getPresence().setActivity(Activity.playing(text));
                } catch (Exception ignored) {}
            }, 40L, ticks);
        }
        if (postEnabled) {
            long ticks = Math.max(20L, postInterval * 20L);
            postTask = Bukkit.getScheduler().runTaskTimerAsynchronously(this, () -> {
                String msg = replaceStatusPlaceholders(postFormat);
                sendDiscordSafe(msg);
            }, 200L, ticks);
        }
    }

    private void cancelSchedulers() {
        if (activityTask != null) { activityTask.cancel(); activityTask = null; }
        if (postTask != null) { postTask.cancel(); postTask = null; }
    }

    private String replaceStatusPlaceholders(String in) {
        int online = Bukkit.getOnlinePlayers().size();
        int max = Bukkit.getMaxPlayers();
        return in.replace("%online%", String.valueOf(online))
                 .replace("%max%", String.valueOf(max))
                 .replace("%server%", Bukkit.getServer().getName());
    }

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

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!joinEnabled) return;
        Player p = e.getPlayer();
        String out = joinFormat
                .replace("%player%", p.getName())
                .replace("%displayname%", p.getDisplayName());
        sendDiscordSafe(out);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        if (!quitEnabled) return;
        Player p = e.getPlayer();
        String out = quitFormat
                .replace("%player%", p.getName())
                .replace("%displayname%", p.getDisplayName());
        sendDiscordSafe(out);
    }

    private void sendDiscordSafe(String content) {
        if (content == null || content.trim().isEmpty()) return;
        if (jda == null) return;
        if (channelId == null || channelId.isEmpty()) return;
        try {
            TextChannel ch = jda.getTextChannelById(channelId);
            if (ch != null) ch.sendMessage(content).queue();
        } catch (Exception ex) {
            getLogger().log(Level.WARNING, "디스코드 전송 실패: " + ex.getMessage());
        }
    }

    private class DiscordListener extends ListenerAdapter {
        @Override
        public void onMessageReceived(MessageReceivedEvent event) {
            if (channelId == null || channelId.isEmpty()) return;
            if (event.getChannel() == null) return;
            if (!event.getChannel().getId().equals(channelId)) return;
            if (ignoreBots && (event.getAuthor().isBot() || event.isWebhookMessage())) return;

            final String author = event.getAuthor().getName();
            String raw = event.getMessage().getContentDisplay();
            if (stripMentions) {
                raw = raw.replace("@everyone", "(everyone)").replace("@here", "(here)");
            }

            String formatted = fmtDcToMc.replace("%author%", author).replace("%message%", raw);
            final String mcOut = allowMcColorInbound
                    ? ChatColor.translateAlternateColorCodes('&', formatted)
                    : ChatColor.stripColor(ChatColor.translateAlternateColorCodes('&', formatted));

            Bukkit.getScheduler().runTask(Main.this, () -> {
                for (Player pl : Bukkit.getOnlinePlayers()) pl.sendMessage(mcOut);
                getServer().getConsoleSender().sendMessage(mcOut);
            });
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        if (!command.getName().equalsIgnoreCase("디스코드")) return false;
        boolean isAdmin = sender.hasPermission("discordbridge.admin");
        if (!isAdmin) { sender.sendMessage(ChatColor.RED + "이 명령은 OP 전용입니다."); return true; }

        if (args.length == 1 && args[0].equalsIgnoreCase("리로드")) {
            reloadConfig();
            loadConfigValues();
            startSchedulers();
            sender.sendMessage(ChatColor.GREEN + "DiscordBridge 설정을 리로드했습니다.");
            if (jda != null) { jda.shutdownNow(); jda = null; }
            if (getConfig().getBoolean("discord.enabled", true)) {
                try { startJDA(); sender.sendMessage(ChatColor.GREEN + "디스코드 봇 재시작 완료."); }
                catch (Exception e) { sender.sendMessage(ChatColor.RED + "디스코드 봇 시작 실패: " + e.getMessage()); }
            }
            return true;
        }

        if (args.length == 1 && args[0].equalsIgnoreCase("인원")) {
            String msg = replaceStatusPlaceholders(postFormat);
            sendDiscordSafe(msg);
            sender.sendMessage(ChatColor.AQUA + "현재 인원 정보를 디스코드에 전송했습니다.");
            return true;
        }

        sender.sendMessage(ChatColor.YELLOW + "사용법: /디스코드 리로드 | /디스코드 인원");
        return true;
    }
}
