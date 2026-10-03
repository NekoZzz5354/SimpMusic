package com.simpmusic.server;

import com.simpmusic.server.network.PlaySongPacket;
import com.simpmusic.server.network.StopSongPacket;
import com.simpmusic.server.network.SyncQueuePacket;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.entity.boss.BossBar.Color;
import net.minecraft.entity.boss.BossBar.Style;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MusicQueueManager {
   private static final Queue<MusicQueueManager.MusicEntry> queue = new ConcurrentLinkedQueue<>();
   private static MusicQueueManager.MusicEntry currentPlaying = null;
   private static MinecraftServer server;
   private static int tickDelay = 0;
   private static int tickCounter = 0;
   private static final Map<UUID, Long> requestCooldowns = new HashMap<>();
   private static final Set<UUID> skipVoters = new HashSet<>();
   private static ServerBossBar musicBossBar = null;
   private static long currentStartTimeMs = 0L;
   private static volatile List<MusicQueueManager.LyricLine> currentLyrics = new ArrayList<>();
   private static String lastBossBarText = "";
   private static boolean startedCalibrated = false;
   private static final Pattern LRC_PATTERN = Pattern.compile("\\[(\\d+):(\\d+)(?:\\.(\\d+))?\\](.*)");

   public static void init() {
      ServerTickEvents.END_SERVER_TICK.register((EndTick)server -> {
         tickCounter++;
         if (tickDelay <= 0 || tickCounter % tickDelay == 0) {
            if (tickCounter % 5 == 0) {
               tickUpdateBossBar();
               checkCurrentSongTimeout();
            }
         }
      });
      ServerPlayConnectionEvents.JOIN.register((Join)(handler, sender, srv) -> {
         ServerPlayerEntity joining = handler.getPlayer();
         if (joining == null) {
            return;
         }

         // 中途加入：补挂 BossBar，并补发当前播放包，让新玩家能听到音乐
         if (currentPlaying != null) {
            if (musicBossBar != null && musicBossBar.isVisible()) {
               musicBossBar.addPlayer(joining);
            }

            try {
               String urlWithVolume = appendVolumeParam(currentPlaying.url);
               int offset = getCurrentPlaybackOffsetSeconds();
               PlaySongPacket.send(joining, currentPlaying.songId, currentPlaying.title, currentPlaying.artist, urlWithVolume, "", offset);
               SimpMusicServer.LOGGER.info(
                  "Re-sent current song to joining player {}: {} - {} (offset {}s)", joining.getName().getString(), currentPlaying.title, currentPlaying.artist, offset
               );
            } catch (Exception e) {
               SimpMusicServer.LOGGER.debug("Re-send current song failed: {}", e.getMessage());
            }
         }
      });
      SimpMusicServer.LOGGER.info("MusicQueueManager initialized (tickDelay={})", tickDelay);
   }

   public static void setServer(MinecraftServer s) {
      server = s;
   }

   public static void setTickDelay(int delay) {
      tickDelay = Math.max(0, delay);
      SimpMusicServer.LOGGER.info("Queue tick delay set to {} ticks", tickDelay);
   }

   public static boolean addToQueue(String songId, String title, String artist, String url, int duration, ServerPlayerEntity requester) {
      if (!checkCooldown(requester)) {
         requester.sendMessage(Text.literal(ModConfig.getPrefix() + " §c请等待 " + ModConfig.getRequestCooldown() + " 秒后再点歌").formatted(Formatting.RED), false);
         return false;
      }

      if (queue.size() >= ModConfig.getMaxTotalQueue()) {
         requester.sendMessage(Text.literal(ModConfig.getPrefix() + " §c队列已满，请稍后再试").formatted(Formatting.RED), false);
         return false;
      }

      if (!ModConfig.isAllowDuplicates()) {
         for (MusicQueueManager.MusicEntry e : queue) {
            if (e.songId.equals(songId)) {
               requester.sendMessage(Text.literal(ModConfig.getPrefix() + " §c该歌曲已在队列中").formatted(Formatting.RED), false);
               return false;
            }
         }

         if (currentPlaying != null && currentPlaying.songId.equals(songId)) {
            requester.sendMessage(Text.literal(ModConfig.getPrefix() + " §c该歌曲正在播放中").formatted(Formatting.RED), false);
            return false;
         }
      }

      long playerCount = queue.stream().filter(ex -> ex.requesterUuid.equals(requester.getUuid())).count();
      if (playerCount >= ModConfig.getMaxQueuePerPlayer()) {
         requester.sendMessage(Text.literal(ModConfig.getPrefix() + " §c你已点歌过多，请等待播放").formatted(Formatting.RED), false);
         return false;
      }

      title = TextUtil.sanitize(title);
      artist = TextUtil.sanitize(artist);
      if (title.isEmpty()) {
         requester.sendMessage(Text.literal(ModConfig.getPrefix() + " §c无效的歌曲标题").formatted(Formatting.RED), false);
         return false;
      }

      if (url != null && !url.isEmpty()) {
         queue.add(new MusicQueueManager.MusicEntry(songId, title, artist, url, duration, requester));
         setCooldown(requester);
         if (currentPlaying == null) {
            playNext();
         }

         return true;
      } else {
         requester.sendMessage(Text.literal(ModConfig.getPrefix() + " §c无法获取该歌曲的播放链接（可能是 VIP 歌曲，请在服务端配置网易云 Cookie）").formatted(Formatting.RED), false);
         return false;
      }
   }

   public static MusicQueueManager.MusicEntry getCurrentPlaying() {
      return currentPlaying;
   }

   public static Queue<MusicQueueManager.MusicEntry> getQueue() {
      return queue;
   }

   public static void skipCurrent() {
      SimpMusicServer.LOGGER.info("Skipping current song: {}", currentPlaying != null ? currentPlaying.title : "null");
      currentPlaying = null;
      hideBossBar();
      broadcastStop();
      if (server != null) {
         server.execute(() -> playNext());
      } else {
         playNext();
      }
   }

   public static void playNext() {
      currentPlaying = queue.poll();
      if (currentPlaying != null && server != null) {
         SimpMusicServer.LOGGER.info("Playing next: {} - {}", currentPlaying.title, currentPlaying.artist);
         skipVoters.clear();   // 换歌后清空上一首的跳过投票
         broadcastPlay(currentPlaying);
         broadcastQueueSync();
         currentStartTimeMs = System.currentTimeMillis();
         lastBossBarText = "";
         startedCalibrated = false;   // 等待客户端实际播放后校准计时
         setupBossBar();
         loadLyricsAsync(currentPlaying.songId);
      } else {
         hideBossBar();
      }
   }

   /**
    * 当前播放进度（秒）：供中途加入/重连的玩家接续播放。
    * 无播放或尚未校准时返回 0；接近歌曲末尾时回退 5 秒，避免接续到即将切歌的位置。
    */
   public static int getCurrentPlaybackOffsetSeconds() {
      if (currentPlaying == null) {
         return 0;
      }

      long elapsedMs = System.currentTimeMillis() - currentStartTimeMs;
      if (elapsedMs <= 0L) {
         return 0;
      }

      int offset = (int)(elapsedMs / 1000L);
      int duration = currentPlaying.duration;
      if (duration > 0 && offset >= duration - 5) {
         return Math.max(0, duration - 5);
      }

      return Math.max(0, offset);
   }

   /**
    * 玩家投票跳过：投票数达到在线人数一半（ceil(n/2)）即跳过当前歌曲。
    * 每名玩家对当前曲目只能投一票；换歌后自动清空。
    */
   public static void voteSkip(ServerPlayerEntity player) {
      if (currentPlaying == null) {
         player.sendMessage(Text.literal(ModConfig.getPrefix() + " §7当前没有正在播放的歌曲").formatted(Formatting.GRAY), false);
         return;
      }

      if (!skipVoters.add(player.getUuid())) {
         player.sendMessage(Text.literal(ModConfig.getPrefix() + " §7你已投过跳过票，等待其他玩家投票").formatted(Formatting.GRAY), false);
         return;
      }

      int online = server != null ? Math.max(1, server.getPlayerManager().getPlayerList().size()) : 1;
      int votes = skipVoters.size();
      int needed = (online + 1) / 2;   // 半数（向上取整）
      SimpMusicServer.LOGGER.info("Skip vote by {} for {}: {}/{} needed", player.getName().getString(), currentPlaying.title, votes, needed);

      if (votes >= needed) {
         broadcastActionbar(Text.literal(ModConfig.getPrefix() + " §e⏭ 跳过投票通过 (" + votes + "/" + online + ")，即将播放下一首"));
         SimpMusicServer.LOGGER.info("Skip vote passed, skipping: {}", currentPlaying.title);
         skipCurrent();
      } else {
         broadcastActionbar(Text.literal(String.format("%s §e%s §7发起了跳过 §f(%d/%d) §7- 输入 §f/skip §7加入", ModConfig.getPrefix(), player.getName().getString(), votes, needed)));
      }
   }

   /**
    * 客户端实际开始播放后回调：以客户端的真实播放位置为基准对齐 BossBar 歌词/进度计时。
    *
    * @param playedSeconds 客户端本次开始播放时已经过的时间（中途加入/重连时为服务端下发的进度）
    *
    * 首次校准用于修正"下载+解码延迟"造成的歌词领先；
    * 当场上只有一名玩家（重连场景，无人受影响）时允许再次校准，
    * 保证歌词与该玩家实际听到的位置严格对齐。
    */
   public static void onClientPlayStarted(ServerPlayerEntity player, String songId, int playedSeconds) {
      if (currentPlaying == null) {
         return;
      }

      if (songId != null && !songId.isEmpty() && !songId.equals(currentPlaying.songId)) {
         return;
      }

      int online = server != null ? Math.max(1, server.getPlayerManager().getPlayerList().size()) : 1;
      if (startedCalibrated && online > 1) {
         // 已存在全局时间轴且还有其他玩家在听：忽略，避免打乱他人歌词进度
         return;
      }

      long offsetMs = Math.max(0, playedSeconds) * 1000L;
      currentStartTimeMs = System.currentTimeMillis() - offsetMs;
      startedCalibrated = true;
      lastBossBarText = "";
      SimpMusicServer.LOGGER.info(
         "Playback timing calibrated by {} (offset {}s, {} online): {} - {}",
         player.getName().getString(),
         playedSeconds,
         online,
         currentPlaying.title,
         currentPlaying.artist
      );
   }

   public static void stopAll() {
      currentPlaying = null;
      queue.clear();
      requestCooldowns.clear();
      skipVoters.clear();
      hideBossBar();
      broadcastStop();
      SimpMusicServer.LOGGER.info("Music queue stopped and cleared");
   }

   public static boolean removeFromQueue(int index) {
      List<MusicQueueManager.MusicEntry> list = new ArrayList<>(queue);
      if (index >= 0 && index < list.size()) {
         queue.remove(list.get(index));
         broadcastQueueSync();
         return true;
      } else {
         return false;
      }
   }

   public static List<MusicQueueManager.MusicEntry> getQueueAsList() {
      return new ArrayList<>(queue);
   }

   private static void setupBossBar() {
      if (ModConfig.isShowBossBar() && server != null && currentPlaying != null) {
         try {
            if (musicBossBar == null) {
               musicBossBar = new ServerBossBar(Text.literal("§d♪ SimpMusic"), Color.PURPLE, Style.PROGRESS);
            }

            musicBossBar.setName(Text.literal("§d♪ " + currentPlaying.title + " §7- " + currentPlaying.artist));
            musicBossBar.setPercent(0.0F);
            musicBossBar.setVisible(true);

            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
               musicBossBar.addPlayer(p);
            }
         } catch (Exception e) {
            SimpMusicServer.LOGGER.debug("setupBossBar error: {}", e.getMessage());
         }
      }
   }

   private static void hideBossBar() {
      try {
         if (musicBossBar != null) {
            musicBossBar.setVisible(false);
            musicBossBar.clearPlayers();
         }
      } catch (Exception var1) {
      }

      currentLyrics.clear();
      lastBossBarText = "";
      startedCalibrated = false;
      skipVoters.clear();
   }

   /** 向所有在线玩家广播 actionbar 消息（顶部提示，不刷屏聊天栏） */
   private static void broadcastActionbar(Text message) {
      if (server != null) {
         for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            p.sendMessage(message, true);
         }
      }
   }

   private static void tickUpdateBossBar() {
      if (currentPlaying != null && musicBossBar != null && musicBossBar.isVisible()) {
         if (ModConfig.isShowBossBar()) {
            try {
               long elapsed = System.currentTimeMillis() - currentStartTimeMs;
               int durationMs = currentPlaying.duration * 1000;
               if (durationMs <= 0) {
                  return;
               }

               float percent = Math.max(0.0F, Math.min(1.0F, (float)elapsed / durationMs));
               musicBossBar.setPercent(percent);
               String lyric = getLyricAt(elapsed);
               if (lyric != null && !lyric.equals(lastBossBarText)) {
                  lastBossBarText = lyric;
                  Text name = Text.literal("§d♪ " + lyric).append(Text.literal(" §8[" + currentPlaying.title + "]").formatted(Formatting.DARK_GRAY));
                  musicBossBar.setName(name);
               }
            } catch (Exception e) {
               SimpMusicServer.LOGGER.debug("tickUpdateBossBar error: {}", e.getMessage());
            }
         }
      }
   }

   private static String getLyricAt(long timeMs) {
      MusicQueueManager.LyricLine best = null;

      for (MusicQueueManager.LyricLine line : currentLyrics) {
         if (line.timeMs > timeMs) {
            break;
         }

         best = line;
      }

      return best != null ? best.text : null;
   }

   private static void loadLyricsAsync(String songId) {
      NeteaseApiClient.getLyric(songId).thenAccept(lrc -> {
         if (lrc != null && !lrc.isEmpty()) {
            List<MusicQueueManager.LyricLine> parsed = parseLyrics(lrc);
            if (!parsed.isEmpty()) {
               currentLyrics = parsed;
               SimpMusicServer.LOGGER.info("Loaded {} lyric lines for song {}", parsed.size(), songId);
            }
         }
      });
   }

   private static List<MusicQueueManager.LyricLine> parseLyrics(String lrc) {
      List<MusicQueueManager.LyricLine> lines = new ArrayList<>();

      try {
         for (String rawLine : lrc.split("\n")) {
            String line = rawLine.trim();
            Matcher m = LRC_PATTERN.matcher(line);
            if (m.matches()) {
               int min = Integer.parseInt(m.group(1));
               int sec = Integer.parseInt(m.group(2));
               long ms = (min * 60L + sec) * 1000L;
               if (m.group(3) != null) {
                  String frac = m.group(3);

                  while (frac.length() < 3) {
                     frac = frac + "0";
                  }

                  ms += Long.parseLong(frac.substring(0, 3));
               }

               String text = TextUtil.sanitize(m.group(4).trim());
               if (!text.isEmpty() && !isMetadataLine(text)) {
                  lines.add(new MusicQueueManager.LyricLine(ms, text));
               }
            }
         }

         lines.sort(Comparator.comparingLong(l -> l.timeMs));
      } catch (Exception e) {
         SimpMusicServer.LOGGER.debug("parseLyrics error: {}", e.getMessage());
      }

      return lines;
   }

   private static boolean isMetadataLine(String text) {
      String t = text.replaceAll("\\s", "");
      return t.startsWith("作词")
         || t.startsWith("作曲")
         || t.startsWith("编曲")
         || t.startsWith("制作")
         || t.startsWith("录音")
         || t.startsWith("混音")
         || t.startsWith("和声")
         || t.startsWith("吉他")
         || t.startsWith("贝斯")
         || t.startsWith("键盘")
         || t.startsWith("鼓")
         || t.startsWith("弦乐")
         || t.startsWith("OP：")
         || t.startsWith("OP:")
         || t.startsWith("SP：")
         || t.startsWith("SP:")
         || t.startsWith("原唱")
         || t.startsWith("翻唱")
         || t.contains("企划")
         || t.contains("出品");
   }

   private static void broadcastPlay(MusicQueueManager.MusicEntry entry) {
      if (server != null) {
         for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            String urlWithVolume = appendVolumeParam(entry.url);
            PlaySongPacket.send(player, entry.songId, entry.title, entry.artist, urlWithVolume, "", 0);
         }

         Text notify = Text.literal(String.format("%s §a♪ 正在播放: §b%s §7- %s", ModConfig.getPrefix(), entry.title, entry.artist));

         for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            player.sendMessage(notify, true);
         }
      }
   }

   private static void broadcastStop() {
      if (server != null) {
         for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            StopSongPacket.send(player);
         }
      }
   }

   private static void broadcastQueueSync() {
      if (server != null) {
         for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            SyncQueuePacket.send(player, getQueueAsList(), currentPlaying);
         }
      }
   }

   private static boolean checkCooldown(ServerPlayerEntity player) {
      long now = System.currentTimeMillis();
      long lastRequest = requestCooldowns.getOrDefault(player.getUuid(), 0L);
      return now - lastRequest >= ModConfig.getRequestCooldown() * 1000L;
   }

   private static void setCooldown(ServerPlayerEntity player) {
      requestCooldowns.put(player.getUuid(), System.currentTimeMillis());
   }

   private static void checkCurrentSongTimeout() {
      if (currentPlaying != null) {
         long elapsedMs = System.currentTimeMillis() - currentStartTimeMs;
         int durationMs = currentPlaying.duration * 1000;
         if (elapsedMs >= durationMs + 3000) {
            SimpMusicServer.LOGGER.info("Song finished (duration {}s): {}", currentPlaying.duration, currentPlaying.title);
            skipCurrent();
         }
      }
   }

   private static String appendVolumeParam(String url) {
      double vol = ModConfig.getServerVolume();
      if (vol <= 0.0) {
         vol = 1.0;
      }

      String separator = url.contains("?") ? "&" : "?";
      return url + separator + "simpmusic_volume=" + vol;
   }

   public static class LyricLine {
      public final long timeMs;
      public final String text;

      public LyricLine(long timeMs, String text) {
         this.timeMs = timeMs;
         this.text = text;
      }
   }

   public static class MusicEntry {
      public final String songId;
      public final String title;
      public final String artist;
      public final String url;
      public final int duration;
      public final UUID requesterUuid;
      public final String requesterName;

      public MusicEntry(String songId, String title, String artist, String url, int duration, ServerPlayerEntity requester) {
         this.songId = songId;
         this.title = title;
         this.artist = artist;
         this.url = url;
         this.duration = duration > 0 ? duration : 240;
         this.requesterUuid = requester.getUuid();
         this.requesterName = requester.getName().getString();
      }
   }
}
