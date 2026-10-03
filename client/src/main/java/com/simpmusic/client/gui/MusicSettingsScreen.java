package com.simpmusic.client.gui;

import com.simpmusic.client.ClientConfig;
import com.simpmusic.client.SimpMusicClient;
import com.simpmusic.client.audio.MusicAudioStream;
import com.simpmusic.client.update.UpdateChecker;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

public class MusicSettingsScreen extends Screen {
   private final Screen parent;
   private TextFieldWidget volumeField;
   private TextFieldWidget bitrateField;
   private ButtonWidget updateToggleButton;
   private TextFieldWidget apiUrlField;
   private TextFieldWidget tokenField;
   private TextFieldWidget intervalField;
   private ButtonWidget mtrCompatButton;
   private ButtonWidget backButton;
   private boolean mtrCompatMode;
   private String statusMessage = "";
   private int statusColor = 11184810;

   public MusicSettingsScreen(Screen parent) {
      super(Text.literal("SimpMusic 设置"));
      this.parent = parent;
      this.mtrCompatMode = ClientConfig.isMtrCompatMode();
   }

   protected void init() {
      int centerX = this.width / 2;
      this.volumeField = new TextFieldWidget(
         this.textRenderer, centerX - 100, this.height / 2 - 80, 200, 20, Text.literal(String.valueOf(ClientConfig.getVolumeMultiplier()))
      );
      this.volumeField.setMaxLength(5);
      this.addDrawableChild(this.volumeField);
      this.bitrateField = new TextFieldWidget(
         this.textRenderer, centerX - 100, this.height / 2 - 50, 200, 20, Text.literal(String.valueOf(ClientConfig.getDefaultBitrate()))
      );
      this.bitrateField.setMaxLength(10);
      this.addDrawableChild(this.bitrateField);
      boolean enabled = UpdateChecker.isEnabled();
      this.updateToggleButton = ButtonWidget.builder(
            Text.literal(enabled ? "§a✓ 更新检测: 开" : "§c✗ 更新检测: 关"),
            button -> {
               boolean newState = !UpdateChecker.isEnabled();
               UpdateChecker.setEnabled(newState);
               button.setMessage(Text.literal(newState ? "§a✓ 更新检测: 开" : "§c✗ 更新检测: 关"));
               this.setStatus(newState ? "已启用更新检测" : "已禁用更新检测", 5635925);
            }
         )
         .dimensions(centerX - 100, this.height / 2 - 15, 200, 20)
         .build();
      this.addDrawableChild(this.updateToggleButton);
      this.apiUrlField = new TextFieldWidget(this.textRenderer, centerX - 100, this.height / 2 + 15, 200, 20, Text.literal(UpdateChecker.getApiBaseUrl()));
      this.apiUrlField.setMaxLength(200);
      this.addDrawableChild(this.apiUrlField);
      this.tokenField = new TextFieldWidget(
         this.textRenderer, centerX - 100, this.height / 2 + 45, 200, 20, Text.literal(UpdateChecker.getToken().isEmpty() ? "" : UpdateChecker.getToken())
      );
      this.tokenField.setMaxLength(100);
      this.addDrawableChild(this.tokenField);
      this.intervalField = new TextFieldWidget(
         this.textRenderer, centerX - 100, this.height / 2 + 75, 200, 20, Text.literal(String.valueOf(ClientConfig.getUpdateIntervalMinutes()))
      );
      this.intervalField.setMaxLength(5);
      this.addDrawableChild(this.intervalField);
      ButtonWidget saveBtn = ButtonWidget.builder(Text.literal("§a\ud83d\udcbe 保存").formatted(Formatting.GREEN), button -> this.saveAll())
         .dimensions(centerX - 100, this.height / 2 + 105, 95, 20)
         .build();
      this.addDrawableChild(saveBtn);
      ButtonWidget checkBtn = ButtonWidget.builder(Text.literal("\ud83d\udd04 检查更新"), button -> {
         this.setStatus("正在检查更新...", 11184810);
         UpdateChecker.manualCheck();
         new Thread(() -> {
            try {
               Thread.sleep(3000L);
            } catch (InterruptedException var2x) {
            }

            MinecraftClient.getInstance().execute(() -> {
               if (UpdateChecker.isUpdateAvailable()) {
                  this.setStatus("发现新版本: v" + UpdateChecker.getLatestVersion(), 16776960);
               } else {
                  this.setStatus("已是最新版本", 5635925);
               }
            });
         }).start();
      }).dimensions(centerX + 5, this.height / 2 + 105, 95, 20).build();
      this.addDrawableChild(checkBtn);
      String mtrBtnText = this.mtrCompatMode ? "MTR兼容: 开 ✓" : "MTR兼容: 关";
      this.mtrCompatButton = ButtonWidget.builder(Text.literal(mtrBtnText), button -> {
         this.mtrCompatMode = !this.mtrCompatMode;
         ClientConfig.setMtrCompatMode(this.mtrCompatMode);
         button.setMessage(Text.literal(this.mtrCompatMode ? "MTR兼容: 开 ✓" : "MTR兼容: 关"));
      }).dimensions(centerX - 100, this.height / 2 + 135, 200, 20).build();
      this.addDrawableChild(this.mtrCompatButton);
      this.backButton = ButtonWidget.builder(Text.literal("返回"), button -> this.client.setScreen(this.parent))
         .dimensions(centerX - 50, this.height / 2 + 165, 100, 20)
         .build();
      this.addDrawableChild(this.backButton);
   }

   private void saveAll() {
      try {
         float vol = Float.parseFloat(this.volumeField.getText().trim());
         ClientConfig.setVolumeMultiplier(Math.max(0.1F, Math.min(2.0F, vol)));
         MusicAudioStream.setVolumeMultiplier(Math.max(0.1F, Math.min(2.0F, vol)));
         int br = Integer.parseInt(this.bitrateField.getText().trim());
         if (br >= 64000 && br <= 320000) {
            ClientConfig.setDefaultBitrate(br);
         }

         String url = this.apiUrlField.getText().trim();
         if (!url.isEmpty()) {
            UpdateChecker.setApiBaseUrl(url);
         }

         String token = this.tokenField.getText().trim();
         UpdateChecker.setToken(token);

         try {
            int interval = Integer.parseInt(this.intervalField.getText().trim());
            ClientConfig.setUpdateIntervalMinutes(Math.max(1, interval));
         } catch (NumberFormatException var6) {
         }

         this.setStatus("✓ 所有设置已保存", 5635925);
      } catch (Exception e) {
         this.setStatus("✗ 保存失败: " + e.getMessage(), 16733525);
      }
   }

   private void setStatus(String msg, int color) {
      this.statusMessage = msg;
      this.statusColor = color;
   }

   public void render(DrawContext context, int mouseX, int mouseY, float delta) {
      this.renderBackground(context, mouseX, mouseY, delta);
      Text title = Text.literal("\ud83c\udfb5 SimpMusic 设置 v" + SimpMusicClient.getVersion()).formatted(new Formatting[]{Formatting.GOLD, Formatting.BOLD});
      context.drawCenteredTextWithShadow(this.textRenderer, title, this.width / 2, this.height / 2 - 110, 16777215);
      if (SimpMusicClient.isMTRDetected()) {
         Text mtr = Text.literal("§a✓ 已检测到 MTR v" + SimpMusicClient.getMTRVersion()).formatted(Formatting.DARK_GREEN);
         context.drawCenteredTextWithShadow(this.textRenderer, mtr, this.width / 2, this.height / 2 - 98, 43520);
      }

      if (UpdateChecker.isUpdateAvailable()) {
         Text up = Text.literal("§e⬆ 发现新版本: v" + UpdateChecker.getLatestVersion()).formatted(Formatting.YELLOW);
         context.drawCenteredTextWithShadow(this.textRenderer, up, this.width / 2, this.height / 2 - 86, 16776960);
      }

      super.render(context, mouseX, mouseY, delta);
      if (!this.statusMessage.isEmpty()) {
         context.drawCenteredTextWithShadow(this.textRenderer, Text.literal(this.statusMessage), this.width / 2, this.height / 2 + 195, this.statusColor);
      }

      Text hint = Text.literal("提示: Token 可提高 GitHub API 限流额度 (5000次/小时)").formatted(Formatting.DARK_GRAY);
      context.drawCenteredTextWithShadow(this.textRenderer, hint, this.width / 2, this.height - 25, 8947848);
   }
}
