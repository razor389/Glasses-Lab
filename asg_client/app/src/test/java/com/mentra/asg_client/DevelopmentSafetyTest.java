package com.mentra.asg_client;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.robolectric.Shadows.shadowOf;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import androidx.test.core.app.ApplicationProvider;
import com.mentra.asg_client.io.bes.BesOtaManager;
import com.mentra.asg_client.io.ota.helpers.OtaHelper;
import com.mentra.asg_client.io.peripheral.events.FactoryResetEvent;
import com.mentra.asg_client.reporting.providers.SentryReportProvider;
import com.mentra.asg_client.reporting.config.SentryConfig;
import com.mentra.asg_client.service.core.handlers.subscribers.FactoryResetEventSubscriber;
import com.mentra.asg_client.service.legacy.managers.AsgClientServiceManager;
import com.mentra.asg_client.service.system.managers.MentraLiveSystemController;
import java.time.Duration;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockedStatic;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** Exercises the real maintenance entry points using an Android 11 third-party app identity. */
@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class, sdk = 30, packageName = "com.mentra.asg_client.thirdparty")
public class DevelopmentSafetyTest {
    @Test
    public void unknownAndForkIdentitiesCannotMaintainStock() {
        assertThat(DevelopmentSafety.allowsStockMaintenance((Context) null)).isFalse();
        assertThat(DevelopmentSafety.allowsStockMaintenance((String) null)).isFalse();
        assertThat(DevelopmentSafety.allowsStockMaintenance("com.mentra.asg_client.thirdparty")).isFalse();
        assertThat(DevelopmentSafety.allowsStockMaintenance("another.fork")).isFalse();
        assertThat(DevelopmentSafety.allowsStockMaintenance("com.mentra.asg_client")).isTrue();
    }

    @Test
    public void forkCannotInitializeRemoteCrashReporting() {
        try (MockedStatic<SentryConfig> configuration = mockStatic(SentryConfig.class)) {
            SentryReportProvider provider = new SentryReportProvider();
            assertThat(provider.initialize(ApplicationProvider.getApplicationContext())).isFalse();
            provider.setEnabled(true);
            assertThat(provider.isEnabled()).isFalse();
            configuration.verifyNoInteractions();
        }
    }

    @Test
    public void firmwareAndPackageOperationsSendNoSystemBroadcasts() {
        Application app = ApplicationProvider.getApplicationContext();
        MentraLiveSystemController controller = new MentraLiveSystemController(app);
        int before = shadowOf(app).getBroadcastIntents().size();
        controller.installSystemOta("/storage/emulated/0/asg/update.zip");
        controller.uninstallPackage(AsgConstants.STOCK_ASG_PACKAGE);
        controller.uninstallPackageViaAdb(AsgConstants.STOCK_ASG_PACKAGE);
        controller.injectAdbCommand("reboot");
        controller.setWifiAdb(false);
        assertThat(OtaHelper.installApk(app, "/storage/emulated/0/asg/asg_client_backup.apk")).isFalse();
        assertThat(OtaHelper.debugInstallMtkFirmware(app)).isFalse();
        assertThat(OtaHelper.debugInstallBesFirmware(app)).isFalse();
        OtaHelper.ensureRecoveryBackupIfNeeded(app);
        OtaHelper.notifyRecoveryInstallCompleted(app);
        assertThat(shadowOf(app).getBroadcastIntents()).hasSize(before);
    }

    @Test
    public void cameraCustomizationRemainsAvailable() {
        Application app = ApplicationProvider.getApplicationContext();
        MentraLiveSystemController controller = new MentraLiveSystemController(app);
        int before = shadowOf(app).getBroadcastIntents().size();
        controller.setCameraTuningConfig(true, false);
        assertThat(shadowOf(app).getBroadcastIntents()).hasSize(before + 1);
        assertThat(shadowOf(app).getBroadcastIntents().get(before).getStringExtra("cmd"))
                .isEqualTo("camconfig");
    }

    @Test
    public void besUpdateIsRejectedBeforeReadingFirmwareOrTouchingUart() {
        Application app = ApplicationProvider.getApplicationContext();
        BesOtaManager manager = new BesOtaManager(null, null, app);
        assertThat(manager.init(null)).isFalse();
        assertThat(manager.startFirmwareUpdate(null)).isFalse();
        assertThat(manager.isBesOtaInProgress()).isFalse();
    }

    @Test
    public void resetDoesNotScheduleInstallOrStopMedia() {
        Application app = ApplicationProvider.getApplicationContext();
        AsgClientServiceManager services = mock(AsgClientServiceManager.class);
        OtaHelper ota = mock(OtaHelper.class);
        new FactoryResetEventSubscriber(services, app, ota)
                .onMcuEvent(new FactoryResetEvent());
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(2));
        verifyNoInteractions(ota);
        verify(services, never()).getMediaCaptureService();
    }

    @Test
    public void recoveryManagerDoesNotLaunchOrDeployStockSidecar() {
        Application app = ApplicationProvider.getApplicationContext();
        int before = shadowOf(app).getBroadcastIntents().size();
        RecoveryWorkerManager manager = new RecoveryWorkerManager(app);
        try {
            manager.initialize();
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(20));
            assertThat(shadowOf(app).getBroadcastIntents()).hasSize(before);
            assertThat(shadowOf(app).getNextStartedActivity()).isNull();
            assertThat(shadowOf(app).getNextStartedService()).isNull();
        } finally {
            manager.cleanup();
        }
    }
}
