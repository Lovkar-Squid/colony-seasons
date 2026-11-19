package me.lovkar.seasons.mixin;

import com.minecolonies.api.entity.ai.statemachine.states.CitizenAIState;
import com.minecolonies.api.entity.ai.statemachine.states.IState;
import com.minecolonies.core.entity.ai.workers.CitizenAI;
import com.minecolonies.core.entity.citizen.EntityCitizen;
import me.lovkar.seasons.festival.Gathering;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Working colonists come to the gathering once per real day (the owner's choice of 19 Nov 2026, design 5 [B]).
 *
 * <p>MineColonies decides what a colonist does next in {@code CitizenAI.calculateNextState}; a worker goes off duty
 * only for rain, leisure time or when his AI has nothing to do. There is no hook to send him to a festival, so this
 * injects at the head of that method, the same place Voyager's night-shift mixin uses, and answers IDLE (off duty)
 * while {@link Gathering#bringWorker} says his colony gathers everyone right now: only in the festival hours of that
 * game day, never past them, so at their end he goes back to work and at bedtime to bed like everybody else. Hunger,
 * sickness, a raid, rain, mourning and sleep still come first.</p>
 *
 * <p>Written to fail soft: {@code require = 0} on the injection and a non-required mixin config, so if a MineColonies
 * update moves or renames the method, nothing is injected, the mod still loads and only off-duty colonists come.
 * Both mixins injecting at the same head work side by side (the csfestival probe, mode voyager): each answers only
 * for its own colonists and hours.</p>
 */
@Mixin(CitizenAI.class)
public abstract class CitizenAIFestivalMixin {

    @Shadow
    @Final
    private EntityCitizen citizen;

    @Inject(method = "calculateNextState", at = @At("HEAD"), cancellable = true, require = 0)
    private void colony_seasons$comeToTheGathering(final CallbackInfoReturnable<IState> callback) {
        if (Gathering.bringWorker(citizen) && !((CitizenAI) (Object) this).shouldEat()) {
            callback.setReturnValue(CitizenAIState.IDLE);
        }
    }
}
