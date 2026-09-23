import app.yxi.desktop.TrayMenuFont;
import java.awt.*;

public class TrayMenuFontCheck {
    public static void main(String[] args) throws Exception {
        EventQueue.invokeAndWait(() -> {
            PopupMenu menu = new PopupMenu();
            for (String label : new String[]{"显示窗口", "新建会话", "设置…", "退出 Yxi"}) menu.add(new MenuItem(label));
            TrayMenuFont.apply(menu);
            for (int i = 0; i < menu.getItemCount(); i++) {
                MenuItem item = menu.getItem(i);
                if (item.getFont() == null || item.getFont().canDisplayUpTo(item.getLabel()) != -1)
                    throw new AssertionError("Missing glyphs: " + item.getLabel());
                if (!item.getFont().equals(menu.getFont())) throw new AssertionError("Font not applied to child");
            }
            Frame owner = new Frame();
            try { owner.add(menu); owner.addNotify(); menu.addNotify(); }
            finally { owner.dispose(); }
            System.out.println("tray native menu font and Chinese glyph coverage ok: " + menu.getFont().getFamily());
        });
    }
}
