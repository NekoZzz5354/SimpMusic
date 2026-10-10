package com.simpmusic.client.audio;

import com.simpmusic.client.ClientConfig;
import com.simpmusic.client.SimpMusicClient;
import com.simpmusic.client.NeteaseApiClient;
import com.simpmusic.client.hud.MusicHud;
import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandlers;
import java.nio.ByteBuffer;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.AudioFormat.Encoding;
import javax.sound.sampled.FloatControl.Type;
import javazoom.spi.mpeg.sampled.convert.MpegFormatConversionProvider;
import javazoom.spi.mpeg.sampled.file.MpegAudioFileReader;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.minecraft.client.MinecraftClient;
import net.minecraft.sound.SoundCategory;
import net.minecraft.text.Text;
import org.lwjgl.openal.AL10;
import org.lwjgl.openal.AL11;

public class MusicAudioStream {
   private static volatile int alSourceId = 0;
   private static volatile StreamingAudioStream currentStream;
   private static volatile Thread decodeThread;
   private static volatile boolean isPlaying = false;
   private static volatile boolean streamEofConsumed = false;
   private static volatile AudioFormat currentPcmFormat;
   private static final ArrayDeque<Integer> alBufferQueue = new ArrayDeque<>();
   private static final int PREFILL_COUNT = 3;
   private static final float BUFFER_SECONDS = 0.25F;
   private static final HttpClient HTTP = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15L)).build();
   private static int bufferSize = 4096;
   private static float volumeMultiplier = 1.0F;
   private static SoundCategory soundCategory = SoundCategory.MASTER;
   private static volatile SourceDataLine activeLine;

   public static void setBufferSize(int size) {
      bufferSize = Math.max(512, Math.min(16384, size));
      SimpMusicClient.LOGGER.debug("Audio buffer size set to {}", bufferSize);
   }

   public static void setVolumeMultiplier(float vol) {
      volumeMultiplier = Math.max(0.0F, Math.min(2.0F, vol));
   }

   public static void setSoundCategory(SoundCategory category) {
      soundCategory = category;
      SimpMusicClient.LOGGER.debug("Sound category set to {}", category);
   }

   public static void init() {
      bufferSize = ClientConfig.getAudioBufferSize();
      volumeMultiplier = ClientConfig.getVolumeMultiplier();
      String cat = ClientConfig.getPreferredSoundCategory();

      try {
         soundCategory = SoundCategory.valueOf(cat.toUpperCase());
      } catch (Exception e) {
         soundCategory = SoundCategory.MASTER;
      }

      ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> tickPlayback());
      SimpMusicClient.LOGGER
         .info(
            "MusicAudioStream initialized: buffer={}, volume={}, category={}, mtr={}",
            new Object[]{bufferSize, volumeMultiplier, soundCategory, SimpMusicClient.isMTRDetected()}
         );
   }

   public static void play(String songId, String title, String artist, String url) {
      play(songId, title, artist, url, 0);
   }

   /**
    * @param startOffsetSeconds 起始播放位置（秒）。中途加入/重连时由服务端下发当前进度，
    *                           解码后跳过对应 PCM 数据实现无缝接续；新歌传 0。
    */
   public static void play(String songId, String title, String artist, String url, int startOffsetSeconds) {
      stop();
      SimpMusicClient.LOGGER.info("Starting playback: {} by {} (offset {}s)", title, artist, startOffsetSeconds);
      float volume = volumeMultiplier;
      if (url != null && url.contains("simpmusic_volume=")) {
         try {
            String prefix = "simpmusic_volume=";
            String volStr = url.substring(url.indexOf(prefix) + prefix.length());
            if (volStr.contains("&")) {
               volStr = volStr.substring(0, volStr.indexOf("&"));
            }

            float parsed = Float.parseFloat(volStr);
            if (parsed > 0.0F) {
               volume = parsed * volumeMultiplier;
            }
         } catch (Exception e) {
            SimpMusicClient.LOGGER.warn("Volume parse failed ({}), using {}", e.getMessage(), volumeMultiplier);
         }
      }

      final int offset = Math.max(0, startOffsetSeconds);
      float finalVolume = volume > 0.0F ? Math.min(2.0F, volume) : 1.0F;
      CompletableFuture.<byte[]>supplyAsync(() -> downloadAudioData(url)).thenAccept(data -> {
         if (data != null && data.length > 0) {
            startStreamingPlayback(data, title, artist, finalVolume, songId, offset);
         } else {
            if (songId != null && !songId.isEmpty()) {
               SimpMusicClient.LOGGER.info("Initial URL failed, re-fetching song url for {}", songId);

               try {
                  NeteaseApiClient.getSongUrl(songId, ClientConfig.getDefaultBitrate()).thenAccept(newUrl -> {
                     if (newUrl != null && !newUrl.equals(url)) {
                        byte[] retry = downloadAudioData(newUrl);
                        if (retry != null && retry.length > 0) {
                           startStreamingPlayback(retry, title, artist, finalVolume, songId, offset);
                           return;
                        }
                     }

                     notifyPlaybackFailed(title, "无法下载音频（可能为 VIP 歌曲）");
                  });
               } catch (Exception e) {
                  SimpMusicClient.LOGGER.error("Re-fetch url error: {}", e.getMessage());
               }
            }
         }
      });
   }

   private static byte[] downloadAudioData(String url) {
      try {
         HttpRequest req = HttpRequest.newBuilder(URI.create(url))
            .header("User-Agent", "SimpMusic/" + SimpMusicClient.VERSION)
            .header("Referer", "https://music.163.com/")
            .timeout(Duration.ofSeconds(30L))
            .build();
         HttpResponse<byte[]> resp = HTTP.send(req, BodyHandlers.ofByteArray());
         byte[] body = resp.body();
         if (resp.statusCode() == 200 && body != null && body.length > 0) {
            if (isAudioData(body)) {
               return body;
            }

            SimpMusicClient.LOGGER.warn("Downloaded content is not audio (likely VIP 404 page), {} bytes", body.length);
            return null;
         }

         SimpMusicClient.LOGGER.error("Audio download failed: HTTP {}", resp.statusCode());
      } catch (Exception e) {
         SimpMusicClient.LOGGER.error("Audio download error: {}", e.getMessage());
      }

      return null;
   }

   private static boolean isAudioData(byte[] data) {
      if (data.length < 4) {
         return false;
      } else if (data[0] == 102 && data[1] == 76 && data[2] == 97 && data[3] == 67) {
         return true;
      } else {
         return (data[0] & 255) == 255 && (data[1] & 224) == 224 ? true : data[0] != 60 && data[0] != 123 && data[0] != 91;
      }
   }

   private static void startStreamingPlayback(byte[] audioData, String title, String artist, float volume, String songId, int startOffsetSeconds) {
      CompletableFuture.<MusicAudioStream.DecodedSource>supplyAsync(() -> {
         try {
            AudioInputStream sourceStream = openDecoderStream(audioData);
            AudioFormat sourceFormat = sourceStream.getFormat();
            float sampleRate = sourceFormat.getSampleRate() > 0.0F ? sourceFormat.getSampleRate() : 44100.0F;
            int channels = sourceFormat.getChannels() > 0 ? sourceFormat.getChannels() : 2;
            AudioFormat pcmFormat = new AudioFormat(Encoding.PCM_SIGNED, sampleRate, 16, channels, channels * 2, sampleRate, false);
            AudioInputStream pcmStream = convertToPcm(sourceStream, pcmFormat);
            // 中途加入/重连：在后台解码线程内完成定位，避免拖慢首批数据导致播放线程误判流已结束
            skipPcm(pcmStream, pcmFormat, startOffsetSeconds);
            return new MusicAudioStream.DecodedSource(pcmFormat, pcmStream);
         } catch (Exception e) {
            SimpMusicClient.LOGGER.error("Audio decode error for {}: {}", title, e.getMessage());
            return null;
         }
      }).thenAccept(decoded -> MinecraftClient.getInstance().execute(() -> {
         if (decoded == null) {
            notifyPlaybackFailed(title, "音频解码失败（可能需要 mp3spi 等解码库）");
         } else {
            try {
               if (!startDirectOpenAl(decoded, title, artist, volume)) {
                  playPcmLineFallback(decoded.pcmStream, decoded.pcmFormat, volume, title, artist);
               }

               // 实际开始播放后：校准服务端计时，并让 HUD 按真实出声时刻对齐歌词/进度
               sendPlayStarted(songId, startOffsetSeconds);
               MusicHud.onPlaybackStarted(songId, startOffsetSeconds);
            } catch (Exception e) {
               SimpMusicClient.LOGGER.error("OpenAL playback failed, fallback: {}", e.getMessage());
               playPcmLineFallback(decoded.pcmStream, decoded.pcmFormat, volume, title, artist);
               sendPlayStarted(songId, startOffsetSeconds);
               MusicHud.onPlaybackStarted(songId, startOffsetSeconds);
            }
         }
      }));
   }

   private static void sendPlayStarted(String songId, int playedSeconds) {
      try {
         net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking.send(
            net.minecraft.util.Identifier.of("simpmusic", "play_started"),
            net.fabricmc.fabric.api.networking.v1.PacketByteBufs.create()
               .writeString(songId != null ? songId : "")
               .writeInt(Math.max(0, playedSeconds))
         );
         SimpMusicClient.LOGGER.debug("Sent play_started for song {} (offset {}s)", songId, playedSeconds);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.debug("send play_started failed: {}", e.getMessage());
      }
   }

   private static boolean startDirectOpenAl(MusicAudioStream.DecodedSource decoded, String title, String artist, float volume) {
      int source = AL10.alGenSources();
      if (source == 0) {
         SimpMusicClient.LOGGER.warn("alGenSources failed (AL error {})", AL10.alGetError());
         return false;
      }

      float safeVolume = clampVolume(volume);
      if (safeVolume <= 0.0F) {
         safeVolume = 1.0F;
      }

      AL10.alSourcef(source, 4106, safeVolume);
      AL10.alSourcei(source, 4103, 0);
      AL11.alSourcei(source, 514, 1);
      AL10.alSource3f(source, 4100, 0.0F, 0.0F, 0.0F);
      alSourceId = source;
      currentStream = new StreamingAudioStream(decoded.pcmFormat);
      currentPcmFormat = decoded.pcmFormat;
      streamEofConsumed = false;
      alBufferQueue.clear();
      isPlaying = true;
      SimpMusicClient.setPlaying(true, title + " - " + artist);
      SimpMusicClient.LOGGER
         .info(
            "Now playing via direct OpenAL: {} ({} Hz, {}ch, vol={}, src={})",
            new Object[]{title, decoded.pcmFormat.getSampleRate(), decoded.pcmFormat.getChannels(), safeVolume, source}
         );
      decodeThread = new Thread(() -> feedPcm(decoded, currentStream), "SimpMusic-Feeder");
      decodeThread.setDaemon(true);
      decodeThread.start();
      return true;
   }

   /** 跳过解码流开头的 PCM 数据，实现从指定秒数开始播放 */
   private static void skipPcm(AudioInputStream pcmStream, AudioFormat fmt, int seconds) {
      if (seconds <= 0 || pcmStream == null || fmt == null) {
         return;
      }

      long bytesPerSecond = (long)(fmt.getSampleRate() * fmt.getChannels() * fmt.getSampleSizeInBits() / 8.0);
      long toSkip = bytesPerSecond * seconds;
      long skipped = 0L;
      byte[] skipBuf = new byte[8192];

      try {
         while (skipped < toSkip) {
            int n = pcmStream.read(skipBuf, 0, (int)Math.min(skipBuf.length, toSkip - skipped));
            if (n <= 0) {
               break;
            }

            skipped += n;
         }

         SimpMusicClient.LOGGER.info("Seeked playback to {}s (skipped {} bytes)", seconds, skipped);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.warn("Seek failed at {}s: {}", seconds, e.getMessage());
      }
   }

   private static void feedPcm(MusicAudioStream.DecodedSource decoded, StreamingAudioStream stream) {
      try {
         byte[] buf = new byte[Math.max(bufferSize, 2048)];

         int n;
         while ((n = decoded.pcmStream.read(buf)) > 0 && currentStream == stream) {
            stream.push(Arrays.copyOf(buf, n));
         }

         stream.finish();
      } catch (Exception e) {
         SimpMusicClient.LOGGER.debug("Feed thread stopped: {}", e.getMessage());
         stream.finish();
      } finally {
         try {
            decoded.pcmStream.close();
         } catch (Exception var11) {
         }
      }
   }

   private static void tickPlayback() {
      if (alSourceId != 0) {
         try {
            for (int processed = AL10.alGetSourcei(alSourceId, 4118); processed > 0 && !alBufferQueue.isEmpty(); processed--) {
               int buf = AL10.alSourceUnqueueBuffers(alSourceId);
               if (buf != 0) {
                  AL10.alDeleteBuffers(buf);
                  alBufferQueue.removeFirstOccurrence(buf);
               }
            }

            StreamingAudioStream stream = currentStream;
            if (stream != null && alBufferQueue.size() < 3) {
               int bufSize = calcBufferBytes(currentPcmFormat, 0.25F);

               while (alBufferQueue.size() < 3) {
                  ByteBuffer data = stream.getBuffer(bufSize);
                  if (data == null) {
                     // 仅在数据源真正结束时判定播放完成；
                     // 数据暂时未就绪（解码/定位中）时保持等待，避免误停
                     if (stream.isEof()) {
                        streamEofConsumed = true;
                     }
                     break;
                  }

                  int buf = AL10.alGenBuffers();
                  int alFmt = formatToAl(currentPcmFormat);
                  AL10.alBufferData(buf, alFmt, data, (int)currentPcmFormat.getSampleRate());
                  AL10.alSourceQueueBuffers(alSourceId, buf);
                  alBufferQueue.add(buf);
               }
            }

            int state = AL10.alGetSourcei(alSourceId, 4112);
            if (state == 4116 && !alBufferQueue.isEmpty() && !streamEofConsumed) {
               AL10.alSourcePlay(alSourceId);
            } else if (state == 4113 || state == 4116) {
               if (alBufferQueue.isEmpty() && streamEofConsumed) {
                  SimpMusicClient.LOGGER.info("Playback finished (all data consumed)");
                  cleanupDirect();
                  MusicHud.endSong();
                  return;
               }

               if (!alBufferQueue.isEmpty()) {
                  AL10.alSourcePlay(alSourceId);
               }
            }
         } catch (Exception e) {
            SimpMusicClient.LOGGER.warn("OpenAL tick error: {}", e.getMessage());
            cleanupDirect();
         }
      }
   }

   private static void cleanupDirect() {
      if (alSourceId != 0) {
         try {
            AL10.alSourceStop(alSourceId);

            while (!alBufferQueue.isEmpty()) {
               try {
                  int buf = AL10.alSourceUnqueueBuffers(alSourceId);
                  if (buf != 0) {
                     AL10.alDeleteBuffers(buf);
                     alBufferQueue.removeFirstOccurrence(buf);
                  } else {
                     int b = alBufferQueue.poll();
                     if (b != 0) {
                        AL10.alDeleteBuffers(b);
                     }
                  }
               } catch (Exception e) {
                  Integer b = alBufferQueue.poll();
                  if (b != null && b != 0) {
                     try {
                        AL10.alDeleteBuffers(b);
                     } catch (Exception var4) {
                     }
                  }
               }
            }

            AL10.alDeleteSources(alSourceId);
         } catch (Exception var6) {
         }

         alSourceId = 0;
      }

      if (currentStream != null) {
         try {
            currentStream.close();
         } catch (Exception var3) {
         }

         currentStream = null;
      }

      Thread feed = decodeThread;
      decodeThread = null;
      if (feed != null) {
         feed.interrupt();
      }

      streamEofConsumed = false;
      currentPcmFormat = null;
      isPlaying = false;
      SimpMusicClient.setPlaying(false, "");
   }

   private static int calcBufferBytes(AudioFormat fmt, float seconds) {
      if (fmt == null) {
         return 8192;
      }

      int bytes = (int)(seconds * fmt.getSampleRate() * fmt.getChannels() * fmt.getSampleSizeInBits() / 8.0);
      return Math.max(1024, bytes);
   }

   private static int formatToAl(AudioFormat fmt) {
      int channels = fmt.getChannels();
      int bits = fmt.getSampleSizeInBits();
      if (bits <= 8) {
         return channels >= 2 ? 4354 : 4352;
      } else {
         return channels >= 2 ? 4355 : 4353;
      }
   }

   private static AudioInputStream openDecoderStream(byte[] data) throws Exception {
      try {
         return new MpegAudioFileReader().getAudioInputStream(new ByteArrayInputStream(data));
      } catch (Exception e) {
         SimpMusicClient.LOGGER.debug("MpegAudioFileReader failed ({}), fallback to AudioSystem", e.getMessage());
         return AudioSystem.getAudioInputStream(new ByteArrayInputStream(data));
      }
   }

   private static AudioInputStream convertToPcm(AudioInputStream source, AudioFormat pcmFormat) throws Exception {
      try {
         return new MpegFormatConversionProvider().getAudioInputStream(pcmFormat, source);
      } catch (Exception e) {
         SimpMusicClient.LOGGER.debug("MpegFormatConversionProvider failed ({}), fallback to AudioSystem", e.getMessage());
         return AudioSystem.getAudioInputStream(pcmFormat, source);
      }
   }

   private static float clampVolume(float v) {
      return Math.max(0.0F, Math.min(2.0F, v));
   }

   private static void notifyPlaybackFailed(String title, String reason) {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client.player != null) {
         client.player.sendMessage(Text.literal("§c⚠ 播放失败: " + title + " (" + reason + ")"), true);
      }
   }

   private static void playPcmLineFallback(AudioInputStream pcmStream, AudioFormat pcmFormat, float volume, String title, String artist) {
      Thread t = new Thread(() -> {
         SourceDataLine line = null;

         try {
            line = AudioSystem.getSourceDataLine(pcmFormat);
            line.open(pcmFormat, Math.max(bufferSize * 4, 4096));
            applyGain(line, volume);
            line.start();
            activeLine = line;
            isPlaying = true;
            SimpMusicClient.setPlaying(true, title + " - " + artist);
            SimpMusicClient.LOGGER.info("Now playing via SourceDataLine (fallback): {}", title);
            byte[] buf = new byte[Math.max(bufferSize, 2048)];

            int n;
            while ((n = pcmStream.read(buf)) > 0 && activeLine == line) {
               line.write(buf, 0, n);
            }

            if (activeLine == line) {
               line.drain();
            }
         } catch (Exception e) {
            if (activeLine == line) {
               SimpMusicClient.LOGGER.error("PCM fallback playback error: {}", e.getMessage());
               notifyPlaybackFailed(title, "播放器错误");
            }
         } finally {
            try {
               pcmStream.close();
            } catch (Exception var19) {
            }

            if (activeLine == line) {
               activeLine = null;
            }

            if (line != null) {
               try {
                  line.stop();
                  line.close();
               } catch (Exception var18) {
               }
            }

            isPlaying = false;
            SimpMusicClient.setPlaying(false, "");
         }
      }, "SimpMusic-Fallback");
      t.setDaemon(true);
      t.start();
   }

   private static void applyGain(SourceDataLine line, float volume) {
      try {
         if (line.isControlSupported(Type.MASTER_GAIN)) {
            FloatControl gain = (FloatControl)line.getControl(Type.MASTER_GAIN);
            float db = 20.0F * (float)Math.log10(Math.max(0.02F, Math.min(2.0F, volume)));
            db = Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), db));
            gain.setValue(db);
         }
      } catch (Exception var4) {
      }
   }

   public static void stop() {
      cleanupDirect();
      SourceDataLine line = activeLine;
      activeLine = null;
      if (line != null) {
         try {
            line.stop();
            line.close();
         } catch (Exception var2) {
         }
      }
   }

   public static boolean isPlaying() {
      return isPlaying;
   }

   public static int getBufferSize() {
      return bufferSize;
   }

   private static class DecodedSource {
      final AudioFormat pcmFormat;
      final AudioInputStream pcmStream;

      DecodedSource(AudioFormat pcmFormat, AudioInputStream pcmStream) {
         this.pcmFormat = pcmFormat;
         this.pcmStream = pcmStream;
      }
   }
}
