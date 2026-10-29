package dev.xantha.vss.networking.client;

import static org.junit.jupiter.api.Assertions.*;

import dev.xantha.vss.testutil.MinecraftTestBootstrap;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.SwingAnimationType;
import net.minecraft.world.item.component.SwingAnimation;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class FarPlayerSwingStateTest {
    @BeforeAll
    static void bootstrap() {
        MinecraftTestBootstrap.initialize();
    }

    @Test
    void preservesOffhandDescriptionAndExpiresWithoutVanillaPlayerTick() {
        FarPlayerSwingState state = new FarPlayerSwingState();
        SwingAnimation animation = new SwingAnimation(SwingAnimationType.WHACK, 4);
        state.start(InteractionHand.OFF_HAND, animation);

        assertEquals(InteractionHand.OFF_HAND, state.currentSwing().hand());
        assertEquals(animation, state.currentSwing().animation());
        assertEquals(4, state.currentSwing().durationTicks());
        state.tick();
        state.tick();
        state.tick();
        assertTrue(state.isSwinging());
        assertTrue(state.animation(1.0F) > state.animation(0.0F));

        for (int tick = 0; tick < 4; tick++) state.tick();
        assertFalse(state.isSwinging());
        assertNull(state.currentSwing());
        assertEquals(0.0F, state.animation(1.0F));
    }

    @Test
    void newServerActionRestartsInterpolationAndCanChangeHands() {
        FarPlayerSwingState state = new FarPlayerSwingState();
        state.start(InteractionHand.MAIN_HAND, SwingAnimation.DEFAULT);
        for (int tick = 0; tick < 3; tick++) state.tick();
        assertTrue(state.animation(1.0F) > 0.0F);

        state.start(InteractionHand.OFF_HAND, SwingAnimation.DEFAULT);
        assertEquals(InteractionHand.OFF_HAND, state.currentSwing().hand());
        assertEquals(0.0F, state.animation(0.0F));
        assertEquals(0.0F, state.animation(1.0F));
        assertTrue(state.isSwinging());
    }
}
