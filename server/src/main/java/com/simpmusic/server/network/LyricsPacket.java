package com.simpmusic.server.network;

import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 服务端 → 客户端：下发整首歌的歌词（含翻译）。
 *
 * <p>歌词在服务端一次性解析为「时间戳 → 原文 + 译文」的行列表后整体下发，
 * 客户端只需按播放进度取行渲染，不必自己做 LRC 解析，避免两端解析差异。
 * 切换歌曲时服务端会再次下发新的歌词包；同一首歌内不会重复下发。
 */
public record LyricsPacket(String songId, List<Line> lines) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "lyrics");

   /** 一行歌词：时间戳（毫秒）、原文、译文（无翻译时为空串） */
   public record Line(long timeMs, String text, String translation) {
   }

   public static void send(ServerPlayerEntity player, String songId, List<Line> lines) {
      PacketByteBuf buf = PacketByteBufs.create();
      buf.writeString(songId);
      buf.writeInt(lines.size());

      for (Line line : lines) {
         buf.writeLong(line.timeMs());
         buf.writeString(line.text() != null ? line.text() : "");
         buf.writeString(line.translation() != null ? line.translation() : "");
      }

      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
