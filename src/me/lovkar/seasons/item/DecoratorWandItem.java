package me.lovkar.seasons.item;

import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.StreetMarks;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.InteractionResultHolder;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.item.component.CustomData;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/**
 * The Decorator's wand (0.2.0, Q11; docs/town-square-design.md "Addendum: the marking wand"): the player marks spots
 * round the colony and picks what goes there, and the colony's Decorator dresses them in every festival window.
 *
 * <ul>
 *   <li>right-click in the air: the next mode (snowman A, snowman B, lantern post, garland, planter), said in the action
 *       bar; the mode is kept on the wand ({@code custom_data});</li>
 *   <li>right-click on a block: marks the cell in front of the clicked face for the mode ({@link StreetMarks#add}: the
 *       cell must be in a colony's claim where the player may manage huts, and so on); a snowman and a post face the
 *       player. In the garland mode the first click picks a marked post and the second click another one: the garland
 *       is marked between them;</li>
 *   <li>shift-right-click on a block: removes the mark whose piece, ground or cell was clicked, and takes its pieces
 *       down at once ({@link StreetMarks#remove}; a post takes its garlands with it).</li>
 * </ul>
 * While the wand is held its holder sees the colony's marks as outlines (client side only, {@code WandMarks}); the server
 * sends them ({@link WandSync}). Everything else happens on the server.
 */
public class DecoratorWandItem extends Item {
    /** The mode's id in the wand's custom data. */
    public static final String MODE = "mode";
    /** The garland's first post (its lower half) and its colony, while the second one is not picked yet. */
    public static final String FROM = "garlandFrom";
    public static final String FROM_COLONY = "garlandColony";

    public DecoratorWandItem(final Properties properties) {
        super(properties);
    }

    /** The wand's mode (snowman A for a new wand). */
    public static StreetMarks.Type mode(final ItemStack stack) {
        final CustomData data = stack.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY);
        final StreetMarks.Type type = data.contains(MODE) ? StreetMarks.Type.byId(data.copyTag().getString(MODE)) : null;
        return type == null ? StreetMarks.Type.SNOWMAN_A : type;
    }

    /** Sets the mode and forgets a garland's first post. */
    public static void setMode(final ItemStack stack, final StreetMarks.Type type) {
        CustomData.update(DataComponents.CUSTOM_DATA, stack, tag -> {
            tag.putString(MODE, type.id());
            tag.remove(FROM);
            tag.remove(FROM_COLONY);
        });
    }

    /** True when {@code stack} is a Decorator's wand. */
    public static boolean is(final ItemStack stack) {
        return stack.getItem() instanceof DecoratorWandItem;
    }

    @Override
    public @NotNull InteractionResultHolder<ItemStack> use(final @NotNull Level level, final @NotNull Player player, final @NotNull InteractionHand hand) {
        final ItemStack stack = player.getItemInHand(hand);
        if (!level.isClientSide) {
            final StreetMarks.Type next = mode(stack).next();
            setMode(stack, next);
            player.displayClientMessage(Component.translatable("com.colony_seasons.wand.mode", Component.translatable(next.nameKey())), true);
        }
        return InteractionResultHolder.sidedSuccess(stack, level.isClientSide);
    }

    @Override
    public @NotNull InteractionResult useOn(final @NotNull UseOnContext context) {
        final Level level = context.getLevel();
        if (level.isClientSide) {
            return InteractionResult.SUCCESS;
        }
        if (!(level instanceof ServerLevel server) || !(context.getPlayer() instanceof ServerPlayer player)) {
            return InteractionResult.PASS;
        }
        final String said = click(server, player, context.getItemInHand(), context.getClickedPos(), context.getClickedFace(),
                player.isSecondaryUseActive());
        if (said != null) {
            player.displayClientMessage(Component.literal("").append(message(said, server, player, context)), true);
        }
        return InteractionResult.CONSUME;
    }

    /** The line for a click's outcome (keys that take the mark's name get it). */
    private static Component message(final String key, final ServerLevel level, final ServerPlayer player, final UseOnContext context) {
        final StreetMarks.Type mode = mode(context.getItemInHand());
        return switch (key) {
            case "com.colony_seasons.wand.marked" -> {
                final int colony = DecorPlacer.colonyAt(level, context.getClickedPos().relative(context.getClickedFace()));
                final int count = colony == DecorPlacer.NO_COLONY ? 0 : DecorLedger.get(level).marks(colony).size();
                yield Component.translatable(key, Component.translatable(mode.nameKey()), count, StreetMarks.MAX_MARKS);
            }
            default -> Component.translatable(key);
        };
    }

    /**
     * One click on a block, on the server: the lang key of what to say, or {@code null} for nothing. Public for the
     * probe, which drives it with a fake player.
     */
    public static String click(final ServerLevel level, final ServerPlayer player, final ItemStack wand, final BlockPos clicked,
                               final Direction face, final boolean remove) {
        final DecorLedger ledger = DecorLedger.get(level);
        if (remove) {
            int colony = DecorPlacer.colonyAt(level, clicked);
            if (colony == DecorPlacer.NO_COLONY) {
                colony = DecorPlacer.colonyAt(level, clicked.relative(face));
            }
            if (colony == DecorPlacer.NO_COLONY) {
                return "com.colony_seasons.wand.no_mark";
            }
            final StreetMarks.Mark mark = StreetMarks.near(ledger, colony, clicked, face);
            if (mark == null) {
                return "com.colony_seasons.wand.no_mark";
            }
            final com.minecolonies.api.colony.IColony owner = com.minecolonies.api.colony.IColonyManager.getInstance().getColonyByWorld(colony, level);
            if (owner == null || !owner.getPermissions().hasPermission(player, com.minecolonies.api.colony.permissions.Action.MANAGE_HUTS)) {
                return "com.colony_seasons.wand.refused.permission";
            }
            StreetMarks.remove(level, colony, mark.pos());
            return "com.colony_seasons.wand.removed";
        }
        final StreetMarks.Type mode = mode(wand);
        if (mode == StreetMarks.Type.GARLAND) {
            return garland(level, player, wand, ledger, clicked, face);
        }
        final BlockPos cell = clicked.relative(face);
        final Direction facing = player.getDirection().getOpposite();
        final String refused = StreetMarks.add(level, player, new StreetMarks.Mark(cell, mode, facing, null));
        return refused == null ? "com.colony_seasons.wand.marked" : refused;
    }

    /** The garland mode: the first click picks a post, the second one another and marks the garland between them. */
    private static String garland(final ServerLevel level, final ServerPlayer player, final ItemStack wand, final DecorLedger ledger,
                                  final BlockPos clicked, final Direction face) {
        int colony = DecorPlacer.colonyAt(level, clicked);
        if (colony == DecorPlacer.NO_COLONY) {
            colony = DecorPlacer.colonyAt(level, clicked.relative(face));
        }
        final StreetMarks.Mark post = colony == DecorPlacer.NO_COLONY ? null : StreetMarks.near(ledger, colony, clicked, face);
        if (post == null || post.type() != StreetMarks.Type.POST) {
            return "com.colony_seasons.wand.garland.not_post";
        }
        final CompoundTag tag = wand.getOrDefault(DataComponents.CUSTOM_DATA, CustomData.EMPTY).copyTag();
        final StreetMarks.Mark first = tag.contains(FROM) && tag.getInt(FROM_COLONY) == colony
                ? ledger.markAt(colony, BlockPos.of(tag.getLong(FROM))) : null;
        if (first == null || first.equals(post)) {
            final int id = colony;
            CustomData.update(DataComponents.CUSTOM_DATA, wand, t -> {
                t.putLong(FROM, post.pos().asLong());
                t.putInt(FROM_COLONY, id);
            });
            return "com.colony_seasons.wand.garland.first";
        }
        CustomData.update(DataComponents.CUSTOM_DATA, wand, t -> {
            t.remove(FROM);
            t.remove(FROM_COLONY);
        });
        final StreetMarks.Garland garland = StreetMarks.garland(first, post);
        if (garland.mark() == null) {
            return garland.refusal();
        }
        final String refused = StreetMarks.add(level, player, garland.mark());
        return refused == null ? "com.colony_seasons.wand.marked" : refused;
    }

    @Override
    public void appendHoverText(final @NotNull ItemStack stack, final @NotNull TooltipContext context, final @NotNull List<Component> lines,
                                final @NotNull TooltipFlag flag) {
        lines.add(Component.translatable("com.colony_seasons.wand.tooltip.mode", Component.translatable(mode(stack).nameKey())));
        lines.add(Component.translatable("com.colony_seasons.wand.tooltip.use"));
    }
}
