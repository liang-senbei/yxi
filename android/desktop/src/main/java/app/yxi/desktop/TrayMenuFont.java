package app.yxi.desktop;

import java.awt.Font;
import java.awt.GraphicsEnvironment;
import java.awt.Menu;
import java.awt.MenuItem;
import java.awt.Toolkit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;

/** AWT native menus do not inherit the Compose font family. */
public final class TrayMenuFont {
    private TrayMenuFont() {}

    public static Font select(String text) {
        Object configured = Toolkit.getDefaultToolkit().getDesktopProperty("win.menu.font");
        Font system = configured instanceof Font ? (Font) configured : new Font(Font.DIALOG, Font.PLAIN, 12);
        Set<String> installed = new HashSet<>(Arrays.asList(
                GraphicsEnvironment.getLocalGraphicsEnvironment().getAvailableFontFamilyNames()));
        // Prefer a physical CJK face: native Windows menu peers cannot reliably use logical fallback.
        for (String family : new String[]{"Microsoft YaHei UI", "Microsoft YaHei", "SimSun", "PingFang SC", "Noto Sans CJK SC"}) {
            if (installed.contains(family)) {
                Font candidate = new Font(family, system.getStyle(), system.getSize()).deriveFont(system.getSize2D());
                if (candidate.canDisplayUpTo(text) == -1) return candidate;
            }
        }
        return system;
    }

    public static void apply(Menu menu) {
        StringBuilder labels = new StringBuilder();
        collect(menu, labels);
        apply(menu, select(labels.toString()));
    }

    private static void collect(Menu menu, StringBuilder labels) {
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            labels.append(item.getLabel());
            if (item instanceof Menu) collect((Menu) item, labels);
        }
    }

    private static void apply(Menu menu, Font font) {
        menu.setFont(font);
        for (int i = 0; i < menu.getItemCount(); i++) {
            MenuItem item = menu.getItem(i);
            item.setFont(font);
            if (item instanceof Menu) apply((Menu) item, font);
        }
    }
}
