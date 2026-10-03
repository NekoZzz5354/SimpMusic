package com.simpmusic.server.network;

import com.simpmusic.server.SimpMusicServer;
import com.simpmusic.server.NeteaseApiClient;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public record RequestSearchPacket(String keyword) {
   public static final Identifier TYPE = new Identifier("simpmusic", "request_search");

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
      String keyword = buf.readString();
      SimpMusicServer.LOGGER.debug("Player {} searching: {}", player.getName().getString(), keyword);
      NeteaseApiClient.searchAsync(keyword, songs -> SearchResultPacket.send(player, songs));
   }
}
