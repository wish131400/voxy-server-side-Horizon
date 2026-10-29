package dev.xantha.vss.networking.client;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import dev.xantha.vss.networking.command.VSSCommandHelp;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.minecraft.network.chat.Component;

public final class VSSClientCommands {
    private VSSClientCommands() {
    }

    public static void registerCommands(CommandDispatcher<FabricClientCommandSource> dispatcher) {
        var root = dispatcher.register(ClientCommandManager.literal("vssclient")
                .executes(context -> clientHelp(context.getSource(), ""))
                .then(helpCommand())
                .then(ClientCommandManager.literal("stats")
                        .executes(context -> showStats(context.getSource()))
                        .then(ClientCommandManager.literal("details").executes(context -> {
                            feedback(context.getSource(),
                                    () -> Component.translatable(
                                            "vss.command.client_stats",
                                            VSSClientNetworking.diagnostics()),
                                    false);
                            return 1;
                        })))
                .then(ClientCommandManager.literal("prediction")
                        .executes(context -> clientHelp(context.getSource(), "prediction"))
                        .then(performanceCommand())
                        .then(ClientCommandManager.literal("rendercapture")
                                .executes(context -> captureRender(context.getSource(), 0.5, 0.65))
                                .then(ClientCommandManager.argument("x", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1))
                                        .then(ClientCommandManager.argument("y", com.mojang.brigadier.arguments.DoubleArgumentType.doubleArg(0, 1))
                                                .executes(context -> captureRender(context.getSource(),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "x"),
                                                        com.mojang.brigadier.arguments.DoubleArgumentType.getDouble(context, "y"))))))
                        .then(ClientCommandManager.literal("capture")
                                .executes(context -> {
                                    var source = context.getSource();
                                    feedback(source, () -> Component.translatable("vss.command.reference_capture.started"), false);
                                    dev.xantha.vss.client.prediction.RustReferenceExport.start().whenComplete((path, failure) ->
                                            net.minecraft.client.Minecraft.getInstance().execute(() -> {
                                                if (failure == null) feedback(source, () -> Component.translatable("vss.command.reference_capture.done", path.toString()), false);
                                                else source.sendError(Component.translatable("vss.command.reference_capture.failed", failure.getMessage()));
                                            }));
                                    return 1;
                                })))
                .then(ClientCommandManager.literal("xaero")
                        .executes(context -> clientHelp(context.getSource(), "xaero"))
                        .then(ClientCommandManager.literal("disable")
                                .executes(context -> {
                                    VSSClientNetworking.setXaeroMapBridge(false);
                                    feedback(context.getSource(), () -> Component.translatable(
                                            "vss.command.xaero.disabled"), false);
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("enable")
                                .executes(context -> {
                                    VSSClientNetworking.setXaeroMapBridge(true);
                                    feedback(context.getSource(), () -> Component.translatable(
                                            "vss.command.xaero.enabled"), false);
                                    return 1;
                                }))
                        .then(ClientCommandManager.literal("reload")
                                .executes(context -> {
                                    int cleared = VSSClientNetworking.reloadXaeroMapData();
                                    if (cleared < 0) {
                                        context.getSource().sendError(Component.translatable(
                                                "vss.command.xaero_reload.no_session"));
                                        return 0;
                                    }
                                    feedback(context.getSource(), () -> Component.translatable(
                                            "vss.command.xaero_reload.started", cleared), false);
                                    return 1;
                                })))
                .then(ClientCommandManager.argument("unknown_command", StringArgumentType.word())
                        .executes(context -> clientHelp(context.getSource(),
                                StringArgumentType.getString(context, "unknown_command")))));
        VSSCommandHelp.clientAliases(root);
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> performanceCommand() {
        return ClientCommandManager.literal("perf")
                .executes(context -> performanceStatus(context.getSource()))
                .then(ClientCommandManager.literal("status").executes(context -> performanceStatus(context.getSource())))
                .then(ClientCommandManager.literal("start").executes(context -> {
                    performanceResult(context.getSource(),
                            dev.xantha.vss.client.prediction.PredictionPerformanceMonitor.start(), "vss.command.perf.started");
                    return 1;
                }))
                .then(ClientCommandManager.literal("stop").executes(context -> {
                    dev.xantha.vss.client.prediction.PredictionPerformanceMonitor.stop();
                    feedback(context.getSource(), () -> Component.translatable("vss.command.perf.stopped"), false); return 1;
                }))
                .then(ClientCommandManager.literal("hud").executes(context -> {
                    boolean on = dev.xantha.vss.client.prediction.PredictionPerformanceMonitor.toggleHud();
                    feedback(context.getSource(), () -> Component.translatable("vss.command.perf.hud", on), false); return 1;
                }))
                .then(ClientCommandManager.literal("mark").then(ClientCommandManager.argument("label", StringArgumentType.greedyString()).executes(context -> {
                    String label = StringArgumentType.getString(context, "label");
                    dev.xantha.vss.client.prediction.PredictionPerformanceMonitor.mark(label);
                    feedback(context.getSource(), () -> Component.translatable("vss.command.perf.mark", label), false); return 1;
                })))
                .then(ClientCommandManager.literal("report").executes(context -> {
                    performanceResult(context.getSource(),
                            dev.xantha.vss.client.prediction.PredictionPerformanceMonitor.report(), "vss.command.perf.report"); return 1;
                }));
    }



    private static int performanceStatus(FabricClientCommandSource source) {
        feedback(source, () -> Component.translatable("vss.command.perf.status",
                dev.xantha.vss.client.prediction.PredictionPerformanceMonitor.status()), false); return 1;
    }

    private static void performanceResult(FabricClientCommandSource source, java.util.concurrent.CompletableFuture<java.nio.file.Path> future, String key) {
        future.whenComplete((path, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (failure == null) feedback(source, () -> Component.translatable(key, path.toString()), false);
            else source.sendError(Component.translatable("vss.command.perf.failed", failure.getClass().getSimpleName()));
        }));
    }

    private static LiteralArgumentBuilder<FabricClientCommandSource> helpCommand() {
        return ClientCommandManager.literal("help")
                .executes(context -> clientHelp(context.getSource(), ""))
                .then(ClientCommandManager.argument("command", StringArgumentType.greedyString())
                        .suggests((context, builder) -> net.minecraft.commands.SharedSuggestionProvider.suggest(
                                VSSCommandHelp.CLIENT_HELP_TOPICS, builder))
                        .executes(context -> clientHelp(context.getSource(),
                                StringArgumentType.getString(context, "command"))));
    }

    private static int captureRender(net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource source, double x, double y) {
        var result = dev.xantha.vss.client.prediction.PredictionRenderCapture.request(x, y);
        if (!result.isCompletedExceptionally()) feedback(source, () -> Component.translatable("vss.command.render_capture.started"), false);
        result.whenComplete((path, failure) -> net.minecraft.client.Minecraft.getInstance().execute(() -> {
            if (failure == null) feedback(source, () -> Component.translatable("vss.command.render_capture.done", path.toString()), false);
            else source.sendError(Component.translatable("vss.command.render_capture.failed", failure.getMessage()));
        }));
        return result.isCompletedExceptionally() ? 0 : 1;
    }

    private static int showStats(FabricClientCommandSource source) {
        feedback(source, () -> Component.translatable("vss.command.client_stats.summary",
                Component.translatable(VSSClientNetworking.isServerEnabled() ? "vss.command.enabled" : "vss.command.disabled"),
                VSSClientNetworking.getEffectiveLodDistanceChunks(), VSSClientNetworking.getColumnsReceived(),
                String.format(java.util.Locale.ROOT, "%.2f", VSSClientNetworking.getBytesReceived() / 1048576.0),
                VSSClientNetworking.getColumnsDropped(),
                Component.translatable(VSSClientNetworking.isPredictionActive() ? "vss.command.enabled" : "vss.command.disabled")), false);
        return 1;
    }
    private static int clientHelp(FabricClientCommandSource source, String topic) {
        return VSSCommandHelp.client(source::sendFeedback, source::sendError, topic);
    }

    private static void feedback(FabricClientCommandSource source,
            java.util.function.Supplier<Component> message, boolean ignoredBroadcast) {
        source.sendFeedback(message.get());
    }

}