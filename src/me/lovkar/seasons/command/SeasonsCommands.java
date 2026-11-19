package me.lovkar.seasons.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.context.CommandContext;
import me.lovkar.seasons.SeasonsConfig;
import me.lovkar.seasons.calendar.CalendarManager;
import me.lovkar.seasons.calendar.Festival;
import me.lovkar.seasons.calendar.Season;
import me.lovkar.seasons.calendar.SeasonState;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;

import java.util.List;

/**
 * {@code /colonyseasons}: {@code status} (everyone), and for operators (permission 2) {@code season set <season>},
 * {@code festival force <id>} and {@code festival clear}. The forced festival is saved with the world.
 */
public final class SeasonsCommands {
    /** The permission level the changing commands need. */
    public static final int PERMISSION = 2;

    private SeasonsCommands() {
    }

    public static void register() {
        NeoForge.EVENT_BUS.addListener(SeasonsCommands::onRegisterCommands);
    }

    private static void onRegisterCommands(final RegisterCommandsEvent event) {
        build(event.getDispatcher());
    }

    /** Public so a test can build the tree on its own dispatcher. */
    public static void build(final CommandDispatcher<CommandSourceStack> dispatcher) {
        final LiteralArgumentBuilder<CommandSourceStack> seasonSet = Commands.literal("set");
        for (final Season season : Season.values()) {
            seasonSet.then(Commands.literal(season.id()).executes(context -> setSeason(context, season)));
        }
        dispatcher.register(Commands.literal("colonyseasons")
                .then(Commands.literal("status").executes(SeasonsCommands::status))
                .then(Commands.literal("season")
                        .requires(source -> source.hasPermission(PERMISSION))
                        .then(seasonSet))
                .then(Commands.literal("festival")
                        .requires(source -> source.hasPermission(PERMISSION))
                        .then(Commands.literal("force")
                                .then(Commands.argument("id", StringArgumentType.word())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(SeasonsConfig.FESTIVAL_IDS, builder))
                                        .executes(SeasonsCommands::forceFestival)))
                        .then(Commands.literal("clear").executes(SeasonsCommands::clearFestival))));
    }

    // ---------------------------------------------------------------- status

    private static int status(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final CalendarManager.Snapshot snapshot = CalendarManager.refresh(source.getServer());
        final SeasonState state = snapshot.season();
        final String whose = state.source() == SeasonState.Source.SERENE
                ? "com.colony_seasons.command.status.source.serene"
                : "com.colony_seasons.command.status.source.own";
        source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.status.season",
                Component.translatable(state.season().translationKey()), state.dayOfYear() + 1, state.yearLength(),
                Component.translatable(whose)), false);
        final List<String> festivals = snapshot.festivals().ids();
        if (festivals.isEmpty()) {
            source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.status.festivals.none"), false);
        } else {
            source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.status.festivals", names(festivals)), false);
        }
        if (!snapshot.forced().isEmpty()) {
            source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.status.forced",
                    Component.translatable(Festival.nameKey(snapshot.forced()))), false);
        }
        return 1;
    }

    // ---------------------------------------------------------------- season set

    private static int setSeason(final CommandContext<CommandSourceStack> context, final Season season) {
        final CommandSourceStack source = context.getSource();
        final CalendarManager.Snapshot snapshot = CalendarManager.setSeason(source.getServer(), season);
        if (snapshot.season().source() == SeasonState.Source.SERENE) {
            // the offset was saved (it is what the own calendar will use), but the game keeps following Serene Seasons
            final int day = snapshot.season().dayOfYear() + 1;
            source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.season.set.serene", day), false);
        } else {
            source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.season.set",
                    Component.translatable(season.translationKey())), true);
        }
        return 1;
    }

    // ---------------------------------------------------------------- festival force / clear

    private static int forceFestival(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String id = StringArgumentType.getString(context, "id");
        if (!SeasonsConfig.FESTIVAL_IDS.contains(id)) {
            source.sendFailure(Component.translatable("com.colony_seasons.command.festival.force.unknown", id,
                    String.join(", ", SeasonsConfig.FESTIVAL_IDS)));
            return 0;
        }
        if (!SeasonsConfig.festivalEnabled(id)) {
            source.sendFailure(Component.translatable("com.colony_seasons.command.festival.force.disabled",
                    Component.translatable(Festival.nameKey(id))));
            return 0;
        }
        CalendarManager.setForcedFestival(source.getServer(), id);
        source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.festival.force",
                Component.translatable(Festival.nameKey(id))), true);
        return 1;
    }

    private static int clearFestival(final CommandContext<CommandSourceStack> context) {
        final CommandSourceStack source = context.getSource();
        final String forced = CalendarManager.data(source.getServer()).forcedFestival();
        if (forced.isEmpty()) {
            source.sendFailure(Component.translatable("com.colony_seasons.command.festival.clear.none"));
            return 0;
        }
        CalendarManager.setForcedFestival(source.getServer(), "");
        source.sendSuccess(() -> Component.translatable("com.colony_seasons.command.festival.clear",
                Component.translatable(Festival.nameKey(forced))), true);
        return 1;
    }

    /** Festival names, separated by commas. */
    private static MutableComponent names(final List<String> ids) {
        final MutableComponent all = Component.empty();
        for (int i = 0; i < ids.size(); i++) {
            if (i > 0) {
                all.append(", ");
            }
            all.append(Component.translatable(Festival.nameKey(ids.get(i))));
        }
        return all;
    }
}
