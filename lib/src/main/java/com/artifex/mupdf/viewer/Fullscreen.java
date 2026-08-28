package com.artifex.mupdf.viewer;

import android.os.Build;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;

/* Shared fullscreen handling for the viewer activities. */
class Fullscreen
{
	/* Hide the status and navigation bars so that the document has the whole
	 * screen to itself. The bars can still be summoned by swiping in from the
	 * edge of the screen, and hide themselves again afterwards.
	 *
	 * This is a no-op until the window is attached, so call it again from
	 * onAttachedToWindow and onWindowFocusChanged. */
	static void hideSystemBars(Window window) {
		if (Build.VERSION.SDK_INT >= 30) {
			window.setDecorFitsSystemWindows(false);
			/* Ask the decor view rather than the window: Window.getInsetsController()
			 * dereferences the decor view and throws if it has not been created yet. */
			WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
			if (controller != null) {
				controller.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
				controller.hide(WindowInsets.Type.systemBars());
			}
		} else {
			window.getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
				View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
				View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN |
				View.SYSTEM_UI_FLAG_HIDE_NAVIGATION |
				View.SYSTEM_UI_FLAG_FULLSCREEN |
				View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
		}
	}

	/* Bring the status and navigation bars back. The window still lays out
	 * edge to edge, so whoever calls this is expected to inset its own
	 * furniture out from under them. */
	static void showSystemBars(Window window) {
		if (Build.VERSION.SDK_INT >= 30) {
			window.setDecorFitsSystemWindows(false);
			WindowInsetsController controller = window.getDecorView().getWindowInsetsController();
			if (controller != null)
				controller.show(WindowInsets.Type.systemBars());
		} else {
			window.getDecorView().setSystemUiVisibility(
				View.SYSTEM_UI_FLAG_LAYOUT_STABLE |
				View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION |
				View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
		}
	}
}
