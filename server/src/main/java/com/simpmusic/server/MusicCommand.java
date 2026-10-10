package com.simpmusic.server;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.simpmusic.server.update.UpdateChecker;
import java.util.List;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.Text;
import net.minecraft.text.ClickEvent.Action;
import net.minecraft.util.Formatting;

public class MusicCommand {
   public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
      dispatcher.register(
         (LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)CommandManager.literal(
                                                "music"
                                             )
                                             .then(
                                                CommandManager.literal("search")
                                                   .then(
                                                      CommandManager.argument("keyword", StringArgumentType.greedyString())
                                                         .executes(
                                                            ctx -> {
                                                               String kw = StringArgumentType.getString(ctx, "keyword");
                                                               ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
                                                               if (player == null) {
                                                                  return 0;
                                                               }

                                                               NeteaseApiClient.searchAsync(
                                                                  kw,
                                                                  results -> {
                                                                     if (results.isEmpty()) {
                                                                        player.sendMessage(
                                                                           Text.literal(ModConfig.getPrefix() + " §c未找到歌曲: " + kw).formatted(Formatting.RED),
                                                                           false
                                                                        );
                                                                     } else {
                                                                        player.sendMessage(
                                                                           Text.literal(ModConfig.getPrefix() + " §a搜索结果 (点击 §n点我播放§a):")
                                                                              .formatted(Formatting.GREEN),
                                                                           false
                                                                        );

                                                                        for (int i = 0; i < Math.min(results.size(), 10); i++) {
                                                                           NeteaseApiClient.SongInfo s = results.get(i);
                                                                           String title = TextUtil.sanitize(s.title);
                                                                           String artist = TextUtil.sanitize(s.artist);
                                                                           Text line = Text.literal(String.format("§7[%d] §f%s§7-§f%s ", i + 1, title, artist))
                                                                              .append(
                                                                                 Text.literal("§n§b点我播放")
                                                                                    .formatted(new Formatting[]{Formatting.UNDERLINE, Formatting.AQUA})
                                                                                    .styled(
                                                                                       style -> style.withClickEvent(
                                                                                          new ClickEvent(
                                                                                             Action.RUN_COMMAND,
                                                                                             String.format("/music request %s %s", s.id, title)
                                                                                          )
                                                                                       )
                                                                                    )
                                                                                    .styled(
                                                                                       style -> style.withHoverEvent(
                                                                                          new HoverEvent(
                                                                                             net.minecraft.text.HoverEvent.Action.SHOW_TEXT,
                                                                                             Text.literal("点击播放: " + title)
                                                                                          )
                                                                                       )
                                                                                    )
                                                                              );
                                                                           player.sendMessage(line, false);
                                                                        }
                                                                     }
                                                                  }
                                                               );
                                                               return 1;
                                                            }
                                                         )
                                                   )
                                             ))
                                          .then(
                                             CommandManager.literal("request")
                                                .then(
                                                   CommandManager.argument("id", StringArgumentType.string())
                                                      .then(
                                                         CommandManager.argument("title", StringArgumentType.greedyString())
                                                            .executes(
                                                               ctx -> {
                                                                  String id = StringArgumentType.getString(ctx, "id");
                                                                  String title = TextUtil.sanitize(StringArgumentType.getString(ctx, "title"));
                                                                  if (title.isEmpty()) {
                                                                     ((ServerCommandSource)ctx.getSource())
                                                                        .getPlayer()
                                                                        .sendMessage(
                                                                           Text.literal(ModConfig.getPrefix() + " §c无效的歌曲标题").formatted(Formatting.RED), false
                                                                        );
                                                                     return 0;
                                                                  }

                                                                  ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
                                                                  if (player == null) {
                                                                     return 0;
                                                                  }

                                                                  NeteaseApiClient.getSongUrlAsync(
                                                                     id,
                                                                     url -> {
                                                                        if (url == null) {
                                                                           player.sendMessage(
                                                                              Text.literal(ModConfig.getPrefix() + " §c无法获取歌曲播放链接（可能是VIP歌曲）")
                                                                                 .formatted(Formatting.RED),
                                                                              false
                                                                           );
                                                                        } else {
                                                                           NeteaseApiClient.getSongInfoAsync(
                                                                              id,
                                                                              info -> {
                                                                                 String artist = info != null && info.artist != null ? info.artist : "Unknown";
                                                                                 String album = info != null && info.album != null ? info.album : "";
                                                                                 String coverUrl = info != null && info.coverUrl != null ? info.coverUrl : "";
                                                                                 int duration = info != null && info.duration > 0 ? info.duration : 240;
                                                                                 boolean ok = MusicQueueManager.addToQueue(
                                                                                    id, title, artist, album, coverUrl, url, duration, player
                                                                                 );
                                                                                 if (ok) {
                                                                                    Text notify = Text.literal(
                                                                                       String.format(
                                                                                          "%s §a♪ %s 点歌: §b%s §7(by %s)",
                                                                                          ModConfig.getPrefix(),
                                                                                          "▶",
                                                                                          title,
                                                                                          player.getName().getString()
                                                                                       )
                                                                                    );

                                                                                    for (ServerPlayerEntity p : ((ServerCommandSource)ctx.getSource())
                                                                                       .getServer()
                                                                                       .getPlayerManager()
                                                                                       .getPlayerList()) {
                                                                                       p.sendMessage(notify, true);
                                                                                    }
                                                                                 } else {
                                                                                    player.sendMessage(
                                                                                       Text.literal(ModConfig.getPrefix() + " §c点歌失败: 队列已满或已达上限")
                                                                                          .formatted(Formatting.RED),
                                                                                       false
                                                                                    );
                                                                                 }
                                                                              }
                                                                           );
                                                                        }
                                                                     }
                                                                  );
                                                                  return 1;
                                                               }
                                                            )
                                                      )
                                                )
                                          ))
                                       .then(
                                          CommandManager.literal("queue")
                                             .executes(
                                                ctx -> {
                                                   ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
                                                   if (player == null) {
                                                      return 0;
                                                   }

                                                   List<MusicQueueManager.MusicEntry> list = MusicQueueManager.getQueueAsList();
                                                   player.sendMessage(Text.literal(ModConfig.getPrefix() + " §6=== 点歌队列 ===").formatted(Formatting.GOLD), false);
                                                   MusicQueueManager.MusicEntry current = MusicQueueManager.getCurrentPlaying();
                                                   if (current != null) {
                                                      player.sendMessage(
                                                         Text.literal(String.format("§a▶ 正在播放: %s - %s", current.title, current.artist))
                                                            .formatted(Formatting.GREEN),
                                                         false
                                                      );
                                                   }

                                                   for (int i = 0; i < list.size(); i++) {
                                                      MusicQueueManager.MusicEntry e = list.get(i);
                                                      player.sendMessage(
                                                         Text.literal(String.format("§7%d. %s - %s (by %s)", i + 1, e.title, e.artist, e.requesterName))
                                                            .formatted(Formatting.GRAY),
                                                         false
                                                      );
                                                   }

                                                   if (list.isEmpty() && current == null) {
                                                      player.sendMessage(Text.literal("§7队列为空").formatted(Formatting.GRAY), false);
                                                   }

                                                   return 1;
                                                }
                                             )
                                       ))
                                    .then(
                                       CommandManager.literal("list")
                                          .executes(
                                             ctx -> {
                                                ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
                                                if (player == null) {
                                                   return 0;
                                                }

                                                List<MusicQueueManager.MusicEntry> list = MusicQueueManager.getQueueAsList();
                                                MusicQueueManager.MusicEntry current = MusicQueueManager.getCurrentPlaying();
                                                player.sendMessage(Text.literal(ModConfig.getPrefix() + " §6=== 歌曲列表 ===").formatted(Formatting.GOLD), false);
                                                if (current != null) {
                                                   player.sendMessage(
                                                      Text.literal(
                                                         String.format(
                                                            "§a▶ 正在播放: §b%s §7- %s §8[点歌: %s] §7(%d:%02d)",
                                                            current.title,
                                                            current.artist,
                                                            current.requesterName,
                                                            current.duration / 60,
                                                            current.duration % 60
                                                         )
                                                      ),
                                                      false
                                                   );
                                                }

                                                if (list.isEmpty()) {
                                                   player.sendMessage(Text.literal("§7队列为空").formatted(Formatting.GRAY), false);
                                                }

                                                for (int i = 0; i < list.size(); i++) {
                                                   MusicQueueManager.MusicEntry e = list.get(i);
                                                   player.sendMessage(
                                                      Text.literal(
                                                         String.format(
                                                            "§7%d. §f%s §7- %s §8[点歌: %s] §7(%d:%02d)",
                                                            i + 1,
                                                            e.title,
                                                            e.artist,
                                                            e.requesterName,
                                                            e.duration / 60,
                                                            e.duration % 60
                                                         )
                                                      ),
                                                      false
                                                   );
                                                }

                                                return 1;
                                             }
                                          )
                                    ))
                                 .then(((LiteralArgumentBuilder)CommandManager.literal("skip").requires(src -> src.hasPermissionLevel(2))).executes(ctx -> {
                                    MusicQueueManager.skipCurrent();
                                    Text notify = Text.literal(ModConfig.getPrefix() + " §e⏭ 已跳过当前歌曲").formatted(Formatting.YELLOW);

                                    for (ServerPlayerEntity p : ((ServerCommandSource)ctx.getSource()).getServer().getPlayerManager().getPlayerList()) {
                                       p.sendMessage(notify, true);
                                    }

                                    return 1;
                                 })))
                              .then(((LiteralArgumentBuilder)CommandManager.literal("stop").requires(src -> src.hasPermissionLevel(2))).executes(ctx -> {
                                 MusicQueueManager.stopAll();
                                 Text notify = Text.literal(ModConfig.getPrefix() + " §c⏹ 已停止播放并清空队列").formatted(Formatting.RED);

                                 for (ServerPlayerEntity p : ((ServerCommandSource)ctx.getSource()).getServer().getPlayerManager().getPlayerList()) {
                                    p.sendMessage(notify, true);
                                 }

                                 return 1;
                              })))
                           .then(
                              ((LiteralArgumentBuilder)CommandManager.literal("remove").requires(src -> src.hasPermissionLevel(2)))
                                 .then(
                                    CommandManager.argument("index", IntegerArgumentType.integer(1))
                                       .executes(
                                          ctx -> {
                                             int idx = IntegerArgumentType.getInteger(ctx, "index") - 1;
                                             boolean ok = MusicQueueManager.removeFromQueue(idx);
                                             if (ok) {
                                                ((ServerCommandSource)ctx.getSource())
                                                   .sendMessage(
                                                      Text.literal(ModConfig.getPrefix() + " §a已移除第 " + (idx + 1) + " 首歌曲").formatted(Formatting.GREEN)
                                                   );
                                             } else {
                                                ((ServerCommandSource)ctx.getSource())
                                                   .sendMessage(Text.literal(ModConfig.getPrefix() + " §c无效的序号").formatted(Formatting.RED));
                                             }

                                             return 1;
                                          }
                                       )
                                 )
                           ))
                        .then(
                           ((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)((LiteralArgumentBuilder)CommandManager.literal("update")
                                          .executes(ctx -> {
                                             ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
                                             if (player == null) {
                                                return 0;
                                             } else if (!player.hasPermissionLevel(2)) {
                                                player.sendMessage(Text.literal(ModConfig.getPrefix() + " §c需要 OP 权限").formatted(Formatting.RED), false);
                                                return 0;
                                             } else {
                                                UpdateChecker.manualCheck(player);
                                                return 1;
                                             }
                                          }))
                                       .then(CommandManager.literal("enable").executes(ctx -> {
                                          if (!((ServerCommandSource)ctx.getSource()).hasPermissionLevel(2)) {
                                             return 0;
                                          }

                                          UpdateChecker.setEnabled(true);
                                          ((ServerCommandSource)ctx.getSource())
                                             .sendMessage(Text.literal(ModConfig.getPrefix() + " §a✓ 更新检测已启用").formatted(Formatting.GREEN));
                                          return 1;
                                       })))
                                    .then(CommandManager.literal("disable").executes(ctx -> {
                                       if (!((ServerCommandSource)ctx.getSource()).hasPermissionLevel(2)) {
                                          return 0;
                                       }

                                       UpdateChecker.setEnabled(false);
                                       ((ServerCommandSource)ctx.getSource())
                                          .sendMessage(Text.literal(ModConfig.getPrefix() + " §e更新检测已禁用").formatted(Formatting.YELLOW));
                                       return 1;
                                    })))
                                 .then(CommandManager.literal("token").then(CommandManager.argument("token", StringArgumentType.string()).executes(ctx -> {
                                    if (!((ServerCommandSource)ctx.getSource()).hasPermissionLevel(2)) {
                                       return 0;
                                    }

                                    String token = StringArgumentType.getString(ctx, "token");
                                    UpdateChecker.setToken(token);
                                    ((ServerCommandSource)ctx.getSource())
                                       .sendMessage(Text.literal(ModConfig.getPrefix() + " §a✓ Token 已保存").formatted(Formatting.GREEN));
                                    return 1;
                                 }))))
                              .then(CommandManager.literal("url").then(CommandManager.argument("apiurl", StringArgumentType.string()).executes(ctx -> {
                                 if (!((ServerCommandSource)ctx.getSource()).hasPermissionLevel(2)) {
                                    return 0;
                                 }

                                 String url = StringArgumentType.getString(ctx, "apiurl");
                                 UpdateChecker.setApiBaseUrl(url);
                                 ((ServerCommandSource)ctx.getSource())
                                    .sendMessage(Text.literal(ModConfig.getPrefix() + " §a✓ API 地址已更新: " + url).formatted(Formatting.GREEN));
                                 return 1;
                              })))
                        ))
                     .then(
                        ((LiteralArgumentBuilder)((LiteralArgumentBuilder)CommandManager.literal("api").requires(src -> src.hasPermissionLevel(2)))
                              .executes(
                                 ctx -> {
                                    ((ServerCommandSource)ctx.getSource())
                                       .sendMessage(
                                          Text.literal(ModConfig.getPrefix() + " §e当前音乐 API: §b" + ModConfig.getApiBaseUrl()).formatted(Formatting.WHITE)
                                       );
                                    return 1;
                                 }
                              ))
                           .then(
                              CommandManager.argument("url", StringArgumentType.string())
                                 .executes(
                                    ctx -> {
                                       String url = StringArgumentType.getString(ctx, "url");
                                       ModConfig.setApiBaseUrl(url);
                                       ((ServerCommandSource)ctx.getSource())
                                          .sendMessage(Text.literal(ModConfig.getPrefix() + " §a✓ 音乐 API 已设置: §b" + url).formatted(Formatting.GREEN));
                                       return 1;
                                    }
                                 )
                           )
                     ))
                  .then(
                     ((LiteralArgumentBuilder)CommandManager.literal("reload").requires(src -> src.hasPermissionLevel(2)))
                        .executes(
                           ctx -> {
                              ModConfig.load();
                              ((ServerCommandSource)ctx.getSource())
                                 .sendMessage(
                                    Text.literal(ModConfig.getPrefix() + " §a✓ 配置已重新加载 (音乐 API: §b" + ModConfig.getApiBaseUrl() + "§a)")
                                       .formatted(Formatting.GREEN)
                                 );
                              return 1;
                           }
                        )
                  ))
               .then(
                  CommandManager.literal("status")
                     .executes(
                        ctx -> {
                           ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
                           if (player == null) {
                              return 0;
                           }

                           player.sendMessage(Text.literal(ModConfig.getPrefix() + " §6=== SimpMusic 状态 ===").formatted(Formatting.GOLD), false);
                           player.sendMessage(Text.literal("§e版本: §b" + SimpMusicServer.getVersion()), false);
                           player.sendMessage(Text.literal("§e音乐 API: §b" + ModConfig.getApiBaseUrl()), false);
                           player.sendMessage(
                              Text.literal("§eMTR 兼容: " + (ModConfig.isMTRCompatible() ? "§a✓ 是 (v" + ModConfig.getMTRVersion() + ")" : "§7否")), false
                           );
                           player.sendMessage(
                              Text.literal("§e更新检测: " + (UpdateChecker.isUpdateAvailable() ? "§a⬆ 有新版本 v" + UpdateChecker.getCachedLatestVersion() : "§7无可用更新")),
                              false
                           );
                           return 1;
                        }
                     )
               ))
            .then(CommandManager.literal("cookie").executes(ctx -> {
               ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
               if (player == null) {
                  return 0;
               }

               player.sendMessage(Text.literal(ModConfig.getPrefix() + " §6=== 网易云 Cookie 状态 ===").formatted(Formatting.GOLD), false);

               if (!CookieManager.hasCookie()) {
                  player.sendMessage(Text.literal("§c✗ 未加载 Cookie —— 只能播放免费歌曲").formatted(Formatting.RED), false);
                  player.sendMessage(Text.literal("§7把浏览器导出的 §f163cookie.json§7 放到："), false);
                  player.sendMessage(Text.literal("§f  SimpMusic/163cookie.json"), false);
                  return 1;
               }

               player.sendMessage(Text.literal("§a✓ Cookie 已加载").formatted(Formatting.GREEN), false);
               player.sendMessage(Text.literal("§e来源: §7" + CookieManager.getLoadedFrom()), false);

               String name = CookieManager.getAccountName();
               if (name == null || name.isEmpty()) {
                  player.sendMessage(Text.literal("§e账号: §7校验中或校验失败（Cookie 可能已过期）"), false);
               } else {
                  int vip = CookieManager.getVipType();
                  String country = CookieManager.getCountry();
                  player.sendMessage(
                     Text.literal("§e账号: §b" + name + (country == null || country.isEmpty() ? "" : " §7@" + country)), false
                  );
                  player.sendMessage(
                     Text.literal("§eVIP: " + (vip > 10 ? "§a✓ 有效 (vipType=" + vip + ") · 可播放 VIP 歌曲" : "§c✗ 非 VIP (vipType=" + vip + ")")),
                     false
                  );
               }

               return 1;
            }))
            .then(CommandManager.literal("help").executes(ctx -> {
               ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
               if (player == null) {
                  return 0;
               }

               player.sendMessage(Text.literal(ModConfig.getPrefix() + " §6=== SimpMusic 帮助 ===").formatted(Formatting.GOLD), false);
               player.sendMessage(Text.literal("§e/music search <关键词> §7- 搜索歌曲"), false);
               player.sendMessage(Text.literal("§e/music request <ID> <标题> §7- 点歌"), false);
               player.sendMessage(Text.literal("§e/music list §7- 列出歌曲及点歌人"), false);
               player.sendMessage(Text.literal("§e/music queue §7- 查看队列"), false);
               player.sendMessage(Text.literal("§e/music skip §7- 跳过(OP)"), false);
               player.sendMessage(Text.literal("§e/music stop §7- 停止(OP)"), false);
               player.sendMessage(Text.literal("§e/music remove <n> §7- 移除(OP)"), false);
               player.sendMessage(Text.literal("§e/music api [url] §7- 查看/设置音乐API(OP)"), false);
               player.sendMessage(Text.literal("§e/music reload §7- 重新加载配置(OP)"), false);
               player.sendMessage(Text.literal("§e/music update §7- 检查更新(OP)"), false);
               player.sendMessage(Text.literal("§e/music status §7- 查看状态"), false);
               player.sendMessage(Text.literal("§e/music cookie §7- 查看网易云 Cookie / VIP 状态"), false);
               player.sendMessage(Text.literal("§e/skip §7- 投票跳过当前歌曲(半数通过)"), false);
               return 1;
            }))
      );
      // 玩家投票跳过指令：半数在线玩家使用后直接跳过当前歌曲
      dispatcher.register(
         CommandManager.literal("skip")
            .executes(
               ctx -> {
                  ServerPlayerEntity player = ((ServerCommandSource)ctx.getSource()).getPlayer();
                  if (player == null) {
                     return 0;
                  }

                  MusicQueueManager.voteSkip(player);
                  return 1;
               }
            )
      );
   }
}
