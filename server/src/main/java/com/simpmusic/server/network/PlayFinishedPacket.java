package com.simpmusic.server.network;

import com.simpmusic.server.MusicQueueManager;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

/**
 * 客户端 → 服务端：本端音频已真正播放结束。
 *
 * <p>用于修正「VIP 歌曲播到一半就停了，但服务端仍显示正在播放」这类状态不同步：
 * 客户端是唯一知道音频何时结束的一方，播完即上报；
 * 服务端据此提前推进队列，而不再干等「时长 + 3 秒」超时。
 */
public record PlayFinishedPacket(String songId, int playedSeconds) {
   public static final Identifier TYPE = Identifier.of("simpmusic", "play_finished");

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
      String songId = buf.readString();
      int playedSeconds = buf.readInt();
      server.execute(() -> MusicQueueManager.onClientPlaybackFinished(player, songId, playedSeconds));
   }
}
