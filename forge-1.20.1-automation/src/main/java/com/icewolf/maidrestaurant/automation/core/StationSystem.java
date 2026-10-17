package com.icewolf.maidrestaurant.automation.core;

import cn.breezeth.ordertocook.block.entity.TakeoutBoxBlockEntity;
import com.github.tartaricacid.touhoulittlemaid.api.task.IMaidTask;
import com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid;
import com.mastermarisa.maid_restaurant.api.ICookTask;
import com.mastermarisa.maid_restaurant.data.TagBlock;
import com.mastermarisa.maid_restaurant.utils.CookTasks;
import net.minecraft.core.BlockPos;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.crafting.RecipeType;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 工位体系：职业识别 + 工作方块（工位）识别 + 板凳识别。
 *
 * <p>设计目标（开发文档 T4 / 4.3②）：
 * <ul>
 *   <li><b>不写死方块类型白名单</b>：厨师类职业的工位识别复用女仆餐厅本体 {@link ICookTask#isValidWorkBlock}
 *       的原有检测代码，自动覆盖炒锅 / 汤锅 / 蒸笼 / 烤箱 / 烤面包机 / 搅拌机 / 农夫乐事锅等全部已注册厨具；</li>
 *   <li>侍者类职业的工位 = OTC 操作台（{@link TakeoutBoxBlockEntity}）；</li>
 *   <li>扩展职业（茶博士 / 调酒师等第三方职业）通过 {@link #registerProfession(String, String, String)}
 *       注册条目即可接入，不修改调度显示器匹配代码；</li>
 *   <li>板凳识别复用女仆餐厅本体的 {@link TagBlock#SIT_BLOCK} 标签（厨凳 / 椅子）。</li>
 * </ul>
 */
public final class StationSystem {

    /** 制作类职业（厨师，以及所有注册为 cookware 工位的扩展职业）。 */
    public static final String PROF_COOK = "cook";
    /** 侍者职业。 */
    public static final String PROF_WAITER = "waiter";
    /** 未识别的第三方职业前缀。 */
    public static final String PROF_EXT_PREFIX = "ext:";

    /** 工位类型：厨具（复用女仆餐厅 ICookTask 检测）。 */
    public static final String STATION_COOKWARE = "cookware";
    /** 工位类型：操作台。 */
    public static final String STATION_COUNTER = "counter";

    private static final Map<String, String> EXT_PROFESSION_STATION = new ConcurrentHashMap<>();
    private static final Map<String, String> EXT_PROFESSION_DISPLAY = new ConcurrentHashMap<>();

    private StationSystem() {
    }

    /**
     * 注册一个扩展职业（第三方附属职业接入点）。
     *
     * @param taskUid      女仆任务 UID（{@code maid.getTask().getUid().toString()}）
     * @param stationType  {@link #STATION_COOKWARE} 或 {@link #STATION_COUNTER}
     * @param displayName  气泡 / GUI 里使用的可读名（如"吧台"）
     */
    public static void registerProfession(String taskUid, String stationType, String displayName) {
        if (taskUid == null || taskUid.isEmpty()) return;
        EXT_PROFESSION_STATION.put(taskUid, stationType == null ? STATION_COOKWARE : stationType);
        EXT_PROFESSION_DISPLAY.put(taskUid, displayName == null ? taskUid : displayName);
    }

    /** 读取女仆当前职业标识。未识别返回 null（无法认领工位）。 */
    public static String professionOf(EntityMaid maid) {
        if (maid == null) return null;
        IMaidTask task;
        try {
            task = maid.getTask();
        } catch (Throwable t) {
            return null;
        }
        if (task == null) return null;
        String uid;
        try {
            ResourceLocation rl = task.getUid();
            uid = rl == null ? "" : rl.toString();
        } catch (Throwable t) {
            uid = "";
        }
        if (MaidUtils.TASK_COOK.equals(uid)) return PROF_COOK;
        if (MaidUtils.TASK_WAITER.equals(uid)) return PROF_WAITER;
        if (EXT_PROFESSION_STATION.containsKey(uid)) return PROF_EXT_PREFIX + uid;
        String cls = task.getClass().getSimpleName().toLowerCase();
        // 启发式：第三方厨艺类职业（茶博士 / 调酒师 / 其他厨具职业）默认按厨具工位处理
        if (uid.contains("cook") || cls.contains("cook") || cls.contains("chef")
                || uid.contains("tea") || cls.contains("tea")
                || uid.contains("bar") || cls.contains("bar")) {
            return PROF_EXT_PREFIX + uid;
        }
        if (uid.contains("wait") || cls.contains("wait") || cls.contains("serve")) {
            return PROF_EXT_PREFIX + uid;
        }
        return null;
    }

    /** 职业对应的工位类型列表。 */
    public static List<String> acceptedStationTypes(String profession) {
        if (profession == null) return List.of();
        if (PROF_WAITER.equals(profession)) return List.of(STATION_COUNTER);
        if (profession.startsWith(PROF_EXT_PREFIX)) {
            String uid = profession.substring(PROF_EXT_PREFIX.length());
            String registered = EXT_PROFESSION_STATION.get(uid);
            if (registered != null) return List.of(registered);
        }
        return List.of(STATION_COOKWARE);
    }

    /**
     * 职业（而非工作方块）的显示名：厨师 / 侍者 / 其他兼容职业。
     *
     * <p>员工总览按本方法返回值分组。扩展职业优先用 {@link #registerProfession} 注册的显示名，
     * 未注册时按任务 UID/类名后缀给出可读名（茶博士 / 调酒师 等常见职业做启发式映射）。</p>
     */
    public static String professionLabel(String profession) {
        if (profession == null) return "其他职业";
        if (PROF_COOK.equals(profession)) return "厨师";
        if (PROF_WAITER.equals(profession)) return "侍者";
        if (profession.startsWith(PROF_EXT_PREFIX)) {
            String uid = profession.substring(PROF_EXT_PREFIX.length());
            String registered = EXT_PROFESSION_DISPLAY.get(uid);
            if (registered != null && !registered.isEmpty()) return registered;
            String suffix = uid.contains(":") ? uid.substring(uid.indexOf(':') + 1) : uid;
            String lower = suffix.toLowerCase();
            if (lower.contains("tea")) return "茶博士";
            if (lower.contains("bartend") || lower.contains("bar")) return "调酒师";
            if (lower.contains("brew")) return "酿酒师";
            if (lower.contains("cook") || lower.contains("chef")) return "厨师";
            if (lower.contains("wait") || lower.contains("serve")) return "侍者";
            return "其他职业(" + suffix + ")";
        }
        return "其他职业";
    }

    /** 职业显示名（气泡文案用）。 */
    public static String professionDisplayName(String profession) {
        if (profession == null) return "工作方块";
        if (PROF_COOK.equals(profession)) return "厨具";
        if (PROF_WAITER.equals(profession)) return "操作台";
        if (profession.startsWith(PROF_EXT_PREFIX)) {
            String uid = profession.substring(PROF_EXT_PREFIX.length());
            String name = EXT_PROFESSION_DISPLAY.get(uid);
            if (name != null) return name;
        }
        return "工作方块";
    }

    public static boolean isProductionProfession(String profession) {
        return profession != null && acceptedStationTypes(profession).contains(STATION_COOKWARE);
    }

    public static boolean isWaiterProfession(String profession) {
        return PROF_WAITER.equals(profession);
    }

    /** 某个方块是否可作为该女仆当前职业的工位（复用本体检测代码，不硬编码方块类）。 */
    public static boolean isStationValidFor(ServerLevel level, EntityMaid maid, BlockPos pos) {
        if (level == null || maid == null || pos == null || !level.isLoaded(pos)) return false;
        String profession = professionOf(maid);
        if (profession == null) return false;
        List<String> types = acceptedStationTypes(profession);
        if (types.contains(STATION_COUNTER)) {
            return level.getBlockEntity(pos) instanceof TakeoutBoxBlockEntity;
        }
        BlockState state = level.getBlockState(pos);
        if (state.isAir()) return false;
        for (ICookTask task : CookTasks.getRegistered()) {
            try {
                if (task.isValidWorkBlock(level, maid, pos)) return true;
            } catch (Throwable ignored) {
            }
        }
        return false;
    }

    /** 工位对应的厨具/设备 UID（厨师用于与配方所需厨具类型匹配）。 */
    public static String stationUid(ServerLevel level, EntityMaid maid, BlockPos pos) {
        if (level == null || maid == null || pos == null) return "";
        for (ICookTask task : CookTasks.getRegistered()) {
            try {
                if (task.isValidWorkBlock(level, maid, pos)) {
                    return task.getUID();
                }
            } catch (Throwable ignored) {
            }
        }
        if (level.getBlockEntity(pos) instanceof TakeoutBoxBlockEntity) return "ordertocook:counter";
        return "unknown:" + pos.toShortString();
    }

    /** 配方类型对应的厨具 UID（三层过滤的"厨具类型匹配"依据）。 */
    public static String requiredCookUid(RecipeType<?> type) {
        if (type == null) return "";
        try {
            String uid = CookTasks.getUID(type);
            return uid == null ? "" : uid;
        } catch (Throwable t) {
            return "";
        }
    }

    /** 是否为合法的女仆餐厅板凳（厨凳/椅子）。 */
    public static boolean isSeat(ServerLevel level, BlockPos pos) {
        if (level == null || pos == null || !level.isLoaded(pos)) return false;
        try {
            return level.getBlockState(pos).is(TagBlock.SIT_BLOCK);
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 在工位附近找一个可站立的板凳：优先工位正下方，其次同层四邻。
     * 返回 null 表示没有板凳（按开发文档，此时不允许认领厨具工位）。
     */
    public static BlockPos findSeatNear(ServerLevel level, BlockPos stationPos) {
        if (level == null || stationPos == null) return null;
        BlockPos[] candidates = new BlockPos[]{
                stationPos.below(),
                stationPos.below().north(), stationPos.below().south(),
                stationPos.below().east(), stationPos.below().west(),
                stationPos.north(), stationPos.south(), stationPos.east(), stationPos.west()
        };
        for (BlockPos p : candidates) {
            if (isSeat(level, p)) return p.immutable();
        }
        return null;
    }

    /**
     * 搜索一个未被认领的可用工位。
     *
     * @param claimed 已被其它女仆认领的工位坐标（asLong）
     */
    public static BlockPos findFreeStation(ServerLevel level, EntityMaid maid, BlockPos center,
                                           int radius, int vertical, Set<Long> claimed) {
        if (level == null || maid == null || center == null) return null;
        String profession = professionOf(maid);
        if (profession == null) return null;
        List<String> types = acceptedStationTypes(profession);

        if (types.contains(STATION_COOKWARE)) {
            // 复用本体选工作位代码：逐个 ICookTask 让本体找，找到后校验未被认领
            for (ICookTask task : CookTasks.getRegistered()) {
                try {
                    BlockPos found = task.searchWorkBlock(level, maid, radius, vertical);
                    if (found != null && !claimed.contains(found.asLong())
                            && isStationValidFor(level, maid, found)) {
                        return found.immutable();
                    }
                } catch (Throwable ignored) {
                }
            }
        }

        // 兜底：以调度显示器为中心的局部扫描（只扫一次，半径/垂直高度受限）
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        BlockPos best = null;
        double bestDist = Double.MAX_VALUE;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (dx * dx + dz * dz > radius * radius) continue;
                for (int dy = -vertical; dy <= vertical; dy++) {
                    m.set(center.getX() + dx, center.getY() + dy, center.getZ() + dz);
                    if (!level.isLoaded(m)) continue;
                    if (claimed.contains(m.asLong())) continue;
                    if (!isStationValidFor(level, maid, m)) continue;
                    double d = m.distSqr(center);
                    if (d < bestDist) {
                        bestDist = d;
                        best = m.immutable();
                    }
                }
            }
        }
        return best;
    }
}
