package com.simpmusic.client;

import com.simpmusic.client.audio.MusicAudioStream;
import com.simpmusic.client.command.ClientMusicCommand;
import com.simpmusic.client.gui.MusicScreen;
import com.simpmusic.client.hud.MusicHud;
import com.simpmusic.client.network.ClientNetworkHandler;
import com.simpmusic.client.update.UpdateChecker;
import java.util.Optional;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents.ClientStarted;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents.EndTick;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents.Join;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil.Type;
import net.minecraft.text.Text;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SimpMusicClient implements ClientModInitializer {
   public static final String MOD_ID = "simpmusic";
   public static final String MOD_NAME = "SimpMusic";
   public static final String VERSION = "1.2.1";
   public static final Logger LOGGER = LoggerFactory.getLogger("SimpMusic");
   private static KeyBinding openMusicScreenKey;
   private static boolean isPlaying = false;
   private static String currentSongTitle = "";
   private static boolean mtrDetected = false;
   private static String mtrVersion = "";

   public void onInitializeClient() {
      LOGGER.info("{} v{} (Client) initializing...", "SimpMusic", VERSION);
      this.detectMTR();

      try {
         String apiUrl = ClientConfig.getMusicApiUrl();
         NeteaseApiClient.setApiBaseUrl(apiUrl);
         LOGGER.info("Music API: {}", apiUrl);
      } catch (Exception e) {
         LOGGER.error("Failed to apply music API config: {}", e.getMessage());
      }

      try {
         ClientMusicCommand.register();
      } catch (Exception e) {
         LOGGER.error("Failed to register client commands: {}", e.getMessage());
      }

      ClientLifecycleEvents.CLIENT_STARTED.register((ClientStarted)client -> {
         try {
            MusicAudioStream.init();
            LOGGER.info("MusicAudioStream initialized after client start.");
         } catch (Exception e) {
            LOGGER.error("Failed to init audio system: {}", e.getMessage());
         }

         this.registerKeyBindings();
         UpdateChecker.init();
         if (UpdateChecker.isEnabled()) {
            ClientPlayConnectionEvents.JOIN.register((Join)(handler, sender, cli) -> new Thread(() -> {
               try {
                  Thread.sleep(90000L);
               } catch (InterruptedException var1x) {
               }

               UpdateChecker.checkForUpdates();
            }).start());
         }

         LOGGER.info("{} v{} (Client) fully started! MTR: {}", new Object[]{"SimpMusic", VERSION, isMTRDetected() ? "YES (" + mtrVersion + ")" : "NO"});
      });
      ClientNetworkHandler.register();

      // 歌曲信息 HUD（屏幕左上角：封面 + 曲目信息 + 歌词/翻译），替代 v1.1.x 的 BossBar 方案
      try {
         HudRenderCallback.EVENT.register((context, tickDelta) -> MusicHud.render(context, tickDelta));
         LOGGER.info("Music HUD renderer registered.");
      } catch (Exception e) {
         LOGGER.error("Failed to register music HUD: {}", e.getMessage());
      }

      LOGGER.info("{} v{} (Client) initialized successfully!", "SimpMusic", VERSION);
   }

   private void registerKeyBindings() {
      try {
         openMusicScreenKey = KeyBindingHelper.registerKeyBinding(new KeyBinding("key.simpmusic.open_menu", Type.KEYSYM, 71, "category.simpmusic.music"));
         ClientTickEvents.END_CLIENT_TICK.register((EndTick)client -> {
            if (openMusicScreenKey != null && openMusicScreenKey.wasPressed()) {
               this.openMusicScreen();
            }
         });
         LOGGER.info("Key bindings registered successfully.");
      } catch (Exception e) {
         LOGGER.error("Failed to register key bindings: {}", e.getMessage());
      }
   }

   private void openMusicScreen() {
      MinecraftClient client = MinecraftClient.getInstance();
      if (client != null) {
         if (isMTRDetected()) {
            client.execute(() -> client.setScreen(new MusicScreen(Text.literal("SimpMusic - 点歌"))));
         } else {
            client.setScreen(new MusicScreen(Text.literal("SimpMusic - 点歌")));
         }
      }
   }

   private void detectMTR() {
      try {
         Optional<ModContainer> mtrContainer = FabricLoader.getInstance().getModContainer("mtr");
         if (mtrContainer.isPresent()) {
            mtrVersion = mtrContainer.get().getMetadata().getVersion().getFriendlyString();
            mtrDetected = true;
            LOGGER.info("MTR detected: version {}", mtrVersion);
            this.applyMTRClientCompatibility();
         } else {
            mtrDetected = false;
            LOGGER.info("MTR not detected — running in standalone mode");
         }
      } catch (Exception e) {
         mtrDetected = false;
         LOGGER.debug("MTR detection skipped: {}", e.getMessage());
      }
   }

   private void applyMTRClientCompatibility() {
      LOGGER.info("MTR client compatibility patches applied:");
      LOGGER.info("  - Playback: OpenAL via SoundEngine (independent from MTR sound)");
      LOGGER.info("  - Key binding: END_CLIENT_TICK priority");
   }

   public static void setPlaying(boolean playing, String title) {
      isPlaying = playing;
      currentSongTitle = title != null ? title : "";
   }

   public static boolean isPlaying() {
      return isPlaying;
   }

   public static String getCurrentSongTitle() {
      return currentSongTitle;
   }

   public static MinecraftClient getClient() {
      return MinecraftClient.getInstance();
   }

   public static boolean isMTRDetected() {
      return mtrDetected;
   }

   public static String getMTRVersion() {
      return mtrVersion;
   }

   public static String getVersion() {
      return VERSION;
   }
}
