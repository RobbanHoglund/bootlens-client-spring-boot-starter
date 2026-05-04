package com.bootlens.client.registration;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationListener;

class BootLensRegistrationLifecycle implements ApplicationListener<ApplicationReadyEvent>, DisposableBean {

    private static final Logger log = LoggerFactory.getLogger(BootLensRegistrationLifecycle.class);

    private final BootLensRegistrationProperties properties;
    private final BootLensRegistrationClient registrationClient;
    private final ScheduledExecutorService executorService;
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean destroyed = new AtomicBoolean(false);

    private volatile boolean registered;
    private volatile boolean warningLogged;

    BootLensRegistrationLifecycle(
        BootLensRegistrationProperties properties,
        BootLensRegistrationClient registrationClient
    ) {
        this(properties, registrationClient, newSingleThreadExecutor());
    }

    BootLensRegistrationLifecycle(
        BootLensRegistrationProperties properties,
        BootLensRegistrationClient registrationClient,
        ScheduledExecutorService executorService
    ) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.registrationClient = Objects.requireNonNull(registrationClient, "registrationClient");
        this.executorService = Objects.requireNonNull(executorService, "executorService");
    }

    @Override
    public void onApplicationEvent(ApplicationReadyEvent event) {
        if (!started.compareAndSet(false, true)) {
            return;
        }

        if (properties.isRegisterOnStartup()) {
            attemptRegistration("startup");
        }

        long heartbeatMillis = Math.max(1_000L, properties.getHeartbeatInterval().toMillis());
        executorService.scheduleAtFixedRate(this::runHeartbeatCycle, heartbeatMillis, heartbeatMillis, TimeUnit.MILLISECONDS);
    }

    @Override
    public void destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return;
        }

        executorService.shutdownNow();

        if (properties.isDeregisterOnShutdown()) {
            RegistrationCallResult result = registrationClient.deregister();
            if (!result.success()) {
                log.debug(
                    "BootLens deregistration did not complete for {} during shutdown: {}",
                    registrationClient.resolvedInstanceId(),
                    result.message()
                );
            }
        }
    }

    void runHeartbeatCycle() {
        if (destroyed.get()) {
            return;
        }

        try {
            if (!registered) {
                attemptRegistration("heartbeat");
                return;
            }

            RegistrationCallResult result = registrationClient.heartbeat();
            if (result.success()) {
                clearFailureState();
                return;
            }
            if (result.notFound()) {
                registered = false;
                clearFailureState();
                log.debug(
                    "BootLens heartbeat reported {} is no longer registered on the server; a new registration will be attempted.",
                    registrationClient.resolvedInstanceId()
                );
                return;
            }
            logFailure("heartbeat", result.message());
        }
        catch (RuntimeException exception) {
            logFailure("heartbeat", exception.getMessage());
        }
    }

    private void attemptRegistration(String source) {
        RegistrationCallResult result = registrationClient.register();
        if (result.success()) {
            registered = true;
            clearFailureState();
            log.info(
                "BootLens {} registration is active for {} via {}.",
                source,
                registrationClient.resolvedInstanceId(),
                registrationClient.heartbeatPath()
            );
            return;
        }

        registered = false;
        logFailure("registration", result.message());
    }

    private void logFailure(String action, String message) {
        String detail = message == null || message.isBlank() ? "BootLens Server is currently unavailable." : message;
        if (!warningLogged) {
            warningLogged = true;
            log.warn("BootLens {} will retry for {}: {}", action, registrationClient.resolvedInstanceId(), detail);
            return;
        }
        log.debug("BootLens {} retry still failing for {}: {}", action, registrationClient.resolvedInstanceId(), detail);
    }

    private void clearFailureState() {
        warningLogged = false;
    }

    private static ScheduledExecutorService newSingleThreadExecutor() {
        ThreadFactory threadFactory = runnable -> {
            Thread thread = new Thread(runnable, "bootlens-registration-heartbeat");
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newSingleThreadScheduledExecutor(threadFactory);
    }
}
