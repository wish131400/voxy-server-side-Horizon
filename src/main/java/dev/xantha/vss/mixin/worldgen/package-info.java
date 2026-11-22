/**
 * 职责：连接世界生成兼容模块与 Minecraft 的注入入口。
 * 依赖：Mixin API、Minecraft 和对应兼容模块。
 * 线程：沿用目标方法的线程，快照按各适配器的生命周期管理。
 * 生命周期：由加载器应用注入，运行时仅在对应模组存在时启用。
 */
package dev.xantha.vss.mixin.worldgen;
