package com.simpmusic.server;

import com.simpmusic.server.network.LyricsPacket;
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
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents.EndTick;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents.Join;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

/**
 * 全服点歌队列核心状态机。
 *
 * <p>v1.2.0：移除原 BossBar 显示方案，歌曲信息与歌词改由客户端 HUD 渲染
 * （屏幕左上角信息卡片）。服务端职责收敛为：
 * <ul>
 *   <li>维护队列与当前播放项，广播播放/停止/队列同步包</li>
 *   <li>抓取歌词（含翻译）并解析为「时间戳 → 原文 + 译文」后下发客户端</li>
 *   <li>计时与超时自动切歌、投票跳过</li>
 * </ul>
 */
public class MusicQueueManager {
   private static final Queue<MusicQueueManager.MusicEntry> queue = new ConcurrentLinkedQueue<>();
   private static MusicQueueManager.MusicEntry currentPlaying = null;
   private static MinecraftServer server;
   private static int tickDelay = 0;
   private static int tickCounter = 0;
   private static final Map<UUID, Long> requestCooldowns = new HashMap<>();
   private static final Set<UUID> skipVoters = new HashSet<>();
   private static long currentStartTimeMs = 0L;
   private static volatile List<MusicQueueManager.LyricLine> currentLyrics = new ArrayList<>();
   private static volatile String lyricsSongId = "";
   private static boolean startedCalibrated = false;

   /** LRC 时间标签：{@code [mm:ss.xx]}，允许一行出现多个（翻译文件常见） */
   private static final Pattern TIME_TAG = Pattern.compile("\\[(\\d+):(\\d+(?:\\.\\d+)?)\\]");
   /** 译文匹配容差：时间戳相差在此范围内视为同一行 */
   private static final long TRANSLATION_TOLERANCE_MS = 500L;

   public static void init() {
      ServerTickEvents.END_SERVER_TICK.register((EndTick)server -> {
         tickCounter++;
         if (tickDelay <= 0 || tickCounter % tickDelay == 0) {
            if (tickCounter % 5 == 0) {
               checkCurrentSongTimeout();
            }

            // 看门狗：队列里有歌却没有在播（状态错乱/异常导致卡死）时自动推进，杜绝「点歌没反应」
            if (tickCounter % 20 == 0) {
               ensureQueueProgress();
            }
         }
      });
      ServerPlayConnectionEvents.JOIN.register((Join)(handler, sender, srv) -> {
         ServerPlayerEntity joining = handler.getPlayer();
         if (joining == null) {
            return;
         }

         // 中途加入：补发当前播放包（含 HUD 元数据）与已加载的歌词，让新玩家立刻看到信息卡片
         if (currentPlaying != null) {
            try {
               String urlWithVolume = appendVolumeParam(currentPlaying.url);
               int offset = getCurrentPlaybackOffsetSeconds();
               PlaySongPacket.send(
                  joining,
                  currentPlaying.songId,
                  currentPlaying.title,
                  currentPlaying.artist,
                  urlWithVolume,
                  currentPlaying.coverUrl,
                  currentPlaying.album,
                  currentPlaying.requesterName,
                  currentPlaying.duration,
                  offset
               );
               sendLyricsTo(joining);
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

   /** 当前是否运行在服务端主线程（队列状态只允许在主线程改动） */
   private static boolean onServerThread() {
      MinecraftServer s = server;
      return s == null || s.isOnThread();
   }

   /**
    * 把任务切回服务端主线程执行。
    *
    * <p>点歌链路（{@code /music request} → 异步 HTTP 回调）原先直接在
    * {@code ForkJoinPool} 线程里调用 {@link #addToQueue}/{@link #playNext}，
    * 与 tick 线程并发改动 {@code currentPlaying}/{@code queue}——
    * 连续点歌时会出现「两首歌被同时 poll」「currentPlaying 被覆盖」等状态错乱，
    * 表现为点过几首歌后再点歌没有任何反应。这里统一收敛到主线程。
    */
   private static void dispatch(Runnable task) {
      MinecraftServer s = server;
      if (s != null) {
         s.execute(task);
      } else {
         task.run();
      }
   }

   public static void setTickDelay(int delay) {
      tickDelay = Math.max(0, delay);
      SimpMusicServer.LOGGER.info("Queue tick delay set to {} ticks", tickDelay);
   }

   public static boolean addToQueue(
      String songId, String title, String artist, String album, String coverUrl, String url, int duration, ServerPlayerEntity requester
   ) {
      if (!onServerThread()) {
         // 兜底：异步回调误在主线程外调用时切回主线程，避免与 tick 线程并发改队列
         final String fSongId = songId;
         final String fTitle = title;
         final String fArtist = artist;
         final String fAlbum = album;
         final String fCover = coverUrl;
         final String fUrl = url;
         final int fDuration = duration;
         final ServerPlayerEntity fPlayer = requester;
         SimpMusicServer.LOGGER.debug("addToQueue dispatched to server thread (caller was off-thread)");
         dispatch(() -> addToQueue(fSongId, fTitle, fArtist, fAlbum, fCover, fUrl, fDuration, fPlayer));
         return true;
      }

      title = TextUtil.sanitize(title);
      artist = TextUtil.sanitize(artist);
      album = album != null ? TextUtil.sanitize(album) : "";
      if (title.isEmpty()) {
         reject(requester, "无效的歌曲标题");
         return false;
      }

      if (url == null || url.isEmpty()) {
         reject(requester, "无法获取该歌曲的播放链接（可能是 VIP 歌曲，请在服务端配置网易云 Cookie）");
         return false;
      }

      if (!checkCooldown(requester)) {
         reject(requester, "请等待 " + ModConfig.getRequestCooldown() + " 秒后再点歌");
         return false;
      }

      if (queue.size() >= ModConfig.getMaxTotalQueue()) {
         reject(requester, "队列已满，请稍后再试");
         return false;
      }

      if (!ModConfig.isAllowDuplicates()) {
         for (MusicQueueManager.MusicEntry e : queue) {
            if (e.songId.equals(songId)) {
               reject(requester, "该歌曲已在队列中");
               return false;
            }
         }

         if (currentPlaying != null && currentPlaying.songId.equals(songId)) {
            reject(requester, "该歌曲正在播放中");
            return false;
         }
      }

      // 每名玩家的上限同时计入「队列中」与「正在播放」的曲目，避免播放中的那首被漏算
      long playerCount = queue.stream().filter(ex -> ex.requesterUuid.equals(requester.getUuid())).count();
      if (currentPlaying != null && currentPlaying.requesterUuid.equals(requester.getUuid())) {
         playerCount++;
      }

      if (playerCount >= ModConfig.getMaxQueuePerPlayer()) {
         reject(requester, "你已点歌 " + playerCount + " 首（上限 " + ModConfig.getMaxQueuePerPlayer() + "），请等待播放");
         return false;
      }

      queue.add(new MusicQueueManager.MusicEntry(songId, title, artist, album, coverUrl, url, duration, requester));
      setCooldown(requester);
      SimpMusicServer.LOGGER.info(
         "Queued by {}: {} - {} (queue={}, playing={})",
         requester.getName().getString(), title, artist, queue.size(), currentPlaying != null ? currentPlaying.title : "none"
      );

      if (currentPlaying == null) {
         playNext();
      } else {
         broadcastQueueSync();
      }

      return true;
   }

   /** 统一的点歌拒绝反馈：聊天栏 + actionbar 双通道，避免玩家误以为「点了没反应」 */
   private static void reject(ServerPlayerEntity player, String reason) {
      SimpMusicServer.LOGGER.info("Request rejected for {}: {}", player.getName().getString(), reason);
      Text msg = Text.literal(ModConfig.getPrefix() + " §c" + reason);
      player.sendMessage(msg, false);
      player.sendMessage(msg, true);
   }

   public static MusicQueueManager.MusicEntry getCurrentPlaying() {
      return currentPlaying;
   }

   public static Queue<MusicQueueManager.MusicEntry> getQueue() {
      return queue;
   }

   public static void skipCurrent() {
      if (!onServerThread()) {
         dispatch(MusicQueueManager::skipCurrent);
         return;
      }

      SimpMusicServer.LOGGER.info("Skipping current song: {}", currentPlaying != null ? currentPlaying.title : "null");
      currentPlaying = null;
      clearLyrics();
      broadcastStop();
      playNext();
   }

   public static void playNext() {
      if (!onServerThread()) {
         dispatch(MusicQueueManager::playNext);
         return;
      }

      currentPlaying = queue.poll();
      if (currentPlaying != null && server != null) {
         SimpMusicServer.LOGGER.info("Playing next: {} - {}", currentPlaying.title, currentPlaying.artist);
         skipVoters.clear();   // 换歌后清空上一首的跳过投票
         clearLyrics();
         broadcastPlay(currentPlaying);
         broadcastQueueSync();
         currentStartTimeMs = System.currentTimeMillis();
         startedCalibrated = false;   // 等待客户端实际播放后校准计时
         if (ModConfig.isShowMusicHud()) {
            loadLyricsAsync(currentPlaying.songId);
         }
      } else {
         clearLyrics();
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
      if (!onServerThread()) {
         final ServerPlayerEntity p = player;
         dispatch(() -> voteSkip(p));
         return;
      }

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
    * 客户端实际开始播放后回调：以客户端的真实播放位置为基准对齐计时（超时切歌与 HUD 进度）。
    *
    * @param playedSeconds 客户端本次开始播放时已经过的时间（中途加入/重连时为服务端下发的进度）
    *
    * 首次校准用于修正"下载+解码延迟"造成的歌词领先；
    * 当场上只有一名玩家（重连场景，无人受影响）时允许再次校准，
    * 保证计时与该玩家实际听到的位置严格对齐。
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
         // 已存在全局时间轴且还有其他玩家在听：忽略，避免打乱他人进度
         return;
      }

      long offsetMs = Math.max(0, playedSeconds) * 1000L;
      currentStartTimeMs = System.currentTimeMillis() - offsetMs;
      startedCalibrated = true;
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
      if (!onServerThread()) {
         dispatch(MusicQueueManager::stopAll);
         return;
      }

      currentPlaying = null;
      queue.clear();
      requestCooldowns.clear();
      skipVoters.clear();
      clearLyrics();
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

   /** 清空当前歌词缓存（换歌/停止时调用），避免把上一首的歌词误发给新玩家 */
   private static void clearLyrics() {
      currentLyrics = new ArrayList<>();
      lyricsSongId = "";
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

   // ------------------------------------------------------------------
   // 歌词：抓取 → 解析（原文 + 译文）→ 下发
   // ------------------------------------------------------------------

   private static void loadLyricsAsync(String songId) {
      NeteaseApiClient.getLyricWithTranslation(songId).thenAccept(data -> {
         if (data == null || data.lrc == null || data.lrc.isEmpty()) {
            SimpMusicServer.LOGGER.info("No lyrics available for song {}", songId);
            return;
         }

         List<MusicQueueManager.LyricLine> parsed = parseLyrics(data.lrc, data.translation);
         if (parsed.isEmpty()) {
            return;
         }

         // 期间可能已切歌，丢弃过期结果
         if (currentPlaying == null || !songId.equals(currentPlaying.songId)) {
            return;
         }

         currentLyrics = parsed;
         lyricsSongId = songId;
         broadcastLyrics();
         long translated = parsed.stream().filter(l -> l.translation != null && !l.translation.isEmpty()).count();
         SimpMusicServer.LOGGER.info(
            "Loaded {} lyric lines ({} translated) for song {}", parsed.size(), translated, songId
         );
      });
   }

   /** 把当前歌词发给某一玩家（歌词尚未加载则跳过，加载完成后会统一广播） */
   private static void sendLyricsTo(ServerPlayerEntity player) {
      List<MusicQueueManager.LyricLine> lines = currentLyrics;
      if (lines == null || lines.isEmpty() || lyricsSongId.isEmpty()) {
         return;
      }

      List<LyricsPacket.Line> payload = new ArrayList<>(lines.size());
      for (MusicQueueManager.LyricLine l : lines) {
         payload.add(new LyricsPacket.Line(l.timeMs, l.text, l.translation));
      }

      try {
         LyricsPacket.send(player, lyricsSongId, payload);
      } catch (Exception e) {
         SimpMusicServer.LOGGER.warn("Send lyrics to {} failed: {}", player.getName().getString(), e.getMessage());
      }
   }

   private static void broadcastLyrics() {
      if (server != null) {
         for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
            sendLyricsTo(p);
         }
      }
   }

   /**
    * 解析 LRC 原文与译文，按时间戳合并为行列表。
    *
    * @param lrc         原文歌词
    * @param translation 译文歌词（可为 null）
    */
   private static List<MusicQueueManager.LyricLine> parseLyrics(String lrc, String translation) {
      List<MusicQueueManager.LyricLine> lines = new ArrayList<>();

      try {
         TreeMap<Long, String> translations = parseTimedMap(translation);

         for (Map.Entry<Long, String> entry : parseTimedMap(lrc).entrySet()) {
            String text = TextUtil.sanitize(entry.getValue());
            if (text.isEmpty() || isMetadataLine(text)) {
               continue;
            }

            String tr = matchTranslation(translations, entry.getKey());
            lines.add(new MusicQueueManager.LyricLine(entry.getKey(), text, tr));
         }

         lines.sort(Comparator.comparingLong(l -> l.timeMs));
      } catch (Exception e) {
         SimpMusicServer.LOGGER.debug("parseLyrics error: {}", e.getMessage());
      }

      return lines;
   }

   /** 把 LRC 文本解析为「时间戳 → 文本」的有序映射（支持一行多时间标签） */
   private static TreeMap<Long, String> parseTimedMap(String lrc) {
      TreeMap<Long, String> map = new TreeMap<>();
      if (lrc == null || lrc.isEmpty()) {
         return map;
      }

      for (String rawLine : lrc.split("\n")) {
         String line = rawLine.trim();
         if (line.isEmpty()) {
            continue;
         }

         Matcher m = TIME_TAG.matcher(line);
         List<Long> times = new ArrayList<>();
         int lastEnd = 0;

         while (m.find()) {
            times.add(toMillis(m.group(1), m.group(2)));
            lastEnd = m.end();
         }

         if (times.isEmpty() || lastEnd >= line.length()) {
            continue;
         }

         String text = line.substring(lastEnd).trim();
         if (text.isEmpty()) {
            continue;
         }

         for (long t : times) {
            // 同一时间戳出现多次时保留首条，避免叠加标签造成的重复
            map.putIfAbsent(t, text);
         }
      }

      return map;
   }

   /** {@code [mm:ss.xx]} → 毫秒 */
   private static long toMillis(String minStr, String secStr) {
      int minutes = Integer.parseInt(minStr);

      if (secStr.contains(".")) {
         String[] parts = secStr.split("\\.");
         long seconds = Long.parseLong(parts[0]);
         String frac = parts[1];
         while (frac.length() < 3) {
            frac = frac + "0";
         }
         return (minutes * 60L + seconds) * 1000L + Long.parseLong(frac.substring(0, 3));
      }

      return (minutes * 60L + Long.parseLong(secStr)) * 1000L;
   }

   /** 按时间戳取译文：优先精确匹配，否则在容差范围内取最近的一行 */
   private static String matchTranslation(TreeMap<Long, String> translations, long timeMs) {
      if (translations.isEmpty()) {
         return "";
      }

      String exact = translations.get(timeMs);
      if (exact != null) {
         return exact;
      }

      Map.Entry<Long, String> floor = translations.floorEntry(timeMs);
      Map.Entry<Long, String> ceil = translations.ceilingEntry(timeMs);
      long floorDelta = floor != null ? Math.abs(timeMs - floor.getKey()) : Long.MAX_VALUE;
      long ceilDelta = ceil != null ? Math.abs(ceil.getKey() - timeMs) : Long.MAX_VALUE;

      if (floorDelta <= TRANSLATION_TOLERANCE_MS && floorDelta <= ceilDelta) {
         return floor.getValue();
      }

      if (ceilDelta <= TRANSLATION_TOLERANCE_MS) {
         return ceil.getValue();
      }

      return "";
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
            try {
               String urlWithVolume = appendVolumeParam(entry.url);
               PlaySongPacket.send(
                  player,
                  entry.songId,
                  entry.title,
                  entry.artist,
                  urlWithVolume,
                  entry.coverUrl,
                  entry.album,
                  entry.requesterName,
                  entry.duration,
                  0
               );
            } catch (Exception e) {
               // 单个玩家发包失败不应中断整个队列
               SimpMusicServer.LOGGER.warn("Send play packet to {} failed: {}", player.getName().getString(), e.getMessage());
            }
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
            try {
               StopSongPacket.send(player);
            } catch (Exception e) {
               SimpMusicServer.LOGGER.warn("Send stop packet to {} failed: {}", player.getName().getString(), e.getMessage());
            }
         }
      }
   }

   private static void broadcastQueueSync() {
      if (server != null) {
         for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            try {
               SyncQueuePacket.send(player, getQueueAsList(), currentPlaying);
            } catch (Exception e) {
               SimpMusicServer.LOGGER.warn("Send queue sync to {} failed: {}", player.getName().getString(), e.getMessage());
            }
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

   /**
    * 队列看门狗：若队列非空却没有任何歌曲在播（此前并发改队列或广播异常都可能造成这种「卡死」），
    * 主动推进队列。保证「点了歌之后一定会有歌在放」。
    */
   private static void ensureQueueProgress() {
      if (server == null) {
         return;
      }

      if (currentPlaying == null && !queue.isEmpty()) {
         SimpMusicServer.LOGGER.warn(
            "Queue stalled with {} pending song(s) — advancing automatically", queue.size()
         );
         playNext();
      }
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

   /** 一行歌词：时间戳、原文、译文（无翻译为空串） */
   public static class LyricLine {
      public final long timeMs;
      public final String text;
      public final String translation;

      public LyricLine(long timeMs, String text, String translation) {
         this.timeMs = timeMs;
         this.text = text;
         this.translation = translation != null ? translation : "";
      }
   }

   public static class MusicEntry {
      public final String songId;
      public final String title;
      public final String artist;
      public final String album;
      public final String coverUrl;
      public final String url;
      public final int duration;
      public final UUID requesterUuid;
      public final String requesterName;

      public MusicEntry(
         String songId, String title, String artist, String album, String coverUrl, String url, int duration, ServerPlayerEntity requester
      ) {
         this.songId = songId;
         this.title = title;
         this.artist = artist;
         this.album = album != null ? album : "";
         this.coverUrl = coverUrl != null ? coverUrl : "";
         this.url = url;
         this.duration = duration > 0 ? duration : 240;
         this.requesterUuid = requester.getUuid();
         this.requesterName = requester.getName().getString();
      }
   }
}
