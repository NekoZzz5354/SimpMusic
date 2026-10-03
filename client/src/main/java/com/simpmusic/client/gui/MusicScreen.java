package com.simpmusic.client.gui;

import com.simpmusic.client.SimpMusicClient;
import com.simpmusic.client.NeteaseApiClient;
import com.simpmusic.client.update.UpdateChecker;
import java.util.ArrayList;
import java.util.List;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.network.PacketByteBuf;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MusicScreen extends Screen {
   private TextFieldWidget searchField;
   private ButtonWidget searchButton;
   private ButtonWidget closeButton;
   private ButtonWidget playButton;
   private ButtonWidget stopButton;
   private ButtonWidget skipButton;
   private ButtonWidget updateButton;
   private static List<NeteaseApiClient.SongInfo> searchResults = new ArrayList<>();
   private static String statusText = "";
   private static boolean isSearching = false;
   private static String updateStatus = "";
   private static boolean updateAvailable = false;

   public MusicScreen(Text title) {
      super(title);
   }

   protected void init() {
      int centerX = this.width / 2;
      int centerY = this.height / 2;
      this.searchField = new TextFieldWidget(this.textRenderer, centerX - 150, centerY - 100, 250, 20, Text.literal("搜索歌曲..."));
      this.searchField.setMaxLength(100);
      this.addDrawableChild(this.searchField);
      this.searchButton = ButtonWidget.builder(Text.literal("\ud83d\udd0d 搜索"), button -> this.performSearch())
         .dimensions(centerX + 110, centerY - 100, 80, 20)
         .build();
      this.addDrawableChild(this.searchButton);
      this.playButton = ButtonWidget.builder(Text.literal("▶ 播放"), button -> runServerCommand("music queue"))
         .dimensions(centerX - 150, centerY - 70, 80, 20)
         .build();
      this.addDrawableChild(this.playButton);
      this.stopButton = ButtonWidget.builder(Text.literal("⏹ 停止"), button -> runServerCommand("music stop"))
         .dimensions(centerX - 60, centerY - 70, 80, 20)
         .build();
      this.addDrawableChild(this.stopButton);
      this.skipButton = ButtonWidget.builder(Text.literal("⏭ 跳过"), button -> runServerCommand("music skip"))
         .dimensions(centerX + 30, centerY - 70, 80, 20)
         .build();
      this.addDrawableChild(this.skipButton);
      boolean hasUpdate = UpdateChecker.isUpdateAvailable();
      String updateBtnText = hasUpdate ? "⬆ 更新可用!" : "\ud83d\udd04 检查更新";
      this.updateButton = ButtonWidget.builder(Text.literal(updateBtnText), button -> {
         UpdateChecker.manualCheck();
         statusText = "正在检查更新...";
      }).dimensions(centerX + 110, centerY - 70, 80, 20).build();
      this.addDrawableChild(this.updateButton);
      this.closeButton = ButtonWidget.builder(Text.literal("关闭"), button -> this.close())
         .dimensions(centerX - 50, centerY + 60, 100, 20)
         .build();
      this.addDrawableChild(this.closeButton);
      if (hasUpdate) {
         String dlUrl = UpdateChecker.getDownloadUrl();
         if (dlUrl != null) {
            ButtonWidget dlBtn = ButtonWidget.builder(Text.literal("⬇ 复制下载链接"), button -> {
               try {
                  MinecraftClient.getInstance().keyboard.setClipboard(dlUrl);
                  statusText = "下载链接已复制到剪贴板";
               } catch (Exception e) {
                  SimpMusicClient.LOGGER.debug("Cannot copy to clipboard: {}", e.getMessage());
                  statusText = "请手动访问: " + dlUrl;
               }
            }).dimensions(centerX - 50, centerY + 85, 100, 20).build();
            this.addDrawableChild(dlBtn);
         }
      }
   }

   private void runServerCommand(String command) {
      MinecraftClient client = SimpMusicClient.getClient();
      if (client != null && client.getNetworkHandler() != null) {
         client.getNetworkHandler().sendCommand(command);
      }
   }

   private void performSearch() {
      String keyword = this.searchField.getText().trim();
      if (!keyword.isEmpty()) {
         isSearching = true;
         statusText = "搜索中...";
         NeteaseApiClient.search(keyword).thenAccept(results -> {
            searchResults = results != null ? results : new ArrayList<>();
            isSearching = false;
            statusText = searchResults.isEmpty() ? "未找到结果" : "找到 " + searchResults.size() + " 首歌曲";
         });
      }
   }

   public void tick() {
      super.tick();
   }

   public void render(DrawContext context, int mouseX, int mouseY, float delta) {
      this.renderBackground(context, mouseX, mouseY, delta);
      Text titleText = Text.literal("\ud83c\udfb5 SimpMusic 点歌台 v" + SimpMusicClient.getVersion()).formatted(new Formatting[]{Formatting.GOLD, Formatting.BOLD});
      context.drawCenteredTextWithShadow(this.textRenderer, titleText, this.width / 2, this.height / 2 - 130, 16777215);
      if (SimpMusicClient.isMTRDetected()) {
         Text mtrText = Text.literal("§a✓ MTR 兼容模式已启用 (v" + SimpMusicClient.getMTRVersion() + ")").formatted(Formatting.DARK_GREEN);
         context.drawCenteredTextWithShadow(this.textRenderer, mtrText, this.width / 2, this.height / 2 - 118, 43520);
      }

      if (SimpMusicClient.isPlaying()) {
         Text playingText = Text.literal("▶ 正在播放: " + SimpMusicClient.getCurrentSongTitle()).formatted(Formatting.GREEN);
         context.drawCenteredTextWithShadow(this.textRenderer, playingText, this.width / 2, this.height / 2 - 105, 5635925);
      }

      super.render(context, mouseX, mouseY, delta);
      int y = this.height / 2 - 40;
      if (isSearching) {
         context.drawTextWithShadow(this.textRenderer, "搜索中...", this.width / 2 - 100, y, 11184810);
      } else if (!searchResults.isEmpty()) {
         context.drawTextWithShadow(this.textRenderer, "搜索结果 (点击点歌):", this.width / 2 - 150, y - 15, 16777215);

         for (int i = 0; i < Math.min(searchResults.size(), 8); i++) {
            NeteaseApiClient.SongInfo song = searchResults.get(i);
            String line = String.format("%d. %s - %s", i + 1, song.title, song.artist);
            int color = 14540253;
            if (mouseX >= this.width / 2 - 150 && mouseX <= this.width / 2 + 150 && mouseY >= y + i * 18 && mouseY <= y + i * 18 + 16) {
               color = 5635925;
               line = "> " + line + " <";
            }

            context.drawTextWithShadow(this.textRenderer, line, this.width / 2 - 150, y + i * 18, color);
         }
      } else if (!statusText.isEmpty()) {
         context.drawTextWithShadow(this.textRenderer, statusText, this.width / 2 - 100, y, 11184810);
      }

      Text hint = Text.literal("提示: 按 ESC 关闭 | /music help 查看命令 | G键 打开菜单").formatted(Formatting.DARK_GRAY);
      context.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2, this.height - 30, 8947848);
   }

   public boolean mouseClicked(double mouseX, double mouseY, int button) {
      int y = this.height / 2 - 40;

      for (int i = 0; i < Math.min(searchResults.size(), 8); i++) {
         if (mouseX >= this.width / 2 - 150 && mouseX <= this.width / 2 + 150 && mouseY >= y + i * 18 && mouseY <= y + i * 18 + 16) {
            NeteaseApiClient.SongInfo song = searchResults.get(i);
            runServerCommand(String.format("music request %s %s", song.id, song.title));
            statusText = "已点歌: " + song.title;
            return true;
         }
      }

      return super.mouseClicked(mouseX, mouseY, button);
   }

   public boolean shouldPause() {
      return false;
   }

   public static void setSearchResults(int count, PacketByteBuf buf) {
      searchResults.clear();

      for (int i = 0; i < count; i++) {
         NeteaseApiClient.SongInfo s = new NeteaseApiClient.SongInfo();
         s.id = buf.readString();
         s.title = buf.readString();
         s.artist = buf.readString();
         s.album = buf.readString();
         s.coverUrl = buf.readString();
         s.duration = buf.readInt();
         searchResults.add(s);
      }
   }

   public static void setUpdateStatus(String status, boolean available) {
      updateStatus = status != null ? status : "";
      updateAvailable = available;
   }
}
