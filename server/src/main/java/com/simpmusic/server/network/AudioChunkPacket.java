package com.simpmusic.server.network;

import java.util.Arrays;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 服务端 → 客户端：音频数据分块下发。
 *
 * <p>一首歌通常几 MB，远超单个自定义包的上限，因此按 {@link #CHUNK_SIZE} 切片，
 * 客户端按 {@code seq} 重组成完整音频后再播放。
 * 每块不超过 100KB，远低于 Minecraft 自定义包 1MiB 的上限。
 */
public record AudioChunkPacket(String songId, int seq, int total, byte[] data) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "audio_chunk");

   /** 每块字节数（< 1MiB 上限，留足余量） */
   public static final int CHUNK_SIZE = 100 * 1024;

   /**
    * 分块下发。放在独立线程并做轻微节流：
    * 一次性把十几 MB 全塞进 netty 写缓冲会挤占服务器带宽与内存，
    * 节流后既能保证速度，也不会让事件循环被突发流量打满。
    * （{@code ServerPlayNetworking.send} 内部会切到 netty 事件循环，跨线程调用是安全的。）
    */
   public static void sendAll(ServerPlayerEntity player, String songId, byte[] audio) {
      int total = (audio.length + CHUNK_SIZE - 1) / CHUNK_SIZE;
      final String id = songId;

      Thread sender = new Thread(() -> {
         for (int i = 0; i < total; i++) {
            int offset = i * CHUNK_SIZE;
            int length = Math.min(CHUNK_SIZE, audio.length - offset);
            byte[] part = Arrays.copyOfRange(audio, offset, offset + length);

            try {
               send(player, id, i, total, part);
            } catch (Exception e) {
               com.simpmusic.server.SimpMusicServer.LOGGER.warn(
                  "[Audio] 分包下发失败 seq={}/{}: {}", i, total, e.getMessage()
               );
               return;
            }

            // 每 4 块（约 400KB）让出一次，避免独占事件循环
            if ((i & 3) == 3) {
               try {
                  Thread.sleep(10L);
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  return;
               }
            }
         }

         com.simpmusic.server.SimpMusicServer.LOGGER.info("[Audio] 分包下发完成: {} ({} 块)", id, total);
      }, "SimpMusic-AudioRelay");

      sender.setDaemon(true);
      sender.start();
   }

   private static void send(ServerPlayerEntity player, String songId, int seq, int total, byte[] part) {
      PacketByteBuf buf = PacketByteBufs.create();
      buf.writeString(songId);
      buf.writeInt(seq);
      buf.writeInt(total);
      buf.writeByteArray(part);
      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
