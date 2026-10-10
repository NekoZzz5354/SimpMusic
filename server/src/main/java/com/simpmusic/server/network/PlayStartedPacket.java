package com.simpmusic.server.network;

import com.simpmusic.server.SimpMusicServer;
import com.simpmusic.server.MusicQueueManager;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 客户端→服务端：告知"本端已实际开始播放"。
 * 用于校准服务端歌词/进度计时（客户端下载+解码存在数秒延迟，
 * 若以广播时刻起算，歌词会领先音乐）。
 */
public record PlayStartedPacket(String songId) {
   public static final Identifier TYPE = new Identifier("simpmusic", "play_started");

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
      String songId = buf.readString();
      int playedSeconds = buf.readInt();
      server.execute(() -> MusicQueueManager.onClientPlayStarted(player, songId, playedSeconds));
   }
}
