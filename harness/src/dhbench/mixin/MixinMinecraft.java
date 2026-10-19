package dhbench.mixin;

import dhbench.Benchmark;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public class MixinMinecraft
{
	/** runTick is called once per rendered frame */
	@Inject(method = "runTick", at = @At("HEAD"))
	private void dhbench$onFrameStart(CallbackInfo ci) { Benchmark.onFrameStart(); }

}
