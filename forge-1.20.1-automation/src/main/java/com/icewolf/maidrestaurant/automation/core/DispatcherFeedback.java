package com.icewolf.maidrestaurant.automation.core;

import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.IChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.chatbubble.implement.TextChatBubbleData;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.icewolf.maidrestaurant.automation.MaidRestaurantAutomation;
import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.core.particles.ParticleTypes;
import net.minecraft.core.BlockPos;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * 调度显示器反馈：聊天气泡 / 绿色粒子 / 聊天栏提示。
 *
 * <p>开发文档 4.3③④：认领失败每 60 秒一次气泡（发气泡前先重试一次认领）；
 * 认领成功在工作方块位置播放原版村民绑定工作站的绿色粒子（仅首次）。</p>
 */
public final class DispatcherFeedback {

    private static final Map<UUID, Long> LAST_BUBBLE = new HashMap<>();
    private static final Map<UUID, Long> LAST_CLAIM_FAIL_BUBBLE = new HashMap<>();
    private static final long CLAIM_FAIL_BUBBLE_INTERVAL = 60 * 20L; // 60 秒

    private DispatcherFeedback() {
    }

    /** 绿色粒子（原版村民认领工作站特效），在工位方块处播放。 */
    public static void workstationClaimed(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null) return;
        try {
            level.sendParticles(ParticleTypes.HAPPY_VILLAGER,
                    pos.getX() + 0.5D, pos.getY() + 1.0D, pos.getZ() + 0.5D,
                    12, 0.35D, 0.35D, 0.35D, 0.0D);
        } catch (Throwable ignored) {
        }
    }

    /** 女仆气泡（带每女仆 3 秒冷却，避免刷屏）。 */
    public static void bubble(EntityMaid maid, String[] messages, int duration) {
        if (maid == null || maid.level().isClientSide || messages == null || messages.length == 0) return;
        try {
            long now = maid.level().getGameTime();
            Long last = LAST_BUBBLE.get(maid.getUUID());
            if (last != null && now - last < 60L) return;
            LAST_BUBBLE.put(maid.getUUID(), now);
            Component text = Component.literal(messages[maid.getRandom().nextInt(messages.length)]);
            TextChatBubbleData data = TextChatBubbleData.create(
                    duration, text, IChatBubbleData.TYPE_2, IChatBubbleData.DEFAULT_PRIORITY);
            if (maid.getChatBubbleManager() != null) {
                maid.getChatBubbleManager().addChatBubble(data);
            }
        } catch (Throwable t) {
            MaidRestaurantAutomation.LOGGER.debug("调度显示器气泡发送失败: {}", t.toString());
        }
    }

    /** 工位认领成功气泡（仅首次认领时由调用方触发）。 */
    public static void claimSuccess(EntityMaid maid, String stationName) {
        bubble(maid, new String[]{
                "这个" + stationName + "以后就是我的工位啦~",
                "认领成功！我会好好守着" + stationName + "的~",
                "从今天起，这个" + stationName + "归我管啦！"
        }, 80);
    }

    /** 认领失败气泡（60 秒一次，文案随职业/工位类型动态替换）。 */
    public static void claimFailed(EntityMaid maid, String stationName, boolean noDeviceAtAll) {
        if (maid == null || maid.level().isClientSide) return;
        long now = maid.level().getGameTime();
        Long last = LAST_CLAIM_FAIL_BUBBLE.get(maid.getUUID());
        if (last != null && now - last < CLAIM_FAIL_BUBBLE_INTERVAL) return;
        LAST_CLAIM_FAIL_BUBBLE.put(maid.getUUID(), now);
        if (noDeviceAtAll) {
            bubbleNoCooldown(maid, new String[]{
                    stationName + "在哪里呢？找不到(´；ω；`)",
                    "一个" + stationName + "都没有...主人放一个好不好",
                    "没有" + stationName + "的话，我没法工作呀..."
            }, 100);
        } else {
            bubbleNoCooldown(maid, new String[]{
                    stationName + "都被别的女仆认领啦(｡•́︿•̀｡)",
                    "没有空的" + stationName + "可以认领...",
                    stationName + "都被占着呢，我再等等~"
            }, 100);
        }
    }

    private static void bubbleNoCooldown(EntityMaid maid, String[] messages, int duration) {
        try {
            Component text = Component.literal(messages[maid.getRandom().nextInt(messages.length)]);
            TextChatBubbleData data = TextChatBubbleData.create(
                    duration, text, IChatBubbleData.TYPE_2, IChatBubbleData.DEFAULT_PRIORITY);
            if (maid.getChatBubbleManager() != null) {
                maid.getChatBubbleManager().addChatBubble(data);
            }
        } catch (Throwable ignored) {
        }
    }

    /** 工位失效 / 被拆气泡。 */
    public static void workstationInvalid(EntityMaid maid, String stationName) {
        bubble(maid, new String[]{
                stationName + "好像不见了...我先停下了",
                "我的" + stationName + "被拆掉了吗？",
                stationName + "不能用了，主人看一下好不好"
        }, 100);
    }

    /** 打单机丢失提示（调度显示器附近玩家聊天栏）。 */
    public static void machineLost(ServerLevel level, BlockPos dispatcherPos) {
        chatToNearby(level, dispatcherPos, Component.literal("[女仆餐厅：自动化] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("绑定的打单机已消失，调度显示器已解绑，请重新放置打单机。")
                        .withStyle(ChatFormatting.RED)), 64.0D);
    }

    /** 订单失败红字提示（开发文档 5.4：静默失败不允许）。 */
    public static void orderFailed(ServerLevel level, BlockPos dispatcherPos, String orderId, String reason) {
        chatToNearby(level, dispatcherPos, Component.literal("[女仆餐厅：自动化] ")
                .withStyle(ChatFormatting.GOLD)
                .append(Component.literal("订单 " + orderId + " 失败：" + reason)
                        .withStyle(ChatFormatting.RED)), 64.0D);
    }

    /** 调度显示器状态提示（绑定成功等）。 */
    public static void info(ServerLevel level, BlockPos dispatcherPos, String message) {
        chatToNearby(level, dispatcherPos, Component.literal("[女仆餐厅：自动化] " + message)
                .withStyle(ChatFormatting.GREEN), 64.0D);
    }

    private static void chatToNearby(ServerLevel level, BlockPos pos, Component message, double radius) {
        if (level == null || pos == null || message == null) return;
        for (ServerPlayer player : level.getPlayers(p ->
                p.distanceToSqr(pos.getX() + 0.5D, pos.getY() + 0.5D, pos.getZ() + 0.5D) <= radius * radius)) {
            player.displayClientMessage(message, false);
        }
    }
}
