package com.simpmusic.server.network;

import com.simpmusic.server.MusicQueueManager;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public record SyncQueuePacket(List<MusicQueueManager.MusicEntry> queue, MusicQueueManager.MusicEntry current) {
   public static final Identifier TYPE = new Identifier("simpmusic", "sync_queue");

   public static void send(ServerPlayerEntity player, List<MusicQueueManager.MusicEntry> queue, MusicQueueManager.MusicEntry current) {
      PacketByteBuf buf = PacketByteBufs.create();
      buf.writeBoolean(current != null);
      if (current != null) {
         buf.writeString(current.songId);
         buf.writeString(current.title);
         buf.writeString(current.artist);
         buf.writeString(current.url);
      }

      buf.writeInt(queue.size());

      for (MusicQueueManager.MusicEntry e : queue) {
         buf.writeString(e.songId);
         buf.writeString(e.title);
         buf.writeString(e.artist);
         buf.writeString(e.requesterName);
      }

      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
