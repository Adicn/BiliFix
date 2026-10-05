package com.xjw.bilifix.in.feature.network;

import android.annotation.SuppressLint;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkRequest;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.SystemClock;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.reflect.Method;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/** Performs one bounded network preparation pass for each Bilibili process startup. */
@SuppressLint("MissingPermission")
public final class NetworkOptimizationHooks {
    private static final String APPLICATION_CLASS_NAME = "tv.danmaku.bili.l";
    private static final String MAIN_ACTIVITY_CLASS_NAME = "tv.danmaku.bili.MainActivityV2";
    private static final long STARTUP_NETWORK_WAIT_MS = 1500L;
    private static final long STARTUP_NETWORK_SETTLE_MS = 250L;
    private static final long STARTUP_NETWORK_BIND_HOLD_MS = 12000L;
    private static final long VISIBLE_STARTUP_MIN_INTERVAL_MS = 15000L;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean initializationComplete = new AtomicBoolean(false);
    private final AtomicBoolean applicationHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean mainActivityHookInstalled = new AtomicBoolean(false);
    private final AtomicBoolean startupCheckCompleted = new AtomicBoolean(false);
    private final AtomicBoolean visibleRecoveryScheduled = new AtomicBoolean(false);

    private volatile ConnectivityManager connectivityManager;
    private volatile long lastPreparationAt;

    public NetworkOptimizationHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        try {
            // The hook must be installed before Bilibili starts Cronet and its first requests.
            installApplicationStartupHook();
        } catch (Throwable throwable) {
            module.warn("network optimization application hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        try {
            installMainActivityStartHook();
        } catch (Throwable throwable) {
            module.warn("network optimization main activity hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        initializeStartupCheck(0);
    }

    private void initializeStartupCheck(int attempt) {
        if (initializationComplete.get()) {
            return;
        }
        Context context = HostApplication.get();
        if (context == null) {
            retryRegistration(attempt);
            return;
        }
        try {
            initializeStartupState(context);
        } catch (Throwable throwable) {
            module.warn("network optimization startup check unavailable: "
                    + throwable.getClass().getSimpleName());
            retryRegistration(attempt);
        }
    }

    private void initializeStartupState(Context context) throws Throwable {
        if (initializationComplete.get() || context == null) {
            return;
        }
        module.ensureFeatureSettings(context);
        ConnectivityManager manager = (ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (manager == null) {
            module.warn("network optimization unavailable: ConnectivityManager is null");
            return;
        }
        connectivityManager = manager;
        initializationComplete.set(true);
        Network active = manager.getActiveNetwork();
        module.info("network optimization startup check initialized"
                + " activeNetwork=" + active
                + " activeValidated=" + isValidatedInternet(getCapabilities(active)));
    }

    private void installApplicationStartupHook() throws Throwable {
        if (applicationHookInstalled.get()) {
            return;
        }
        Class<?> applicationClass = module.load(classLoader, APPLICATION_CLASS_NAME);
        Method onCreate = module.declaredMethod(applicationClass, "onCreate");
        if (!applicationHookInstalled.compareAndSet(false, true)) {
            return;
        }
        try {
            module.addHook("BiliApplication.onCreate network optimization", onCreate, chain -> {
                Object target = chain.getThisObject();
                Network boundNetwork = null;
                if (target instanceof Context) {
                    boundNetwork = prepareApplicationStartup((Context) target);
                }
                try {
                    Object result = chain.proceed();
                    releaseStartupBindingLater(boundNetwork);
                    return result;
                } catch (Throwable throwable) {
                    releaseStartupBinding(boundNetwork);
                    throw throwable;
                }
            });
            module.info("network optimization BiliApplication startup hook installed");
        } catch (Throwable throwable) {
            applicationHookInstalled.set(false);
            throw throwable;
        }
    }

    private void installMainActivityStartHook() throws Throwable {
        if (mainActivityHookInstalled.get()) {
            return;
        }
        Class<?> activityClass = module.load(classLoader, MAIN_ACTIVITY_CLASS_NAME);
        boolean installed = false;
        try {
            installMainActivityLifecycleHook(activityClass, "onStart");
            installed = true;
        } catch (Throwable throwable) {
            module.warn("network optimization onStart hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        try {
            installMainActivityLifecycleHook(activityClass, "onResume");
            installed = true;
        } catch (Throwable throwable) {
            module.warn("network optimization onResume hook unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        if (!installed) {
            throw new NoSuchMethodException("MainActivityV2 lifecycle methods unavailable");
        }
        mainActivityHookInstalled.set(true);
        module.info("network optimization MainActivityV2 lifecycle hooks installed");
    }

    private void installMainActivityLifecycleHook(Class<?> activityClass, String methodName)
            throws Throwable {
        Method lifecycleMethod = module.declaredMethod(activityClass, methodName);
        module.addHook("MainActivityV2." + methodName + " network optimization",
                lifecycleMethod, chain -> {
                    Object target = chain.getThisObject();
                    Network boundNetwork = target instanceof Context
                            ? prepareVisibleStartup((Context) target) : null;
                    try {
                        Object result = chain.proceed();
                        releaseStartupBindingLater(boundNetwork);
                        return result;
                    } catch (Throwable throwable) {
                        releaseStartupBinding(boundNetwork);
                        throw throwable;
                    }
                });
    }

    private void retryRegistration(int attempt) {
        if (attempt >= 40 || initializationComplete.get()) {
            return;
        }
        mainHandler.postDelayed(() -> initializeStartupCheck(attempt + 1), 100L);
    }

    private Network prepareApplicationStartup(Context context) {
        try {
            initializeStartupState(context);
        } catch (Throwable throwable) {
            module.warn("network optimization application state unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        if (!module.isNetworkOptimizationEnabled()) {
            module.info("network optimization startup pass skipped: feature disabled");
            return null;
        }
        if (!startupCheckCompleted.compareAndSet(false, true)) {
            return null;
        }
        if (!claimPreparationSlot()) {
            return null;
        }

        long startedAt = SystemClock.elapsedRealtime();
        boolean ready = awaitValidatedNetwork();
        long waited = SystemClock.elapsedRealtime() - startedAt;
        if (!ready) {
            module.warn("network optimization startup pass expired"
                    + " waitedMs=" + waited
                    + "; BiliApplication.onCreate continues unchanged");
            return null;
        }

        Network boundNetwork = bindProcessToActiveNetwork();
        module.info("network optimization startup pass passed"
                + " waitedMs=" + waited
                + " boundNetwork=" + boundNetwork
                + "; BiliApplication.onCreate continues");
        return boundNetwork;
    }

    /**
     * Handles a warm process whose main Activity is shown again after the Application already
     * exists. This path is deliberately fast on the UI thread and only waits asynchronously if
     * Android has not marked a usable default network yet.
     */
    private Network prepareVisibleStartup(Context context) {
        try {
            initializeStartupState(context);
        } catch (Throwable throwable) {
            module.warn("network optimization visible state unavailable: "
                    + throwable.getClass().getSimpleName());
        }
        if (!module.isNetworkOptimizationEnabled()) {
            module.info("network optimization visible pass skipped: feature disabled");
            return null;
        }
        if (!claimPreparationSlot()) {
            return null;
        }

        Network boundNetwork = bindProcessToActiveNetwork();
        module.info("network optimization visible pass"
                + " boundNetwork=" + boundNetwork
                + "; MainActivityV2.onStart continues");
        if (boundNetwork == null) {
            scheduleVisibleNetworkRecovery();
        }
        return boundNetwork;
    }

    private boolean claimPreparationSlot() {
        long now = SystemClock.elapsedRealtime();
        synchronized (this) {
            if (now - lastPreparationAt < VISIBLE_STARTUP_MIN_INTERVAL_MS) {
                return false;
            }
            lastPreparationAt = now;
            return true;
        }
    }

    private void scheduleVisibleNetworkRecovery() {
        if (!visibleRecoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        new Thread(() -> {
            try {
                if (awaitValidatedNetwork()) {
                    Network boundNetwork = bindProcessToActiveNetwork();
                    module.info("network optimization visible recovery completed"
                            + " boundNetwork=" + boundNetwork);
                    releaseStartupBindingLater(boundNetwork);
                } else {
                    module.warn("network optimization visible recovery expired");
                }
            } finally {
                visibleRecoveryScheduled.set(false);
            }
        }, "BiliFix-VisibleNetworkRecovery").start();
    }

    private NetworkCapabilities getCapabilities(Network network) {
        ConnectivityManager manager = connectivityManager;
        if (manager == null || network == null) {
            return null;
        }
        try {
            return manager.getNetworkCapabilities(network);
        } catch (Throwable throwable) {
            return null;
        }
    }

    private static boolean isValidatedInternet(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /** Waits for the current default network to be validated, then removes the callback. */
    private boolean awaitValidatedNetwork() {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) {
            return false;
        }
        long deadline = SystemClock.elapsedRealtime() + STARTUP_NETWORK_WAIT_MS;
        try {
            Network active = manager.getActiveNetwork();
            if (isValidatedInternet(getCapabilities(active))) {
                return waitForStableNetwork(manager, active, deadline);
            }
        } catch (Throwable throwable) {
            module.debug("network optimization startup network check failed: "
                    + throwable.getClass().getSimpleName());
            return false;
        }

        CountDownLatch ready = new CountDownLatch(1);
        ConnectivityManager.NetworkCallback callback = new ConnectivityManager.NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                signalIfValidated(network, ready);
            }

            @Override
            public void onCapabilitiesChanged(Network network, NetworkCapabilities capabilities) {
                if (isValidatedInternet(capabilities)) {
                    ready.countDown();
                }
            }
        };
        NetworkRequest request = new NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build();
        HandlerThread callbackThread = new HandlerThread("BiliFix-NetworkGate");
        callbackThread.start();
        try {
            manager.registerNetworkCallback(request, callback,
                    new Handler(callbackThread.getLooper()));
            while (SystemClock.elapsedRealtime() < deadline) {
                Network current = manager.getActiveNetwork();
                if (isValidatedInternet(getCapabilities(current))) {
                    ready.countDown();
                    break;
                }
                long remaining = deadline - SystemClock.elapsedRealtime();
                ready.await(Math.min(remaining, 100L), TimeUnit.MILLISECONDS);
                if (ready.getCount() == 0L) {
                    break;
                }
            }
            Network current = manager.getActiveNetwork();
            return ready.getCount() == 0L || isValidatedInternet(getCapabilities(current));
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        } catch (Throwable throwable) {
            module.debug("network optimization startup network wait failed: "
                    + throwable.getClass().getSimpleName());
            return false;
        } finally {
            try {
                manager.unregisterNetworkCallback(callback);
            } catch (Throwable ignored) {
                // The callback is intentionally short-lived and may already be removed.
            }
            callbackThread.quitSafely();
        }
    }

    private boolean waitForStableNetwork(ConnectivityManager manager, Network expected,
            long deadline) {
        long stableUntil = Math.min(deadline,
                SystemClock.elapsedRealtime() + STARTUP_NETWORK_SETTLE_MS);
        while (SystemClock.elapsedRealtime() < stableUntil) {
            Network current = manager.getActiveNetwork();
            if (current == null || !current.equals(expected)
                    || !isValidatedInternet(getCapabilities(current))) {
                return false;
            }
            SystemClock.sleep(Math.min(50L, stableUntil - SystemClock.elapsedRealtime()));
        }
        Network current = manager.getActiveNetwork();
        return current != null && current.equals(expected)
                && isValidatedInternet(getCapabilities(current));
    }

    private void signalIfValidated(Network network, CountDownLatch ready) {
        if (isValidatedInternet(getCapabilities(network))) {
            ready.countDown();
        }
    }

    /**
     * Gives Cronet a concrete, already validated route while it creates its first connections.
     * VPN networks are left untouched so the user's VPN policy remains authoritative.
     */
    private Network bindProcessToActiveNetwork() {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) {
            return null;
        }
        try {
            Network active = manager.getActiveNetwork();
            NetworkCapabilities capabilities = getCapabilities(active);
            if (!isValidatedInternet(capabilities) || active == null) {
                return null;
            }
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
                module.info("network optimization startup binding skipped: active network is VPN");
                return null;
            }
            if (manager.bindProcessToNetwork(active)) {
                return active;
            }
            module.warn("network optimization startup binding rejected by ConnectivityManager");
        } catch (Throwable throwable) {
            module.warn("network optimization startup binding failed: "
                    + throwable.getClass().getSimpleName());
        }
        return null;
    }

    private void releaseStartupBindingLater(Network boundNetwork) {
        if (boundNetwork == null) {
            return;
        }
        mainHandler.postDelayed(() -> releaseStartupBinding(boundNetwork),
                STARTUP_NETWORK_BIND_HOLD_MS);
    }

    private void releaseStartupBinding(Network boundNetwork) {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) {
            return;
        }
        try {
            Network current = manager.getBoundNetworkForProcess();
            if (boundNetwork.equals(current)) {
                manager.bindProcessToNetwork(null);
                module.info("network optimization startup binding released");
            }
        } catch (Throwable throwable) {
            module.debug("network optimization startup binding release failed: "
                    + throwable.getClass().getSimpleName());
        }
    }
}
