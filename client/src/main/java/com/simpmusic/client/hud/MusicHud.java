package com.simpmusic.client.hud;

import com.simpmusic.client.ClientConfig;
import com.simpmusic.client.SimpMusicClient;
import com.mojang.blaze3d.systems.RenderSystem;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;

/**
 * 屏幕左上角歌曲信息 HUD（v1.2.0 取代原 BossBar 方案）。
 *
 * <p>布局（整体贴屏幕左上角）：
 * <pre>
 * ┌───────────────────────────────────────────┐
 * │  曲名（加粗）                     ┌───────┐ │
 * │  曲师                            │ 封面  │ │
 * │  专辑                            │ 1 : 1 │ │
 * │  01:23 / 04:00  点歌: xxx        └───────┘ │
 * │  ─────────────────────────────────────────  │
 * │  ♪ 当前歌词行（过长自动横向滑动）             │
 * │    歌词翻译（外语歌并列展示，同样会滑动）      │
 * │    下一句（暗色预览）                        │
 * │  ▓▓▓▓▓▓▓▓░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░░ │
 * └───────────────────────────────────────────┘
 * </pre>
 *
 * <p>v1.2.1：歌词行过长时改为**横向滑动（marquee）**展示，并用剪刀裁剪到歌词区内，
 * 不再被直接截断或在面板外溢出。
 */
public final class MusicHud {
   private static final int PAD = 6;
   private static final int COVER = 46;
   private static final int GAP = 8;
   private static final int LINE_H = 10;
   private static final int TEXT_MAX_W = 150;

   /** 歌词滚动速度（GUI 单位 / 秒）与循环间隔 */
   private static final double MARQUEE_SPEED = 22.0;
   private static final int MARQUEE_GAP = 28;

   private static final int COL_BG = 0xC00E0A1A;
   private static final int COL_BORDER = 0x66B06CFF;
   private static final int COL_TITLE = 0xFFFFFFFF;
   private static final int COL_ARTIST = 0xFFB9B9CC;
   private static final int COL_ALBUM = 0xFF8A8AA6;
   private static final int COL_TIME = 0xFFCFCFE4;
   private static final int COL_LYRIC = 0xFFFFE08A;
   private static final int COL_TRANS = 0xFF8FD8FF;
   private static final int COL_NEXT = 0xFF7A7A94;
   private static final int COL_PLACEHOLDER = 0xFF86869C;
   private static final int COL_BAR_BG = 0xFF2A2A3E;
   private static final int COL_BAR_FG = 0xFFB06CFF;
   private static final int COL_COVER_BG = 0xFF23233A;

   private static volatile boolean active = false;
   private static volatile String songId = "";
   private static volatile String title = "";
   private static volatile String artist = "";
   private static volatile String album = "";
   private static volatile String requester = "";
   private static volatile int durationSeconds = 0;
   private static volatile long startTimeMs = 0L;
   private static volatile List<Line> lines = new ArrayList<>();

   private MusicHud() {
   }

   /** 一行歌词：时间戳（毫秒）、原文、译文 */
   public record Line(long timeMs, String text, String translation) {
   }

   /** 收到播放指令：登记曲目元数据并开始（tentative）计时 */
   public static void beginSong(
      String id, String songTitle, String songArtist, String songAlbum, String songRequester, String coverUrl, int duration, int offsetSeconds
   ) {
      songId = id != null ? id : "";
      title = songTitle != null ? songTitle : "";
      artist = songArtist != null ? songArtist : "";
      album = songAlbum != null ? songAlbum : "";
      requester = songRequester != null ? songRequester : "";
      durationSeconds = Math.max(0, duration);
      lines = new ArrayList<>();
      startTimeMs = System.currentTimeMillis() - Math.max(0, offsetSeconds) * 1000L;
      active = true;
      CoverTexture.request(coverUrl);
   }

   /**
    * 解码完成、真正出声后回调：以真实播放位置为基准重置计时，
    * 消除下载/解码延迟造成的歌词与进度领先。
    */
   public static void onPlaybackStarted(String id, int offsetSeconds) {
      if (id != null && !id.isEmpty() && !songId.isEmpty() && !id.equals(songId)) {
         return;
      }

      startTimeMs = System.currentTimeMillis() - Math.max(0, offsetSeconds) * 1000L;
      active = true;
   }

   /** 接收服务端下发的整首歌歌词；只在仍播放同一首歌时生效 */
   public static void setLyrics(String id, List<Line> incoming) {
      if (incoming == null || incoming.isEmpty()) {
         return;
      }

      if (id != null && !id.isEmpty() && !songId.isEmpty() && !id.equals(songId)) {
         return;
      }

      List<Line> sorted = new ArrayList<>(incoming);
      sorted.sort((a, b) -> Long.compare(a.timeMs(), b.timeMs()));
      lines = sorted;
      SimpMusicClient.LOGGER.debug("HUD received {} lyric lines for {}", sorted.size(), id);
   }

   /** 播放结束/停止：隐藏 HUD 并释放封面 */
   public static void endSong() {
      active = false;
      lines = new ArrayList<>();
      CoverTexture.reset();
   }

   public static boolean isActive() {
      return active;
   }

   /** 由 HUD 渲染回调每帧调用 */
   public static void render(DrawContext context, float tickDelta) {
      if (!active || !ClientConfig.isShowMusicHud()) {
         return;
      }

      MinecraftClient client = MinecraftClient.getInstance();
      if (client == null || client.textRenderer == null || client.options == null) {
         return;
      }

      if (client.options.hudHidden) {
         return;
      }

      TextRenderer tr = client.textRenderer;
      float scale = (float)ClientConfig.getMusicHudScale();

      long now = System.currentTimeMillis();
      long elapsedMs = Math.max(0L, now - startTimeMs);
      long durationMs = durationSeconds > 0 ? durationSeconds * 1000L : 0L;
      if (durationMs > 0 && elapsedMs > durationMs) {
         elapsedMs = durationMs;
      }

      List<Line> ls = lines;
      int idx = -1;
      for (int i = 0; i < ls.size(); i++) {
         if (ls.get(i).timeMs() <= elapsedMs) {
            idx = i;
         } else {
            break;
         }
      }

      Line cur = idx >= 0 ? ls.get(idx) : null;
      Line nxt = idx + 1 < ls.size() ? ls.get(idx + 1) : null;
      String curText = cur != null ? cur.text() : "";
      String curTrans = cur != null && cur.translation() != null ? cur.translation() : "";
      String nextText = nxt != null ? nxt.text() : "";

      boolean hasCur = !curText.isEmpty();
      boolean hasTrans = !curTrans.isEmpty();
      boolean hasNext = !nextText.isEmpty();

      // ---- 文本块宽度 ----
      int textWidth = tr.getWidth(title);
      if (!artist.isEmpty()) {
         textWidth = Math.max(textWidth, tr.getWidth(artist));
      }
      if (!album.isEmpty()) {
         textWidth = Math.max(textWidth, tr.getWidth(album));
      }
      if (!requester.isEmpty()) {
         textWidth = Math.max(textWidth, tr.getWidth("点歌: " + requester));
      }
      textWidth = Math.max(70, Math.min(TEXT_MAX_W, textWidth));

      // ---- 面板尺寸 ----
      int x0 = 6;
      int y0 = 6;
      int textX = x0 + PAD;
      int coverX = textX + textWidth + GAP;
      int panelW = coverX + COVER + PAD - x0;

      int lyricRows = (hasCur ? 1 : 0) + (hasTrans ? 1 : 0) + (hasNext ? 1 : 0);
      if (lyricRows == 0) {
         lyricRows = 1;
      }

      int lyricsTop = y0 + PAD + COVER + 7;
      int lyricsH = lyricRows * LINE_H;
      int barY = lyricsTop + lyricsH + 4;
      int panelH = barY + 3 + PAD - y0;
      int lyricMaxW = panelW - PAD * 2;

      MatrixStack matrices = context.getMatrices();
      matrices.push();
      matrices.scale(scale, scale, 1.0F);

      // ---- 背景 + 描边 ----
      context.fill(x0, y0, x0 + panelW, y0 + panelH, COL_BG);
      context.fill(x0, y0, x0 + panelW, y0 + 1, COL_BORDER);
      context.fill(x0, y0 + panelH - 1, x0 + panelW, y0 + panelH, COL_BORDER);
      context.fill(x0, y0, x0 + 1, y0 + panelH, COL_BORDER);
      context.fill(x0 + panelW - 1, y0, x0 + panelW, y0 + panelH, COL_BORDER);

      // ---- 左侧信息块 ----
      int ty = y0 + PAD;
      context.drawTextWithShadow(
         tr, Text.literal(tr.trimToWidth(title, textWidth)).formatted(Formatting.BOLD), textX, ty, COL_TITLE
      );
      ty += LINE_H + 1;

      if (!artist.isEmpty()) {
         context.drawTextWithShadow(tr, tr.trimToWidth(artist, textWidth), textX, ty, COL_ARTIST);
         ty += LINE_H;
      }

      if (!album.isEmpty()) {
         context.drawTextWithShadow(tr, tr.trimToWidth(album, textWidth), textX, ty, COL_ALBUM);
         ty += LINE_H;
      }

      context.drawTextWithShadow(tr, formatTime(elapsedMs) + " / " + formatTime(durationMs), textX, ty, COL_TIME);
      ty += LINE_H;

      if (!requester.isEmpty()) {
         context.drawTextWithShadow(tr, tr.trimToWidth("点歌: " + requester, textWidth), textX, ty, COL_ALBUM);
      }

      // ---- 右侧 1:1 封面 ----
      drawCover(context, tr, coverX, y0 + PAD);

      // ---- 下方歌词：过长自动横向滑动，并裁剪在歌词区内 ----
      int ly = lyricsTop;

      if (hasCur) {
         drawScrolling(context, tr, scale, "♪ " + curText, x0 + PAD, ly, lyricMaxW, now, COL_LYRIC);
         ly += LINE_H;
      }

      if (hasTrans) {
         drawScrolling(context, tr, scale, curTrans, x0 + PAD, ly, lyricMaxW, now, COL_TRANS);
         ly += LINE_H;
      }

      if (hasNext) {
         drawScrolling(context, tr, scale, nextText, x0 + PAD, ly, lyricMaxW, now, COL_NEXT);
         ly += LINE_H;
      }

      if (!hasCur && !hasNext) {
         String placeholder = ls.isEmpty() ? "纯音乐 / 暂无歌词" : "♪ 前奏…";
         context.drawTextWithShadow(tr, placeholder, x0 + PAD, ly, COL_PLACEHOLDER);
      }

      // ---- 进度条 ----
      int barX = x0 + PAD;
      int barW = panelW - PAD * 2;
      context.fill(barX, barY, barX + barW, barY + 3, COL_BAR_BG);

      if (durationMs > 0) {
         int fillW = (int)(barW * (elapsedMs / (double)durationMs));
         if (fillW > 0) {
            context.fill(barX, barY, barX + Math.min(fillW, barW), barY + 3, COL_BAR_FG);
         }
      }

      matrices.pop();
   }

   /**
    * 绘制一行歌词：宽度不超过可用宽度时正常绘制；
    * 超过时以跑马灯方式横向循环滑动，并用剪刀限制在歌词区内，
    * 避免文字溢出面板或被硬截断而看不全。
    */
   private static void drawScrolling(
      DrawContext context, TextRenderer tr, float scale, String text, int x, int y, int availW, long now, int color
   ) {
      if (text == null || text.isEmpty() || availW <= 0) {
         return;
      }

      int textW = tr.getWidth(text);

      if (textW <= availW) {
         context.drawTextWithShadow(tr, text, x, y, color);
         return;
      }

      // 首尾相接循环：总长 = 文本宽 + 间隔
      int total = textW + MARQUEE_GAP;
      int offset = (int)((now / 1000.0 * MARQUEE_SPEED) % total);

      // 剪刀坐标位于 GUI 空间，不含本 HUD 自身的缩放，需要手动乘上 scale
      int clipX1 = (int)Math.floor(x * scale);
      int clipY1 = (int)Math.floor(y * scale);
      int clipX2 = (int)Math.ceil((x + availW) * scale);
      int clipY2 = (int)Math.ceil((y + LINE_H) * scale);

      context.enableScissor(clipX1, clipY1, clipX2, clipY2);
      context.drawTextWithShadow(tr, text, x - offset, y, color);
      context.drawTextWithShadow(tr, text, x - offset + total, y, color);
      context.disableScissor();
   }

   /** 绘制 1:1 封面：非正方形时按中心裁切，无纹理时画占位块 */
   private static void drawCover(DrawContext context, TextRenderer tr, int coverX, int coverY) {
      Identifier texture = CoverTexture.getId();

      if (texture != null && CoverTexture.hasImage()) {
         int w = CoverTexture.getWidth();
         int h = CoverTexture.getHeight();
         int side = Math.min(w, h);
         float u = (w - side) / 2.0F;
         float v = (h - side) / 2.0F;

         RenderSystem.enableBlend();
         RenderSystem.defaultBlendFunc();
         context.drawTexture(texture, coverX, coverY, COVER, COVER, u, v, side, side, Math.max(1, w), Math.max(1, h));
         RenderSystem.disableBlend();
      } else {
         context.fill(coverX, coverY, coverX + COVER, coverY + COVER, COL_COVER_BG);
         context.drawTextWithShadow(tr, "♪", coverX + COVER / 2 - 3, coverY + COVER / 2 - 4, 0x66FFFFFF);
      }

      // 封面描边
      context.fill(coverX - 1, coverY - 1, coverX + COVER + 1, coverY, COL_BORDER);
      context.fill(coverX - 1, coverY + COVER, coverX + COVER + 1, coverY + COVER + 1, COL_BORDER);
      context.fill(coverX - 1, coverY, coverX, coverY + COVER, COL_BORDER);
      context.fill(coverX + COVER, coverY, coverX + COVER + 1, coverY + COVER, COL_BORDER);
   }

   private static String formatTime(long ms) {
      long totalSeconds = Math.max(0L, ms / 1000L);
      return String.format("%d:%02d", totalSeconds / 60L, totalSeconds % 60L);
   }

   /** 供调试/命令查询：当前 HUD 曲目与歌词行数 */
   public static String describe() {
      if (!active) {
         return "HUD 未激活";
      }

      return String.format(
         "HUD: %s - %s | 歌词 %d 行 | 封面 %s",
         title, artist, lines.size(), CoverTexture.hasImage() ? "已就绪" : "未加载"
      );
   }
}
