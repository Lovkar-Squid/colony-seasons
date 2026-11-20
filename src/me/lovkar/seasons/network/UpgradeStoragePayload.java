package me.lovkar.seasons.network;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.minecolonies.api.colony.buildings.IBuilding;
import com.minecolonies.api.colony.permissions.Action;
import me.lovkar.seasons.ColonySeasons;
import me.lovkar.seasons.colony.BuildingDecorator;
import net.minecraft.core.BlockPos;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.network.chat.Component;
import net.minecraft.network.codec.StreamCodec;
import net.minecraft.network.protocol.common.custom.CustomPacketPayload;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.neoforged.neoforge.network.handling.IPayloadContext;

/**
 * The Workshop window's "Upgrade Storage" button (S10e): the client names the hut, the server decides. It works like
 * MineColonies' {@code UpgradeWarehouseMessage} (one emerald block, free in creative, every rack one row larger), with
 * one change of order: MineColonies upgrades first and takes the block afterwards, without checking the level or that
 * the player has a block at all; here the level, the step and the price are checked and the block taken first, and
 * only then are the racks upgraded. A refusal changes nothing and charges nothing.
 *
 * @param hut the Workshop's hut block
 */
public record UpgradeStoragePayload(BlockPos hut) implements CustomPacketPayload {

    public static final CustomPacketPayload.Type<UpgradeStoragePayload> TYPE =
            new CustomPacketPayload.Type<>(ResourceLocation.fromNamespaceAndPath(ColonySeasons.MODID, "upgrade_storage"));

    public static final StreamCodec<RegistryFriendlyByteBuf, UpgradeStoragePayload> STREAM_CODEC =
            StreamCodec.composite(BlockPos.STREAM_CODEC, UpgradeStoragePayload::hut, UpgradeStoragePayload::new);

    /** MineColonies' range for a building message ({@code IRangeRestrictedPacket}): within 15 blocks of the hut. */
    public static final int MAX_RANGE = 15;

    /** What the server made of one click. Every outcome but {@link #UPGRADED} changed nothing. */
    public enum Result {
        UPGRADED(""),
        NO_COLONY("com.colony_seasons.gui.storage.refused.no_hut"),
        NO_BUILDING("com.colony_seasons.gui.storage.refused.no_hut"),
        NO_PERMISSION("com.colony_seasons.gui.storage.refused.permission"),
        TOO_FAR("com.colony_seasons.gui.storage.refused.too_far"),
        NOT_A_WORKSHOP("com.colony_seasons.gui.storage.refused.no_hut"),
        NOT_OFFERED("com.colony_seasons.gui.storage.refused.not_offered"),
        NO_PRICE("com.colony_seasons.gui.storage.refused.no_price");

        private final String key;

        Result(final String key) {
            this.key = key;
        }

        /** The chat line for a refusal (lang key), "" for none. */
        public String key() {
            return key;
        }
    }

    @Override
    public CustomPacketPayload.Type<? extends CustomPacketPayload> type() {
        return TYPE;
    }

    /** Runs on the server when the packet arrives: the work goes to the server thread. */
    public static void handle(final UpgradeStoragePayload payload, final IPayloadContext context) {
        context.enqueueWork(() -> {
            if (context.player() instanceof ServerPlayer player) {
                final Result result = apply(player, payload.hut());
                if (result != Result.UPGRADED) {
                    player.sendSystemMessage(Component.translatable(result.key()));
                }
            }
        });
    }

    /**
     * One click, checked in this order: the range (the position comes from the client, so nothing that touches a
     * chunk runs before it; MineColonies' colony lookup by position loads the chunk), that the chunk is loaded, the
     * colony and the building at {@code hut} in the player's level, the player's right to manage huts, that it is a
     * Workshop, that the next step is offered (level and steps), the price (one emerald block from the inventory,
     * none in creative); then the racks are upgraded.
     */
    public static Result apply(final ServerPlayer player, final BlockPos hut) {
        if (player.blockPosition().distSqr(hut) > (double) MAX_RANGE * MAX_RANGE) {
            return Result.TOO_FAR;
        }
        if (!player.level().isLoaded(hut)) {
            return Result.NO_COLONY;
        }
        final IColony colony = IColonyManager.getInstance().getColonyByPosFromWorld(player.level(), hut);
        if (colony == null) {
            return Result.NO_COLONY;
        }
        final IBuilding building = colony.getServerBuildingManager().getBuilding(hut);
        if (building == null) {
            return Result.NO_BUILDING;
        }
        if (!colony.getPermissions().hasPermission(player, Action.MANAGE_HUTS)) {
            return Result.NO_PERMISSION;
        }
        if (!(building instanceof BuildingDecorator workshop)) {
            return Result.NOT_A_WORKSHOP;
        }
        if (!workshop.canUpgradeStorage()) {
            return Result.NOT_OFFERED;
        }
        if (!player.isCreative()) {
            final Inventory inventory = player.getInventory();
            int slot = -1;
            for (int i = 0; i < inventory.getContainerSize(); i++) {
                final ItemStack stack = inventory.getItem(i);
                if (!stack.isEmpty() && stack.is(Items.EMERALD_BLOCK)) {
                    slot = i;
                    break;
                }
            }
            if (slot < 0) {
                return Result.NO_PRICE;
            }
            inventory.removeItem(slot, 1);
            inventory.setChanged();
        }
        workshop.upgradeStorage(player.level());
        return Result.UPGRADED;
    }
}
