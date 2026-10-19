package dhbench;

import org.lwjgl.opengl.GL;
import org.lwjgl.opengl.GL33;

/**
 * GPU time of each DH render step, measured with GL timestamp queries. <br>
 * Results are read back a few frames later so the queries never stall the CPU. <br>
 * Only works when MC uses its OpenGL backend, otherwise it turns itself off.
 */
public class GpuTimers
{
	private static final int FRAMES_IN_FLIGHT = 4;
	private static final int MAX_TIMERS_PER_FRAME = 32;

	private static boolean disabled = false;
	private static int[][] queryIds = null;
	private static final String[][] LABELS = new String[FRAMES_IN_FLIGHT][MAX_TIMERS_PER_FRAME];
	private static final int[] TIMER_COUNT = new int[FRAMES_IN_FLIGHT];
	private static int frameSlot = 0;



	/** Collects the timers recorded {@link #FRAMES_IN_FLIGHT} frames ago and starts a new frame. */
	public static void onFrameStart(Benchmark.FrameRecorder recorder)
	{
		if (disabled)
		{
			return;
		}

		if (queryIds == null)
		{
			try
			{
				GL.getCapabilities();
			}
			catch (IllegalStateException noGlContext)
			{
				disabled = true;
				return;
			}

			queryIds = new int[FRAMES_IN_FLIGHT][MAX_TIMERS_PER_FRAME * 2];
			for (int[] frameQueries : queryIds)
			{
				GL33.glGenQueries(frameQueries);
			}
		}

		frameSlot = (frameSlot + 1) % FRAMES_IN_FLIGHT;
		int[] frameQueries = queryIds[frameSlot];
		for (int i = 0; i < TIMER_COUNT[frameSlot]; i++)
		{
			long startNanos = GL33.glGetQueryObjectui64(frameQueries[i * 2], GL33.GL_QUERY_RESULT);
			long endNanos = GL33.glGetQueryObjectui64(frameQueries[i * 2 + 1], GL33.GL_QUERY_RESULT);
			if (recorder != null)
			{
				recorder.addGpuTime(LABELS[frameSlot][i], endNanos - startNanos);
			}
		}
		TIMER_COUNT[frameSlot] = 0;
	}

	public static void begin(String label)
	{
		if (queryIds == null || TIMER_COUNT[frameSlot] >= MAX_TIMERS_PER_FRAME)
		{
			return;
		}

		LABELS[frameSlot][TIMER_COUNT[frameSlot]] = label;
		GL33.glQueryCounter(queryIds[frameSlot][TIMER_COUNT[frameSlot] * 2], GL33.GL_TIMESTAMP);
	}

	public static void end()
	{
		if (queryIds == null || TIMER_COUNT[frameSlot] >= MAX_TIMERS_PER_FRAME)
		{
			return;
		}

		GL33.glQueryCounter(queryIds[frameSlot][TIMER_COUNT[frameSlot] * 2 + 1], GL33.GL_TIMESTAMP);
		TIMER_COUNT[frameSlot]++;
	}

}
