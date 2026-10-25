package com.carmelita.debloattv;

import com.carmelita.tvtools.AdbConnection;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.NetworkInterface;
import java.net.Socket;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/** Busca en la red WiFi del teléfono equipos con el puerto ADB (5555) abierto. */
final class TvScanner {
    private static final int TIMEOUT_MS = 400;

    private TvScanner() {
    }

    static List<String> scan() throws InterruptedException {
        final List<String> found = Collections.synchronizedList(new ArrayList<String>());
        ExecutorService pool = Executors.newFixedThreadPool(48);
        for (String prefix : localPrefixes()) {
            for (int i = 1; i < 255; i++) {
                final String host = prefix + i;
                pool.execute(() -> {
                    try (Socket s = new Socket()) {
                        s.connect(new InetSocketAddress(host, AdbConnection.DEFAULT_PORT), TIMEOUT_MS);
                        found.add(host);
                    } catch (Exception ignored) {
                    }
                });
            }
        }
        pool.shutdown();
        pool.awaitTermination(30, TimeUnit.SECONDS);
        List<String> sorted = new ArrayList<>(found);
        Collections.sort(sorted);
        return sorted;
    }

    /** "192.168.1." para cada red local (/24) a la que está conectado el teléfono. */
    static List<String> localPrefixes() {
        List<String> out = new ArrayList<>();
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                for (InetAddress addr : Collections.list(ni.getInetAddresses())) {
                    if (!(addr instanceof Inet4Address) || !addr.isSiteLocalAddress()) continue;
                    String ip = addr.getHostAddress();
                    String prefix = ip.substring(0, ip.lastIndexOf('.') + 1);
                    if (!out.contains(prefix)) out.add(prefix);
                }
            }
        } catch (Exception ignored) {
        }
        return out;
    }
}
