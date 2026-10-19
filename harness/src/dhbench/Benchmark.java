package dhbench;

import com.seibel.distanthorizons.api.enums.rendering.EDhApiRendererMode;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.objects.IDhApiVertexBufferWrapper;
import com.seibel.distanthorizons.api.interfaces.render.renderDef.AbstractDhApiRenderDefinition;
import com.seibel.distanthorizons.core.api.internal.SharedApi;
import com.seibel.distanthorizons.core.config.Config;
import com.seibel.distanthorizons.core.dataObjects.render.bufferBuilding.LodBufferContainer;
import com.seibel.distanthorizons.core.pos.DhSectionPos;
import com.seibel.distanthorizons.core.dependencyInjection.SingletonInjector;
import com.seibel.distanthorizons.core.level.IDhLevel;
import com.seibel.distanthorizons.core.render.RenderBufferHandler;
import com.seibel.distanthorizons.core.render.RenderThreadTaskHandler;
import com.seibel.distanthorizons.core.util.objects.SortedArraySet;
import com.seibel.distanthorizons.core.util.threading.PriorityTaskPicker;
import com.seibel.distanthorizons.core.util.threading.ThreadPoolUtil;
import com.seibel.distanthorizons.core.world.AbstractDhWorld;
import com.seibel.distanthorizons.core.wrapperInterfaces.minecraft.IMinecraftRenderWrapper;
import com.seibel.distanthorizons.coreapi.DependencyInjection.OverrideInjector;
import com.seibel.distanthorizons.coreapi.ModInfo;
import it.unimi.dsi.fastutil.booleans.BooleanConsumer;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.fabricmc.api.ClientModInitializer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.BackupConfirmScreen;
import net.minecraft.client.gui.screens.ConfirmScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.server.MinecraftServer;

import java.lang.management.BufferPoolMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

/**
 * Dev-only harness, never shipped: waits for the LODs to finish loading,
 * records frame times for a few fixed camera directions with DH on and off,
 * writes the results as JSON to {@code -Ddhbench.output} and closes the game.
 */
public class Benchmark implements ClientModInitializer
{
	private static final Path OUTPUT_FILE = Paths.get(System.getProperty("dhbench.output"));

	// exactly north at pitch 0 gives an identity model view matrix, which DH treats as "no matrix" and skips rendering
	private static final int[] VIEW_YAWS = { 30, 120, 210, 300 };
	private static final long SETTLE_MS = 2_000;
	private static final long MEASURE_MS = 8_000;
	private static final long LOD_IDLE_MS = 5_000;
	private static final long MAX_WAIT_MS = 10 * 60_000;

	private static volatile float viewYaw = 0;
	private static volatile float viewPitch = 0;
	private static volatile boolean controllingCamera = false;
	private static volatile FrameRecorder activeRecorder = null;

	private static long lastFrameNanos = 0;
	private static long dhRenderStartNanos = 0;
	private static Screen handledScreen = null;



	//=======//
	// hooks //
	//=======//

	@Override
	public void onInitializeClient()
	{
		Thread thread = new Thread(Benchmark::run, "DH Benchmark");
		thread.setDaemon(true);
		thread.start();
	}

	public static void onFrameStart()
	{
		long now = System.nanoTime();
		FrameRecorder recorder = activeRecorder;
		if (recorder != null && lastFrameNanos != 0)
		{
			recorder.addFrame(now - lastFrameNanos);
		}
		lastFrameNanos = now;
		GpuTimers.onFrameStart(recorder);

		LocalPlayer player = Minecraft.getInstance().player;
		if (controllingCamera && player != null)
		{
			player.setYRot(viewYaw);
			player.setXRot(viewPitch);
			player.yRotO = viewYaw;
			player.xRotO = viewPitch;
		}
	}

	public static void onDhRenderStart() { dhRenderStartNanos = System.nanoTime(); }
	public static void onDhRenderEnd()
	{
		FrameRecorder recorder = activeRecorder;
		if (recorder != null)
		{
			recorder.addDhRenderTime(System.nanoTime() - dhRenderStartNanos);
		}
	}



	//=========//
	// running //
	//=========//

	private static void run()
	{
		Map<String, Object> result = new LinkedHashMap<>();
		try
		{
			waitUntil(() ->
			{
				skipWorldOpeningPrompts();
				return Minecraft.getInstance().player != null && SharedApi.getAbstractDhWorld() != null;
			}, MAX_WAIT_MS);
			long joinNanos = System.nanoTime();
			controllingCamera = true;

			result.put("dhVersion", ModInfo.VERSION);
			result.put("renderingApi", OverrideInjector.INSTANCE.get(AbstractDhApiRenderDefinition.class).getName());
			log("joined the world, waiting for the LODs to load");
			freezeTimeAndWeather();
			result.put("lodsFullyLoaded", waitForLodsToLoad());
			result.put("lodLoadSeconds", (System.nanoTime() - joinNanos) / 1e9);

			String showAngles = System.getProperty("dhbench.showAngles");
			if (showAngles != null)
			{
				showAngles(showAngles);
				return;
			}

			List<Map<String, Object>> views = new ArrayList<>();
			result.put("dh", measureViews("dh", views));
			result.put("memory", measureMemory());

			Config.Client.Advanced.Debugging.rendererMode.setWithoutSaving(EDhApiRendererMode.DISABLED);
			result.put("vanilla", measureViews("vanilla", views));
			Config.Client.Advanced.Debugging.rendererMode.setWithoutSaving(EDhApiRendererMode.DEFAULT);

			result.put("views", views);
		}
		catch (Throwable e)
		{
			e.printStackTrace();
			result.put("error", e.toString());
		}
		finally
		{
			try
			{
				Files.write(OUTPUT_FILE, toJson(result).getBytes(StandardCharsets.UTF_8));
				log("wrote " + OUTPUT_FILE);
			}
			catch (Exception e)
			{
				e.printStackTrace();
			}
			Minecraft.getInstance().execute(() -> Minecraft.getInstance().stop());
		}
	}

	/** The benchmark world is a throwaway copy, so the prompts MC shows when upgrading it to a newer version are answered automatically. */
	private static void skipWorldOpeningPrompts()
	{
		Minecraft minecraft = Minecraft.getInstance();
		Screen screen = currentScreen(minecraft);
		if (screen == null || screen == handledScreen)
		{
			return;
		}

		if (screen instanceof BackupConfirmScreen)
		{
			handledScreen = screen;
			minecraft.execute(() -> ((BackupConfirmScreen.Listener) readField(BackupConfirmScreen.class, "onProceed", screen)).proceed(/*backup*/ false, /*eraseCache*/ false));
		}
		else if (screen instanceof ConfirmScreen)
		{
			handledScreen = screen;
			minecraft.execute(() -> ((BooleanConsumer) readField(ConfirmScreen.class, "callback", screen)).accept(true));
		}
	}
	private static Object readField(Class<?> owner, String name, Object instance)
	{
		try
		{
			Field field = owner.getDeclaredField(name);
			field.setAccessible(true);
			return field.get(instance);
		}
		catch (ReflectiveOperationException e)
		{
			throw new RuntimeException(e);
		}
	}

	/** MC 26.3 moved the current screen from "Minecraft.screen" to "Minecraft.gui.screen()" */
	private static Screen currentScreen(Minecraft minecraft)
	{
		try
		{
			Object gui = Minecraft.class.getField("gui").get(minecraft);
			return gui == null ? null : (Screen) gui.getClass().getMethod("screen").invoke(gui);
		}
		catch (ReflectiveOperationException e)
		{
			try
			{
				return (Screen) Minecraft.class.getField("screen").get(minecraft);
			}
			catch (ReflectiveOperationException ignore)
			{
				return null;
			}
		}
	}

	/** Keeps the lighting, sky and cloud colors the same between runs, older MC versions use the camel case game rule names */
	private static void freezeTimeAndWeather()
	{
		MinecraftServer server = Minecraft.getInstance().getSingleplayerServer();
		server.execute(() ->
		{
			for (String command : new String[] { "time set noon", "weather clear", "gamerule advance_time false", "gamerule advance_weather false", "gamerule doDaylightCycle false", "gamerule doWeatherCycle false" })
			{
				server.getCommands().performPrefixedCommand(server.createCommandSourceStack(), command);
			}
		});
	}

	/** @return false if DH was still busy when the wait timed out */
	private static boolean waitForLodsToLoad() throws Exception
	{
		// don't confuse "hasn't started yet" with "done"
		waitUntil(() -> ThreadPoolUtil.getRenderLoadingExecutor() != null && ThreadPoolUtil.getRenderLoadingExecutor().getCompletedTaskCount() > 0, MAX_WAIT_MS);

		long deadline = System.currentTimeMillis() + MAX_WAIT_MS;
		long idleSince = System.currentTimeMillis();
		long lastLogMs = 0;
		while (System.currentTimeMillis() < deadline)
		{
			String busyWork = busyRenderWork();
			if (!busyWork.isEmpty())
			{
				idleSince = System.currentTimeMillis();
				if (System.currentTimeMillis() - lastLogMs > 10_000)
				{
					lastLogMs = System.currentTimeMillis();
					log("still loading:" + busyWork);
				}
			}
			else if (System.currentTimeMillis() - idleSince >= LOD_IDLE_MS)
			{
				return true;
			}
			Thread.sleep(250);
		}
		return false;
	}
	/**
	 * Only the work that changes what's rendered is waited on,
	 * world gen and parent LOD updates can keep running in the background without affecting the frame.
	 * @return an empty string when nothing is queued or running
	 */
	private static String busyRenderWork() throws Exception
	{
		Field renderQueueField = RenderThreadTaskHandler.class.getDeclaredField("RENDER_THREAD_RUNNABLE_QUEUE");
		renderQueueField.setAccessible(true);
		int renderThreadTasks = ((Collection<?>) renderQueueField.get(null)).size();

		return (renderThreadTasks == 0 ? "" : " render thread tasks " + renderThreadTasks)
			+ busyDescription("render loading", ThreadPoolUtil.getRenderLoadingExecutor(), true)
			// DH's beacon refresh sleeps inside a file handler task, so only queued file work counts
			+ busyDescription("file handler", ThreadPoolUtil.getFileHandlerExecutor(), false)
			+ busyDescription("chunk to LOD", ThreadPoolUtil.getChunkToLodBuilderExecutor(), true);
	}
	private static String busyDescription(String name, PriorityTaskPicker.Executor executor, boolean countRunningTasks)
	{
		if (executor == null || (executor.getQueueSize() == 0 && (!countRunningTasks || executor.getRunningTaskCount() == 0)))
		{
			return "";
		}
		return " " + name + " " + executor.getRunningTaskCount() + "/" + executor.getQueueSize();
	}

	/** Holds each "yaw:pitch" view long enough for bench.sh to screenshot it, instead of measuring */
	private static void showAngles(String angles) throws InterruptedException
	{
		for (String angle : angles.split(","))
		{
			String[] yawAndPitch = angle.split(":");
			viewYaw = Float.parseFloat(yawAndPitch[0]);
			viewPitch = Float.parseFloat(yawAndPitch[1]);
			Thread.sleep(3_000);
			log("showing yaw " + yawAndPitch[0] + " pitch " + yawAndPitch[1]);
			Thread.sleep(3_000);
		}
	}

	private static Map<String, Object> measureViews(String rendererName, List<Map<String, Object>> viewList) throws Exception
	{
		FrameRecorder combined = new FrameRecorder();
		for (int yaw : VIEW_YAWS)
		{
			viewYaw = yaw;
			log("measuring " + rendererName + " at yaw " + yaw);
			Thread.sleep(SETTLE_MS);

			FrameRecorder recorder = new FrameRecorder();
			activeRecorder = recorder;
			Thread.sleep(MEASURE_MS);
			activeRecorder = null;

			Map<String, Object> view = new LinkedHashMap<>();
			view.put("renderer", rendererName);
			view.put("yaw", yaw);
			view.put("vboRenderCount", vboRenderCounts());
			view.put("opaqueVertices", onRenderThread(Benchmark::opaqueVertexCounts));
			view.putAll(recorder.summarize());
			viewList.add(view);
			combined.addAll(recorder);
		}
		return combined.summarize();
	}

	private static String vboRenderCounts()
	{
		StringBuilder builder = new StringBuilder();
		AbstractDhWorld world = SharedApi.getAbstractDhWorld();
		for (IDhLevel level : world.getAllLoadedLevels())
		{
			RenderBufferHandler renderBufferHandler = level.getRenderBufferHandler();
			if (renderBufferHandler != null)
			{
				builder.append(renderBufferHandler.getVboRenderDebugMenuString());
			}
		}
		return builder.toString();
	}

	/** opaque vertices DH draws for the current view, in total and per detail level */
	private static Map<String, Object> opaqueVertexCounts()
	{
		Map<String, Object> counts = new TreeMap<>();
		long total = 0;
		for (IDhLevel level : SharedApi.getAbstractDhWorld().getAllLoadedLevels())
		{
			RenderBufferHandler renderBufferHandler = level.getRenderBufferHandler();
			if (renderBufferHandler == null)
			{
				continue;
			}

			SortedArraySet<LodBufferContainer> visibleBuffers = renderBufferHandler.getColumnRenderBuffers();
			for (int i = 0; i < visibleBuffers.size(); i++)
			{
				LodBufferContainer container = visibleBuffers.get(i);
				long containerVertices = 0;
				for (IDhApiVertexBufferWrapper buffer : container.getVboOpaqueWrappers())
				{
					containerVertices += (buffer == null) ? 0 : buffer.getVertexCount();
				}
				counts.merge("detail" + DhSectionPos.getDetailLevel(container.pos), containerVertices, (a, b) -> (Long) a + (Long) b);
				total += containerVertices;
			}
		}
		counts.put("total", total);
		return counts;
	}

	private static Map<String, Object> measureMemory() throws Exception
	{
		System.gc();
		Thread.sleep(1_000);
		System.gc();

		Map<String, Object> memory = new LinkedHashMap<>();
		MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
		memory.put("heapUsedMb", heap.getUsed() / 1e6);
		memory.put("heapMaxMb", heap.getMax() / 1e6);
		for (BufferPoolMXBean pool : ManagementFactory.getPlatformMXBeans(BufferPoolMXBean.class))
		{
			memory.put(pool.getName() + "BufferMb", pool.getMemoryUsed() / 1e6);
		}
		memory.put("processRssMb", readProcStatusKb("VmRSS") / 1e3);

		CompletableFuture<String> gpuName = new CompletableFuture<>();
		Minecraft.getInstance().execute(() ->
		{
			try { gpuName.complete(org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER)); }
			catch (Throwable e) { gpuName.complete("unknown"); }
		});
		memory.put("gpu", gpuName.get());
		IMinecraftRenderWrapper mcRender = SingletonInjector.INSTANCE.get(IMinecraftRenderWrapper.class);
		memory.put("resolution", mcRender.getTargetFramebufferViewportWidth() + "x" + mcRender.getTargetFramebufferViewportHeight());
		return memory;
	}
	private static double readProcStatusKb(String key) throws Exception
	{
		Path status = Paths.get("/proc/self/status");
		if (!Files.exists(status))
		{
			return Double.NaN;
		}

		for (String line : Files.readAllLines(status))
		{
			if (line.startsWith(key + ":"))
			{
				return Double.parseDouble(line.replaceAll("[^0-9]", ""));
			}
		}
		return Double.NaN;
	}

	private static void log(String message) { System.out.println("[dhbench] " + message); }

	/** DH rebuilds its render lists on the render thread every frame, so they are only read there */
	private static <T> T onRenderThread(Supplier<T> supplier) throws Exception
	{
		CompletableFuture<T> future = new CompletableFuture<>();
		Minecraft.getInstance().execute(() -> future.complete(supplier.get()));
		return future.get();
	}

	private static void waitUntil(BooleanSupplier condition, long timeoutMs) throws Exception
	{
		long deadline = System.currentTimeMillis() + timeoutMs;
		while (!condition.getAsBoolean())
		{
			if (System.currentTimeMillis() > deadline)
			{
				throw new IllegalStateException("timed out waiting for the world/LODs");
			}
			Thread.sleep(250);
		}
	}



	//=========//
	// helpers //
	//=========//

	public static class FrameRecorder
	{
		private final LongArrayList frameNanos = new LongArrayList();
		private long dhRenderNanos = 0;
		private final Map<String, Long> gpuNanosByStep = new TreeMap<>();

		public synchronized void addFrame(long nanos) { this.frameNanos.add(nanos); }
		public synchronized void addDhRenderTime(long nanos) { this.dhRenderNanos += nanos; }
		public synchronized void addGpuTime(String step, long nanos) { this.gpuNanosByStep.merge(step, nanos, Long::sum); }
		public synchronized void addAll(FrameRecorder other)
		{
			this.frameNanos.addAll(other.frameNanos);
			this.dhRenderNanos += other.dhRenderNanos;
			other.gpuNanosByStep.forEach(this::addGpuTime);
		}

		public synchronized Map<String, Object> summarize()
		{
			long[] sorted = this.frameNanos.toLongArray();
			Arrays.sort(sorted);
			long totalNanos = 0;
			for (long nanos : sorted)
			{
				totalNanos += nanos;
			}

			int worstCount = Math.max(1, sorted.length / 100);
			long worstNanos = 0;
			for (int i = sorted.length - worstCount; i < sorted.length; i++)
			{
				worstNanos += sorted[i];
			}

			Map<String, Object> summary = new LinkedHashMap<>();
			summary.put("frames", sorted.length);
			summary.put("avgFps", sorted.length / (totalNanos / 1e9));
			summary.put("onePercentLowFps", worstCount / (worstNanos / 1e9));
			summary.put("avgFrameMs", totalNanos / 1e6 / sorted.length);
			summary.put("p50FrameMs", sorted[(int) (sorted.length * 0.50)] / 1e6);
			summary.put("p99FrameMs", sorted[(int) (sorted.length * 0.99)] / 1e6);
			summary.put("maxFrameMs", sorted[sorted.length - 1] / 1e6);
			summary.put("dhRenderThreadMsPerFrame", this.dhRenderNanos / 1e6 / sorted.length);
			Map<String, Object> gpuMsPerFrame = new LinkedHashMap<>();
			this.gpuNanosByStep.forEach((step, nanos) -> gpuMsPerFrame.put(step, nanos / 1e6 / sorted.length));
			summary.put("gpuMsPerFrame", gpuMsPerFrame);
			return summary;
		}
	}

	private static String toJson(Object value)
	{
		if (value instanceof Map)
		{
			StringJoiner joiner = new StringJoiner(",\n", "{\n", "\n}");
			((Map<?, ?>) value).forEach((key, entry) -> joiner.add(toJson(key.toString()) + ": " + toJson(entry)));
			return joiner.toString();
		}
		else if (value instanceof List)
		{
			StringJoiner joiner = new StringJoiner(",\n", "[\n", "\n]");
			((List<?>) value).forEach(entry -> joiner.add(toJson(entry)));
			return joiner.toString();
		}
		else if (value instanceof String)
		{
			return "\"" + ((String) value).replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
		}
		else if (value instanceof Double && !Double.isFinite((Double) value))
		{
			return "null";
		}
		return String.valueOf(value);
	}

}
