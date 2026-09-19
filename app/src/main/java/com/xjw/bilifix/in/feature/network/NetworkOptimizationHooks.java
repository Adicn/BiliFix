package com.xjw.bilifix.in.feature.network;

import android.net.Uri;

import com.xjw.bilifix.in.core.HookApi;
import com.xjw.bilifix.in.core.HostApplication;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.lang.reflect.Method;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/** Uses a short-lived public-DNS fallback for Bilibili hosts when the feature is enabled. */
public final class NetworkOptimizationHooks {
    private static final int DNS_PORT = 53;
    private static final int DNS_TIMEOUT_MS = 850;
    private static final long POSITIVE_CACHE_MS = 5 * 60 * 1000L;
    private static final long NEGATIVE_CACHE_MS = 15 * 1000L;

    // These are IPv4 literals so resolving the resolver itself cannot recurse into this hook.
    private static final byte[][] DNS_SERVERS = {
            {(byte) 223, 5, 5, 5},
            {119, 29, 29, 29},
            {1, 1, 1, 1},
            {8, 8, 8, 8},
            {9, 9, 9, 9}
    };

    private static final ConcurrentHashMap<String, CacheEntry> CACHE =
            new ConcurrentHashMap<>();

    private final HookApi module;
    private final ClassLoader classLoader;

    public NetworkOptimizationHooks(HookApi module, ClassLoader classLoader) {
        this.module = module;
        this.classLoader = classLoader;
    }

    public void install() {
        installInetAddressHooks();
        installOkHttpDohRewrite();
        installCronetDohRewrite();
    }

    private void installInetAddressHooks() {
        try {
            Class<?> inetAddressClass = module.load(classLoader, "java.net.InetAddress");
            Method getAllByName = module.declaredMethod(
                    inetAddressClass, "getAllByName", String.class);
            Method getByName = module.declaredMethod(
                    inetAddressClass, "getByName", String.class);

            module.addHook("network optimization InetAddress.getAllByName",
                    getAllByName, chain -> resolveOrProceed(chain, true));
            module.addHook("network optimization InetAddress.getByName",
                    getByName, chain -> resolveOrProceed(chain, false));
            module.info("hook group ready: network optimization DNS fallback");
        } catch (Throwable throwable) {
            module.error("hook group unavailable: network optimization DNS fallback", throwable);
        }
    }

    /**
     * Bilibili 3.20.x uses its own HTTPS DNS provider before creating an OkHttp request. The
     * provider is compatible with the JSON DoH response format, so changing only this endpoint
     * avoids the blocked Google resolver without touching normal API requests.
     */
    private void installOkHttpDohRewrite() {
        try {
            Class<?> requestBuilderClass = module.load(classLoader, "okhttp3.a0$a");
            int installed = 0;
            for (Method method : requestBuilderClass.getDeclaredMethods()) {
                if (!isSingleStringBuilderMethod(requestBuilderClass, method)) {
                    continue;
                }
                method.setAccessible(true);
                module.addHook("network optimization OkHttp DoH URL " + method.getName(),
                        method, chain -> rewriteStringArgument(chain, 0));
                installed++;
            }
            if (installed == 0) {
                throw new NoSuchMethodException("no one-string OkHttp request builder method");
            }
            module.info("hook group ready: network optimization OkHttp DoH rewrite methods="
                    + installed);
        } catch (Throwable throwable) {
            module.warn("hook group unavailable: network optimization OkHttp DoH rewrite "
                    + throwable.getClass().getSimpleName());
        }
    }

    private void installCronetDohRewrite() {
        int installed = 0;
        for (String className : new String[] {
                "org.chromium.net.CronetEngine",
                "org.chromium.net.impl.CronetUrlRequestContext"
        }) {
            try {
                Class<?> owner = module.load(classLoader, className);
                for (Method method : owner.getDeclaredMethods()) {
                    if (!"newUrlRequestBuilder".equals(method.getName())
                            || method.getParameterCount() == 0
                            || method.getParameterTypes()[0] != String.class) {
                        continue;
                    }
                    method.setAccessible(true);
                    module.addHook("network optimization Cronet DoH URL " + className,
                            method, chain -> rewriteStringArgument(chain, 0));
                    installed++;
                }
            } catch (ClassNotFoundException ignored) {
                // Cronet is optional in some processes and may not be present there.
            } catch (Throwable throwable) {
                module.warn("Cronet DoH hook unavailable: class=" + className
                        + " error=" + throwable.getClass().getSimpleName());
            }
        }
        if (installed > 0) {
            module.info("hook group ready: network optimization Cronet DoH rewrite methods="
                    + installed);
        } else {
            module.warn("hook group unavailable: network optimization Cronet DoH rewrite");
        }
    }

    private static boolean isSingleStringBuilderMethod(Class<?> builderClass, Method method) {
        return method.getParameterCount() == 1
                && method.getParameterTypes()[0] == String.class
                && builderClass.isAssignableFrom(method.getReturnType());
    }

    private Object rewriteStringArgument(
            io.github.libxposed.api.XposedInterface.Chain chain, int argumentIndex)
            throws Throwable {
        if (HostApplication.get() != null) {
            module.ensureFeatureSettings(HostApplication.get());
        }
        if (!module.isNetworkOptimizationEnabled()) {
            return chain.proceed();
        }
        Object value = chain.getArg(argumentIndex);
        if (!(value instanceof String)) {
            return chain.proceed();
        }
        String rewritten = rewriteDohUrl((String) value);
        if (rewritten == null) {
            return chain.proceed();
        }
        Object[] args = chain.getArgs().toArray();
        args[argumentIndex] = rewritten;
        module.info("network optimization DoH URL rewrite: "
                + ((String) value).replace("https://dns.google", "dns.google")
                + " -> dns.alidns.com");
        return chain.proceed(args);
    }

    private static String rewriteDohUrl(String rawUrl) {
        try {
            Uri uri = Uri.parse(rawUrl);
            String host = uri.getHost();
            if (host == null || !"dns.google".equalsIgnoreCase(host)
                    || !"/resolve".equals(uri.getPath())) {
                return null;
            }
            return uri.buildUpon().scheme("https").authority("dns.alidns.com").build()
                    .toString();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private Object resolveOrProceed(
            io.github.libxposed.api.XposedInterface.Chain chain,
            boolean allByName) throws Throwable {
        Object rawHost = chain.getArg(0);
        if (!(rawHost instanceof String)) {
            return chain.proceed();
        }
        String host = normalizeHost((String) rawHost);
        if (!isBilibiliHost(host)) {
            return chain.proceed();
        }

        // Settings can be loaded after the early boot hooks in the main process.
        if (HostApplication.get() != null) {
            module.ensureFeatureSettings(HostApplication.get());
        }
        if (!module.isNetworkOptimizationEnabled()) {
            return chain.proceed();
        }

        try {
            InetAddress[] addresses = resolve(host);
            if (addresses != null && addresses.length > 0) {
                return allByName ? addresses : addresses[0];
            }
        } catch (Throwable throwable) {
            module.debug("network optimization DNS query failed: host=" + host
                    + " error=" + throwable.getClass().getSimpleName());
        }

        // The original resolver remains the last resort, so enabling this feature cannot turn a
        // temporary UDP/53 restriction into a permanent failure.
        return chain.proceed();
    }

    private InetAddress[] resolve(String host) {
        long now = System.currentTimeMillis();
        CacheEntry cached = CACHE.get(host);
        if (cached != null && cached.expiresAt > now) {
            return cached.addresses();
        }

        List<InetAddress> addresses = queryAllServers(host, 1);
        if (addresses.isEmpty()) {
            // IPv4 is tried first because it is available on more mobile networks. IPv6 is only
            // queried when no IPv4 answer exists, keeping startup latency bounded in the common
            // case.
            addresses = queryAllServers(host, 28);
        }

        InetAddress[] result = addresses.toArray(new InetAddress[0]);
        long lifetime = result.length == 0 ? NEGATIVE_CACHE_MS : POSITIVE_CACHE_MS;
        CACHE.put(host, new CacheEntry(result, now + lifetime));
        if (CACHE.size() > 256) {
            CACHE.clear();
        }

        if (result.length > 0) {
            module.info("network optimization DNS override: host=" + host
                    + " addresses=" + result.length);
        } else {
            module.debug("network optimization DNS fallback unavailable: host=" + host);
        }
        return result;
    }

    private static List<InetAddress> queryAllServers(String host, int type) {
        List<InetAddress> result = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (byte[] server : DNS_SERVERS) {
            try {
                for (InetAddress address : query(server, host, type)) {
                    String key = address.getHostAddress();
                    if (seen.add(key)) {
                        result.add(address);
                    }
                }
            } catch (SocketTimeoutException ignored) {
                // Try the next resolver before falling back to the system resolver.
            } catch (IOException ignored) {
                // A resolver can be unavailable on a particular mobile network.
            }
        }
        return result;
    }

    private static List<InetAddress> query(byte[] server, String host, int type)
            throws IOException {
        int id = ThreadLocalRandom.current().nextInt(0x10000);
        byte[] request = buildQuery(id, host, type);
        InetAddress serverAddress = InetAddress.getByAddress(null, server);
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout(DNS_TIMEOUT_MS);
            socket.send(new DatagramPacket(request, request.length, serverAddress, DNS_PORT));
            byte[] response = new byte[2048];
            DatagramPacket packet = new DatagramPacket(response, response.length);
            socket.receive(packet);
            return parseResponse(packet.getData(), packet.getOffset(), packet.getLength(),
                    id, host, type);
        }
    }

    private static byte[] buildQuery(int id, String host, int type) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream(96);
        writeU16(output, id);
        writeU16(output, 0x0100); // recursion desired
        writeU16(output, 1); // one question
        writeU16(output, 0);
        writeU16(output, 0);
        writeU16(output, 0);
        for (String label : host.split("\\.")) {
            byte[] bytes = label.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
            if (bytes.length == 0 || bytes.length > 63) {
                throw new IOException("invalid DNS label");
            }
            output.write(bytes.length);
            output.write(bytes);
        }
        output.write(0);
        writeU16(output, type);
        writeU16(output, 1); // IN
        return output.toByteArray();
    }

    private static List<InetAddress> parseResponse(
            byte[] data, int offset, int length, int id, String host, int type) {
        List<InetAddress> result = new ArrayList<>();
        int end = offset + length;
        if (length < 12 || readU16(data, offset) != id) {
            return result;
        }
        int flags = readU16(data, offset + 2);
        if ((flags & 0x8000) == 0 || (flags & 0x000f) != 0) {
            return result;
        }
        int questionCount = readU16(data, offset + 4);
        int answerCount = readU16(data, offset + 6);
        int cursor = offset + 12;
        for (int index = 0; index < questionCount; index++) {
            cursor = skipName(data, cursor, end);
            if (cursor < 0 || cursor + 4 > end) {
                return result;
            }
            cursor += 4;
        }

        Set<String> seen = new HashSet<>();
        for (int index = 0; index < answerCount; index++) {
            cursor = skipName(data, cursor, end);
            if (cursor < 0 || cursor + 10 > end) {
                return result;
            }
            int recordType = readU16(data, cursor);
            int recordClass = readU16(data, cursor + 2);
            int recordLength = readU16(data, cursor + 8);
            cursor += 10;
            if (cursor + recordLength > end) {
                return result;
            }
            if (recordClass == 1 && recordType == type
                    && ((type == 1 && recordLength == 4)
                    || (type == 28 && recordLength == 16))) {
                byte[] address = new byte[recordLength];
                System.arraycopy(data, cursor, address, 0, recordLength);
                try {
                    InetAddress parsed = InetAddress.getByAddress(host, address);
                    if (seen.add(parsed.getHostAddress())) {
                        result.add(parsed);
                    }
                } catch (UnknownHostException ignored) {
                    // Ignore malformed address records and continue parsing the response.
                }
            }
            cursor += recordLength;
        }
        return result;
    }

    private static int skipName(byte[] data, int cursor, int end) {
        while (cursor < end) {
            int size = data[cursor] & 0xff;
            if (size == 0) {
                return cursor + 1;
            }
            if ((size & 0xc0) == 0xc0) {
                return cursor + 2 <= end ? cursor + 2 : -1;
            }
            if (size > 63 || cursor + 1 + size > end) {
                return -1;
            }
            cursor += size + 1;
        }
        return -1;
    }

    private static void writeU16(ByteArrayOutputStream output, int value) {
        output.write((value >>> 8) & 0xff);
        output.write(value & 0xff);
    }

    private static int readU16(byte[] data, int offset) {
        return ((data[offset] & 0xff) << 8) | (data[offset + 1] & 0xff);
    }

    private static String normalizeHost(String rawHost) {
        String host = rawHost.trim().toLowerCase(Locale.ROOT);
        while (host.endsWith(".")) {
            host = host.substring(0, host.length() - 1);
        }
        return host;
    }

    private static boolean isBilibiliHost(String host) {
        return isDomain(host, "bilibili.com")
                || isDomain(host, "bilibili.tv")
                || isDomain(host, "biliapi.net")
                || isDomain(host, "bilivideo.com")
                || isDomain(host, "hdslb.com");
    }

    private static boolean isDomain(String host, String root) {
        return host.equals(root) || host.endsWith('.' + root);
    }

    private static final class CacheEntry {
        private final InetAddress[] addresses;
        private final long expiresAt;

        CacheEntry(InetAddress[] addresses, long expiresAt) {
            this.addresses = addresses;
            this.expiresAt = expiresAt;
        }

        InetAddress[] addresses() {
            return addresses.clone();
        }
    }
}
