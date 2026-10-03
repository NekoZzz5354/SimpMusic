package com.simpmusic.server.network;

import com.simpmusic.server.NeteaseApiClient;
import java.util.List;
import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public record SearchResultPacket(List<NeteaseApiClient.SongInfo> songs) {
   public static final Identifier TYPE = new Identifier("simpmusic", "search_result");

   public static void send(ServerPlayerEntity player, List<NeteaseApiClient.SongInfo> songs) {
      PacketByteBuf buf = PacketByteBufs.create();
      buf.writeInt(songs.size());

      for (NeteaseApiClient.SongInfo s : songs) {
         buf.writeString(s.id);
         buf.writeString(s.title);
         buf.writeString(s.artist);
         buf.writeString(s.album != null ? s.album : "");
         buf.writeString(s.coverUrl != null ? s.coverUrl : "");
         buf.writeInt(s.duration);
      }

      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
