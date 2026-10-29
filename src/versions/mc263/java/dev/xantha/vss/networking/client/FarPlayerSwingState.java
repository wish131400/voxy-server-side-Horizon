package dev.xantha.vss.networking.client;

import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.item.component.SwingAnimation;

/** Manual animation clock for synthetic players that do not run a vanilla entity tick. */
final class FarPlayerSwingState {
    private LivingEntity.SwingState clock = new LivingEntity.SwingState();
    private LivingEntity.SwingDescription description;

    void start(InteractionHand hand, SwingAnimation animation) {
        // Each rising edge from the server is a new action, including rapid
        // repeated swings. A fresh clock resets the previous interpolation too.
        clock = new LivingEntity.SwingState();
        clock.startIfAble(hand, animation, animation.duration());
        description = new LivingEntity.SwingDescription(hand, animation, animation.duration());
    }

    void tick() {
        clock.tick();
        if (!clock.isSwinging()) description = null;
    }

    LivingEntity.SwingDescription currentSwing() {
        return description;
    }

    float animation(float partialTick) {
        return clock.getAnimation(partialTick);
    }

    boolean isSwinging() {
        return clock.isSwinging();
    }
}
