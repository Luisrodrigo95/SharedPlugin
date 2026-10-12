package dev.shared.rodrigo.behaviours;

import dev.shared.rodrigo.config.InterfaceBinderConfig;
import dev.shared.rodrigo.config.NetworkInterfaceOptions;
import dev.shared.rodrigo.proxy.EmbeddedBinderProxy;
import eu.darkbot.api.PluginAPI;
import eu.darkbot.api.config.ConfigSetting;
import eu.darkbot.api.extensions.Behavior;
import eu.darkbot.api.extensions.Configurable;
import eu.darkbot.api.extensions.Feature;
import eu.darkbot.api.extensions.Installable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.SocketAddress;
import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.logging.Level;
import java.util.logging.Logger;

@Feature(name = "Network Interface / IP Binder", description = "Binds outgoing bot traffic to a selected secondary IP / local network interface")
public class InterfaceBinder implements Behavior, Configurable<InterfaceBinderConfig>, Installable {

    private static final Logger LOGGER = Logger.getLogger(InterfaceBinder.class.getName());

    private InterfaceBinderConfig config;
    private ConfigSetting<InterfaceBinderConfig> configSetting;
    private ConfigSetting<String> statusSetting;
    private EmbeddedBinderProxy activeProxy;
    private InetAddress currentBoundIp;
    private ProxySelector defaultProxySelector;

    public InterfaceBinder(PluginAPI api) {
        // Dependencies injected via DarkBotAPI if needed
    }

    @Override
    @SuppressWarnings("unchecked")
    public void setConfig(ConfigSetting<InterfaceBinderConfig> configSetting) {
        this.configSetting = configSetting;
        this.config = configSetting != null ? configSetting.getValue() : null;

        if (configSetting instanceof ConfigSetting.Parent) {
            ConfigSetting.Parent<?> parent = (ConfigSetting.Parent<?>) configSetting;
            ConfigSetting<?> s = parent.getChildren().get("status");
            if (s != null) {
                this.statusSetting = (ConfigSetting<String>) s;
            }

            // React immediately to any user change in the settings UI
            for (ConfigSetting<?> child : parent.getChildren().values()) {
                if (child != s) {
                    child.addListener(v -> updateBinding());
                }
            }
        }

        if (configSetting != null) {
            configSetting.addListener(c -> updateBinding());
        }

        updateBinding();
    }

    private void setStatus(String status) {
        if (config != null) {
            config.status = status;
        }
        if (statusSetting != null && !Objects.equals(statusSetting.getValue(), status)) {
            try {
                statusSetting.setValue(status);
            } catch (Exception ignored) {
                // Ignore if UI setting component is not yet fully initialized
            }
        }
    }

    @Override
    public void install(PluginAPI api) {
        this.defaultProxySelector = ProxySelector.getDefault();
        updateBinding();
    }

    @Override
    public void uninstall() {
        stopProxyOnly();
        setStatus("Disabled");
        if (defaultProxySelector != null) {
            ProxySelector.setDefault(defaultProxySelector);
        }
    }

    @Override
    public void onTickBehavior() {
        updateBinding();
    }

    @Override
    public void onStoppedBehavior() {
        updateBinding();
    }

    public synchronized void updateBinding() {
        if (config == null) {
            return;
        }

        if (!config.enabled) {
            stopProxyOnly();
            setStatus("Disabled");
            return;
        }

        InetAddress desiredIp = resolveTargetIp();
        if (desiredIp == null) {
            // "Default (System Route)" selected
            stopProxyOnly();
            setStatus("Active - Default System Route (No Proxy)");
            return;
        }

        // Specific IP is selected (e.g. eth0 - 5.175.184.107 or manual override)
        if (activeProxy == null || !Objects.equals(currentBoundIp, desiredIp) || !activeProxy.isRunning()) {
            enableBinding(desiredIp);
        }
    }

    private InetAddress resolveTargetIp() {
        // 1. Check manual override first
        if (config.manualIpOverride != null && !config.manualIpOverride.trim().isEmpty()) {
            try {
                return InetAddress.getByName(config.manualIpOverride.trim());
            } catch (Exception e) {
                setStatus("Error: Invalid Manual IP (" + config.manualIpOverride + ")");
                return null;
            }
        }

        // 2. Check dropdown selection
        String selected = config.targetInterface;
        if (selected == null || selected.equals(NetworkInterfaceOptions.DEFAULT_ROUTE)) {
            return null;
        }

        try {
            // Expected format: "eth0 - 5.175.184.108"
            int dashIndex = selected.lastIndexOf(" - ");
            String ipStr = dashIndex != -1 ? selected.substring(dashIndex + 3).trim() : selected.trim();
            return InetAddress.getByName(ipStr);
        } catch (Exception e) {
            setStatus("Error resolving selected interface IP");
            return null;
        }
    }

    private synchronized void enableBinding(InetAddress targetIp) {
        stopProxyOnly();

        try {
            EmbeddedBinderProxy proxy = new EmbeddedBinderProxy(targetIp);
            proxy.start();

            this.activeProxy = proxy;
            this.currentBoundIp = targetIp;

            int port = proxy.getPort();
            String host = "127.0.0.1";

            // Apply JVM Proxy System Properties
            System.setProperty("http.proxyHost", host);
            System.setProperty("http.proxyPort", String.valueOf(port));
            System.setProperty("https.proxyHost", host);
            System.setProperty("https.proxyPort", String.valueOf(port));
            System.setProperty("socksProxyHost", host);
            System.setProperty("socksProxyPort", String.valueOf(port));
            System.setProperty("http.nonProxyHosts", "localhost|127.*|[::1]");

            // Install active ProxySelector fallback
            ProxySelector.setDefault(new ProxySelector() {
                private final Proxy socksProxy = new Proxy(Proxy.Type.SOCKS, new InetSocketAddress(host, port));
                private final Proxy httpProxy = new Proxy(Proxy.Type.HTTP, new InetSocketAddress(host, port));

                @Override
                public List<Proxy> select(URI uri) {
                    if (uri == null || uri.getHost() == null) {
                        return Collections.singletonList(Proxy.NO_PROXY);
                    }
                    String h = uri.getHost().toLowerCase();
                    if (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("::1")) {
                        return Collections.singletonList(Proxy.NO_PROXY);
                    }
                    String scheme = uri.getScheme();
                    if ("socket".equalsIgnoreCase(scheme)) {
                        return Collections.singletonList(socksProxy);
                    }
                    return Collections.singletonList(httpProxy);
                }

                @Override
                public void connectFailed(URI uri, SocketAddress sa, IOException ioe) {
                    LOGGER.log(Level.WARNING, () -> "[InterfaceBinder] Proxy connection failed for " + uri + ": " + ioe.getMessage());
                }
            });

            setStatus("Active - Bound to " + targetIp.getHostAddress() + " (Port " + port + ")");
            LOGGER.log(Level.INFO, () -> "[InterfaceBinder] Successfully activated on " + targetIp.getHostAddress() + " (Port " + port + ")");

        } catch (Exception e) {
            setStatus("Error starting proxy: " + e.getMessage());
            LOGGER.log(Level.WARNING, () -> "[InterfaceBinder] Error starting proxy: " + e.getMessage());
            stopProxyOnly();
        }
    }

    private synchronized void stopProxyOnly() {
        if (activeProxy != null) {
            activeProxy.stop();
            activeProxy = null;
        }
        currentBoundIp = null;

        // Clear System Properties
        System.clearProperty("http.proxyHost");
        System.clearProperty("http.proxyPort");
        System.clearProperty("https.proxyHost");
        System.clearProperty("https.proxyPort");
        System.clearProperty("socksProxyHost");
        System.clearProperty("socksProxyPort");
        System.clearProperty("http.nonProxyHosts");

        if (defaultProxySelector != null) {
            ProxySelector.setDefault(defaultProxySelector);
        }
    }
}
