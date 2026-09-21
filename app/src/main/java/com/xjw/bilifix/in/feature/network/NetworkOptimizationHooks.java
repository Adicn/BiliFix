package com.xjw.bilifix.in.feature.network;

import android.annotation.SuppressLint;
import android.app.Activity;
import android.app.Application;
import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.lang.ref.WeakReference;
import java.util.concurrent.atomic.AtomicBoolean;

/** Recovers the homepage after a validated network handover or startup transition. */
@SuppressLint("MissingPermission")
public final class NetworkOptimizationHooks {
    private static final String MAIN_ACTIVITY_NAME = "tv.danmaku.bili.MainActivityV2";
    private static final String NETWORK_STATE_PREFERENCES = "bilifix_in_network_state";
    private static final String KEY_LAST_TRANSPORT = "last_transport";
    private static final String KEY_LAST_TRANSPORT_AT = "last_transport_at";
    private static final long NETWORK_RECOVERY_DELAY_MS = 750L;
    private static final long STARTUP_RECOVERY_DELAY_MS = 5000L;
    private static final long STARTUP_TRANSITION_WINDOW_MS = 15 * 60 * 1000L;
    private static final long RECOVERY_COOLDOWN_MS = 4000L;
    private static final long NETWORK_POLL_INTERVAL_MS = 500L;
    private static final long NETWORK_POLL_WINDOW_MS = 30000L;

    private final HookApi module;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final AtomicBoolean callbackRegistered = new AtomicBoolean(false);
    private final AtomicBoolean activityCallbacksRegistered = new AtomicBoolean(false);
    private final AtomicBoolean recoveryRefreshScheduled = new AtomicBoolean(false);
    private final AtomicBoolean refreshPending = new AtomicBoolean(false);
    private final AtomicBoolean networkPolling = new AtomicBoolean(false);
    private final AtomicBoolean startupRecoveryScheduled = new AtomicBoolean(false);

    private volatile WeakReference<Activity> mainActivity = new WeakReference<>(null);
    private volatile ConnectivityManager connectivityManager;
    private volatile SharedPreferences networkStatePreferences;
    private volatile Network lastValidatedNetwork;
    private volatile Network currentNetwork;
    private volatile String currentTransport = "unknown";
    private volatile String previousPersistedTransport = "unknown";
    private volatile long previousPersistedTransportAt;
    private volatile boolean waitingForValidatedNetwork;
    private volatile boolean networkWasLost;
    private volatile long lastRecoveryAt;
    private volatile long networkPollDeadline;

    public NetworkOptimizationHooks(HookApi module) {
        this.module = module;
    }

    public void install() {
        registerNetworkCallback(0);
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
            ConnectivityManager manager = (ConnectivityManager)
                    context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) {
                module.warn("network optimization unavailable: ConnectivityManager is null");
                return;
            }

            connectivityManager = manager;
            networkStatePreferences = context.getSharedPreferences(
                    NETWORK_STATE_PREFERENCES, Context.MODE_PRIVATE);
            registerActivityCallbacks(context);
            Network initial = manager.getActiveNetwork();
            NetworkCapabilities initialCapabilities = initial == null
                    ? null : manager.getNetworkCapabilities(initial);
            initializeTransportState(initialCapabilities);
            currentNetwork = initial;
            waitingForValidatedNetwork = initial == null;
            lastValidatedNetwork = waitingForValidatedNetwork ? null : initial;
            networkWasLost = initial == null;

            manager.registerDefaultNetworkCallback(networkCallback, mainHandler);
            callbackRegistered.set(true);
            module.info("network optimization network recovery callback registered"
                    + " initialNetwork=" + initial
                    + " initialValidated=" + !waitingForValidatedNetwork);
            startNetworkPolling();
        } catch (Throwable throwable) {
            module.warn("network optimization network callback unavailable: "
                    + throwable.getClass().getSimpleName());
            retryRegistration(attempt);
        }
    }

    private void registerActivityCallbacks(Context context) {
        if (activityCallbacksRegistered.get()) {
            return;
        }
        Context applicationContext = context.getApplicationContext();
        if (!(applicationContext instanceof Application)) {
            module.warn("network optimization activity callbacks unavailable: application=null");
            return;
        }
        Application application = (Application) applicationContext;
        if (!activityCallbacksRegistered.compareAndSet(false, true)) {
            return;
        }
        application.registerActivityLifecycleCallbacks(activityCallbacks);
        module.info("network optimization activity lifecycle callbacks registered");
    }

    private void retryRegistration(int attempt) {
        if (attempt >= 40 || callbackRegistered.get()) {
            return;
        }
        mainHandler.postDelayed(() -> registerNetworkCallback(attempt + 1), 100L);
    }

    private final Application.ActivityLifecycleCallbacks activityCallbacks =
            new Application.ActivityLifecycleCallbacks() {
                @Override
                public void onActivityCreated(Activity activity,
                        android.os.Bundle savedInstanceState) {
                }

                @Override
                public void onActivityStarted(Activity activity) {
                    if (isMainActivity(activity)) {
                        mainActivity = new WeakReference<>(activity);
                        startNetworkPolling();
                    }
                }

                @Override
                public void onActivityResumed(Activity activity) {
                    if (isMainActivity(activity)) {
                        mainActivity = new WeakReference<>(activity);
                        scheduleStartupRecoveryIfNeeded();
                        refreshPendingActivity(activity);
                        startNetworkPolling();
                    }
                }

                @Override
                public void onActivityPaused(Activity activity) {
                }

                @Override
                public void onActivityStopped(Activity activity) {
                    if (mainActivity.get() == activity) {
                        mainActivity = new WeakReference<>(null);
                    }
                }

                @Override
                public void onActivitySaveInstanceState(Activity activity,
                        android.os.Bundle outState) {
                }

                @Override
                public void onActivityDestroyed(Activity activity) {
                    if (mainActivity.get() == activity) {
                        mainActivity = new WeakReference<>(null);
                    }
                }
            };

    private static boolean isMainActivity(Activity activity) {
        return activity != null && MAIN_ACTIVITY_NAME.equals(activity.getClass().getName());
    }

    private final ConnectivityManager.NetworkCallback networkCallback =
            new ConnectivityManager.NetworkCallback() {
                @Override
                public void onAvailable(Network network) {
                    module.info("network optimization network available: " + network);
                    observeNetwork(network, getCapabilities(network), "callback-available");
                    startNetworkPolling();
                }

                @Override
                public void onLost(Network network) {
                    if (currentNetwork == null || currentNetwork.equals(network)) {
                        currentNetwork = null;
                        waitingForValidatedNetwork = true;
                        networkWasLost = true;
                        module.info("network optimization network lost; waiting for recovery");
                    }
                    startNetworkPolling();
                }

                @Override
                public void onCapabilitiesChanged(
                        Network network, NetworkCapabilities capabilities) {
                    observeNetwork(network, capabilities, "callback-capabilities");
                }
            };

    private void observeNetwork(
            Network network, NetworkCapabilities capabilities, String source) {
        boolean validated = isValidatedInternet(capabilities);
        Network previousNetwork = currentNetwork;
        String previousTransport = currentTransport;
        String transport = transportName(capabilities);
        boolean switched = previousNetwork != null
                && network != null
                && !previousNetwork.equals(network);
        boolean transportSwitched = validated
                && isTrackedTransport(previousTransport)
                && isTrackedTransport(transport)
                && !previousTransport.equals(transport);
        boolean recovered = validated
                && "wifi".equals(transport)
                && (networkWasLost
                || transportSwitched
                || (lastValidatedNetwork != null && !lastValidatedNetwork.equals(network)));

        currentNetwork = network;
        if (!validated) {
            waitingForValidatedNetwork = true;
            if (switched) {
                networkWasLost = true;
            }
            return;
        }

        lastValidatedNetwork = network;
        currentTransport = transport;
        rememberTransport(transport);
        waitingForValidatedNetwork = false;
        networkWasLost = false;
        if (recovered) {
            module.info("network optimization validated network observed: source=" + source
                    + " network=" + network
                    + " transport=" + transportName(capabilities));
            scheduleHomepageRecovery(network, capabilities);
        }
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

    private void startNetworkPolling() {
        if (!callbackRegistered.get() || !networkPolling.compareAndSet(false, true)) {
            return;
        }
        networkPollDeadline = SystemClock.uptimeMillis() + NETWORK_POLL_WINDOW_MS;
        mainHandler.post(networkPollRunnable);
    }

    private final Runnable networkPollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!callbackRegistered.get()) {
                networkPolling.set(false);
                return;
            }
            if (SystemClock.uptimeMillis() >= networkPollDeadline) {
                networkPolling.set(false);
                return;
            }
            pollNetworkState();
            mainHandler.postDelayed(this, NETWORK_POLL_INTERVAL_MS);
        }
    };

    private void pollNetworkState() {
        ConnectivityManager manager = connectivityManager;
        if (manager == null) {
            return;
        }
        try {
            Network active = manager.getActiveNetwork();
            observeNetwork(active, getCapabilities(active), "poll");
        } catch (Throwable throwable) {
            module.debug("network optimization network poll failed: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private static boolean isValidatedInternet(NetworkCapabilities capabilities) {
        return capabilities != null
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                && capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }

    /**
     * Bilibili can start after Android has already selected Wi-Fi. Only treat that as a
     * recovery when the module recently observed cellular data in an earlier process.
     */
    private void scheduleStartupRecoveryIfNeeded() {
        if (!module.isNetworkOptimizationEnabled()
                || !startupRecoveryScheduled.compareAndSet(false, true)) {
            return;
        }
        long now = System.currentTimeMillis();
        if (!"cellular".equals(previousPersistedTransport)
                || previousPersistedTransportAt <= 0L
                || now - previousPersistedTransportAt > STARTUP_TRANSITION_WINDOW_MS) {
            return;
        }
        ConnectivityManager manager = connectivityManager;
        if (manager == null) {
            return;
        }
        Network active;
        NetworkCapabilities capabilities;
        try {
            active = manager.getActiveNetwork();
            capabilities = getCapabilities(active);
        } catch (Throwable throwable) {
            module.debug("network optimization startup network check failed: "
                    + throwable.getClass().getSimpleName());
            return;
        }
        if (!isValidatedInternet(capabilities)
                || capabilities == null
                || !capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return;
        }
        module.info("network optimization startup Wi-Fi fallback scheduled: network="
                + active + " previousTransport=" + previousPersistedTransport
                + "; homepage refresh scheduled in "
                + STARTUP_RECOVERY_DELAY_MS + "ms");
        mainHandler.postDelayed(() -> {
            try {
                if (!module.isNetworkOptimizationEnabled()) {
                    return;
                }
                Network current = manager.getActiveNetwork();
                NetworkCapabilities currentCapabilities = getCapabilities(current);
                if (!isValidatedInternet(currentCapabilities)
                        || currentCapabilities == null
                        || !currentCapabilities.hasTransport(
                        NetworkCapabilities.TRANSPORT_WIFI)) {
                    module.info("network optimization startup Wi-Fi fallback cancelled: "
                            + "validated Wi-Fi is no longer active");
                    return;
                }
                refreshPending.set(true);
                Activity activity = mainActivity.get();
                if (activity != null) {
                    refreshPendingActivity(activity);
                }
            } catch (Throwable throwable) {
                module.warn("network optimization startup Wi-Fi fallback failed: "
                        + throwable.getClass().getSimpleName());
            }
        }, STARTUP_RECOVERY_DELAY_MS);
    }

    private void initializeTransportState(NetworkCapabilities capabilities) {
        if (capabilities == null) {
            return;
        }
        String current = transportName(capabilities);
        currentTransport = current;
        SharedPreferences preferences = networkStatePreferences;
        if (preferences == null) {
            return;
        }
        try {
            previousPersistedTransport = preferences.getString(KEY_LAST_TRANSPORT, "unknown");
            previousPersistedTransportAt = preferences.getLong(KEY_LAST_TRANSPORT_AT, 0L);
            rememberTransport(current);
            module.info("network optimization transport state: previous="
                    + previousPersistedTransport + " current=" + current);
        } catch (Throwable throwable) {
            module.debug("network optimization transport state unavailable: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private void rememberTransport(String transport) {
        if (!isTrackedTransport(transport)) {
            return;
        }
        SharedPreferences preferences = networkStatePreferences;
        if (preferences != null) {
            preferences.edit()
                    .putString(KEY_LAST_TRANSPORT, transport)
                    .putLong(KEY_LAST_TRANSPORT_AT, System.currentTimeMillis())
                    .apply();
        }
    }

    private static boolean isTrackedTransport(String transport) {
        return "wifi".equals(transport)
                || "cellular".equals(transport)
                || "vpn".equals(transport);
    }

    private void scheduleHomepageRecovery(
            Network network, NetworkCapabilities capabilities) {
        if (!module.isNetworkOptimizationEnabled()) {
            return;
        }
        long now = SystemClock.elapsedRealtime();
        if (now - lastRecoveryAt < RECOVERY_COOLDOWN_MS) {
            return;
        }
        lastRecoveryAt = now;
        module.info("network optimization network recovered: network=" + network
                + " transport=" + transportName(capabilities) + "; homepage refresh scheduled");
        if (!recoveryRefreshScheduled.compareAndSet(false, true)) {
            return;
        }
        mainHandler.postDelayed(() -> {
            recoveryRefreshScheduled.set(false);
            if (!module.isNetworkOptimizationEnabled()) {
                return;
            }
            refreshPending.set(true);
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
        if (capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
            return "wifi";
        }
        if (capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) {
            return "cellular";
        }
        if (capabilities != null
                && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) {
            return "vpn";
        }
        return "other";
    }
}
