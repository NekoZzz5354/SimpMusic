package com.simpmusic.client.audio;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import net.minecraft.client.sound.Sound;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.client.sound.SoundManager;
import net.minecraft.client.sound.WeightedSoundSet;
import net.minecraft.client.sound.SoundInstance.AttenuationType;
import net.minecraft.sound.SoundCategory;
import net.minecraft.util.Identifier;

public class MusicSoundInstance implements SoundInstance {
   private static boolean tickEnabled = true;
   private final Identifier soundId;
   private final String title;
   private final String artist;
   private final AudioInputStream audioStream;
   private final AudioFormat format;
   private final float volume;
   private final float pitch;
   private final SoundCategory category;
   private boolean done = false;
   private boolean hasStarted = false;
   private final byte[] buffer;
   private int bufferOffset = 0;
   private int bufferLength = 0;

   public MusicSoundInstance(String title, String artist, AudioInputStream audioStream, AudioFormat format, float volume, SoundCategory category) {
      this.soundId = new Identifier("simpmusic", "music_stream_" + System.currentTimeMillis() + "_" + Math.random());
      this.title = title != null ? title : "Unknown";
      this.artist = artist != null ? artist : "Unknown";
      this.audioStream = audioStream;
      this.format = format;
      this.volume = Math.max(0.0F, Math.min(2.0F, volume));
      this.pitch = 1.0F;
      this.category = category != null ? category : SoundCategory.MASTER;
      int bufSize = MusicAudioStream.getBufferSize();
      this.buffer = new byte[bufSize];
      this.bufferLength = 0;
      this.bufferOffset = 0;
      this.preloadBuffer();
   }

   public static void setTickEnabled(boolean enabled) {
      tickEnabled = enabled;
   }

   public Identifier getId() {
      return this.soundId;
   }

   public SoundCategory getCategory() {
      return this.category;
   }

   public float getVolume() {
      return this.volume;
   }

   public float getPitch() {
      return this.pitch;
   }

   public double getX() {
      return 0.0;
   }

   public double getY() {
      return 0.0;
   }

   public double getZ() {
      return 0.0;
   }

   public boolean isRepeatable() {
      return false;
   }

   public int getRepeatDelay() {
      return 0;
   }

   public boolean isRelative() {
      return true;
   }

   public WeightedSoundSet getSoundSet(SoundManager soundManager) {
      return null;
   }

   public Sound getSound() {
      return null;
   }

   public AttenuationType getAttenuationType() {
      return AttenuationType.NONE;
   }

   public void tick() {
      if (tickEnabled) {
         if (!this.done) {
            try {
               if (this.bufferOffset >= this.bufferLength) {
                  this.preloadBuffer();
               }

               if (this.bufferLength <= 0) {
                  this.done = true;
                  this.closeStream();
               }
            } catch (Exception e) {
               this.done = true;
               this.closeStream();
            }
         }
      }
   }

   private void preloadBuffer() {
      try {
         this.bufferOffset = 0;
         this.bufferLength = this.audioStream.read(this.buffer);
         if (this.bufferLength <= 0) {
            this.bufferLength = 0;
            this.done = true;
            this.closeStream();
         }
      } catch (Exception e) {
         this.bufferLength = 0;
         this.done = true;
      }
   }

   private void closeStream() {
      try {
         this.audioStream.close();
      } catch (Exception var2) {
      }
   }

   public AudioInputStream getAudioStream() {
      return this.audioStream;
   }

   public AudioFormat getAudioFormat() {
      return this.format;
   }

   public String getTitle() {
      return this.title;
   }

   public String getArtist() {
      return this.artist;
   }

   public byte[] getBuffer() {
      return this.buffer;
   }

   public int getBufferLength() {
      return this.bufferLength;
   }

   public int getBufferOffset() {
      return this.bufferOffset;
   }

   public void advanceBuffer(int bytes) {
      this.bufferOffset += bytes;
   }
}
