package com.deepseekharness.app.ui;

import android.app.Activity;
import android.view.View;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.core.view.WindowInsetsControllerCompat;

import com.deepseekharness.app.R;

/** 两种网页内核共用安全显示区；系统栏、挖孔和输入法都不能覆盖正文。 */
public final class WebFullscreenUi {
  /** 避免全局页面边距处理覆盖网页的全屏设置。 */
  public interface Host {}

  private WebFullscreenUi() {}

  public static void install(Activity activity) {
    // 旧 Android 的 FLAG_FULLSCREEN 会阻止 adjustResize，不能依赖输入法出现时再取消全屏。
    activity.getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_FULLSCREEN);
    WindowCompat.setDecorFitsSystemWindows(activity.getWindow(), false);
    View content = activity.findViewById(android.R.id.content);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    ViewCompat.setOnApplyWindowInsetsListener(
        content,
        (view, insets) -> {
          if (PictureInPictureActivity.showing(activity)) {
            view.setPadding(0, 0, 0, 0);
            return WindowInsetsCompat.CONSUMED;
          }
          Insets bars =
              insets.getInsets(
                  WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
          // 顶部保留稳定安全区，避免 ROM 在键盘/焦点切换时短暂报告状态栏不可见而把正文顶上去。
          Insets stableTop =
              insets.getInsetsIgnoringVisibility(
                  WindowInsetsCompat.Type.systemBars()
                      | WindowInsetsCompat.Type.displayCutout()
                      | WindowInsetsCompat.Type.captionBar());
          Insets safe = Insets.max(bars, stableTop);
          int keyboard = insets.getInsets(WindowInsetsCompat.Type.ime()).bottom;
          // 额外留出 8dp：贴边的输入框与缓存统计栏不紧压导航键。
          int bottomGap = Math.round(8 * view.getResources().getDisplayMetrics().density);
          view.setPadding(
              safe.left, safe.top, safe.right, Math.max(safe.bottom, keyboard) + bottomGap);
          return WindowInsetsCompat.CONSUMED;
        });
    applySystemBars(activity);
    ViewCompat.requestApplyInsets(content);
  }

  public static void applySystemBars(Activity activity) {
    if (PictureInPictureActivity.showing(activity)) return;
    WindowInsetsControllerCompat controller =
        WindowCompat.getInsetsController(activity.getWindow(), activity.getWindow().getDecorView());
    int color =
        activity instanceof PictureInPictureActivity host
            ? host.browserThemeColor()
            : activity.getColor(R.color.surface);
    View content = activity.findViewById(android.R.id.content);
    if (content != null) {
      int left = content.getPaddingLeft(), top = content.getPaddingTop();
      int right = content.getPaddingRight(), bottom = content.getPaddingBottom();
      content.setBackgroundColor(color);
      content.setPadding(left, top, right, bottom);
    }
    controller.setAppearanceLightStatusBars(
        androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5);
    controller.setAppearanceLightNavigationBars(
        androidx.core.graphics.ColorUtils.calculateLuminance(color) > 0.5);
    activity.getWindow().setStatusBarColor(android.graphics.Color.TRANSPARENT);
    activity
        .getWindow()
        .setNavigationBarColor(
            android.os.Build.VERSION.SDK_INT >= 26
                ? color
                : activity.getColor(R.color.terminal_surface));
    controller.setSystemBarsBehavior(
        WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
    // 状态栏与系统导航栏都保持显示；正文由 insets 避让，输入框与统计栏不会被导航键遮挡。
    controller.show(WindowInsetsCompat.Type.systemBars());
  }

  /** 当前实例核验后的主题色只改变绘制背景和图标，不重新加载页面。 */
  public static void applyThemeColor(Activity activity, int color) {
    if (activity == null || activity.isFinishing() || activity.isDestroyed()) return;
    if (activity instanceof PictureInPictureActivity host) host.applyBrowserTheme(color);
    applySystemBars(activity);
  }
}
