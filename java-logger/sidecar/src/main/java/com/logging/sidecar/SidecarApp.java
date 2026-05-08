package com.logging.sidecar;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CountDownLatch;

/**
 * Standalone entry point. Configuration is read from environment variables
 * (see {@link SidecarConfig}); installs a JVM shutdown hook so SIGTERM from
 * kubelet flushes any pending records and persists the checkpoint cleanly.
 */
public final class SidecarApp {

    private static final Logger LOG = LoggerFactory.getLogger(SidecarApp.class);

    public static void main(String[] args) throws InterruptedException {
        SidecarConfig config = SidecarConfig.fromEnvironment();
        Sidecar sidecar = new Sidecar(config);
        CountDownLatch latch = new CountDownLatch(1);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            LOG.info("Shutdown hook fired");
            sidecar.close();
            latch.countDown();
        }, "sidecar-shutdown"));

        sidecar.start();
        latch.await();
    }
}
