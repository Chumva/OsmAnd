package net.osmand.plus.inapp;

import android.os.RemoteException;

import androidx.annotation.NonNull;

import com.android.installreferrer.api.InstallReferrerClient;
import com.android.installreferrer.api.InstallReferrerClient.InstallReferrerResponse;
import com.android.installreferrer.api.InstallReferrerStateListener;

import net.osmand.PlatformUtil;
import net.osmand.plus.OsmandApplication;
import net.osmand.plus.settings.backend.OsmandSettings;

import org.apache.commons.logging.Log;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

public class InstallReferrerReader {

	private static final Log LOG = PlatformUtil.getLog(InstallReferrerReader.class);

	// Counted down when the read started by readOnce() is over, whatever its outcome
	private static volatile CountDownLatch readFinished;

	public static void readOnce(@NonNull OsmandApplication app) {
		OsmandSettings settings = app.getSettings();
		if (!app.isUserAndroidIdAllowed() || settings.INSTALL_REFERRER_CHECKED.get()) {
			return;
		}
		CountDownLatch finished = new CountDownLatch(1);
		readFinished = finished;
		try {
			InstallReferrerClient client = InstallReferrerClient.newBuilder(app).build();
			client.startConnection(new InstallReferrerStateListener() {
				@Override
				public void onInstallReferrerSetupFinished(int responseCode) {
					try {
						if (responseCode == InstallReferrerResponse.OK) {
							// The setting may have been switched off while the connection was being set up
							if (app.isUserAndroidIdAllowed()) {
								String referrer = client.getInstallReferrer().getInstallReferrer();
								settings.INSTALL_REFERRER.set(InstallReferrer.filter(referrer));
								settings.INSTALL_REFERRER_CHECKED.set(true);
							}
						} else if (responseCode != InstallReferrerResponse.SERVICE_UNAVAILABLE
								&& responseCode != InstallReferrerResponse.SERVICE_DISCONNECTED) {
							// FEATURE_NOT_SUPPORTED, DEVELOPER_ERROR, PERMISSION_ERROR: the next start would fail the same way
							settings.INSTALL_REFERRER_CHECKED.set(true);
						}
						// SERVICE_UNAVAILABLE, SERVICE_DISCONNECTED: try again on the next start
					} catch (RemoteException | RuntimeException e) {
						LOG.error("Install referrer error", e);
					} finally {
						client.endConnection();
						finished.countDown();
					}
				}

				@Override
				public void onInstallReferrerServiceDisconnected() {
					finished.countDown();
				}
			});
		} catch (RuntimeException e) {
			LOG.error("Install referrer error", e);
			finished.countDown();
		}
	}

	/**
	 * Waits, at most timeoutMs, for the read started by {@link #readOnce} to finish, so that the
	 * first requests of an install can carry its campaign. Returns at once when no read is running.
	 * Not for the main thread: the read itself completes there.
	 */
	public static void awaitRead(long timeoutMs) {
		CountDownLatch finished = readFinished;
		if (finished != null) {
			try {
				finished.await(timeoutMs, TimeUnit.MILLISECONDS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
			}
		}
	}
}
