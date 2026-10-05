package org.thunderdog.challegram.theme;

import android.content.Context;
import android.os.Build;

import androidx.core.content.ContextCompat;

import org.drinkless.tdlib.TdApi;
import org.thunderdog.challegram.Log;
import org.thunderdog.challegram.navigation.ViewController;

import org.thunderdog.challegram.tool.UI;
import android.widget.Toast;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

public class MonetThemeGenerator {

  public static void generateAndInstallTheme (ViewController<?> context, boolean isDark) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return;

    try {
      String assetName = isDark ? "monet_x_dark.tgx-theme" : "monet_x_light.tgx-theme";
      Context ctx = context.context();
      InputStream is = ctx.getAssets().open(assetName);
      byte[] bytes = new byte[is.available()];
      is.read(bytes);
      is.close();
      String themeText = new String(bytes, "UTF-8");

      Map<String, Integer> monetList = new HashMap<>();
      String[] types = {"accent1", "accent2", "accent3", "neutral1", "neutral2"};
      String[] prefixes = {"a1", "a2", "a3", "n1", "n2"};
      int[] shades = {0, 10, 50, 100, 200, 300, 400, 500, 600, 700, 800, 900, 1000};

      for (int i = 0; i < types.length; i++) {
        for (int shade : shades) {
          int resId = ctx.getResources().getIdentifier("system_" + types[i] + "_" + shade, "color", "android");
          if (resId != 0) {
            int color = ContextCompat.getColor(ctx, resId);
            monetList.put(prefixes[i] + "_" + shade, color);
          }
        }
      }

      // Hardcoded fallbacks directly fetched from Android system or commonly used defaults
      monetList.put("monetRedDark", 0xffc62828);
      monetList.put("monetRedLight", 0xffe53935);
      monetList.put("monetRedCall", 0xffd32f2f);
      monetList.put("monetGreenCall", 0xff4caf50);

      themeText = themeText.replace("$", "");
      List<String> keys = new ArrayList<>(monetList.keySet());
      Collections.sort(keys, (a, b) -> Integer.compare(b.length(), a.length()));
      
      for (String key : keys) {
        String hex = String.format("#%06X", (0xFFFFFF & monetList.get(key)));
        themeText = themeText.replace(key, hex);
      }

      File outFile = new File(ctx.getCacheDir(), isDark ? "Monet_Dark.tgx-theme" : "Monet_Light.tgx-theme");
      FileOutputStream fos = new FileOutputStream(outFile);
      fos.write(themeText.getBytes("UTF-8"));
      fos.close();

      TdApi.LocalFile localFile = new TdApi.LocalFile(outFile.getAbsolutePath(), true, true, true, true, 0, 0, 0);
      TdApi.File reqFile = new TdApi.File(0, 0, 0, localFile, null);

      me.vkryl.core.lambda.RunnableData<org.thunderdog.challegram.telegram.TdlibUi.ImportedTheme> onDone = new me.vkryl.core.lambda.RunnableData<org.thunderdog.challegram.telegram.TdlibUi.ImportedTheme>() {
        @Override
        public void runWithData (org.thunderdog.challegram.telegram.TdlibUi.ImportedTheme theme) {
          String newName = isDark ? "Material You Dark" : "Material You Light";
          theme.name = newName;
          
          for (org.thunderdog.challegram.theme.ThemeInfo test : org.thunderdog.challegram.unsorted.Settings.instance().getCustomThemes()) {
            if (newName.equals(test.getName())) {
               org.thunderdog.challegram.unsorted.Settings.instance().removeCustomTheme(org.thunderdog.challegram.theme.ThemeManager.resolveCustomThemeId(test.getId()));
            }
          }

          int themeId = org.thunderdog.challegram.theme.ThemeManager.instance().installCustomTheme(theme);
          org.thunderdog.challegram.theme.ThemeManager.instance().changeGlobalTheme(context.tdlib(), theme.theme, false, null);
          UI.showToast(newName + " Applied & Saved!", Toast.LENGTH_SHORT);

          if (context instanceof org.thunderdog.challegram.ui.SettingsThemeController) {
             org.thunderdog.challegram.ui.SettingsThemeController c = (org.thunderdog.challegram.ui.SettingsThemeController) context;
             org.thunderdog.challegram.theme.ThemeInfo ti = new org.thunderdog.challegram.theme.ThemeInfo(themeId, theme.name, theme.wallpaper, theme.parentThemeId, org.thunderdog.challegram.unsorted.Settings.THEME_FLAG_INSTALLED);
             
             boolean found = false;
             for (org.thunderdog.challegram.ui.ListItem item : c.adapter.getItems()) {
                 if (item.getId() == org.thunderdog.challegram.R.id.btn_theme && item.getData() instanceof org.thunderdog.challegram.theme.ThemeInfo) {
                     org.thunderdog.challegram.theme.ThemeInfo existing = (org.thunderdog.challegram.theme.ThemeInfo) item.getData();
                     if (newName.equals(existing.getName())) {
                         found = true;
                         existing.setLoadedTheme(theme.theme);
                         c.updateTheme(existing);
                         break;
                     }
                 }
             }

             if (!found) {
                 c.addTheme(ti);
             }
          }
        }
      };

      context.tdlib().ui().readCustomTheme(context, reqFile, onDone, null);
      
    } catch (Exception e) {
      Log.e("Failed to generate Monet theme", e);
      UI.showToast("Monet Error: " + e.getMessage(), Toast.LENGTH_LONG);
    }
  }
}
