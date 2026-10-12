package dev.shared.rodrigo.config;

import dev.shared.do_gamer.utils.ConfigHtmlInstructions;
import eu.darkbot.api.config.annotations.Configuration;
import eu.darkbot.api.config.annotations.Dropdown;
import eu.darkbot.api.config.annotations.Editor;
import eu.darkbot.api.config.annotations.Option;
import eu.darkbot.api.config.annotations.Readonly;
import eu.darkbot.api.config.annotations.Text;

import java.util.Locale;

@Configuration("interface_binder")
public class InterfaceBinderConfig {

    /**
     * Compact reference panel with narrow width and short lines.
     */
    public static class Instructions extends ConfigHtmlInstructions {
        @Override
        public String getEditorValue() {
            boolean isSpanish = false;
            try {
                Locale loc = null;
                try {
                    loc = com.github.manolo8.darkbot.utils.I18n.getLocale();
                } catch (Throwable ignored) {
                }
                if (loc == null) {
                    loc = Locale.getDefault();
                }
                if (loc != null && "es".equalsIgnoreCase(loc.getLanguage())) {
                    isSpanish = true;
                }
            } catch (Throwable ignored) {
            }

            if (isSpanish) {
                return "<table width=\"240\" border=\"0\" cellspacing=\"0\" cellpadding=\"1\"><tr><td>"
                        + "<b>Enlace de IP:</b><br>"
                        + "Enruta el tr\u00e1fico del bot por<br>"
                        + "una IP o interfaz local.<br><br>"
                        + "<b>Uso:</b><br>"
                        + "1. Marca <i>Activar</i>.<br>"
                        + "2. Selecciona la IP<br>"
                        + "&nbsp;&nbsp;&nbsp;(o escribe una manual)."
                        + "</td></tr></table>";
            } else {
                return "<table width=\"240\" border=\"0\" cellspacing=\"0\" cellpadding=\"1\"><tr><td>"
                        + "<b>IP Binder:</b><br>"
                        + "Routes bot traffic through<br>"
                        + "a local IP or interface.<br><br>"
                        + "<b>Usage:</b><br>"
                        + "1. Check <i>Enable</i>.<br>"
                        + "2. Select your IP<br>"
                        + "&nbsp;&nbsp;&nbsp;(or enter manual IP)."
                        + "</td></tr></table>";
            }
        }
    }

    @Option("")
    @Readonly
    @Editor(Instructions.class)
    public String instructions = null;

    @Option("rodrigo.interface_binder.enabled")
    public boolean enabled = false;

    @Option("rodrigo.interface_binder.target_interface")
    @Dropdown(options = NetworkInterfaceOptions.class)
    public String targetInterface = NetworkInterfaceOptions.DEFAULT_ROUTE;

    @Option("rodrigo.interface_binder.manual_ip")
    @Text(length = 20, placeholder = "e.g. 5.175.184.108")
    public String manualIpOverride = "";

    @Option("rodrigo.interface_binder.status")
    @Readonly
    @Text(length = 35)
    public String status = "Disabled";
}
