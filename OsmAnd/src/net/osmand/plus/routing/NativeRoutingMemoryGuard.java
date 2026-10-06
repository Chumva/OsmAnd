package net.osmand.plus.routing;

import android.app.ActivityManager;
import android.content.Context;
import android.os.Debug;

import androidx.annotation.NonNull;

import net.osmand.PlatformUtil;
import net.osmand.router.RouteCalculationProgress;

import org.apache.commons.logging.Log;

/**
 * Native routing (A* and HH) has no limit on its search graph, only on the road tile cache, so a
 * long route without HH (pedestrian, truck, HH fallback) grows the native heap until the process
 * is killed. The guard polls the native heap of the process while a route is calculated and
 * cancels the calculation (the native loop polls isCancelled) once the heap reaches a limit that
 * leaves the system a reserve of RAM, so the user gets an error instead of a crash.
 */
class NativeRoutingMemoryGuard {

	private static final Log log = PlatformUtil.getLog(NativeRoutingMemoryGuard.class);

	private static final long MB = 1 << 20;
	private static final long RESERVED_RAM = 1024 * MB;
	private static final long MIN_LIMIT = 512 * MB;
	private static final long MAX_LIMIT = 3072 * MB;
	private static final long CHECK_INTERVAL_MS = 200;

	private final RouteCalculationParams params;
	private final long limit;
	private final Thread thread;
	private long peakAllocated;

	private NativeRoutingMemoryGuard(@NonNull RouteCalculationParams params) {
		this.params = params;
		this.limit = getLimit(params.ctx);
		this.thread = new Thread(this::watch, "RoutingMemoryGuard");
		thread.setDaemon(true);
	}

	@NonNull
	static NativeRoutingMemoryGuard start(@NonNull RouteCalculationParams params) {
		NativeRoutingMemoryGuard guard = new NativeRoutingMemoryGuard(params);
		guard.thread.start();
		return guard;
	}

	/**
	 * Device RAM minus 1 GB for the system and the rest of the app, within 512 MB and 3 GB:
	 * in crash reports the process died from 3.1 GB of native heap on.
	 */
	private static long getLimit(@NonNull Context ctx) {
		ActivityManager.MemoryInfo info = new ActivityManager.MemoryInfo();
		((ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE)).getMemoryInfo(info);
		return Math.max(MIN_LIMIT, Math.min(MAX_LIMIT, info.totalMem - RESERVED_RAM));
	}

	private void watch() {
		RouteCalculationProgress progress = params.calculationProgress;
		while (!progress.isCancelled) {
			long allocated = Debug.getNativeHeapAllocatedSize();
			peakAllocated = Math.max(peakAllocated, allocated);
			if (allocated > limit) {
				params.memoryLimitExceeded = true;
				progress.isCancelled = true;
				log.error("Route calculation stopped: native heap " + allocated / MB + " MB, limit " + limit / MB + " MB");
				break;
			}
			try {
				Thread.sleep(CHECK_INTERVAL_MS);
			} catch (InterruptedException e) {
				break;
			}
		}
		log.info("Route calculation native heap peak " + peakAllocated / MB + " MB, limit " + limit / MB + " MB");
	}

	/**
	 * Stops the watcher and waits for it, so the flags do not change after this returns; a second
	 * call is a no-op. Returns true if the guard cancelled the calculation: isCancelled stays set,
	 * and RouteRecalculationTask tells this stop from a requested one by params.memoryLimitExceeded.
	 */
	boolean stop() {
		thread.interrupt();
		try {
			thread.join();
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
		}
		return params.memoryLimitExceeded;
	}
}
