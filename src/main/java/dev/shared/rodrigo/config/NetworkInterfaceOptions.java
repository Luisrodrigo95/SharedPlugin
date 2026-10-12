package dev.shared.rodrigo.config;

import eu.darkbot.api.config.annotations.Dropdown;

import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.InterfaceAddress;
import java.net.NetworkInterface;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

public class NetworkInterfaceOptions implements Dropdown.Options<String> {

    private static final Logger LOGGER = Logger.getLogger(NetworkInterfaceOptions.class.getName());
    public static final String DEFAULT_ROUTE = "Default (System Route)";

    @Override
    public List<String> options() {
        List<String> list = new ArrayList<>();
        list.add(DEFAULT_ROUTE);

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces != null) {
                while (interfaces.hasMoreElements()) {
                    NetworkInterface iface = interfaces.nextElement();
                    if (isInterfaceUsable(iface)) {
                        collectInterfaceAddresses(iface, list);
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.log(Level.WARNING, () -> "[InterfaceBinder] Error scanning network interfaces: " + e.getMessage());
        }

        return list;
    }

    private static boolean isInterfaceUsable(NetworkInterface iface) {
        try {
            return iface.isUp() && !iface.isLoopback();
        } catch (SocketException e) {
            // Cannot query interface state, ignore it
            return false;
        }
    }

    private static void collectInterfaceAddresses(NetworkInterface iface, List<String> list) {
        for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
            InetAddress addr = ifaceAddr.getAddress();
            if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                list.add(iface.getName() + " - " + addr.getHostAddress());
            }
        }
    }

    @Override
    public String getText(String option) {
        return option;
    }

    @Override
    public String getTooltip(String option) {
        if (DEFAULT_ROUTE.equals(option)) {
            return "Uses standard operating system routing via the default gateway.";
        }
        return "Binds outgoing bot traffic to: " + option;
    }
}
