package com.simpmusic.client.audio;

import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import javax.sound.sampled.AudioFileFormat;
import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.UnsupportedAudioFileException;
import javax.sound.sampled.AudioFileFormat.Type;
import javax.sound.sampled.spi.AudioFileReader;

public class FlacAudioFileReader extends AudioFileReader {
   @Override
   public AudioFileFormat getAudioFileFormat(InputStream stream) throws IOException {
      AudioFormat format = new AudioFormat(44100.0F, 16, 2, true, false);
      return new AudioFileFormat(Type.WAVE, format, -1);
   }

   @Override
   public AudioFileFormat getAudioFileFormat(URL url) throws IOException {
      try (InputStream is = url.openStream()) {
         return this.getAudioFileFormat(new BufferedInputStream(is));
      }
   }

   @Override
   public AudioFileFormat getAudioFileFormat(File file) throws IOException {
      try (InputStream is = new FileInputStream(file)) {
         return this.getAudioFileFormat(new BufferedInputStream(is));
      }
   }

   @Override
   public AudioInputStream getAudioInputStream(InputStream stream) throws IOException, UnsupportedAudioFileException {
      return AudioSystem.getAudioInputStream(stream);
   }

   @Override
   public AudioInputStream getAudioInputStream(URL url) throws IOException, UnsupportedAudioFileException {
      return this.getAudioInputStream(new BufferedInputStream(url.openStream()));
   }

   @Override
   public AudioInputStream getAudioInputStream(File file) throws IOException, UnsupportedAudioFileException {
      return this.getAudioInputStream(new BufferedInputStream(new FileInputStream(file)));
   }
}
