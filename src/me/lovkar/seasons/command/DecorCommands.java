package me.lovkar.seasons.command;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * {@code /colonyseasons decor list [colony]} and {@code decor remove <colony>}, both permission 2, both on the level
 * the command is run in (design 3.3). {@code remove} takes everything the ledger lists for a colony down by the
 * removal rule at once; what lies in unloaded chunks is marked and the server sweep finishes it as the chunks load.
 * The README tells admins to run it before they take the mod out.
 */
public final class DecorCommands {

    private DecorCommands() {
    }

    /** The {@code decor} branch, added under {@code /colonyseasons} by {@link SeasonsCommands}. */
    public static LiteralArgumentBuilder<CommandSourceStack> node() {
        return Commands.literal("decor")
                .requires(source -> source.hasPermission(SeasonsCommands.PERMISSION))
                .then(Commands.literal("list")
                        .executes(context -> list(context, -1))
                        .then(Commands.argument("colony", IntegerArgumentType.integer(0))
                                .executes(context -> list(context, IntegerArgumentType.getInteger(context, "colony")))))
                .then(Commands.literal("remove")
                        .then(Commands.argument("colony", IntegerArgumentType.integer(0))
                                .executes(DecorCommands::remove)));
    }

    private static int list(final CommandContext<CommandSourceStack> context, final int only) {
        final CommandSourceStack source = context.getSource();
        final DecorLedger ledger = DecorLedger.get(source.getLevel());
        final List<Integer> ids = only >= 0 ? List.of(only) : ledger.colonies();
        int shown = 0;
        for (final int id : ids) {
            final List<DecorLedger.GroupView> groups = ledger.groups(id);
            if (groups.isEmpty()) {
                continue;
            }
            shown++;
            final int entries = ledger.count(id);
            source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.decor.list.colony", id, groups.size(), entries), false);
            if (only >= 0) {
                for (final DecorLedger.GroupView group : groups) {
                    final Component kind = Component.translatable(group.owner().kind() == DecorLedger.Kind.SQUARE
                            ? "com.colony_seasons.command.decor.kind.square" : "com.colony_seasons.command.decor.kind.building");
                    source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.decor.list.group", kind,
                            group.owner().pos().toShortString(), group.size(), group.festival().isEmpty() ? "-" : group.festival()), false);
                }
            }
        }
        if (shown == 0) {
            source.sendSuccess(() -> only >= 0
                    ? Component.translatable("com.colony_seasons.command.decor.list.empty", only)
                    : Component.translatable("com.colony_seasons.command.decor.list.none"), false);
        }
        return shown;
    }

    private static int remove(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final int id = IntegerArgumentType.getInteger(context, "colony");
        final ServerLevel level = source.getLevel();
        final DecorLedger ledger = DecorLedger.get(level);
        if (ledger.groups(id).isEmpty()) {
            source.sendFailure(Component.translatable("com.colony_seasons.command.decor.list.empty", id));
            return 0;
        }
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(id, level);
        final int[] lost = new int[1];
        final DecorPlacer.Removed removed = DecorPlacer.removeColony(level, ledger, id, DecorWork.sinkOf(colony, lost));
        ledger.flush(level);
        source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.decor.remove", id, removed.removed(),
                removed.forgotten(), removed.waiting()), true);
        if (lost[0] > 0) {
            // no Decorator's hut in the colony to keep the items of what came down
            final int items = lost[0];
            source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.decor.remove.lost", items, id), true);
        }
        return Math.max(1, removed.removed());
    }
}
