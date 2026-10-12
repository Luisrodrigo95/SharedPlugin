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

public class NetworkInterfaceOptions implements Dropdown.Options<String> {

    public static final String DEFAULT_ROUTE = "Default (System Route)";

    @Override
    public List<String> options() {
        List<String> list = new ArrayList<>();
        list.add(DEFAULT_ROUTE);

        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            if (interfaces == null) {
                return list;
            }

            while (interfaces.hasMoreElements()) {
                NetworkInterface iface = interfaces.nextElement();
                try {
                    if (!iface.isUp() || iface.isLoopback()) {
                        continue;
                    }
                } catch (SocketException e) {
                    continue;
                }

                for (InterfaceAddress ifaceAddr : iface.getInterfaceAddresses()) {
                    InetAddress addr = ifaceAddr.getAddress();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress() && !addr.isLinkLocalAddress()) {
                        list.add(iface.getName() + " - " + addr.getHostAddress());
                    }
                }
            }
        } catch (Exception e) {
            System.err.println("[InterfaceBinder] Error scanning network interfaces: " + e.getMessage());
        }

        return list;
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
