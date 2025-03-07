package fr.loxoz.mods.betterwaystonesmenu.screen;

import com.mojang.blaze3d.systems.RenderSystem;
import com.mojang.blaze3d.vertex.PoseStack;
import fr.loxoz.mods.betterwaystonesmenu.BetterWaystonesMenu;
import fr.loxoz.mods.betterwaystonesmenu.compat.CText;
import fr.loxoz.mods.betterwaystonesmenu.compat.IPositionedTooltipProvider;
import fr.loxoz.mods.betterwaystonesmenu.compat.ScissorHelper;
import fr.loxoz.mods.betterwaystonesmenu.compat.TooltipPos;
import fr.loxoz.mods.betterwaystonesmenu.compat.widget.TexturedButtonTooltipWidget;
import fr.loxoz.mods.betterwaystonesmenu.util.BWMSortMode;
import fr.loxoz.mods.betterwaystonesmenu.widget.ScrollBarWidget;
import fr.loxoz.mods.betterwaystonesmenu.util.WaystoneUtils;
import fr.loxoz.mods.betterwaystonesmenu.widget.BetterWaystoneButton;
import fr.loxoz.mods.betterwaystonesmenu.widget.ClearableTextFieldWidget;
import net.blay09.mods.balm.api.Balm;
import net.blay09.mods.balm.mixin.ScreenAccessor;
import net.blay09.mods.waystones.api.IWaystone;
import net.blay09.mods.waystones.client.gui.widget.ITooltipProvider;
import net.blay09.mods.waystones.client.gui.widget.RemoveWaystoneButton;
import net.blay09.mods.waystones.client.gui.widget.SortWaystoneButton;
import net.blay09.mods.waystones.client.gui.widget.WaystoneButton;
import net.blay09.mods.waystones.core.PlayerWaystoneManager;
import net.blay09.mods.waystones.core.WaystoneTypes;
import net.blay09.mods.waystones.menu.WaystoneSelectionMenu;
import net.blay09.mods.waystones.network.message.RequestEditWaystoneMessage;
import net.blay09.mods.waystones.network.message.SelectWaystoneMessage;
import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.renderer.GameRenderer;
import net.minecraft.client.renderer.Rect2i;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.Vec3;
import net.minecraftforge.registries.ForgeRegistries;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.text.Collator;
import java.util.*;
import java.util.function.Predicate;

public abstract class BetterWaystoneSelectionScreenBase extends AbstractContainerScreen<WaystoneSelectionMenu> {
    public static int WAYSTONE_NAME_MAX_WIDTH = 260;
    public static int CONTENT_WIDTH = 200;
    public static int BTN_GAP = 2;
    public static int UI_GAP = 8;
    public static final ResourceLocation MENU_TEXTURE = new ResourceLocation(BetterWaystonesMenu.MOD_ID , "textures/gui/menu.png");
    public static BWMSortMode sortMode = BWMSortMode.INDEX;
    private final List<IWaystone> waystones;
    private final List<BetterWaystoneSelectionScreen.PositionedWaystoneButton> scrollableButtons = new ArrayList<>();
    private Screen originalScreen = null;
    protected Rect2i area_heading = new Rect2i(0, 0, 0, 0);
    protected Rect2i area_title = new Rect2i(0, 0, 0, 0);
    protected Rect2i area_query = new Rect2i(0, 0, 0, 0);
    protected Rect2i area_scrollBox = new Rect2i(0, 0, 0, 0);
    private Component heading_title;
    private final ScrollBarWidget scrollBar = new ScrollBarWidget();
    private EditBox queryField;
    private SortModeButtonWidget sortModeBtn;
    private String query = "";
    private final List<IWaystone> visibleWaystones = new ArrayList<>();

    public BetterWaystoneSelectionScreenBase(WaystoneSelectionMenu container, Inventory playerInventory, Component title) {
        super(container, playerInventory, title);
        this.waystones = container.getWaystones();
        //noinspection SuspiciousNameCombination
        this.imageWidth = this.imageHeight = CONTENT_WIDTH;
        this.updateFilters();
        scrollBar.animated = true;
    }

    public void setOriginalScreen(Screen originalScreen) { this.originalScreen = originalScreen; }
    public Screen getOriginalScreen() { return originalScreen; }

    protected @Nullable Vec3 getOriginPos() {
        if (this.menu.getWaystoneFrom() != null) {
            return Vec3.atBottomCenterOf(this.menu.getWaystoneFrom().getPos());
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            return this.minecraft.player.position();
        }
        return null;
    }

    protected @Nullable ResourceKey<Level> getOriginDim() {
        if (this.menu.getWaystoneFrom() != null) {
            return this.menu.getWaystoneFrom().getDimension();
        }
        if (this.minecraft != null && this.minecraft.player != null) {
            return this.minecraft.player.clientLevel.dimension();
        }
        return null;
    }

    protected void updateFilters() {
        this.visibleWaystones.clear();
        String query = this.query.trim();
        List<IWaystone> list;

        if (query.isBlank()) {
            list = this.waystones;
        }
        else {
            var parts = query.split(" +");
            list = this.waystones.stream().filter(waystones -> {
                for (var part : parts) {
                    if (!waystones.getName().contains(part)) return false;
                }
                return true;
            }).toList();
        }

        final Vec3 origin = Optional.ofNullable(getOriginPos()).orElse(new Vec3(0, 0, 0));
        Comparator<IWaystone> comparator = switch (sortMode) {
            case NAME -> (w1, w2) -> Collator.getInstance().compare(w1.getName(), w2.getName());
            case DISTANCE -> (w1, w2) -> (int) (origin.distanceToSqr(Vec3.atBottomCenterOf(w1.getPos())) - origin.distanceToSqr(Vec3.atBottomCenterOf(w2.getPos())));
            default -> null;
        };
        if (comparator != null) {
            list = list.stream().sorted(comparator).toList();
        }

        this.visibleWaystones.addAll(list);
    }

    protected boolean isIconHeading() {
        return switch (this.menu.getWarpMode()) {
            case WARP_STONE, WARP_SCROLL -> true;
            default -> false;
        };
    }

    protected boolean shouldShownHeading() {
        return this.menu.getWaystoneFrom() != null || isIconHeading();
    }

    @Override
    protected void init() {
        // content width and x excluding heading's size
        int cw = CONTENT_WIDTH;
        int cx = width/2 - cw/2;
        this.heading_title = this.menu.getWaystoneFrom() == null ? null : WaystoneUtils.getTrimmedWaystoneName(this.menu.getWaystoneFrom(), font, getMaxNameWidth());
        // title width (36 = space for icon and edit button)
        int title_w = this.heading_title != null ? (font.width(this.heading_title) + 36) : 0;
        // side button size
        int sbw = 20 + UI_GAP;
        // side buttons positions
        int cbw = cw + sbw * 2; // content + side buttons width
        int cbx = (width - cbw) / 2; // content + side buttons x
        // sizes that will be used as the layout
        this.imageWidth = Math.max(cbw, title_w);
        this.imageHeight = (int) (this.height * 0.6f) + (UI_GAP * 2);
        super.init();
        // compute layout
        int hw = Math.max(title_w, cw); // heading width
        int hx = (width - hw) / 2; // heading x
        int ry = topPos; // root y
        int rh = imageHeight; // root height
        int rb = ry + rh; // root bottom

        this.area_heading = new Rect2i(hx, ry, hw, 18);
        this.area_title = new Rect2i(cx, this.area_heading.getY() + this.area_heading.getHeight() + UI_GAP, cw, font.lineHeight);
        this.area_query = new Rect2i(cx, this.area_title.getY() + this.area_title.getHeight() + UI_GAP, cw, 20);
        // store bottom Y pos + UI_GAP of area_query
        int aq_bpos = this.area_query.getY() + this.area_query.getHeight() + UI_GAP;
        this.area_scrollBox = new Rect2i(cx, aq_bpos, cw, rb - aq_bpos);

        //// heading
        // rename button
        if (this.menu.getWaystoneFrom() != null) {
            this.addRenderableWidget(new TexturedButtonTooltipWidget(area_heading.getX() + area_heading.getWidth() - 18, area_heading.getY(), 18, 18, 0, 40, 18, MENU_TEXTURE, 256, 256, (btn) ->
                    Balm.getNetworking().sendToServer(new RequestEditWaystoneMessage(this.menu.getWaystoneFrom().getWaystoneUid()))
            , CText.translatable("gui.betterwaystonesmenu.waystone_selection.rename")));
        }

        //// side buttons
        // return to original button
        this.addRenderableWidget(new TexturedButtonTooltipWidget(cbx, area_query.getY(), 20, 20, 0, 0, 20, MENU_TEXTURE, 256, 256, (btn) -> {
            if (originalScreen == null) return;
            BetterWaystonesMenu.inst().openOriginalScreen(originalScreen);
        }, CText.translatable("gui.betterwaystonesmenu.waystone_selection.return_to_original")));

        //// query area
        // query field
        this.queryField = new ClearableTextFieldWidget(font, area_query.getX(), area_query.getY(), area_query.getWidth() - 20 - UI_GAP, area_query.getHeight(), CText.translatable("gui.betterwaystonesmenu.waystone_selection.query_waystones"));
        this.queryField.setMaxLength(128);
        this.queryField.setValue(query);
        this.addRenderableWidget(this.queryField);

        // sort button
        this.sortModeBtn = new SortModeButtonWidget(area_query.getX() + area_query.getWidth() - 20, area_query.getY(), 20, 20, MENU_TEXTURE, 0, 92, 20, sortMode);
        this.addRenderableWidget(this.sortModeBtn);

        //// scrollbar
        scrollBar.setPosSize(area_scrollBox.getX() + area_scrollBox.getWidth() - 8, area_scrollBox.getY(), 8, area_scrollBox.getHeight());
        addWidget(scrollBar);

        //// waystone buttons
        this.updateList();
    }

    private void updateList() {
        this.scrollableButtons.clear();

        Predicate<Object> removePredicate = (button) -> button instanceof WaystoneButton || button instanceof SortWaystoneButton || button instanceof RemoveWaystoneButton;
        ((ScreenAccessor) this).balm_getChildren().removeIf(removePredicate);
        ((ScreenAccessor) this).balm_getNarratables().removeIf(removePredicate);
        ((ScreenAccessor) this).balm_getRenderables().removeIf(removePredicate);

        int y = area_scrollBox.getY();
        int contentH = 0;

        for (IWaystone waystone : visibleWaystones) {
            var btn = this.createWaystoneButton(y, waystone);
            this.addWidget(btn);
            scrollableButtons.add(new PositionedWaystoneButton(btn));
            if (contentH != 0) contentH += BTN_GAP;
            contentH += 20;
            y += 20 + BTN_GAP;
        }

        scrollBar.contentHeight = contentH;
    }

    private BetterWaystoneButton createWaystoneButton(int y, IWaystone waystone) {
        IWaystone waystoneFrom = this.menu.getWaystoneFrom();
        Player player = Minecraft.getInstance().player;
        int xpLevelCost = Math.round((float) PlayerWaystoneManager.predictExperienceLevelCost(Objects.requireNonNull(player), waystone, this.menu.getWarpMode(), waystoneFrom));
        BetterWaystoneButton btnWaystone = new BetterWaystoneButton(this.width / 2 - 100, y, waystone, xpLevelCost, (button) ->
                this.onWaystoneSelected(waystone)
        , getOriginPos(), getOriginDim());
        btnWaystone.setWidth(CONTENT_WIDTH - scrollBar.width - BTN_GAP);
        if (waystoneFrom != null && waystone.getWaystoneUid().equals(waystoneFrom.getWaystoneUid())) {
            btnWaystone.active = false;
        }

        return btnWaystone;
    }

    protected void onWaystoneSelected(IWaystone waystone) {
        Balm.getNetworking().sendToServer(new SelectWaystoneMessage(waystone.getWaystoneUid()));
    }

    @Override
    public boolean mouseDragged(double mouseX, double mouseY, int button, double deltaX, double deltaY) {
        if (getFocused() == scrollBar) {
            scrollBar.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
            return true;
        }
        return super.mouseDragged(mouseX, mouseY, button, deltaX, deltaY);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
        if (area_scrollBox.contains((int) mouseX, (int) mouseY) || scrollBar.hovered) {
            scrollBar.mouseScrolled(mouseX, mouseY, amount);
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, amount);
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        scrollBar.mouseReleased(mouseX, mouseY, button);
        return super.mouseReleased(mouseX, mouseY, button);
    }

    @Override
    public boolean changeFocus(boolean p_94728_) {
        boolean bl = super.changeFocus(p_94728_);
        if (bl) {
            for (var pos : scrollableButtons) {
                if (!pos.getButton().isFocused()) continue;
                if (scrollBar.isElementFullyVisible(pos.viewY, pos.viewY + pos.getButton().getHeight())) continue;
                scrollBar.scrollTo(pos.viewY - scrollBar.y - (scrollBar.height - pos.getButton().getHeight()) / 2f);
            }
        }
        return bl;
    }

    @Override
    protected void containerTick() {
        if (!query.equals(queryField.getValue())) {
            query = queryField.getValue();
            this.updateFilters();
            this.updateList();
        }
        if (sortModeBtn.getValue() != sortMode) {
            sortMode = sortModeBtn.getValue();
            this.updateFilters();
            this.updateList();
        }
    }

    public int getMaxNameWidth() {
        return Math.min(WAYSTONE_NAME_MAX_WIDTH, width - 36);
    }

    @Override
    public void render(@NotNull PoseStack matrices, int mouseX, int mouseY, float partialTicks) {
        this.renderBackground(matrices);
        // heading bg + icon
        if (shouldShownHeading()) {
            IWaystone fromWaystone = this.menu.getWaystoneFrom();
            boolean iconHeading = fromWaystone == null;

            int hx1 = area_heading.getX() + (!iconHeading ? 0 : (area_heading.getWidth() - 16) / 2 -1);
            int hx2 = !iconHeading ? (area_heading.getX() + area_heading.getWidth()) : (hx1 + 18);

            fill(matrices, hx1, area_heading.getY(), hx2, area_heading.getY() + area_heading.getHeight(), 0x66000000);

            if (!iconHeading) {
                drawCenteredString(matrices, font, heading_title, area_heading.getX() + area_heading.getWidth()/2, area_heading.getY() + area_heading.getHeight()/2 - font.lineHeight/2, 0xffffff);
                // icon
                RenderSystem.setShaderTexture(0, MENU_TEXTURE);
                RenderSystem.enableBlend();
                int u = 0;
                if (fromWaystone.getWaystoneType().equals(WaystoneTypes.WAYSTONE)) u = 16;
                blit(matrices, area_heading.getX() + 1, area_heading.getY() + 1, u, 76, 16, 16);
                RenderSystem.disableBlend();
            }
            else {
                ResourceLocation item_key = switch (this.menu.getWarpMode()) {
                    case WARP_STONE -> new ResourceLocation("waystones", "warp_stone");
                    case WARP_SCROLL -> new ResourceLocation("waystones", "warp_scroll");
                    default -> null;
                };

                Item item = null;
                if (item_key != null) {
                    item = ForgeRegistries.ITEMS.getValue(item_key);
                }

                if (item != null) {
                    this.itemRenderer.blitOffset = 100.0f;
                    this.itemRenderer.renderAndDecorateItem(new ItemStack(item), area_heading.getX() + area_heading.getWidth()/2 - 8, area_heading.getY() + area_heading.getHeight()/2 - 8);
                    this.itemRenderer.blitOffset = 0f;
                }
            }
        }
        // results count
        drawCenteredString(matrices, font, CText.translatable("gui.betterwaystonesmenu.waystone_selection.showing", visibleWaystones.size(), waystones.size()), width/2, area_scrollBox.getY() + area_scrollBox.getHeight() + UI_GAP, 0x33ffffff);
        // if no waystones or results message
        if (this.visibleWaystones.size() == 0) {
            var message = query.isBlank() ?
                    CText.translatable("gui.waystones.waystone_selection.no_waystones_activated").withStyle(style -> style.withColor(ChatFormatting.RED)) :
                    CText.translatable("gui.betterwaystonesmenu.waystone_selection.no_results").withStyle(style -> style.withColor(ChatFormatting.GRAY));
            drawCenteredString(matrices, font, message, area_scrollBox.getX() + area_scrollBox.getWidth()/2, (area_scrollBox.getY() + area_scrollBox.getHeight()/2) - (font.lineHeight/2), 0xffffff);
        }
        // scrollable buttons
        matrices.pushPose();
        // buttons
        ScissorHelper.enableScissor(minecraft, area_scrollBox, 0, 0);
        for (var pos : this.scrollableButtons) {
            if (scrollBar.isElementVisible(pos.viewY, pos.viewY + pos.getButton().getHeight())) {
                pos.getButton().render(matrices, mouseX, mouseY, partialTicks);
                pos.getButton().y = pos.viewY - (int) scrollBar.getScrollY();
                pos.getButton().viewportVisible = true;
            }
            else {
                pos.getButton().viewportVisible = false; // this is a temp fix to avoid clicking on the button is not visible on the scroll box's viewport
            }
        }
        ScissorHelper.disableScissor();
        matrices.popPose();
        // widgets and labels
        super.render(matrices, mouseX, mouseY, partialTicks);
        // scrollbar
        scrollBar.render(matrices, mouseX, mouseY, partialTicks);
        // container slot's tooltip
        this.renderTooltip(matrices, mouseX, mouseY);
        // tooltips
        for (var child : children()) {
            if (!(child instanceof ITooltipProvider provider) || !provider.shouldShowTooltip()) continue;
            TooltipPos pos;
            if (provider instanceof IPositionedTooltipProvider positioned) {
                pos = positioned.getTooltipPos(mouseX, mouseY);
            }
            else {
                pos = new TooltipPos(mouseX, mouseY);
            }
            this.renderTooltip(matrices, provider.getTooltip(), Optional.empty(), pos.x(), pos.y());
        }
    }

    @Override
    protected void renderBg(@NotNull PoseStack p_97787_, float p_97788_, int p_97789_, int p_97790_) {}

    @Override
    protected void renderLabels(@NotNull PoseStack matrices, int mouseX, int mouseY) {
        RenderSystem.setShaderColor(1.0f, 1.0f, 1.0f, 1.0f);
        matrices.pushPose();
        matrices.translate(-leftPos, -topPos, 0);
        drawCenteredString(matrices, font, this.getTitle().copy().withStyle(style -> style.withColor(ChatFormatting.GRAY)), area_title.getX() + area_title.getWidth()/2, area_title.getY(), 0xffffff);
        matrices.popPose();
    }

    protected boolean allowSorting() { return true; }
    protected boolean allowDeletion() { return true; }

    public static class PositionedWaystoneButton {
        private final BetterWaystoneButton button;
        public int viewY;

        public PositionedWaystoneButton(BetterWaystoneButton button) {
            this.button = button;
            this.viewY = button.y;
        }

        public BetterWaystoneButton getButton() { return button; }
    }

    public static class SortModeButtonWidget extends Button implements IPositionedTooltipProvider {
        private final ResourceLocation identifier;
        private final int u;
        private final int v;
        private final int vHover;
        BWMSortMode mode;
        List<BWMSortMode> values;

        public SortModeButtonWidget(int x, int y, int width, int height, ResourceLocation identifier, int u, int v, int vHover, BWMSortMode mode) {
            super(x, y, width, height, CText.empty(), $ -> {}, NO_TOOLTIP);
            this.identifier = identifier;
            this.u = u;
            this.v = v;
            this.vHover = vHover;
            this.mode = mode;
            values = Arrays.asList(BWMSortMode.values());
        }

        public Component getSortModeName() {
            return CText.translatable("gui.betterwaystonesmenu.waystone_selection.sort_modes." + mode.getId());
        }

        public int getIndex() { return values.indexOf(mode); }

        public BWMSortMode getValue() { return mode; }

        @Override
        public void onPress() {
            int index = getIndex() + 1;
            if (index >= values.size()) index = 0;
            mode = values.get(index);
        }

        @Override
        public @NotNull Component getMessage() {
            return CText.translatable("gui.betterwaystonesmenu.waystone_selection.sort_mode_prefix", getSortModeName());
        }

        @Override
        public void renderButton(@NotNull PoseStack matrices, int mouseX, int mouseY, float delta) {
            RenderSystem.setShader(GameRenderer::getPositionTexShader);
            RenderSystem.setShaderTexture(0, this.identifier);
            blit(matrices, x, y, u + getIndex() * width, v + (isHoveredOrFocused() ? vHover : 0), this.width, this.height, 256, 256);
            if (this.isHovered) {
                this.renderToolTip(matrices, mouseX, mouseY);
            }
        }

        @Override
        public boolean shouldShowTooltip() {
            return isHoveredOrFocused();
        }

        @Override
        public List<Component> getTooltip() {
            return List.of(getMessage());
        }
    }
}
