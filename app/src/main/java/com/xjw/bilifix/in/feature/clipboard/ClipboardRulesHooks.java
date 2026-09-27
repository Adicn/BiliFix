package com.xjw.bilifix.in.feature.clipboard;

import android.os.SystemClock;

import com.xjw.bilifix.in.core.HookApi;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicLong;

/** Avoids repeatedly retrying the optional clipboard rules request during a network outage. */
public final class ClipboardRulesHooks {
    private static final String CLIPBOARD_CHECKER_CLASS =
            "tv.danmaku.bili.ui.clipboard.ClipboardChecker";
    private static final String RULES_METHOD_NAME = "l0";
    private static final long DUPLICATE_REQUEST_COOLDOWN_MS = 60_000L;

    private final HookApi module;
    private final ClassLoader classLoader;
    private final AtomicLong lastRequestAt = new AtomicLong(0L);

    public ClipboardRulesHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        try {
            Class<?> checkerClass = module.load(classLoader, CLIPBOARD_CHECKER_CLASS);
            int installed = 0;
            for (Method method : checkerClass.getDeclaredMethods()) {
                if (!RULES_METHOD_NAME.equals(method.getName())
                        || method.getParameterTypes().length != 0) {
                    continue;
                }
                method.setAccessible(true);
                module.addHook("ClipboardChecker duplicate rules request guard", method,
                        chain -> {
                            long now = SystemClock.elapsedRealtime();
                            long previous = lastRequestAt.get();
                            if (previous > 0L
                                    && now - previous < DUPLICATE_REQUEST_COOLDOWN_MS) {
                                module.debug("clipboard rules duplicate request skipped");
                                return defaultValue(method.getReturnType());
                            }
                            lastRequestAt.set(now);
                            return chain.proceed();
                        });
                installed++;
            }
            if (installed == 0) {
                module.warn("clipboard rules request guard unavailable: method not found");
                return;
            }
            module.info("clipboard rules request guard ready: methods=" + installed
                    + " cooldownMs=" + DUPLICATE_REQUEST_COOLDOWN_MS);
        } catch (Throwable throwable) {
            module.warn("clipboard rules request guard unavailable: "
                    + throwable.getClass().getSimpleName());
        }
    }

    private static Object defaultValue(Class<?> returnType) {
        if (!returnType.isPrimitive() || returnType == void.class) {
            return null;
        }
        if (returnType == boolean.class) {
            return false;
        }
        if (returnType == char.class) {
            return '\0';
        }
        if (returnType == long.class) {
            return 0L;
        }
        if (returnType == double.class) {
            return 0D;
        }
        if (returnType == float.class) {
            return 0F;
        }
        if (returnType == byte.class) {
            return (byte) 0;
        }
        if (returnType == short.class) {
            return (short) 0;
        }
        return 0;
    }
}
