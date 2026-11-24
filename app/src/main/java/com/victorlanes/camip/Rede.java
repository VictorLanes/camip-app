package com.victorlanes.camip;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Collections;

/** IP do celular na rede local (Wi-Fi, roteador do próprio celular ou cabo). */
public final class Rede {
    private Rede() {
    }

    public static String ip() {
        String melhor = null;
        try {
            for (NetworkInterface ni : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                if (!ni.isUp() || ni.isLoopback()) continue;
                String n = ni.getName();
                for (InetAddress a : Collections.list(ni.getInetAddresses())) {
                    if (!(a instanceof Inet4Address) || a.isLoopbackAddress()) continue;
                    String ip = a.getHostAddress();
                    if (n.startsWith("wlan") || n.startsWith("eth") || n.startsWith("ap") || n.startsWith("swlan")) return ip;
                    if (melhor == null && a.isSiteLocalAddress() && !n.startsWith("rmnet") && !n.startsWith("tun")) melhor = ip;
                }
            }
        } catch (Exception ignored) {
        }
        return melhor != null ? melhor : "0.0.0.0";
    }
}
