package com.xjw.bilifix.in.feature.network;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.ref.WeakReference;
import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recovers the homepage after a validated Android network handover. */
@SuppressLint("MissingPermission")
public final class NetworkOptimizationHooks {
    private static final long NETWORK_RECOVERY_DELAY_MS = 750L;
    private static final long RECOVERY_COOLDOWN_MS = 4000L;
    private static final int MAX_REGISTRATION_ATTEMPTS = 40;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean callbackRegistered = new AtomicBoolean(false);
    private final AtomicBoolean recoveryRefreshScheduled = new AtomicBoolean(false);
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);

    private volatile WeakReference<Activity> mainActivity = new WeakReference<>(null);
    private volatile Network lastValidatedNetwork;
    private volatile Network currentNetwork;
    private volatile boolean networkWasLost;
    private volatile long lastRecoveryAt;

    public NetworkOptimizationHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installMainActivityHooks();
        registerNetworkCallback(0);
    }

    private void installMainActivityHooks() {
        try {
            Class<?> activityClass = module.load(
                    classLoader, "tv.danmaku.bili.MainActivityV2");
            Method onResume = activityClass.getDeclaredMethod("onResume");
            onResume.setAccessible(true);
            Method onPause = activityClass.getDeclaredMethod("onPause");
            onPause.setAccessible(true);

            module.addHook("network optimization main activity resume", onResume, chain -> {
                Object result = chain.proceed();
                Object activityObject = chain.getThisObject();
                if (activityObject instanceof Activity) {
                    Activity activity = (Activity) activityObject;
                    mainActivity = new WeakReference<>(activity);
                    refreshPendingActivity(activity);
                }
                return result;
            });
            module.addHook("network optimization main activity pause", onPause, chain -> {
                Object activityObject = chain.getThisObject();
                Object result = chain.proceed();
                Activity activity = activityObject instanceof Activity
                        ? (Activity) activityObject : null;
                if (activity != null && mainActivity.get() == activity) {
                    mainActivity = new WeakReference<>(null);
                }
                return result;
            });
            module.info("hook group ready: network optimization activity recovery");
        } catch (Throwable throwable) {
            module.warn("hook group unavailable: network optimization activity recovery "
                    + throwable.getClass().getSimpleName());
        }
    }

    private void registerNetworkCallback(int attempt) {
        if (callbackRegistered.get()) {
            return;
        }
        Context context = HostApplication.get();
        if (context == null) {
            retryRegistration(attempt);
            return;
        }
        try {
            module.ensureFeatureSettings(context);
            ConnectivityManager connectivityManager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (connectivityManager == null) {
                module.warn("network optimization unavailable: ConnectivityManager is null");
                return;
            }

            Network initial = connectivityManager.getActiveNetwork();
            currentNetwork = initial;
            lastValidatedNetwork = initial;
            connectivityManager.registerDefaultNetworkCallback(networkCallback, mainHandler);
            callbackRegistered.set(true);
            module.info("network optimization network recovery callback registered");
        } catch (Throwable throwable) {
            module.warn("network optimization network callback unavailable: "
                    + throwable.getClass().getSimpleName());
            retryRegistration(attempt);
        }
    }

    private void retryRegistration(int attempt) {
        if (attempt >= MAX_REGISTRATION_ATTEMPTS || callbackRegistered.get()) {
            return;
        }
        mainHandler.postDelayed(() -> registerNetworkCallback(attempt + 1), 100L);
    }

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    currentNetwork = network;
                    if (lastValidatedNetwork != null
                            && !lastValidatedNetwork.equals(network)) {
                        networkWasLost = true;
                    }
                }

                @Override
                public void onLost(Network network) {
                    if (currentNetwork == null || currentNetwork.equals(network)) {
                        currentNetwork = null;
                        networkWasLost = true;
                        module.info("network optimization network lost; waiting for recovery");
                    }
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    if (!isValidatedInternet(capabilities)) {
                        return;
                    }
                    boolean switched = lastValidatedNetwork != null
                            && !lastValidatedNetwork.equals(network);
                    boolean recovered = networkWasLost || switched;
                    currentNetwork = network;
                    lastValidatedNetwork = network;
                    networkWasLost = false;
                    if (recovered) {
                        scheduleHomepageRecovery(network, capabilities);
                    }
                }
            };

    private static boolean isValidatedInternet(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    private void scheduleHomepageRecovery(
            Network network, NetworkCapabilities capabilities) {
        if (!module.isNetworkOptimizationEnabled()) {
            return;
        }
        long now = System.currentTimeMillis();
        if (now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) {
            return;
        }
        lastRecoveryAt = now;
        refreshPending.set(true);
        String transport = transportName(capabilities);
        module.info("network optimization network recovered: network=" + network
                + " transport=" + transport + "; homepage refresh scheduled");
        if (!recoveryRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        mainHandler.postDelayed(() -> {
            recoveryRefreshScheduled.set(false);
            Activity activity = mainActivity.get();
            if (activity != null) {
                refreshPendingActivity(activity);
            }
        }, NETWORK_RECOVERY_DELAY_MS);
    }

    private void refreshPendingActivity(Activity activity) {
        if (!refreshPending.get()
                || activity.isFinishing()
                || activity.isDestroyed()
                || !module.isNetworkOptimizationEnabled()) {
            return;
        }
        if (!refreshPending.compareAndSet(true, false)) {
            return;
        }
        module.info("network optimization refreshing homepage after network recovery");
        mainHandler.post(() -> {
            if (!activity.isFinishing() && !activity.isDestroyed()) {
                activity.recreate();
            }
        });
    }

    private static String transportName(NetworkCapabilities capabilities) {
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "wifi";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "cellular";
        }
        if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "vpn";
        }
        return "other";
    }
}
