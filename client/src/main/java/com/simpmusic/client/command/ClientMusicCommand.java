package com.simpmusic.client.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.simpmusic.client.ClientConfig;
import com.simpmusic.client.SimpMusicClient;
import com.simpmusic.client.NeteaseApiClient;
import com.simpmusic.client.audio.MusicAudioStream;
import com.simpmusic.client.gui.MusicScreen;
import com.simpmusic.client.gui.MusicSettingsScreen;
import com.simpmusic.client.update.UpdateChecker;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.client.MinecraftClient;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;

@Environment(EnvType.CLIENT)
public class ClientMusicCommand {
   public static void register() {
      ClientCommandRegistrationCallback.EVENT.register((ClientCommandRegistrationCallback)(dispatcher, registryAccess) -> registerCommands(dispatcher));
   }

   private static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommandManager.literal(
                                       "simpmusic"
                                    )
                                    .then(ClientCommandManager.literal("gui").executes(ctx -> {
                                       ((FabricClientCommandSource)ctx.getSource()).getClient().setScreen(new MusicScreen(Text.literal("SimpMusic")));
                                       return 1;
                                    })))
                                 .then(ClientCommandManager.literal("settings").executes(ctx -> {
                                    ((FabricClientCommandSource)ctx.getSource()).getClient().setScreen(new MusicSettingsScreen(null));
                                    return 1;
                                 })))
                              .then(ClientCommandManager.literal("stop").executes(ctx -> {
                                 MusicAudioStream.stop();
                                 SimpMusicClient.setPlaying(false, "");
                                 ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§c⏹ 已停止本地播放").formatted(Formatting.RED));
                                 return 1;
                              })))
                           .then(
                              ClientCommandManager.literal("search")
                                 .then(ClientCommandManager.argument("keyword", StringArgumentType.greedyString()).executes(ctx -> {
                                    String kw = StringArgumentType.getString(ctx, "keyword");
                                    MinecraftClient mc = ((FabricClientCommandSource)ctx.getSource()).getClient();
                                    if (mc != null && mc.getNetworkHandler() != null) {
                                       mc.getNetworkHandler().sendCommand("music search " + kw);
                                    }
                                    return 1;
                                 }))
                           ))
                        .then(
                           ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)ClientCommandManager.literal(
                                             "update"
                                          )
                                          .executes(ctx -> {
                                             UpdateChecker.manualCheck();
                                             return 1;
                                          }))
                                       .then(ClientCommandManager.literal("enable").executes(ctx -> {
                                          UpdateChecker.setEnabled(true);
                                          ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a✓ 更新检测已启用").formatted(Formatting.GREEN));
                                          return 1;
                                       })))
                                    .then(ClientCommandManager.literal("disable").executes(ctx -> {
                                       UpdateChecker.setEnabled(false);
                                       ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e更新检测已禁用").formatted(Formatting.YELLOW));
                                       return 1;
                                    })))
                                 .then(
                                    ClientCommandManager.literal("token")
                                       .then(ClientCommandManager.argument("token", StringArgumentType.string()).executes(ctx -> {
                                          String token = StringArgumentType.getString(ctx, "token");
                                          UpdateChecker.setToken(token);
                                          ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a✓ Token 已保存").formatted(Formatting.GREEN));
                                          return 1;
                                       }))
                                 ))
                              .then(
                                 ClientCommandManager.literal("url")
                                    .then(ClientCommandManager.argument("apiurl", StringArgumentType.string()).executes(ctx -> {
                                       String url = StringArgumentType.getString(ctx, "apiurl");
                                       UpdateChecker.setApiBaseUrl(url);
                                       ((FabricClientCommandSource)ctx.getSource())
                                          .sendFeedback(Text.literal("§a✓ API 地址已更新: " + url).formatted(Formatting.GREEN));
                                       return 1;
                                    }))
                              )
                        ))
                     .then(((LiteralArgumentBuilder)ClientCommandManager.literal("api").executes(ctx -> {
                        ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e当前音乐 API: §b" + NeteaseApiClient.getApiBaseUrl()));
                        return 1;
                     })).then(ClientCommandManager.argument("url", StringArgumentType.string()).executes(ctx -> {
                        String url = StringArgumentType.getString(ctx, "url");
                        NeteaseApiClient.setApiBaseUrl(url);
                        ClientConfig.setMusicApiUrl(url);
                        ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a✓ 音乐 API 已设置: §b" + url).formatted(Formatting.GREEN));
                        return 1;
                     }))))
                  .then(ClientCommandManager.literal("reload").executes(ctx -> {
                     ClientConfig.load();
                     NeteaseApiClient.setApiBaseUrl(ClientConfig.getMusicApiUrl());
                     ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§a✓ 客户端配置已重新加载").formatted(Formatting.GREEN));
                     return 1;
                  })))
               .then(
                  ClientCommandManager.literal("status")
                     .executes(
                        ctx -> {
                           ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§6=== SimpMusic 状态 ===").formatted(Formatting.GOLD));
                           ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e版本: §b" + SimpMusicClient.getVersion()));
                           ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e音乐 API: §b" + NeteaseApiClient.getApiBaseUrl()));
                           ((FabricClientCommandSource)ctx.getSource())
                              .sendFeedback(
                                 Text.literal("§eMTR 检测: " + (SimpMusicClient.isMTRDetected() ? "§a✓ 是 (v" + SimpMusicClient.getMTRVersion() + ")" : "§7否"))
                              );
                           ((FabricClientCommandSource)ctx.getSource())
                              .sendFeedback(Text.literal("§e更新检测: " + (UpdateChecker.isEnabled() ? "§a✓ 启用" : "§c✗ 禁用")));
                           if (UpdateChecker.isUpdateAvailable()) {
                              ((FabricClientCommandSource)ctx.getSource())
                                 .sendFeedback(Text.literal("§e最新版本: §a" + UpdateChecker.getLatestVersion()).formatted(Formatting.YELLOW));
                           }

                           return 1;
                        }
                     )
               ))
            .then(ClientCommandManager.literal("help").executes(ctx -> {
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§6=== SimpMusic 客户端命令 ===").formatted(Formatting.GOLD));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic gui §7- 打开点歌界面"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic settings §7- 打开设置"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic stop §7- 停止本地播放"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic search <kw> §7- 搜索歌曲"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic api [url] §7- 查看/设置音乐 API"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic reload §7- 重新加载配置"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic update §7- 检查更新"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic update enable|disable §7- 启用/禁用更新"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic update token <token> §7- 设置 GitHub Token"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic update url <url> §7- 设置更新 API 地址"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic status §7- 查看状态"));
               ((FabricClientCommandSource)ctx.getSource()).sendFeedback(Text.literal("§e/simpmusic help §7- 显示帮助"));
               return 1;
            }))
      );
   }
}
