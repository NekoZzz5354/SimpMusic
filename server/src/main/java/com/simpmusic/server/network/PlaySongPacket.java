package com.simpmusic.server.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 服务端 → 客户端：开始播放指定歌曲。
 *
 * <p>v1.2.0 起随包下发 HUD 所需的全套元数据：专辑名、点歌人、时长，
 * 配合封面 {@code coverUrl} 让客户端在屏幕左上角渲染完整的歌曲信息卡片。
 * {@code startOffsetSeconds} 为起始播放位置（秒）：新歌为 0；中途加入/重连的玩家按当前进度跳过，
 * 实现无缝接续而不是从头播放。
 */
public record PlaySongPacket(
   String songId,
   String title,
   String artist,
   String url,
   String coverUrl,
   String album,
   String requester,
   int durationSeconds,
   int startOffsetSeconds
) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "play_song");

   public static void send(
      ServerPlayerEntity player,
      String songId,
      String title,
      String artist,
      String url,
      String coverUrl,
      String album,
      String requester,
      int durationSeconds,
      int startOffsetSeconds
   ) {
      PacketByteBuf buf = PacketByteBufs.create();
      buf.writeString(songId);
      buf.writeString(title);
      buf.writeString(artist);
      buf.writeString(url);
      buf.writeString(coverUrl != null ? coverUrl : "");
      buf.writeString(album != null ? album : "");
      buf.writeString(requester != null ? requester : "");
      buf.writeInt(Math.max(0, durationSeconds));
      buf.writeInt(Math.max(0, startOffsetSeconds));
      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
