package com.simpmusic.client.audio;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import javax.sound.sampled.AudioFormat;
import net.minecraft.client.sound.AudioStream;

/**
 * 流式 PCM 缓冲。
 *
 * <p>v1.2.3 修复内存暴涨：此前队列是**无界**的 {@link LinkedBlockingQueue}，
 * 而解码线程会尽可能快地把整首歌解码成 PCM 并全部塞进队列；
 * 消费者（渲染线程每帧只取 0.25 秒）根本追不上，于是整首歌的 PCM 长期驻留内存——
 * 一首 4 分钟 44.1kHz 立体声 16bit 的歌约 **40MB+**，连着播几首就会把内存吃满。
 *
 * <p>现在队列改为**有界**并在写满时阻塞，形成背压：解码速度被限制到与播放速度相当，
 * 常驻内存只保留队列上限对应的几秒音频。
 */
public class StreamingAudioStream implements AudioStream {
   /** 队列上限（块数）。每块约 0.25 秒音频，24 块 ≈ 6 秒，足够喂满 OpenAL 缓冲 */
   private static final int MAX_QUEUED_CHUNKS = 24;
   /** 写满时的等待粒度（毫秒） */
   private static final long PUSH_WAIT_MS = 50L;

   private final AudioFormat format;
   private final BlockingQueue<byte[]> queue = new LinkedBlockingQueue<>(MAX_QUEUED_CHUNKS);
   private volatile boolean eof = false;
   private volatile boolean closed = false;
   private byte[] current;
   private int currentPos;

   public StreamingAudioStream(AudioFormat format) {
      this.format = format;
   }

   /**
    * 放入一块 PCM 数据。
    *
    * <p>队列满时会阻塞等待消费者取走（背压），避免把整首歌解码后全部堆在内存里。
    * 流已关闭/结束时立即返回，保证解码线程能及时退出。
    */
   public void push(byte[] data) {
      if (data == null || data.length == 0 || this.closed || this.eof) {
         return;
      }

      try {
         while (!this.closed && !this.eof && !this.queue.offer(data, PUSH_WAIT_MS, TimeUnit.MILLISECONDS)) {
            // 队列已满：等待播放端消费，形成背压
         }
      } catch (InterruptedException e) {
         Thread.currentThread().interrupt();
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
      // 先置 closed 再清队列：正在阻塞的 push 会立即返回，解码线程得以退出
      this.closed = true;
      this.eof = true;
      this.queue.clear();
      this.current = null;
   }
}
