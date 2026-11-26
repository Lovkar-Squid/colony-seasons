package me.lovkar.seasons.command;

import com.minecolonies.api.colony.IColony;
import com.minecolonies.api.colony.IColonyManager;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.lovkar.seasons.decor.DecorLedger;
import me.lovkar.seasons.decor.DecorPlacer;
import me.lovkar.seasons.decor.DecorWork;
import me.lovkar.seasons.decor.Squares;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerLevel;

import java.util.List;

/**
 * {@code /colonyseasons decor list [colony]} and {@code decor remove <colony>}, both permission 2, both on the level
 * the command is run in (design 3.3). {@code remove} takes everything the ledger lists for a colony down by the
 * removal rule at once; what lies in unloaded chunks is marked and the server sweep finishes it as the chunks load.
 * The storeroom of a Workshop (S10g2) is one more group: {@code list} shows it on its own line, {@code remove} takes it
 * off with the rest, and the Decorator dresses it again at his next check. {@code list <colony>} also says where the
 * colony's centrepiece stands (0.2.0: its main Town Square, its festival marker, or none).
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
                    final Component kind = Component.translatable(switch (group.owner().kind()) {
                        case SQUARE -> "com.colony_seasons.command.decor.kind.square";
                        case STOREROOM -> "com.colony_seasons.command.decor.kind.storeroom";
                        case BUILDING -> "com.colony_seasons.command.decor.kind.building";
                    });
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
        if (only >= 0) {
            // where the colony's centrepiece stands, also when nothing is listed (0.2.0)
            final Component square = squareLine(source.getLevel(), ledger, only);
            if (square != null) {
                source.sendSuccess(() -> square, false);
            }
        }
        return shown;
    }

    /** Where the colony's centrepiece stands now ({@link Squares#of}); {@code null} for a colony MineColonies does not have. */
    private static Component squareLine(final ServerLevel level, final DecorLedger ledger, final int id) {
        final IColony colony = IColonyManager.getInstance().getColonyByWorld(id, level);
        if (colony == null) {
            return null;
        }
        final Squares.Square square = Squares.of(level, colony, ledger);
        return switch (square.source()) {
            case BUILDING -> !square.located()
                    ? Component.translatable("com.colony_seasons.command.decor.list.square.unlocated", square.building().toShortString())
                    : Component.translatable(square.busy() ? "com.colony_seasons.command.decor.list.square.busy"
                            : "com.colony_seasons.command.decor.list.square.building", square.building().toShortString(), square.anchor().toShortString());
            case MARKER -> Component.translatable("com.colony_seasons.command.decor.list.square.marker", square.anchor().toShortString());
            case HALL, NONE -> Component.translatable("com.colony_seasons.command.decor.list.square.none");
        };
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
