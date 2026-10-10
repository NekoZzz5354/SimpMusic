package com.simpmusic.server;

import com.simpmusic.server.network.LyricsPacket;
import com.simpmusic.server.network.PlaySongPacket;
import com.simpmusic.server.network.PlayStartedPacket;
import com.simpmusic.server.network.RequestSearchPacket;
import com.simpmusic.server.network.SearchResultPacket;
import com.simpmusic.server.network.StopSongPacket;
import com.simpmusic.server.network.SyncQueuePacket;
import com.simpmusic.server.update.UpdateChecker;
import java.util.Optional;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStarted;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents.ServerStopping;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpMusicServer implements ModInitializer {
   public static final String MOD_ID = "simpmusic";
   public static final String MOD_NAME = "SimpMusic";
   public static final String VERSION = "1.2.0";
   public static final Logger LOGGER = LoggerFactory.getLogger("SimpMusic");
   private static MinecraftServer serverInstance;

   public void onInitialize() {
      LOGGER.info("{} v{} (Server) initializing...", "SimpMusic", VERSION);
      this.checkMTRCompatibility();
      ModConfig.load();
      CookieManager.load();
      CommandRegistrationCallback.EVENT.register((CommandRegistrationCallback)(dispatcher, registryAccess, environment) -> MusicCommand.register(dispatcher));
      ServerPlayNetworking.registerGlobalReceiver(PlaySongPacket.TYPE, PlaySongPacket::handle);
      ServerPlayNetworking.registerGlobalReceiver(StopSongPacket.TYPE, StopSongPacket::handle);
      ServerPlayNetworking.registerGlobalReceiver(SyncQueuePacket.TYPE, SyncQueuePacket::handle);
      ServerPlayNetworking.registerGlobalReceiver(SearchResultPacket.TYPE, SearchResultPacket::handle);
      ServerPlayNetworking.registerGlobalReceiver(RequestSearchPacket.TYPE, RequestSearchPacket::handle);
      ServerPlayNetworking.registerGlobalReceiver(PlayStartedPacket.TYPE, PlayStartedPacket::handle);
      ServerPlayNetworking.registerGlobalReceiver(LyricsPacket.TYPE, LyricsPacket::handle);
      MusicQueueManager.init();
      ServerLifecycleEvents.SERVER_STARTED.register((ServerStarted)server -> {
         serverInstance = server;
         MusicQueueManager.setServer(server);
         LOGGER.info("{} v{} server started, SimpMusic ready!", "SimpMusic", VERSION);
         if (ModConfig.isUpdateCheckerEnabled()) {
            UpdateChecker.checkForUpdates();
         }
      });
      ServerLifecycleEvents.SERVER_STOPPING.register((ServerStopping)server -> {
         MusicQueueManager.stopAll();
         LOGGER.info("{} server stopping, cleaned up queue.", "SimpMusic");
      });
      LOGGER.info("{} v{} (Server) initialized successfully!", "SimpMusic", VERSION);
   }

   private void checkMTRCompatibility() {
      try {
         Optional<ModContainer> mtrContainer = FabricLoader.getInstance().getModContainer("mtr");
         if (mtrContainer.isPresent()) {
            String mtrVersion = mtrContainer.get().getMetadata().getVersion().getFriendlyString();
            LOGGER.info("Detected MTR (Minecraft Transit Railway) version: {}", mtrVersion);
            if (mtrVersion.contains("3.2.2")) {
               LOGGER.info("MTR 3.2.2 detected — applying compatibility patches for hotfix-1");
               this.applyMTR322Compatibility();
            }

            ModConfig.setMTRCompatible(true);
            ModConfig.setMTRVersion(mtrVersion);
         } else {
            LOGGER.info("MTR not detected — running in standalone mode");
            ModConfig.setMTRCompatible(false);
         }
      } catch (Exception e) {
         LOGGER.warn("MTR compatibility check skipped: {}", e.getMessage());
         ModConfig.setMTRCompatible(false);
      }
   }

   private void applyMTR322Compatibility() {
      MusicQueueManager.setTickDelay(2);
      ModConfig.setPreferredSoundCategory("MASTER");
      LOGGER.info("MTR 3.2.2 compatibility patches applied:");
      LOGGER.info("  - Queue tick delay: 2 ticks");
      LOGGER.info("  - Sound category: MASTER (avoid MTR RECORDS conflict)");
   }

   public static MinecraftServer getServer() {
      return serverInstance;
   }

   public static String getVersion() {
      return VERSION;
   }
}
