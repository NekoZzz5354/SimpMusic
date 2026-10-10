package com.simpmusic.client.network;

import com.simpmusic.client.SimpMusicClient;
import com.simpmusic.client.audio.MusicAudioStream;
import com.simpmusic.client.gui.MusicScreen;
import com.simpmusic.client.hud.MusicHud;
import java.util.ArrayList;
import java.util.List;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

public class ClientNetworkHandler {
   public static void register() {
      // ---- 开始播放：携带 HUD 所需的完整元数据（封面 / 专辑 / 点歌人 / 时长）----
      ClientPlayNetworking.registerGlobalReceiver(Identifier.of("simpmusic", "play_song"), (client, handler, buf, responseSender) -> {
         String songId = buf.readString();
         String title = buf.readString();
         String artist = buf.readString();
         String url = buf.readString();
         String coverUrl = buf.readString();
         String album = buf.readString();
         String requester = buf.readString();
         int duration = buf.readInt();
         int startOffset = buf.readInt();
         SimpMusicClient.LOGGER.info(
            "Playing: {} - {} ({}) offset={}s cover={}", title, artist, url, startOffset, coverUrl.isEmpty() ? "none" : "yes"
         );
         client.execute(() -> {
            try {
               MusicHud.beginSong(songId, title, artist, album, requester, coverUrl, duration, startOffset);
               MusicAudioStream.play(songId, title, artist, url, startOffset);
               SimpMusicClient.setPlaying(true, title + " - " + artist);
               if (client.player != null) {
                  client.player.sendMessage(Text.literal("§a\ud83c\udfb5 正在播放: " + title + " - " + artist), true);
               }
            } catch (Exception e) {
               SimpMusicClient.LOGGER.error("Play error (caught): {}", e.getMessage());
               if (client.player != null) {
                  client.player.sendMessage(Text.literal("§c⚠ 播放失败: " + title + " — 可能不兼容当前音频格式"), true);
               }
            }
         });
      });

      // ---- 歌词（含翻译）：整体下发，客户端直接缓存备用 ----
      ClientPlayNetworking.registerGlobalReceiver(Identifier.of("simpmusic", "lyrics"), (client, handler, buf, responseSender) -> {
         String songId = buf.readString();
         int count = buf.readInt();
         List<MusicHud.Line> lines = new ArrayList<>(Math.max(0, count));

         for (int i = 0; i < count; i++) {
            long timeMs = buf.readLong();
            String text = buf.readString();
            String translation = buf.readString();
            lines.add(new MusicHud.Line(timeMs, text, translation));
         }

         client.execute(() -> MusicHud.setLyrics(songId, lines));
      });

      ClientPlayNetworking.registerGlobalReceiver(Identifier.of("simpmusic", "stop_song"), (client, handler, buf, responseSender) -> client.execute(() -> {
         try {
            MusicAudioStream.stop();
            MusicHud.endSong();
            SimpMusicClient.setPlaying(false, "");
            if (client.player != null) {
               client.player.sendMessage(Text.literal("§c⏹ 音乐已停止"), true);
            }
         } catch (Exception e) {
            SimpMusicClient.LOGGER.debug("Stop error (caught): {}", e.getMessage());
         }
      }));

      ClientPlayNetworking.registerGlobalReceiver(Identifier.of("simpmusic", "sync_queue"), (client, handler, buf, responseSender) -> {
         boolean hasCurrent = buf.readBoolean();
         String currentTitle = "";
         if (hasCurrent) {
            buf.readString();
            currentTitle = buf.readString();
            buf.readString();
            buf.readString();
         }

         int queueSize = buf.readInt();

         for (int i = 0; i < queueSize; i++) {
            buf.readString();
            buf.readString();
            buf.readString();
            buf.readString();
         }

         String finalTitle = currentTitle;
         client.execute(() -> {
            if (!finalTitle.isEmpty()) {
               SimpMusicClient.setPlaying(true, finalTitle);
            }
         });
      });

      ClientPlayNetworking.registerGlobalReceiver(Identifier.of("simpmusic", "search_result"), (client, handler, buf, responseSender) -> {
         int count = buf.readInt();
         client.execute(() -> MusicScreen.setSearchResults(count, buf));
      });

      // 断开服务器（退出到主菜单）时立即停止本地播放，避免"退出后仍在放歌"
      ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
         try {
            MusicAudioStream.stop();
            MusicHud.endSong();
            SimpMusicClient.setPlaying(false, "");
            SimpMusicClient.LOGGER.info("Disconnected from server — playback stopped");
         } catch (Exception e) {
            SimpMusicClient.LOGGER.debug("Disconnect stop error: {}", e.getMessage());
         }
      });
   }
}
