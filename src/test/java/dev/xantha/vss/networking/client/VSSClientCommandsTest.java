package dev.xantha.vss.networking.client;

import com.mojang.brigadier.CommandDispatcher;
import net.minecraft.commands.CommandSource;
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource;
import net.minecraft.network.chat.Component;
import net.minecraft.world.phys.Vec2;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class VSSClientCommandsTest {
    @BeforeAll static void initialize() {
        if (System.getProperty("vss.configDir") == null)
            System.setProperty("vss.configDir", (java.nio.file.Path.of("build", "tmp", "command-tests")).resolve("config").toString());
        net.minecraft.SharedConstants.tryDetectVersion();
    }

    @Test void localHelpAndAliasesNeedNoOperatorPermission() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        VSSClientCommands.registerCommands(dispatcher);
        FabricClientCommandSource source = org.mockito.Mockito.mock(FabricClientCommandSource.class);
        for (String input : new String[] {"vssclient", "vssclient help prediction", "vssclient 帮助 地图",
                "vssclient 状态", "vssclient 地图 开启", "vssclient 地图 关闭", "vssclient 地图 重载",
                "vssclient 预测 渲染采集 0.5 0.65", "vssclient 预测 参考采集",
                "vssclient prediction perf start", "vssclient prediction perf stop", "vssclient prediction perf hud",
                "vssclient prediction perf status", "vssclient prediction perf report", "vssclient prediction perf mark loading a",
                "vssclient 预测 性能 开始", "vssclient 预测 性能 状态", "vssclient 预测 性能 报告", "vssclient 预测 性能 标记 缓存恢复"}) {
            var parsed = dispatcher.parse(input, source);
            assertFalse(parsed.getReader().canRead(), input + " => " + parsed.getExceptions());
            assertNotNull(parsed.getContext().getCommand(), input);
        }
    }

    @Test void retiredIndependentRendererPathSelectorsAreUnavailable() {
        CommandDispatcher<FabricClientCommandSource> dispatcher = new CommandDispatcher<>();
        VSSClientCommands.registerCommands(dispatcher);
        FabricClientCommandSource source = org.mockito.Mockito.mock(FabricClientCommandSource.class);
        for (String input : new String[]{"vssclient prediction perf path auto",
                "vssclient prediction perf path resident", "vssclient prediction perf path ordinary",
                "vssclient 预测 性能 路径 ordinary"}) {
            assertTrue(dispatcher.parse(input, source).getReader().canRead(), input);
        }
    }
}