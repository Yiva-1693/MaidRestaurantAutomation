package com.icewolf.maidrestaurant.automation.client.screen;

import com.icewolf.maidrestaurant.automation.block.entity.DispatcherBlockEntity;
import com.icewolf.maidrestaurant.automation.menu.DispatcherMenu;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import javax.annotation.Nullable;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Mth;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.level.block.entity.BlockEntity;

/**
 * 「餐厅调度中心」界面：只读展示绑定打单机状态、员工总览、订单队列与食物队列。
 *
 * <p>背景与文字全部由 fill / drawString 手绘（半透明深色 0xE6161B22 + 金色描边），
 * 不依赖贴图、原版 tooltip 渲染或任何外部库。</p>
 *
 * <p><b>本次改版的核心：整页单一滚动 + 横向流式排列。</b></p>
 * <ul>
 *     <li><b>唯一全局滚动</b>：整个界面只有一根滚动条、一个 {@code scrollOffset}。顶部信息区
 *     （标题 / 状态 / 打单机 / 餐厅 / 提示）也属于滚动内容，不单独固定；滚轮在窗口任意位置
 *     滚动整页，视口用 {@code enableScissor} 裁剪，内容不超出视口时不画滚动条。</li>
 *     <li><b>员工总览</b>：店长单独一行、居中显示金色 chip；其余玩家名（青色 chip）保持原有
 *     横向流式排列；女仆名按职业分组（组标题为「职业名（数量）」标签：厨师暖色 / 侍者冷色 /
 *     其他职业淡灰金；组内已认领绿 / 未认领红 chip），
 *     每组内部横向流式排列、超过可用宽度自动换行，组与组之间留 2px 间距；鼠标悬停在某个
 *     女仆 chip 上时，弹出 2 列 × 3 行（厨具 / 主人 / 工位）的自绘表格；底部一行显示
 *     {@code getStaffFooter()}。</li>
 *     <li><b>订单队列</b>：按「已接待 / 未接待」分两组，每组一行 chip 组标题；订单两列对齐，
 *     左列「★价格 / 剩余时间 / 顾客」、右列「状态 / 处理侍者」，行带淡黑底、失败订单偏红，
 *     悬停显示 Hover1~3 三行信息。</li>
 *     <li><b>食物队列</b>：两列竖排，左列菜名（含 Flag 前缀色）、右列负责人 / 状态，
 *     列宽按内容自动测量并限制在可用宽度内。</li>
 * </ul>
 *
 * <p>布局在 {@link #recomputeLayout()} 中每帧重新测量：所有 y 都以「滚动内容顶部」为原点
 * 记录成相对坐标，绘制时统一加滚动偏移换算成屏幕坐标，因此内容可以比窗口高，
 * 720p（约 426×240）与 1080p（约 480×270）都靠全局滚动查看全部信息，不会溢出屏幕。</p>
 */
public class DispatcherScreen extends AbstractContainerScreen<DispatcherMenu> {

    // ==================== 语言键 ====================
    private static final String KEY_STAFF = "screen.maid_restaurant_automation.dispatcher.staff";
    private static final String KEY_ORDERS = "screen.maid_restaurant_automation.dispatcher.orders";
    private static final String KEY_FOOD = "screen.maid_restaurant_automation.dispatcher.food";
    private static final String KEY_LOADING = "screen.maid_restaurant_automation.dispatcher.loading";
    private static final String KEY_PROFESSION = "screen.maid_restaurant_automation.dispatcher.profession";
    private static final String KEY_OWNER = "screen.maid_restaurant_automation.dispatcher.owner";
    private static final String KEY_STATION = "screen.maid_restaurant_automation.dispatcher.station";
    private static final String KEY_NONE = "screen.maid_restaurant_automation.dispatcher.none";
    private static final String KEY_EMPTY_MAID = "screen.maid_restaurant_automation.dispatcher.empty.maid";
    private static final String KEY_EMPTY_ORDER = "screen.maid_restaurant_automation.dispatcher.empty.order";
    private static final String KEY_EMPTY_FOOD = "screen.maid_restaurant_automation.dispatcher.empty.food";
    private static final String KEY_HINT = "screen.maid_restaurant_automation.dispatcher.hint";
    private static final String KEY_STATUS = "screen.maid_restaurant_automation.dispatcher.status";
    private static final String KEY_STATUS_UNBOUND = "screen.maid_restaurant_automation.dispatcher.status.unbound";
    private static final String KEY_STATUS_OK = "screen.maid_restaurant_automation.dispatcher.status.ok";
    private static final String KEY_STATUS_LOST = "screen.maid_restaurant_automation.dispatcher.status.lost";
    private static final String KEY_STATUS_NO_STAFF = "screen.maid_restaurant_automation.dispatcher.status.no_staff";
    private static final String KEY_MACHINE = "screen.maid_restaurant_automation.dispatcher.machine";
    private static final String KEY_MACHINE_UNBOUND = "screen.maid_restaurant_automation.dispatcher.machine.unbound";

    // ==================== 尺寸 / 布局常量 ====================
    /** 主面板最大 / 最小宽度（逻辑像素）。 */
    private static final int GUI_MAX_WIDTH = 400;
    private static final int GUI_MIN_WIDTH = 180;
    /** 主面板与屏幕左右边缘的预留间距。 */
    private static final int GUI_SIDE_MARGIN = 6;
    /** 主面板与屏幕上下边缘的预留间距（面板纵向占满窗口，滚动才有意义）。 */
    private static final int GUI_SCREEN_MARGIN = 4;
    /** 主面板内边距（子区块与金色描边之间的距离）。 */
    private static final int PANEL_MARGIN = 6;
    /** 区块内文字左侧缩进。 */
    private static final int TEXT_INSET = 5;
    /** 视口在面板边框内再收进去的留白。 */
    private static final int VIEW_TOP_PAD = 5;
    private static final int VIEW_BOTTOM_PAD = 4;

    /** 顶部区各行高度：标题行、普通信息行、提示行前置间距。 */
    private static final int TITLE_ROW_H = 12;
    private static final int TOP_LINE_H = 10;
    private static final int NOTICE_GAP = 4;
    /** 顶部信息区与第一个区块的间距。 */
    private static final int TOP_TO_STAFF_GAP = 6;

    /** 区块：标题区（标题 + 分割线）高度、区块底部留白、区块之间的间距。 */
    private static final int SECTION_HEADER_H = 14;
    private static final int SECTION_BOTTOM_PAD = 4;
    private static final int SECTION_GAP = 5;

    /** 员工总览：店长行高、chip 高度 / 行距 / 间距 / 内边距、页脚行高。 */
    private static final int BOSS_ROW_H = 12;
    private static final int CHIP_H = 10;
    private static final int CHIP_ROW_H = 12;
    private static final int CHIP_GAP = 4;
    private static final int CHIP_PAD_X = 3;
    /** 女仆职业分组：组标题行高（与 chip 行距一致）、组间距、职业键分隔符与保底组名。 */
    private static final int GROUP_TITLE_H = CHIP_ROW_H;
    private static final int GROUP_GAP = 2;
    /** 旧契约 "Profession"（工作方块类型 · UID）的分隔符：仅在 "Job" 缺失时用于回退分组。 */
    private static final String PROFESSION_SEPARATOR = " · ";
    /** 服务端 "Job" 字段的两个主职业显示名，其余（茶博士 / 调酒师 / 其他职业(xxx)…）统一排后。 */
    private static final String JOB_COOK = "厨师";
    private static final String JOB_WAITER = "侍者";
    /** "Job" 与 "Profession" 都为空时的保底组名。 */
    private static final String GROUP_OTHER = "其他职业";
    /** 服务端在食物队列为空时硬编码的占位菜名：GUI 过滤掉并改用语言键显示空状态。 */
    private static final String FOOD_PLACEHOLDER = "暂无待制作食物";
    /** 页脚：分割线与文字之间的间距 + 文字行高。 */
    private static final int FOOTER_GAP = 4;
    private static final int FOOTER_LINE_H = 10;

    /** 订单行高、食物行高、空列表占位行高；两列之间的固定间距。 */
    private static final int ORDER_ROW_H = 12;
    private static final int FOOD_ROW_H = 10;
    private static final int EMPTY_ROW_H = 11;
    private static final int COLUMN_GAP = 8;
    /** 整页内容底部留白。 */
    private static final int CONTENT_BOTTOM_PAD = 4;

    /** 全局滚动：每格滚轮滚动的像素、滚动条宽度、内容右侧给滚动条让出的空间。 */
    private static final int SCROLL_STEP = 18;
    private static final int SCROLL_BAR_W = 3;
    private static final int SCROLL_SPACE = 3;

    /** chip 类型：玩家（青色）/ 女仆（绿或红）。店长 chip 单独居中绘制，不进流式列表。 */
    private static final int CHIP_PLAYER = 1;
    private static final int CHIP_MAID = 2;

    // ==================== 颜色 ====================
    private static final int COLOR_BG = 0xE6161B22;
    private static final int COLOR_BORDER = 0xFFD9B36C;
    private static final int COLOR_SUB_BG = 0x66101519;
    private static final int COLOR_SUB_BORDER = 0x66D9B36C;
    private static final int COLOR_HOVER_ROW = 0x33FFFFFF;
    /** 订单行：普通订单淡黑底、失败订单淡红底（整行文字另外转红）。 */
    private static final int COLOR_ORDER_ROW_BG = 0x22000000;
    private static final int COLOR_ORDER_ROW_FAIL_BG = 0x33CC3333;
    private static final int COLOR_HOVER_BG = 0xEE10151C;
    private static final int COLOR_DIVIDER = 0x55D9B36C;
    private static final int COLOR_DIVIDER_SOFT = 0x33D9B36C;
    private static final int COLOR_SCROLL_TRACK = 0x33FFFFFF;
    private static final int COLOR_SCROLL_BAR = 0xCCD9B36C;
    private static final int COLOR_TEXT = 0xFFFFFF;
    private static final int COLOR_UNCLAIMED_VALUE = 0xFF8080;

    /** chip 配色：店长金色、玩家青色、已认领女仆绿色、未认领女仆红色。 */
    private static final int COLOR_CHIP_BOSS_BG = 0x33E8C35A;
    private static final int COLOR_CHIP_BOSS_EDGE = 0x88E8C35A;
    private static final int COLOR_CHIP_PLAYER_BG = 0x3322CCEE;
    private static final int COLOR_CHIP_PLAYER_EDGE = 0x7722CCEE;
    private static final int COLOR_CHIP_CLAIMED_BG = 0x3322CC66;
    private static final int COLOR_CHIP_CLAIMED_EDGE = 0x7722CC66;
    private static final int COLOR_CHIP_UNCLAIMED_BG = 0x44DD5555;
    private static final int COLOR_CHIP_UNCLAIMED_EDGE = 0x88DD5555;
    /** 职业组标题标签：默认淡灰底 + 淡金边，与玩家青色 / 女仆绿红 chip 明显区分。 */
    private static final int COLOR_CHIP_GROUP_BG = 0x33B8C0CC;
    private static final int COLOR_CHIP_GROUP_EDGE = 0x66D9B36C;
    /** 厨师组暖色、侍者组冷色；其他职业继续沿用上面的淡灰 / 淡金。 */
    private static final int COLOR_CHIP_GROUP_COOK_BG = 0x33E8A35A;
    private static final int COLOR_CHIP_GROUP_COOK_EDGE = 0x88E8A35A;
    private static final int COLOR_CHIP_GROUP_WAITER_BG = 0x3322CCEE;
    private static final int COLOR_CHIP_GROUP_WAITER_EDGE = 0x7722CCEE;
    private static final int COLOR_CHIP_HOVER = 0x33FFFFFF;

    // ==================== 顶部文案（每帧按 BE 快照刷新） ====================
    /** 标题（headerLines[0]，为空时回退 this.title）。 */
    @Nullable
    private String titleLine;
    private String machineLine = "";
    @Nullable
    private String restaurantLine;
    private String bossName = "";
    private String footerText = "";
    private boolean hasFooter;
    private final List<String> noticeShown = new ArrayList<>();

    // ==================== 布局结果：全部是「相对滚动内容顶部」的 y（-1 = 不显示） ====================
    private int relStatusY;
    private int relMachineY = -1;
    private int relRestaurantY = -1;
    private int relNoticeY = -1;
    private int relStaffTop;
    private int relStaffBottom;
    private int relBossY;
    private int relFlowTop;
    private int relFooterY = -1;
    private int relOrderTop;
    private int relOrderListTop;
    private int relOrderBottom;
    private int relFoodTop;
    private int relFoodListTop;
    private int relFoodBottom;
    /** 整页内容总高度（不含顶部标题行上方留白）。 */
    private int contentHeight;

    // ==================== 视口（屏幕绝对坐标） ====================
    private int viewportLeft;
    private int viewportTop;
    private int viewportRight;
    private int viewportBottom;

    // ==================== 全局滚动状态 ====================
    /** 当前滚动偏移（像素），限制在 [0, maxScroll]。 */
    private int scrollOffset;
    /** 最大滚动偏移；<= 0 表示内容不超出视口（不画滚动条）。 */
    private int maxScroll;

    // ==================== 流式 chip 排版结果（每帧重算） ====================
    /** 玩家 + 女仆名字 chip（不含职业组标题，组标题单独按 {@link #maidGroups} 绘制）。 */
    private final List<Chip> chips = new ArrayList<>();
    /** 女仆职业分组（组标题行 + 组内女仆），顺序已按「厨师 → 侍者 → 其他职业」排好。 */
    private final List<MaidGroup> maidGroups = new ArrayList<>();
    /** 员工流式区总高度（玩家行 + 各组标题行 / chip 行 + 组间距），像素。 */
    private int flowHeight = EMPTY_ROW_H;
    /** 订单 / 食物的有效条目（已过滤 null 与服务端空队列占位），布局与绘制共用。 */
    private final List<CompoundTag> orderEntries = new ArrayList<>();
    private final List<CompoundTag> foodEntries = new ArrayList<>();
    /** 订单两列排版结果（按「已接待 → 未接待」分组后的绘制顺序）。 */
    private final List<OrderRow> orderRows = new ArrayList<>();
    /** 食物两列排版结果（按服务端 Flag 升序）。 */
    private final List<FoodRow> foodRows = new ArrayList<>();
    /** 订单分组标题 / 计数 / 相对 y：数量为 0 的组也画标题（无任何订单时改为一行占位）。 */
    private int receivedOrderCount;
    private int unreceivedOrderCount;
    private String receivedOrderTitle = "";
    private String unreceivedOrderTitle = "";
    private int receivedOrderTitleWidth;
    private int unreceivedOrderTitleWidth;
    private int relReceivedOrderTitleY = -1;
    private int relUnreceivedOrderTitleY = -1;
    private int relOrderEmptyY = -1;
    /** 两列列宽：布局阶段按内容测量并限制最大宽度，绘制阶段直接复用以保证逐行对齐。 */
    private int orderLeftW;
    private int orderRightW;
    private int foodLeftW;
    private int foodRightW;

    // ==================== 本帧悬停的条目（renderBg 中更新，super.render 之后绘制悬浮框） ====================
    @Nullable
    private CompoundTag hoveredMaid;
    @Nullable
    private CompoundTag hoveredOrder;

    public DispatcherScreen(DispatcherMenu menu, Inventory inventory, Component title) {
        super(menu, inventory, title);
        this.imageWidth = GUI_MAX_WIDTH;
        this.imageHeight = 220;
    }

    @Override
    protected void init() {
        super.init();
        // 窗口尺寸变化会重新 init，滚动偏移保留（recomputeLayout 里会夹紧），不会跳回顶部
        this.recomputeLayout();
    }

    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        // 每帧重算：服务端快照随时会刷新（提示行出现 / 消失、员工增减、订单变化），
        // 整页高度与 chip 换行必须跟着数据与窗口尺寸走。
        this.recomputeLayout();
        this.renderBackground(graphics);
        super.render(graphics, mouseX, mouseY, partialTick);
        // 悬浮表格 / 悬浮订单信息自绘，且必须在 super.render 之后，避免被面板覆盖。
        this.renderHoverOverlay(graphics, mouseX, mouseY);
    }

    /** 背景全部手绘：只有一层压暗的世界，主面板在 renderBg 中绘制。 */
    @Override
    public void renderBackground(GuiGraphics graphics) {
        graphics.fill(0, 0, this.width, this.height, 0x99101418);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        this.hoveredMaid = null;
        this.hoveredOrder = null;

        int x = this.leftPos;
        int y = this.topPos;

        // ===== 主面板：半透明深色 + 金色描边 + 顶部内嵌高光 =====
        graphics.fill(x, y, x + this.imageWidth, y + this.imageHeight, COLOR_BG);
        graphics.renderOutline(x, y, this.imageWidth, this.imageHeight, COLOR_BORDER);
        graphics.fill(x + 1, y + 1, x + this.imageWidth - 1, y + 2, COLOR_DIVIDER_SOFT);

        // ===== 唯一视口：整页内容一起滚动，超出部分用 scissor 裁掉 =====
        graphics.enableScissor(this.viewportLeft, this.viewportTop, this.viewportRight, this.viewportBottom);
        this.drawPage(graphics, mouseX, mouseY);
        graphics.disableScissor();

        // ===== 全局滚动条（不参与裁剪，画在面板右侧留白里） =====
        this.drawGlobalScrollbar(graphics);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        // 不渲染原版标题与背包标签（全部手绘）
    }

    // ==================== 整页绘制 ====================

    /**
     * 绘制滚动内容：所有相对 y 都通过 {@link #pageY(int)} 换算成屏幕坐标，
     * 因此顶部信息区与三个区块共享同一个滚动偏移（没有任何独立滚动）。
     */
    private void drawPage(GuiGraphics graphics, int mouseX, int mouseY) {
        int panelLeft = this.leftPos + PANEL_MARGIN;
        int contentW = this.imageWidth - PANEL_MARGIN * 2;
        int textX = panelLeft + TEXT_INSET;
        int textW = Math.max(40, contentW - TEXT_INSET * 2 - SCROLL_SPACE);

        // ===== 标题（样式沿用上一版：headerLines[0] 优先，回退 §6§l + this.title） =====
        String titleText = this.titleLine != null ? this.titleLine : "§6§l" + this.title.getString();
        graphics.drawString(this.font, titleText,
                panelLeft + (contentW - this.plainWidth(titleText)) / 2, this.pageY(0) + 2, COLOR_TEXT);

        // ===== 状态行：按 getStatusMode() 变色（0 灰 / 1 绿 / 2 红 / 3 黄） =====
        DispatcherBlockEntity dispatcher = this.getDispatcher();
        int mode = dispatcher != null ? dispatcher.getStatusMode() : -1;
        graphics.drawString(this.font, this.clip("§7" + tr(KEY_STATUS) + statusText(mode), textW),
                textX, this.pageY(this.relStatusY) + 1, COLOR_TEXT);

        // ===== 右上角操作提示 =====
        String hint = "§8" + tr(KEY_HINT);
        graphics.drawString(this.font, hint,
                panelLeft + contentW - TEXT_INSET - SCROLL_SPACE - this.plainWidth(hint),
                this.pageY(this.relStatusY) + 1, COLOR_TEXT);

        // ===== 打单机行（headerLines[1]，缺失时本地兜底） =====
        if (this.relMachineY >= 0) {
            graphics.drawString(this.font, this.machineLine,
                    textX, this.pageY(this.relMachineY) + 1, COLOR_TEXT);
        }
        // ===== 餐厅 / 店长 / 等级行（headerLines[2]，可能不存在） =====
        if (this.restaurantLine != null && this.relRestaurantY >= 0) {
            graphics.drawString(this.font, this.restaurantLine,
                    textX, this.pageY(this.relRestaurantY) + 1, COLOR_TEXT);
        }
        // ===== 顶部黄色提示（可多行，跟随滚动） =====
        if (this.relNoticeY >= 0) {
            for (int i = 0; i < this.noticeShown.size(); i++) {
                graphics.drawString(this.font, this.noticeShown.get(i), textX,
                        this.pageY(this.relNoticeY + i * TOP_LINE_H) + 1, COLOR_TEXT);
            }
        }

        // ===== 员工总览区块 =====
        this.drawSection(graphics, this.relStaffTop, this.relStaffBottom - this.relStaffTop, tr(KEY_STAFF));
        this.drawBossRow(graphics);
        this.drawChips(graphics, mouseX, mouseY);
        this.drawStaffFooter(graphics);

        // ===== 订单队列区块 =====
        this.drawSection(graphics, this.relOrderTop, this.relOrderBottom - this.relOrderTop, tr(KEY_ORDERS));
        this.drawOrderRows(graphics, mouseX, mouseY, textX);

        // ===== 食物队列区块 =====
        this.drawSection(graphics, this.relFoodTop, this.relFoodBottom - this.relFoodTop, tr(KEY_FOOD));
        this.drawFoodRows(graphics, textX);
    }

    /** 区块外框：半透明底 + 金边 + 居中金色标题 + 标题下分割线。整体可能被 scissor 裁掉一部分。 */
    private void drawSection(GuiGraphics graphics, int relTop, int height, String title) {
        if (height <= 0) {
            return;
        }
        int panelLeft = this.leftPos + PANEL_MARGIN;
        int contentW = this.imageWidth - PANEL_MARGIN * 2;
        int top = this.pageY(relTop);
        int bottom = top + height;
        // 完全滚出视口时直接跳过
        if (bottom < this.viewportTop || top > this.viewportBottom) {
            return;
        }
        graphics.fill(panelLeft, top, panelLeft + contentW, bottom, COLOR_SUB_BG);
        graphics.renderOutline(panelLeft, top, contentW, height, COLOR_SUB_BORDER);
        String text = "§6" + title;
        graphics.drawString(this.font, text,
                panelLeft + (contentW - this.plainWidth(text)) / 2, top + 3, COLOR_TEXT);
        graphics.fill(panelLeft + 4, top + SECTION_HEADER_H - 3,
                panelLeft + contentW - 4, top + SECTION_HEADER_H - 2, COLOR_DIVIDER);
    }

    /**
     * 店长行：整行居中的金色 chip，绘制方式与女仆 / 玩家 chip 完全一致（半透明底色 +
     * 1px 边框 + 左右内边距），只是配色换成金色、文字用 §e。店长名字为空时整行不画。
     */
    private void drawBossRow(GuiGraphics graphics) {
        if (this.bossName.isEmpty()) {
            return;
        }
        int panelLeft = this.leftPos + PANEL_MARGIN;
        int contentW = this.imageWidth - PANEL_MARGIN * 2;
        int flowW = Math.max(40, contentW - TEXT_INSET * 2 - SCROLL_SPACE);
        String shown = this.clip("§e" + this.bossName, Math.max(8, flowW - CHIP_PAD_X * 2));
        int chipW = this.plainWidth(shown) + CHIP_PAD_X * 2;
        int sx = panelLeft + (contentW - chipW) / 2;
        int sy = this.pageY(this.relBossY) + (BOSS_ROW_H - CHIP_H) / 2;
        if (sy + CHIP_H < this.viewportTop || sy > this.viewportBottom) {
            return;
        }
        this.drawChip(graphics, sx, sy, chipW, shown, COLOR_CHIP_BOSS_BG, COLOR_CHIP_BOSS_EDGE, false);
    }

    // ==================== 流式 chip 绘制 + 悬浮判定 ====================

    /**
     * 先画各职业组的标题标签（「职业名（数量）」：厨师暖色 / 侍者冷色 / 其他职业淡灰金，
     * 不参与悬浮），再画横向流式 chip：
     * 玩家青色、女仆已认领绿 / 未认领红，只显示名字。
     *
     * <p>chip / 组标题的位置是 {@link #chips}、{@link #maidGroups} 在测量阶段算好的相对坐标，
     * 这里换算成屏幕坐标后：完全滚出视口的不画；鼠标落在某个女仆 chip 的矩形内则记录
     * {@link #hoveredMaid}，由 {@link #renderHoverOverlay} 在 super.render 之后弹出 2×3 详情表格。</p>
     */
    private void drawChips(GuiGraphics graphics, int mouseX, int mouseY) {
        int panelLeft = this.leftPos + PANEL_MARGIN;
        if (this.chips.isEmpty()) {
            // 一条员工信息都没有：显示占位文案
            graphics.drawString(this.font, "§7" + tr(KEY_EMPTY_MAID),
                    panelLeft + TEXT_INSET, this.pageY(this.relFlowTop) + 1, COLOR_TEXT);
            return;
        }
        // 职业组标题：与名字 chip 同高，厨师暖色 / 侍者冷色 / 其他职业淡灰金，且不响应悬浮
        for (MaidGroup group : this.maidGroups) {
            int sx = panelLeft + TEXT_INSET;
            int sy = this.pageY(group.relTitleY);
            if (sy + CHIP_H < this.viewportTop || sy > this.viewportBottom) {
                continue;
            }
            this.drawChip(graphics, sx, sy, group.titleWidth, group.title,
                    group.titleBg(), group.titleEdge(), false);
        }
        for (Chip chip : this.chips) {
            int sx = panelLeft + chip.relX;
            int sy = this.pageY(chip.relY);
            // 完全滚出视口：画了也看不见，悬浮也不可能命中
            if (sy + CHIP_H < this.viewportTop || sy > this.viewportBottom) {
                continue;
            }
            boolean hover = this.isInside(mouseX, mouseY, sx, sy, chip.width, CHIP_H);
            int bg = COLOR_CHIP_PLAYER_BG;
            int edge = COLOR_CHIP_PLAYER_EDGE;
            if (chip.kind == CHIP_MAID) {
                boolean claimed = chip.tag != null && chip.tag.getBoolean("Claimed");
                if (claimed) {
                    bg = COLOR_CHIP_CLAIMED_BG;
                    edge = COLOR_CHIP_CLAIMED_EDGE;
                } else {
                    bg = COLOR_CHIP_UNCLAIMED_BG;
                    edge = COLOR_CHIP_UNCLAIMED_EDGE;
                }
            }
            this.drawChip(graphics, sx, sy, chip.width, chip.text, bg, edge, hover);
            if (hover && chip.kind == CHIP_MAID && chip.tag != null) {
                this.hoveredMaid = chip.tag;
            }
        }
    }

    /**
     * 单个 chip 的统一绘制（店长 / 玩家 / 女仆共用同一套格式）：半透明底色 + 1px 边框 +
     * 左右 {@link #CHIP_PAD_X} 内边距的文字；悬停时再叠一层高亮。
     */
    private void drawChip(GuiGraphics graphics, int sx, int sy, int width, String text,
            int bg, int edge, boolean hover) {
        graphics.fill(sx, sy, sx + width, sy + CHIP_H, bg);
        graphics.renderOutline(sx, sy, width, CHIP_H, edge);
        if (hover) {
            graphics.fill(sx, sy, sx + width, sy + CHIP_H, COLOR_CHIP_HOVER);
        }
        graphics.drawString(this.font, text, sx + CHIP_PAD_X, sy + 1, COLOR_TEXT);
    }

    /** 底部一行：getStaffFooter()（服务端已带颜色码），上方一条淡金分割线。 */
    private void drawStaffFooter(GuiGraphics graphics) {
        if (!this.hasFooter) {
            return;
        }
        int panelLeft = this.leftPos + PANEL_MARGIN;
        int contentW = this.imageWidth - PANEL_MARGIN * 2;
        int rowY = this.pageY(this.relFooterY);
        graphics.fill(panelLeft + 4, rowY - FOOTER_GAP / 2, panelLeft + contentW - 4,
                rowY - FOOTER_GAP / 2 + 1, COLOR_DIVIDER_SOFT);
        graphics.drawString(this.font, this.footerText, panelLeft + TEXT_INSET, rowY + 1, COLOR_TEXT);
    }

    // ==================== 订单 / 食物行绘制 ====================

    /**
     * 订单队列：先画「已接待 / 未接待」两行 chip 组标题，再逐行画订单。
     *
     * <p>每行左侧「★价格 / 剩余时间 / 顾客」、右侧「状态 / 处理侍者」，两列 x 坐标由布局阶段
     * 测量好的 {@link #orderLeftW} 决定，因此所有行严格对齐；普通行淡黑底，失败订单淡红底且
     * 整行文字转红。鼠标落在行矩形内时记录 {@link #hoveredOrder}，由
     * {@link #renderHoverOverlay} 在 super.render 之后画 Hover1~3。</p>
     */
    private void drawOrderRows(GuiGraphics graphics, int mouseX, int mouseY, int textX) {
        int panelLeft = this.leftPos + PANEL_MARGIN;
        int contentW = this.imageWidth - PANEL_MARGIN * 2;
        if (this.orderRows.isEmpty()) {
            if (this.relOrderEmptyY >= 0) {
                graphics.drawString(this.font, "§7" + tr(KEY_EMPTY_ORDER),
                        textX, this.pageY(this.relOrderEmptyY) + 1, COLOR_TEXT);
            }
            return;
        }
        this.drawOrderGroupTitle(graphics, this.relReceivedOrderTitleY,
                this.receivedOrderTitle, this.receivedOrderTitleWidth);
        this.drawOrderGroupTitle(graphics, this.relUnreceivedOrderTitleY,
                this.unreceivedOrderTitle, this.unreceivedOrderTitleWidth);

        int rightX = textX + this.orderLeftW + COLUMN_GAP;
        for (OrderRow row : this.orderRows) {
            int rowY = this.pageY(row.relY);
            if (rowY + ORDER_ROW_H < this.viewportTop || rowY > this.viewportBottom) {
                continue;
            }
            int rowBg = row.failed ? COLOR_ORDER_ROW_FAIL_BG : COLOR_ORDER_ROW_BG;
            graphics.fill(panelLeft + 2, rowY, panelLeft + contentW - 2, rowY + ORDER_ROW_H - 1, rowBg);
            if (this.isInside(mouseX, mouseY, panelLeft + 1, rowY, contentW - 2, ORDER_ROW_H)) {
                graphics.fill(panelLeft + 2, rowY, panelLeft + contentW - 2, rowY + ORDER_ROW_H - 1,
                        COLOR_HOVER_ROW);
                this.hoveredOrder = row.tag;
            }
            int textY = rowY + 2;
            graphics.drawString(this.font, row.left, textX, textY, COLOR_TEXT);
            if (!row.right.isEmpty()) {
                graphics.drawString(this.font, row.right, rightX, textY, COLOR_TEXT);
            }
        }
    }

    /** 一行订单组标题：沿用员工总览组标题的淡灰底 + 淡金边 chip，位置取布局阶段的相对 y。 */
    private void drawOrderGroupTitle(GuiGraphics graphics, int relY, String title, int width) {
        if (relY < 0 || width <= 0) {
            return;
        }
        int sy = this.pageY(relY);
        if (sy + CHIP_H < this.viewportTop || sy > this.viewportBottom) {
            return;
        }
        this.drawChip(graphics, this.leftPos + PANEL_MARGIN + TEXT_INSET, sy, width, title,
                COLOR_CHIP_GROUP_BG, COLOR_CHIP_GROUP_EDGE, false);
    }

    /**
     * 食物队列：两列竖排（左列菜名、右列负责人 / 状态），行首不响应点击与悬停；
     * 两列 x 坐标由布局阶段测量好的 {@link #foodLeftW} 决定，保证逐行对齐。
     */
    private void drawFoodRows(GuiGraphics graphics, int textX) {
        if (this.foodRows.isEmpty()) {
            graphics.drawString(this.font, "§7" + tr(KEY_EMPTY_FOOD),
                    textX, this.pageY(this.relFoodListTop) + 1, COLOR_TEXT);
            return;
        }
        int rightX = textX + this.foodLeftW + COLUMN_GAP;
        for (FoodRow row : this.foodRows) {
            int rowY = this.pageY(row.relY);
            if (rowY + FOOD_ROW_H < this.viewportTop || rowY > this.viewportBottom) {
                continue;
            }
            graphics.drawString(this.font, row.left, textX, rowY + 1, COLOR_TEXT);
            if (!row.right.isEmpty()) {
                graphics.drawString(this.font, row.right, rightX, rowY + 1, COLOR_TEXT);
            }
        }
    }

    // ==================== 全局滚动条 ====================

    /**
     * 整页唯一一根滚动条：贴在面板右侧留白里，滑块高度按「视口 / 整页内容」比例算；
     * 内容不超出视口（maxScroll <= 0）时完全不画。
     */
    private void drawGlobalScrollbar(GuiGraphics graphics) {
        int viewH = this.viewportBottom - this.viewportTop;
        if (this.maxScroll <= 0 || viewH <= 0 || this.contentHeight <= 0) {
            return;
        }
        int barX = this.leftPos + this.imageWidth - PANEL_MARGIN - SCROLL_BAR_W - 1;
        int barH = Math.max(12, viewH * viewH / this.contentHeight);
        int barY = this.viewportTop + (viewH - barH) * this.scrollOffset / this.maxScroll;
        graphics.fill(barX, this.viewportTop, barX + SCROLL_BAR_W, this.viewportBottom, COLOR_SCROLL_TRACK);
        graphics.fill(barX, barY, barX + SCROLL_BAR_W, barY + barH, COLOR_SCROLL_BAR);
    }

    // ==================== 布局测量（每帧一次） ====================

    /**
     * 按当前窗口尺寸与服务端快照重算整套布局。
     *
     * <p>公式（全部相对「滚动内容顶部」，单位像素）：</p>
     * <pre>
     * 顶部区  = 12(标题) + 10(状态) + 10(打单机) + [10(餐厅)] + [4 + 10×提示行数]
     * 员工区  = 14(标题区) + 12(店长) + 12×玩家 chip 行数
     *           + Σ[2(组间距) + 12(组标题) + 12×组内 chip 行数] + [FOOTER_GAP + 10(页脚)] + 4(底白)
     * 订单区  = 14(标题区) + [12(已接待标题) + 12×已接待数 + 2(组间距)
     *           + 12(未接待标题) + 12×未接待数]（无订单时 11 占位行）+ 4(底白)
     * 食物区  = 14(标题区) + 10×食物数 + 4(底白)（无食物时 11 占位行）
     * 区块间隔 6(顶部→员工) / 5(员工→订单) / 5(订单→食物) / 4(整页底部)
     * </pre>
     *
     * <p>窗口高度只决定视口高度，不决定内容高度：内容比视口高时滚动，
     * 因此 720p 与 1080p 都不需要压缩或截断信息。</p>
     */
    private void recomputeLayout() {
        if (this.font == null) {
            return;
        }
        DispatcherBlockEntity dispatcher = this.getDispatcher();
        List<String> headers = dispatcher != null ? safeList(dispatcher.getHeaderLines())
                : Collections.<String>emptyList();
        List<String> notices = dispatcher != null ? safeList(dispatcher.getNoticeLines())
                : Collections.<String>emptyList();
        List<String> players = dispatcher != null ? safeList(dispatcher.getPlayerNames())
                : Collections.<String>emptyList();
        List<CompoundTag> maids = dispatcher != null ? safeCompound(dispatcher.getMaidEntries())
                : Collections.<CompoundTag>emptyList();
        List<CompoundTag> rawOrders = dispatcher != null ? safeCompound(dispatcher.getOrderEntries())
                : Collections.<CompoundTag>emptyList();
        List<CompoundTag> rawFoods = dispatcher != null ? safeCompound(dispatcher.getFoodEntries())
                : Collections.<CompoundTag>emptyList();
        String boss = dispatcher != null ? nullToEmpty(dispatcher.getBossName()) : "";
        String footer = dispatcher != null ? nullToEmpty(dispatcher.getStaffFooter()) : "";

        // ---------- 1. 面板 / 视口几何（面板纵向占满窗口，横向自适应） ----------
        int availW = Math.max(120, this.width - GUI_SIDE_MARGIN * 2);
        this.imageWidth = Mth.clamp(availW, GUI_MIN_WIDTH, GUI_MAX_WIDTH);
        this.imageHeight = Math.max(80, this.height - GUI_SCREEN_MARGIN * 2);
        this.leftPos = (this.width - this.imageWidth) / 2;
        this.topPos = (this.height - this.imageHeight) / 2;

        this.viewportLeft = this.leftPos + 1;
        this.viewportRight = this.leftPos + this.imageWidth - 1;
        this.viewportTop = this.topPos + VIEW_TOP_PAD;
        this.viewportBottom = this.topPos + this.imageHeight - VIEW_BOTTOM_PAD;

        int contentW = this.imageWidth - PANEL_MARGIN * 2;
        int flowW = Math.max(40, contentW - TEXT_INSET * 2 - SCROLL_SPACE);

        // ---------- 2. 顶部文案（headerLines 不足 3 行时本地兜底） ----------
        this.titleLine = firstNonEmpty(headers, 0);
        String headerMachine = firstNonEmpty(headers, 1);
        this.machineLine = headerMachine != null ? headerMachine : buildMachineFallback(dispatcher);
        this.machineLine = this.clip(this.machineLine, flowW);
        this.restaurantLine = firstNonEmpty(headers, 2);
        if (this.restaurantLine != null) {
            this.restaurantLine = this.clip(this.restaurantLine, flowW);
        }

        this.noticeShown.clear();
        for (String line : notices) {
            if (line != null && !line.isEmpty()) {
                this.noticeShown.add(this.clip(yellow(line), flowW));
            }
        }

        this.bossName = boss;
        this.footerText = this.clip(footer, flowW);
        this.hasFooter = !this.footerText.isEmpty();

        // ---------- 3. 有效条目（过滤空 tag 与服务端空队列占位，布局与绘制共用同一份） ----------
        this.orderEntries.clear();
        for (CompoundTag entry : rawOrders) {
            if (entry != null && !isEmptyOrderPlaceholder(entry)) {
                this.orderEntries.add(entry);
            }
        }
        this.foodEntries.clear();
        for (CompoundTag entry : rawFoods) {
            if (entry != null && !isEmptyFoodPlaceholder(entry)) {
                this.foodEntries.add(entry);
            }
        }

        // ---------- 4. 顶部区（跟随滚动，不固定） ----------
        int y = 0;
        // 标题行固定在 y = 0，占 TITLE_ROW_H
        y += TITLE_ROW_H;
        this.relStatusY = y;                      // 状态行（右侧带操作提示）
        y += TOP_LINE_H;
        this.relMachineY = y;                     // 打单机行
        y += TOP_LINE_H;
        if (this.restaurantLine != null) {
            this.relRestaurantY = y;              // 餐厅 / 店长 / 等级行
            y += TOP_LINE_H;
        } else {
            this.relRestaurantY = -1;
        }
        if (!this.noticeShown.isEmpty()) {
            y += NOTICE_GAP;
            this.relNoticeY = y;                  // 黄色提示行（可多行）
            y += this.noticeShown.size() * TOP_LINE_H;
        } else {
            this.relNoticeY = -1;
        }
        y += TOP_TO_STAFF_GAP;

        // ---------- 5. 员工总览：店长行 + 流式 chip + 页脚 ----------
        this.relStaffTop = y;
        this.relBossY = this.relStaffTop + SECTION_HEADER_H;
        this.relFlowTop = this.relBossY + BOSS_ROW_H;
        this.buildChips(players, maids, flowW, this.relFlowTop);

        // 流式区高度已在 buildChips 里算好：玩家行 + 各组标题行 / chip 行 / 组间距
        int flowH = this.flowHeight;
        int staffContentBottom = this.relFlowTop + flowH;
        if (this.hasFooter) {
            this.relFooterY = staffContentBottom + FOOTER_GAP;
            staffContentBottom = this.relFooterY + FOOTER_LINE_H;
        } else {
            this.relFooterY = -1;
        }
        this.relStaffBottom = staffContentBottom + SECTION_BOTTOM_PAD;

        // ---------- 6. 订单队列：已接待 / 未接待两组 + 两列对齐行 ----------
        this.relOrderTop = this.relStaffBottom + SECTION_GAP;
        this.relOrderListTop = this.relOrderTop + SECTION_HEADER_H;
        int orderH = this.layoutOrderRows(this.relOrderListTop, flowW);
        this.relOrderBottom = this.relOrderListTop + orderH + SECTION_BOTTOM_PAD;

        // ---------- 7. 食物队列：两列对齐行 ----------
        this.relFoodTop = this.relOrderBottom + SECTION_GAP;
        this.relFoodListTop = this.relFoodTop + SECTION_HEADER_H;
        this.layoutFoodRows(this.relFoodListTop, flowW);
        int foodH = this.foodRows.isEmpty() ? EMPTY_ROW_H : this.foodRows.size() * FOOD_ROW_H;
        this.relFoodBottom = this.relFoodListTop + foodH + SECTION_BOTTOM_PAD;

        // ---------- 8. 整页高度与滚动范围 ----------
        this.contentHeight = this.relFoodBottom + CONTENT_BOTTOM_PAD;
        int viewH = Math.max(0, this.viewportBottom - this.viewportTop);
        this.maxScroll = Math.max(0, this.contentHeight - viewH);
        this.scrollOffset = Mth.clamp(this.scrollOffset, 0, this.maxScroll);
    }

    /**
     * 流式 chip 排版：先玩家（青色），再按职业分组排女仆（每组先一行组标题，组内
     * 已认领绿 / 未认领红）；不再有「店员」标签 chip。
     *
     * <p>玩家仍从 {@code flowTop} 开始横向流式排列。女仆按服务端的 {@code "Job"} 职业显示名
     * （厨师 / 侍者 / 茶博士 / 调酒师 / 其他职业(xxx)…）分组，缺失或空串时回退到
     * {@code "Profession"} 中 {@code " · "} 之前的部分，再空归入「其他职业」；组顺序为
     * 厨师 → 侍者 → 其他职业（同一优先级按组名排序）。每组先占 {@link #GROUP_TITLE_H} 一行画组标题，组内 chip
     * 再横向流式排列、超出 {@code flowW} 换行。组与组之间（以及玩家行与第一组之间）留
     * {@link #GROUP_GAP} 像素。所有坐标都是相对内容顶部的 y，结果写进 {@link #chips} /
     * {@link #maidGroups}，绘制与悬浮判定都只加同一个滚动偏移；流式区总高度写入
     * {@link #flowHeight} 供 {@link #recomputeLayout()} 使用。</p>
     */
    private void buildChips(List<String> players, List<CompoundTag> maids, int flowW, int flowTop) {
        this.chips.clear();
        this.maidGroups.clear();
        int maxChipTextW = Math.max(8, flowW - CHIP_PAD_X * 2);

        // ---------- 1. 女仆按职业分组：组内顺序保持服务端给的顺序（已按名字排序） ----------
        for (CompoundTag maid : maids) {
            if (maid == null) {
                continue;
            }
            String key = jobGroupKey(maid);
            MaidGroup group = null;
            for (MaidGroup candidate : this.maidGroups) {
                if (candidate.key.equals(key)) {
                    group = candidate;
                    break;
                }
            }
            if (group == null) {
                group = new MaidGroup(key);
                this.maidGroups.add(group);
            }
            group.maids.add(maid);
        }
        // ---------- 2. 组排序：厨师 → 侍者 → 其他职业（同一优先级按组名排序） ----------
        this.maidGroups.sort(DispatcherScreen::compareGroups);

        // ---------- 3. 玩家 chip：青色，只显示名字，保持在最上面且样式不变 ----------
        int y = flowTop;
        int row = 0;
        int cursor = 0;
        int playerRows = 0;
        for (String name : players) {
            if (name == null || name.isEmpty()) {
                continue;
            }
            String shown = this.clip(name, maxChipTextW);
            int w = this.font.width(shown) + CHIP_PAD_X * 2;
            if (cursor > 0 && cursor + w > flowW) {
                row++;
                cursor = 0;
            }
            this.chips.add(new Chip("§b" + shown, TEXT_INSET + cursor,
                    flowTop + row * CHIP_ROW_H, w, CHIP_PLAYER, null));
            cursor += w + CHIP_GAP;
            playerRows = row + 1;
        }
        if (playerRows > 0) {
            y += playerRows * CHIP_ROW_H;
        }

        // ---------- 4. 逐组排版：组标题行 + 组内女仆 chip ----------
        for (int gi = 0; gi < this.maidGroups.size(); gi++) {
            MaidGroup group = this.maidGroups.get(gi);
            // 玩家行与第一组之间、组与组之间留 2px
            if (playerRows > 0 || gi > 0) {
                y += GROUP_GAP;
            }
            group.title = this.clip("§7" + group.key + "§8（" + group.maids.size() + "）",
                    maxChipTextW);
            group.relTitleY = y;
            group.titleWidth = this.plainWidth(group.title) + CHIP_PAD_X * 2;

            int chipTop = y + GROUP_TITLE_H;
            int groupRow = 0;
            int groupCursor = 0;
            for (CompoundTag maid : group.maids) {
                String name = nullToEmpty(maid.getString("Name"));
                if (name.isEmpty()) {
                    name = tr(KEY_NONE);
                }
                boolean claimed = maid.getBoolean("Claimed");
                String shown = this.clip(name, maxChipTextW);
                int w = this.font.width(shown) + CHIP_PAD_X * 2;
                if (groupCursor > 0 && groupCursor + w > flowW) {
                    groupRow++;
                    groupCursor = 0;
                }
                this.chips.add(new Chip((claimed ? "§a" : "§c") + shown, TEXT_INSET + groupCursor,
                        chipTop + groupRow * CHIP_ROW_H, w, CHIP_MAID, maid));
                groupCursor += w + CHIP_GAP;
            }
            y = chipTop + (groupRow + 1) * CHIP_ROW_H;
        }

        // ---------- 5. 流式区总高度（含玩家行 / 组标题行 / 组内 chip 行 / 组间距） ----------
        this.flowHeight = this.chips.isEmpty() ? EMPTY_ROW_H : y - flowTop;
    }

    // ==================== 订单 / 食物两列排版 ====================

    /**
     * 订单两列排版：按 {@code "Received"} 分成「已接待 / 未接待」两组（组内保持服务端顺序），
     * 每组先占一行 chip 组标题，组间留 {@link #GROUP_GAP}；两列列宽按内容测量后限制在
     * {@code maxW} 内。所有 y 相对滚动内容顶部，写入 {@link #orderRows} 供绘制与 hover 共用。
     *
     * @return 订单列表区高度（不含区块底部留白）
     */
    private int layoutOrderRows(int listTop, int maxW) {
        this.orderRows.clear();
        this.receivedOrderCount = 0;
        this.unreceivedOrderCount = 0;
        int naturalLeft = 0;
        int naturalRight = 0;

        for (CompoundTag entry : this.orderEntries) {
            boolean received = entry.getBoolean("Received");
            boolean failed = entry.getBoolean("Failed");
            if (received) {
                this.receivedOrderCount++;
            } else {
                this.unreceivedOrderCount++;
            }
            int price = entry.getInt("Price");
            String priceText = price < 0 ? "?" : Integer.toString(price);
            String remain = nullToEmpty(entry.getString("Remain"));
            if (remain.isEmpty()) {
                remain = "--:--";
            }
            String customer = nullToEmpty(entry.getString("Customer"));
            String state = nullToEmpty(entry.getString("State"));
            String handler = nullToEmpty(entry.getString("Handler"));
            // 左列：★价格 / 剩余时间 / 顾客；失败订单整行转红（与淡红行底呼应）
            String left = failed
                    ? "§c★" + priceText + " §c" + remain + " §c" + customer
                    : "§6★" + priceText + " §7" + remain + " §f" + customer;
            // 右列：状态 + 可选的「→ 处理侍者」；没有 Handler 时只显示状态
            String right;
            if (failed) {
                right = "§c" + state;
                if (!handler.isEmpty()) {
                    right += " §8→ §c" + handler;
                }
            } else if (!handler.isEmpty()) {
                right = "§7" + state + " §8→ §a" + handler;
            } else {
                right = "§8" + state;
            }
            this.orderRows.add(new OrderRow(entry, received, failed, left, right));
            naturalLeft = Math.max(naturalLeft, this.plainWidth(left));
            naturalRight = Math.max(naturalRight, this.plainWidth(right));
        }

        // 组标题：与员工总览职业组标题同款 chip（淡灰底 + 淡金边）；数量为 0 也显示
        int titleTextW = Math.max(8, maxW - CHIP_PAD_X * 2);
        this.receivedOrderTitle = this.clip(
                "§7已接待§8（" + this.receivedOrderCount + "）", titleTextW);
        this.receivedOrderTitleWidth = this.plainWidth(this.receivedOrderTitle) + CHIP_PAD_X * 2;
        this.unreceivedOrderTitle = this.clip(
                "§7未接待§8（" + this.unreceivedOrderCount + "）", titleTextW);
        this.unreceivedOrderTitleWidth = this.plainWidth(this.unreceivedOrderTitle) + CHIP_PAD_X * 2;

        int[] columns = fitTwoColumns(maxW, naturalLeft, naturalRight);
        this.orderLeftW = columns[0];
        this.orderRightW = columns[1];

        int y = listTop;
        if (this.orderRows.isEmpty()) {
            // 两组都为空：不画两个 0 计数标题，整体显示一行「暂无订单」
            this.relReceivedOrderTitleY = -1;
            this.relUnreceivedOrderTitleY = -1;
            this.relOrderEmptyY = y;
            y += EMPTY_ROW_H;
        } else {
            this.relOrderEmptyY = -1;
            this.relReceivedOrderTitleY = y;
            y += GROUP_TITLE_H;
            for (OrderRow row : this.orderRows) {
                if (row.received) {
                    row.relY = y;
                    y += ORDER_ROW_H;
                }
            }
            y += GROUP_GAP;
            this.relUnreceivedOrderTitleY = y;
            y += GROUP_TITLE_H;
            for (OrderRow row : this.orderRows) {
                if (!row.received) {
                    row.relY = y;
                    y += ORDER_ROW_H;
                }
            }
        }

        for (OrderRow row : this.orderRows) {
            row.left = this.clip(row.rawLeft, this.orderLeftW);
            row.right = this.clip(row.rawRight, this.orderRightW);
        }
        return y - listTop;
    }

    /**
     * 食物两列排版：左列菜名（Flag 0 带金色 ▶ 前缀），右列负责人 / 状态；两列列宽按全部
     * 条目内容测量后限制在 {@code maxW} 内，所有行共用同一组 x 坐标以保证逐行对齐。
     */
    private void layoutFoodRows(int listTop, int maxW) {
        this.foodRows.clear();
        int naturalLeft = 0;
        int naturalRight = 0;
        for (CompoundTag entry : this.foodEntries) {
            int flag = entry.getInt("Flag");
            String dish = nullToEmpty(entry.getString("Dish"));
            String assignee = nullToEmpty(entry.getString("Assignee"));
            String left;
            String right;
            switch (flag) {
                case 0:
                    // 制作中：左列金色 ▶ + 菜名，右列厨师名 + 黄色状态
                    left = "§6▶ §b" + dish;
                    right = assignee.isEmpty() ? "§8待分配" : "§a" + assignee + " §e(制作中)";
                    break;
                case 1:
                    // 已分配
                    left = "§b" + dish;
                    right = assignee.isEmpty() ? "§8待分配" : "§a" + assignee;
                    break;
                case 3:
                    // 已完成（在操作台）
                    left = "§7" + dish;
                    right = "§a已完成";
                    break;
                case 2:
                default:
                    // 待分配
                    left = "§b" + dish;
                    right = "§8待分配";
                    break;
            }
            this.foodRows.add(new FoodRow(left, right));
            naturalLeft = Math.max(naturalLeft, this.plainWidth(left));
            naturalRight = Math.max(naturalRight, this.plainWidth(right));
        }

        int[] columns = fitTwoColumns(maxW, naturalLeft, naturalRight);
        this.foodLeftW = columns[0];
        this.foodRightW = columns[1];
        int y = listTop;
        for (FoodRow row : this.foodRows) {
            row.left = this.clip(row.rawLeft, this.foodLeftW);
            row.right = this.clip(row.rawRight, this.foodRightW);
            row.relY = y;
            y += FOOD_ROW_H;
        }
    }

    /**
     * 两列自动列宽：内容总宽放得下时完全按内容宽（不浪费空隙）；放不下时优先保证右列
     * 至少可获得一个最小可读宽度，左列吃剩余空间，两列总宽（含 {@link #COLUMN_GAP}）
     * 始终不超过 {@code maxW}。超宽文字在排版阶段再按列宽裁剪加省略号。
     */
    private static int[] fitTwoColumns(int maxW, int naturalLeft, int naturalRight) {
        int usable = Math.max(8, maxW - COLUMN_GAP);
        int left = Math.max(0, naturalLeft);
        int right = Math.max(0, naturalRight);
        if (left + right <= usable) {
            return new int[]{left, right};
        }
        int rightFloor = Math.min(right, Math.max(16, usable / 4));
        left = Math.min(left, Math.max(0, usable - rightFloor));
        right = Math.min(right, Math.max(0, usable - left));
        if (left + right > usable) {
            left = Math.max(0, usable - right);
        }
        return new int[]{left, right};
    }

    /** 服务端空订单队列占位：State / Handler / Hover1 全空（真实订单至少带状态文案）。 */
    private static boolean isEmptyOrderPlaceholder(CompoundTag entry) {
        return entry.getString("State").isEmpty()
                && entry.getString("Handler").isEmpty()
                && entry.getString("Hover1").isEmpty();
    }

    /** 服务端空食物队列占位：硬编码 {@link #FOOD_PLACEHOLDER} 且没有负责人；空菜名也跳过。 */
    private static boolean isEmptyFoodPlaceholder(CompoundTag entry) {
        String dish = entry.getString("Dish");
        return dish.isEmpty()
                || (FOOD_PLACEHOLDER.equals(dish) && entry.getString("Assignee").isEmpty());
    }

    /**
     * 女仆职业分组键：优先用服务端新增的 {@code "Job"} 职业显示名（厨师 / 侍者 / 茶博士 /
     * 调酒师 / 其他职业(xxx)…）；缺失或空串时回退到 {@link #professionGroupKey(String)}，
     * 再空则归入「其他职业」。
     */
    private static String jobGroupKey(CompoundTag maid) {
        String job = nullToEmpty(maid.getString("Job")).trim();
        return job.isEmpty() ? professionGroupKey(maid.getString("Profession")) : job;
    }

    /**
     * 旧契约回退：取 "Profession" 中 {@code " · "} 之前的部分作为分组键（如
     * {@code 厨具 · PotCookTask → 厨具}）；没有分隔符时用整个字符串，空串归入「其他职业」。
     */
    private static String professionGroupKey(@Nullable String profession) {
        String text = nullToEmpty(profession);
        int sep = text.indexOf(PROFESSION_SEPARATOR);
        String key = (sep >= 0 ? text.substring(0, sep) : text).trim();
        return key.isEmpty() ? GROUP_OTHER : key;
    }

    /** 职业组排序：厨师 → 侍者 → 其他职业（茶博士 / 调酒师 / 其他职业(xxx)… 按组名排序）。 */
    private static int compareGroups(MaidGroup a, MaidGroup b) {
        int pa = groupPriority(a.key);
        int pb = groupPriority(b.key);
        if (pa != pb) {
            return pa < pb ? -1 : 1;
        }
        return a.key.compareTo(b.key);
    }

    /** 组优先级：厨师 = 0、侍者 = 1、其余职业 = 2。 */
    private static int groupPriority(String key) {
        if (JOB_COOK.equals(key)) {
            return 0;
        }
        if (JOB_WAITER.equals(key)) {
            return 1;
        }
        return 2;
    }

    // ==================== 自绘悬浮框（不用 renderTooltip，避免版本差异） ====================

    private void renderHoverOverlay(GuiGraphics graphics, int mouseX, int mouseY) {
        if (this.hoveredMaid != null) {
            this.drawMaidTable(graphics, mouseX, mouseY, this.hoveredMaid);
        } else if (this.hoveredOrder != null) {
            this.drawOrderHover(graphics, mouseX, mouseY, this.hoveredOrder);
        }
    }

    /**
     * 女仆详情：2 列 × 3 行表格（列 1 = 厨具 / 主人 / 工位 标签，列 2 = Profession / Owner / Station）。
     * 半透明底 + 金边 + 竖分隔线 + 行分隔线全部自绘，位置跟随鼠标并夹在屏幕内。
     */
    private void drawMaidTable(GuiGraphics graphics, int mouseX, int mouseY, CompoundTag entry) {
        boolean claimed = entry.getBoolean("Claimed");
        String[][] cells = new String[][]{
                {tr(KEY_PROFESSION), nullToEmpty(entry.getString("Profession"))},
                {tr(KEY_OWNER), nullToEmpty(entry.getString("Owner"))},
                {tr(KEY_STATION), nullToEmpty(entry.getString("Station"))}
        };
        for (String[] cell : cells) {
            if (cell[1].isEmpty()) {
                cell[1] = tr(KEY_NONE);
            }
            // 防御：异常长的值（自定义名字 / 坐标）先截断，保证表格不会宽出屏幕
            cell[1] = this.clip(cell[1], 120);
        }
        int labelW = 0;
        int valueW = 0;
        for (String[] cell : cells) {
            labelW = Math.max(labelW, this.plainWidth(cell[0]));
            valueW = Math.max(valueW, this.plainWidth(cell[1]));
        }
        int padX = 5;
        int padY = 4;
        int rowH = 11;
        int colGap = 8;
        int boxW = padX * 2 + labelW + colGap + valueW;
        int boxH = padY * 2 + rowH * cells.length;
        int[] pos = this.placeBox(mouseX, mouseY, boxW, boxH);
        int boxX = pos[0];
        int boxY = pos[1];

        // 半透明底 + 金边
        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, COLOR_HOVER_BG);
        graphics.renderOutline(boxX, boxY, boxW, boxH, COLOR_BORDER);

        // 网格：两列之间的竖线 + 每行之间的横线
        int dividerX = boxX + padX + labelW + colGap / 2;
        graphics.fill(dividerX, boxY + 1, dividerX + 1, boxY + boxH - 1, COLOR_DIVIDER);
        for (int i = 0; i < cells.length; i++) {
            int rowY = boxY + padY + i * rowH;
            if (i > 0) {
                graphics.fill(boxX + 1, rowY - 2, boxX + boxW - 1, rowY - 1, COLOR_DIVIDER_SOFT);
            }
            graphics.drawString(this.font, "§7" + cells[i][0], boxX + padX, rowY + 1, COLOR_TEXT);
            // 未认领女仆的工位值用偏红色，和列表里的红色 chip 呼应
            int valueColor = (i == 2 && !claimed) ? COLOR_UNCLAIMED_VALUE : COLOR_TEXT;
            graphics.drawString(this.font, cells[i][1], boxX + padX + labelW + colGap, rowY + 1,
                    valueColor);
        }
    }

    /** 订单详情：Hover1~3 三行自绘悬浮框（内容已带颜色码，缺省行自动跳过）。 */
    private void drawOrderHover(GuiGraphics graphics, int mouseX, int mouseY, CompoundTag entry) {
        List<String> lines = new ArrayList<>();
        for (int i = 1; i <= 3; i++) {
            String line = entry.getString("Hover" + i);
            if (line != null && !line.isEmpty()) {
                lines.add(line);
            }
        }
        if (lines.isEmpty()) {
            return;
        }
        int textW = 0;
        for (String line : lines) {
            textW = Math.max(textW, this.plainWidth(line));
        }
        int padX = 5;
        int padY = 4;
        int boxW = textW + padX * 2;
        int boxH = padY * 2 + lines.size() * 10;
        int[] pos = this.placeBox(mouseX, mouseY, boxW, boxH);
        int boxX = pos[0];
        int boxY = pos[1];
        graphics.fill(boxX, boxY, boxX + boxW, boxY + boxH, COLOR_HOVER_BG);
        graphics.renderOutline(boxX, boxY, boxW, boxH, COLOR_BORDER);
        for (int i = 0; i < lines.size(); i++) {
            graphics.drawString(this.font, lines.get(i), boxX + padX, boxY + padY + i * 10, COLOR_TEXT);
        }
    }

    /** 悬浮框摆放：默认在鼠标右下，空间不够时翻到另一侧，最后夹在屏幕 4px 边距内。 */
    private int[] placeBox(int mouseX, int mouseY, int boxW, int boxH) {
        int boxX = mouseX + 12;
        int boxY = mouseY - 6;
        if (boxX + boxW > this.width - 4) {
            boxX = mouseX - boxW - 12;
        }
        boxX = Mth.clamp(boxX, 4, Math.max(4, this.width - boxW - 4));
        if (boxY + boxH > this.height - 4) {
            boxY = this.height - boxH - 4;
        }
        boxY = Mth.clamp(boxY, 4, Math.max(4, this.height - boxH - 4));
        return new int[]{boxX, boxY};
    }

    // ==================== 滚轮：整页单一滚动 ====================

    /**
     * 整页滚动：不再按鼠标位置区分面板，滚轮在窗口任意位置都作用于同一个 {@link #scrollOffset}。
     * 每格滚轮滚动 {@link #SCROLL_STEP}（18px），并夹紧在 [0, maxScroll]；
     * 内容不超出视口时交回父类（无滚动可做）。
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (this.maxScroll <= 0 || delta == 0.0D) {
            return super.mouseScrolled(mouseX, mouseY, delta);
        }
        int step = (int)Math.round(delta * SCROLL_STEP);
        if (step == 0) {
            step = delta > 0.0D ? -SCROLL_STEP : SCROLL_STEP;
        }
        // delta > 0（向上滚）应显示更靠前的内容，即减小偏移
        this.scrollOffset = Mth.clamp(this.scrollOffset - step, 0, this.maxScroll);
        return true;
    }

    // ==================== 坐标 / 文本工具 ====================

    /** 内容相对 y → 屏幕 y：叠加唯一一份全局滚动偏移。 */
    private int pageY(int relY) {
        return this.viewportTop + relY - this.scrollOffset;
    }

    /** 鼠标是否落在矩形内，且落在可视视口内（滚动出视口的行不响应悬浮）。 */
    private boolean isInside(double mouseX, double mouseY, int x, int y, int w, int h) {
        if (mouseY < this.viewportTop || mouseY >= this.viewportBottom
                || mouseX < this.viewportLeft || mouseX >= this.viewportRight) {
            return false;
        }
        return mouseX >= x && mouseX < x + w && mouseY >= y && mouseY < y + h;
    }

    /**
     * 按像素宽度裁剪文本：保留原有 § 颜色码（颜色码本身不计宽度），末尾补省略号。
     * 订单 Text / 页脚 / 提示行等带颜色码的文本都走这里，避免文字画出区块。
     */
    private String clip(@Nullable String text, int maxWidth) {
        if (text == null || text.isEmpty() || maxWidth <= 0) {
            return "";
        }
        if (this.plainWidth(text) <= maxWidth) {
            return text;
        }
        int ellipsisW = this.font.width("…");
        StringBuilder sb = new StringBuilder();
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                // 颜色码：原样保留，不占宽度
                sb.append(c).append(text.charAt(++i));
                continue;
            }
            if (Character.isHighSurrogate(c) && i + 1 < text.length()) {
                String pair = text.substring(i, i + 2);
                int pairW = this.font.width(pair);
                if (width + pairW + ellipsisW > maxWidth) {
                    break;
                }
                sb.append(pair);
                width += pairW;
                i++;
                continue;
            }
            int charW = this.font.width(String.valueOf(c));
            if (width + charW + ellipsisW > maxWidth) {
                break;
            }
            sb.append(c);
            width += charW;
        }
        return sb.append('…').toString();
    }

    /**
     * 带 § 颜色码文本的真实绘制宽度（跳过颜色码本身）。
     * 纯文本走 {@code font.width} 快路径，只有含颜色码时才逐字符累加。
     */
    private int plainWidth(@Nullable String text) {
        if (text == null || text.isEmpty()) {
            return 0;
        }
        if (text.indexOf('§') < 0) {
            return this.font.width(text);
        }
        int width = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '§' && i + 1 < text.length()) {
                i++;
            } else if (Character.isHighSurrogate(c) && i + 1 < text.length()) {
                width += this.font.width(text.substring(i, i + 2));
                i++;
            } else {
                width += this.font.width(String.valueOf(c));
            }
        }
        return width;
    }

    // ==================== 数据访问 ====================

    @Nullable
    private DispatcherBlockEntity getDispatcher() {
        if (this.minecraft == null || this.minecraft.level == null) {
            return null;
        }
        BlockPos pos = this.menu.getBlockPos();
        if (pos == null) {
            return null;
        }
        BlockEntity blockEntity = this.minecraft.level.getBlockEntity(pos);
        if (blockEntity instanceof DispatcherBlockEntity) {
            return (DispatcherBlockEntity)blockEntity;
        }
        return null;
    }

    /** 状态行文案：0 灰（未绑定）/ 1 绿（正常）/ 2 红（打单机丢失）/ 3 黄（无员工认领工位）。 */
    private static String statusText(int mode) {
        switch (mode) {
            case 0:
                return "§8● " + tr(KEY_STATUS_UNBOUND);
            case 1:
                return "§a● " + tr(KEY_STATUS_OK);
            case 2:
                return "§c● " + tr(KEY_STATUS_LOST);
            case 3:
                return "§e● " + tr(KEY_STATUS_NO_STAFF);
            default:
                return "§8● " + tr(KEY_LOADING);
        }
    }

    /** 未拿到服务端快照时的本地打单机文案（从 BE 的绑定状态拼出来）。 */
    private static String buildMachineFallback(@Nullable DispatcherBlockEntity dispatcher) {
        if (dispatcher == null) {
            return "§8" + tr(KEY_MACHINE) + tr(KEY_LOADING);
        }
        if (!dispatcher.hasBoundMachine()) {
            return "§8" + tr(KEY_MACHINE_UNBOUND);
        }
        BlockPos boundPos = dispatcher.getBoundMachinePos();
        if (boundPos == null) {
            return "§7" + tr(KEY_MACHINE) + "§f?";
        }
        return "§7" + tr(KEY_MACHINE) + "§f"
                + boundPos.getX() + ", " + boundPos.getY() + ", " + boundPos.getZ();
    }

    private static String tr(String key) {
        return Component.translatable(key).getString();
    }

    private static List<String> safeList(@Nullable List<String> list) {
        return list != null ? list : Collections.<String>emptyList();
    }

    private static List<CompoundTag> safeCompound(@Nullable List<CompoundTag> list) {
        return list != null ? list : Collections.<CompoundTag>emptyList();
    }

    private static String nullToEmpty(@Nullable String text) {
        return text == null ? "" : text;
    }

    @Nullable
    private static String firstNonEmpty(@Nullable List<String> lines, int index) {
        if (lines == null || index < 0 || index >= lines.size()) {
            return null;
        }
        String line = lines.get(index);
        if (line == null || line.isEmpty()) {
            return null;
        }
        return line;
    }

    /** 提示行统一黄色：已带颜色码的行保持原样。 */
    private static String yellow(@Nullable String line) {
        if (line == null || line.isEmpty()) {
            return "";
        }
        return line.startsWith("§") ? line : "§e" + line;
    }

    // ==================== 内部数据结构 ====================

    /**
     * 流式排版后的一个 chip。
     *
     * <p>{@code relX / relY} 是相对「滚动内容顶部」的坐标（relX 相对内容左边缘，已含缩进），
     * 绘制和悬浮判定都换算成屏幕坐标 {@code (panelLeft + relX, viewportTop + relY - scrollOffset)}，
     * 因此滚动只需改一个偏移，无需重排 chip。</p>
     */
    private static final class Chip {
        private final String text;
        private final int relX;
        private final int relY;
        private final int width;
        private final int kind;
        @Nullable
        private final CompoundTag tag;

        private Chip(String text, int relX, int relY, int width, int kind, @Nullable CompoundTag tag) {
            this.text = text;
            this.relX = relX;
            this.relY = relY;
            this.width = width;
            this.kind = kind;
            this.tag = tag;
        }
    }

    /**
     * 一个女仆职业组：分组键（"Job" 职业显示名；回退时为 "Profession" 前缀）+ 组内女仆 +
     * 组标题行的排版结果。
     *
     * <p>{@code relTitleY / titleWidth} 相对「滚动内容顶部」，组内女仆的 chip 仍放进
     * {@link #chips} 统一绘制与悬浮判定，因此滚动 / hover 不需要第二套坐标逻辑。</p>
     */
    private static final class MaidGroup {
        private final String key;
        private final List<CompoundTag> maids = new ArrayList<>();
        private String title = "";
        private int relTitleY;
        private int titleWidth;

        private MaidGroup(String key) {
            this.key = key;
        }

        /** 组标题底色：厨师暖色 / 侍者冷色 / 其他职业沿用淡灰。 */
        private int titleBg() {
            if (JOB_COOK.equals(this.key)) {
                return COLOR_CHIP_GROUP_COOK_BG;
            }
            if (JOB_WAITER.equals(this.key)) {
                return COLOR_CHIP_GROUP_WAITER_BG;
            }
            return COLOR_CHIP_GROUP_BG;
        }

        /** 组标题边框色：与 {@link #titleBg()} 一一对应。 */
        private int titleEdge() {
            if (JOB_COOK.equals(this.key)) {
                return COLOR_CHIP_GROUP_COOK_EDGE;
            }
            if (JOB_WAITER.equals(this.key)) {
                return COLOR_CHIP_GROUP_WAITER_EDGE;
            }
            return COLOR_CHIP_GROUP_EDGE;
        }
    }

    /**
     * 一条订单的两列排版结果：{@code rawLeft / rawRight} 是带 § 颜色码的原始文本，
     * 布局阶段按列宽裁剪后写入 {@code left / right}，绘制时直接用；
     * {@code tag} 保留给 {@link #drawOrderHover} 读取 Hover1~3，{@code relY} 用于
     * {@link #pageY(int)} 换算与行矩形 hover 判定。
     */
    private static final class OrderRow {
        private final CompoundTag tag;
        private final boolean received;
        private final boolean failed;
        private final String rawLeft;
        private final String rawRight;
        private String left = "";
        private String right = "";
        private int relY;

        private OrderRow(CompoundTag tag, boolean received, boolean failed,
                String rawLeft, String rawRight) {
            this.tag = tag;
            this.received = received;
            this.failed = failed;
            this.rawLeft = rawLeft;
            this.rawRight = rawRight;
        }
    }

    /**
     * 一份食物的两列排版结果：左列菜名（含 Flag 前缀色）、右列负责人 / 状态；
     * 列宽裁剪在布局阶段完成，绘制只需左列 x 与相对 y。食物行不参与 hover。
     */
    private static final class FoodRow {
        private final String rawLeft;
        private final String rawRight;
        private String left = "";
        private String right = "";
        private int relY;

        private FoodRow(String rawLeft, String rawRight) {
            this.rawLeft = rawLeft;
            this.rawRight = rawRight;
        }
    }
}
