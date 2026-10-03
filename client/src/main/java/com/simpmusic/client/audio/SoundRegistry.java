package com.simpmusic.client.audio;

import com.simpmusic.client.SimpMusicClient;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.sound.SoundEvent;
import net.minecraft.util.Identifier;

public class SoundRegistry {
   public static final Identifier MUSIC_STREAM_ID = Identifier.of("simpmusic", "music_stream");

   public static void registerSounds() {
      SoundEvent event = SoundEvent.of(MUSIC_STREAM_ID);
      Registry.register(Registries.SOUND_EVENT, MUSIC_STREAM_ID, event);
      SimpMusicClient.LOGGER.info("SimpMusic sound registered: {}", MUSIC_STREAM_ID);
   }
}
