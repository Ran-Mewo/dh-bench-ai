package dhbench.mixin;

import net.minecraft.SharedConstants;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Mutable;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * DH dev builds turn on Blaze3D's per-draw validation, which release builds don't have.
 * The higher priority makes this run after DH's own SharedConstants mixin.
 */
@Mixin(value = SharedConstants.class, priority = 2000)
public class MixinSharedConstants
{
	@Mutable
	@Shadow @Final public static boolean IS_RUNNING_IN_IDE;

	@Inject(method = "<clinit>", at = @At("TAIL"))
	private static void dhbench$disableValidation(CallbackInfo ci) { IS_RUNNING_IN_IDE = false; }

}
