package dhbench.mixin;

import com.seibel.distanthorizons.api.interfaces.render.renderDef.IDhApiTerrainRenderer;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderParam;
import com.seibel.distanthorizons.core.render.renderer.LodRenderer;
import dhbench.GpuTimers;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Wraps each step of DH's render pass in a GPU timer */
@Mixin(value = LodRenderer.class, remap = false)
public class MixinLodRenderer
{
	private static final String RENDER_PASS = "renderTerrain(Lcom/seibel/distanthorizons/core/render/RenderParam;Lcom/seibel/distanthorizons/core/wrapperInterfaces/minecraft/IProfilerWrapper;Z)V";
	private static final String TERRAIN_PASS = "renderTerrain(Lcom/seibel/distanthorizons/api/interfaces/render/renderDef/IDhApiTerrainRenderer;Lcom/seibel/distanthorizons/core/render/RenderBufferHandler;Lcom/seibel/distanthorizons/core/render/RenderParam;Z)V";
	private static final String RENDER_DEF = "Lcom/seibel/distanthorizons/api/interfaces/render/renderDef/";

	@Inject(method = TERRAIN_PASS, at = @At("HEAD"))
	private void dhbench$terrainStart(IDhApiTerrainRenderer terrainRenderer, RenderBufferHandler bufferHandler, RenderParam renderParam, boolean opaquePass, CallbackInfo ci) { GpuTimers.begin(opaquePass ? "terrainOpaque" : "terrainTransparent"); }
	@Inject(method = TERRAIN_PASS, at = @At("RETURN"))
	private void dhbench$terrainEnd(CallbackInfo ci) { GpuTimers.end(); }

	@Inject(method = RENDER_PASS, at = @At(value = "INVOKE", target = RENDER_DEF + "IDhApiMetaRenderer;clearDhDepthAndColorTextures"))
	private void dhbench$clearStart(CallbackInfo ci) { GpuTimers.begin("clear"); }
	@Inject(method = RENDER_PASS, at = @At(value = "INVOKE", target = RENDER_DEF + "IDhApiGenericRenderer;render"))
	private void dhbench$genericStart(CallbackInfo ci) { GpuTimers.begin("generic"); }
	@Inject(method = RENDER_PASS, at = @At(value = "INVOKE", target = RENDER_DEF + "IDhApiSsaoRenderer;render"))
	private void dhbench$ssaoStart(CallbackInfo ci) { GpuTimers.begin("ssao"); }
	@Inject(method = RENDER_PASS, at = @At(value = "INVOKE", target = RENDER_DEF + "IDhApiFogRenderer;render"))
	private void dhbench$fogStart(CallbackInfo ci) { GpuTimers.begin("fog"); }
	@Inject(method = RENDER_PASS, at = @At(value = "INVOKE", target = RENDER_DEF + "IDhApiFarFadeRenderer;render"))
	private void dhbench$farFadeStart(CallbackInfo ci) { GpuTimers.begin("farFade"); }
	@Inject(method = RENDER_PASS, at = @At(value = "INVOKE", target = RENDER_DEF + "IDhApiAntiAliasRenderer;render"))
	private void dhbench$antiAliasStart(CallbackInfo ci) { GpuTimers.begin("antiAlias"); }
	@Inject(method = RENDER_PASS, at = @At(value = "INVOKE", target = RENDER_DEF + "IDhApiMetaRenderer;copyToMcTexture"))
	private void dhbench$applyStart(CallbackInfo ci) { GpuTimers.begin("applyToMc"); }

	@Inject(method = RENDER_PASS, at = {
		@At(value = "INVOKE", target = RENDER_DEF + "IDhApiMetaRenderer;clearDhDepthAndColorTextures", shift = At.Shift.AFTER),
		@At(value = "INVOKE", target = RENDER_DEF + "IDhApiGenericRenderer;render", shift = At.Shift.AFTER),
		@At(value = "INVOKE", target = RENDER_DEF + "IDhApiSsaoRenderer;render", shift = At.Shift.AFTER),
		@At(value = "INVOKE", target = RENDER_DEF + "IDhApiFogRenderer;render", shift = At.Shift.AFTER),
		@At(value = "INVOKE", target = RENDER_DEF + "IDhApiFarFadeRenderer;render", shift = At.Shift.AFTER),
		@At(value = "INVOKE", target = RENDER_DEF + "IDhApiAntiAliasRenderer;render", shift = At.Shift.AFTER),
		@At(value = "INVOKE", target = RENDER_DEF + "IDhApiMetaRenderer;copyToMcTexture", shift = At.Shift.AFTER),
	})
	private void dhbench$stepEnd(CallbackInfo ci) { GpuTimers.end(); }

}
