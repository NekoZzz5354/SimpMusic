package com.simpmusic.client.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sound.AudioStream;

public class StreamingAudioStream implements AudioStream {
   private final AudioFormat format;
   private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>();
   private volatile boolean eof = false;
   private byte[] current;
   private int currentPos;

   public StreamingAudioStream(AudioFormat format) {
      this.format = format;
   }

   public void push(byte[] data) {
      if (data != null && data.length > 0 && !this.eof) {
         this.queue.offer(data);
      }
   }

   public void finish() {
      this.eof = true;
   }

   /** 数据源是否已全部产出完毕（用于区分"真正结束"与"数据暂时未就绪"） */
   public boolean isEof() {
      return this.eof;
   }

   public AudioFormat getFormat() {
      return this.format;
   }

   public ByteBuffer getBuffer(int size) throws IOException {
      ByteBuffer out = ByteBuffer.allocateDirect(size);
      int total = 0;
      // 等待窗口保持很短：本方法由渲染线程每帧调用，长时间阻塞会卡顿；
      // 数据未就绪时快速返回 null，下一帧再取
      long deadline = System.currentTimeMillis() + 100L;

      while (total < size) {
         if (this.current == null || this.currentPos >= this.current.length) {
            if (this.queue.isEmpty()) {
               if (this.eof || System.currentTimeMillis() > deadline) {
                  break;
               }

               try {
                  byte[] chunk = this.queue.poll(10L, TimeUnit.MILLISECONDS);
                  if (chunk != null) {
                     this.current = chunk;
                     this.currentPos = 0;
                  }
                  continue;
               } catch (InterruptedException e) {
                  Thread.currentThread().interrupt();
                  break;
               }
            }

            this.current = this.queue.poll();
            this.currentPos = 0;
         }

         int n = Math.min(size - total, this.current.length - this.currentPos);
         out.put(this.current, this.currentPos, n);
         this.currentPos += n;
         total += n;
      }

      out.flip();
      return total == 0 ? null : out;
   }

   public void close() throws IOException {
      this.eof = true;
      this.queue.clear();
      this.current = null;
   }
}
