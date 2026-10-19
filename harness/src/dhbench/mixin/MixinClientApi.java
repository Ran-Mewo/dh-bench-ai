package dhbench.mixin;

import com.seibel.distanthorizons.core.api.internal.ClientApi;
import dhbench.Benchmark;
import dhbench.GpuTimers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Times everything DH does on the render thread: LOD passes, render thread tasks and the vanilla fade passes */
@Mixin(value = ClientApi.class, remap = false)
public class MixinClientApi
{
	private static final String VANILLA_FADE_RENDER = "Lcom/seibel/distanthorizons/api/interfaces/render/renderDef/IDhApiVanillaFadeRenderer;render";

	@Inject(method = { "renderLodLayer", "renderFadeOpaque", "renderFadeTransparent" }, at = @At("HEAD"))
	private void dhbench$onRenderStart(CallbackInfo ci) { Benchmark.onDhRenderStart(); }

	@Inject(method = { "renderLodLayer", "renderFadeOpaque", "renderFadeTransparent" }, at = @At("RETURN"))
	private void dhbench$onRenderEnd(CallbackInfo ci) { Benchmark.onDhRenderEnd(); }

	@Inject(method = { "renderFadeOpaque", "renderFadeTransparent" }, at = @At(value = "INVOKE", target = VANILLA_FADE_RENDER))
	private void dhbench$vanillaFadeStart(CallbackInfo ci) { GpuTimers.begin("vanillaFade"); }

	@Inject(method = { "renderFadeOpaque", "renderFadeTransparent" }, at = @At(value = "INVOKE", target = VANILLA_FADE_RENDER, shift = At.Shift.AFTER))
	private void dhbench$vanillaFadeEnd(CallbackInfo ci) { GpuTimers.end(); }

}
