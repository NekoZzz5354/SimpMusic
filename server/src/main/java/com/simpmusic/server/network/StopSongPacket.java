package com.simpmusic.server.network;

import net.fabricmc.fabric.api.networking.v1.PacketByteBufs;
import net.fabricmc.fabric.api.networking.v1.PacketSender;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayNetworkHandler;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.Identifier;

public record StopSongPacket() {
   public static final Identifier TYPE = new Identifier("simpmusic", "stop_song");

   public static void send(ServerPlayerEntity player) {
      PacketByteBuf buf = PacketByteBufs.create();
      ServerPlayNetworking.send(player, TYPE, buf);
   }

   public static void handle(
      MinecraftServer server, ServerPlayerEntity player, ServerPlayNetworkHandler handler, PacketByteBuf buf, PacketSender responseSender
   ) {
   }
}
