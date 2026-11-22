package com.artifex.mupdf.viewer;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;
import android.util.Log;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Locale;

/* How flowing text (Markdown, and ebooks) is presented: colours, font and
 * spacing. Shared by every document and remembered between launches. */
public class ReadingSettings
{
	/* Follow the system's dark mode. */
	public static final String THEME_AUTO = "auto";
	public static final String THEME_LIGHT = "light";
	public static final String THEME_DARK = "dark";
	public static final String THEME_SEPIA = "sepia";

	/* The document's own fonts (for Markdown, which has none, the system's). */
	public static final String FONT_DEFAULT = "default";
	public static final String FONT_ROBOTO = "roboto";
	public static final String FONT_MERRIWEATHER = "merriweather";
	public static final String FONT_LITERATA = "literata";
	public static final String FONT_NOTO_SERIF = "notoserif";
	public static final String FONT_ALICE = "alice";
	public static final String FONT_MONO = "mono";

	public static final String[] FONTS = { FONT_DEFAULT, FONT_ROBOTO, FONT_MERRIWEATHER, FONT_LITERATA,
		FONT_NOTO_SERIF, FONT_ALICE, FONT_MONO };

	/* Bundled fonts (assets/fonts/<Family>-<Style>.ttf) by setting, and
	 * whether they come in all four styles or only Regular (Alice), in which
	 * case bold and italic are synthesised from it. */
	private static final String[][] BUNDLED = {
		{ FONT_ROBOTO, "Roboto", "all" },
		{ FONT_MERRIWEATHER, "Merriweather", "all" },
		{ FONT_LITERATA, "Literata", "all" },
		{ FONT_NOTO_SERIF, "NotoSerif", "all" },
		{ FONT_ALICE, "Alice", "regular" },
	};
	private static final String[][] STYLES = {
		{ "Regular", "normal", "normal" },
		{ "Italic", "italic", "normal" },
		{ "Bold", "normal", "bold" },
		{ "BoldItalic", "italic", "bold" },
	};

	/* Sepia, as in common reading apps: warm paper with brown ink. */
	private static final int SEPIA_BG = 0xfff0dec6;
	private static final String SEPIA_FG = "#5f4a30";
	private static final String SEPIA_LINK = "#2b5a80";

	public static final int MIN_TEXT_ZOOM = 60, MAX_TEXT_ZOOM = 250;
	public static final float MIN_LINE_HEIGHT = 1.2f, MAX_LINE_HEIGHT = 2.4f;
	public static final float MIN_PARA_SPACING = 0.4f, MAX_PARA_SPACING = 2.4f;
	public static final int MIN_MARGIN = 0, MAX_MARGIN = 64;

	private static final String PREFS = "reading_settings";

	public String theme = THEME_AUTO;
	public String font = FONT_DEFAULT;
	public int textZoom = 100;          /* percent */
	public float lineHeight = 1.6f;     /* multiple of the font size */
	public float paraSpacing = 1.0f;    /* em */
	public int margin = 16;             /* CSS px either side */

	public static ReadingSettings load(Context context) {
		SharedPreferences p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
		ReadingSettings s = new ReadingSettings();
		s.theme = p.getString("theme", s.theme);
		s.font = p.getString("font", s.font);
		boolean known = false;
		for (String f : FONTS)
			known |= f.equals(s.font);
		if (!known)
			s.font = FONT_DEFAULT;
		s.textZoom = p.getInt("textZoom", s.textZoom);
		s.lineHeight = p.getFloat("lineHeight", s.lineHeight);
		s.paraSpacing = p.getFloat("paraSpacing", s.paraSpacing);
		s.margin = p.getInt("margin", s.margin);
		return s;
	}

	public void save(Context context) {
		context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
			.putString("theme", theme)
			.putString("font", font)
			.putInt("textZoom", textZoom)
			.putFloat("lineHeight", lineHeight)
			.putFloat("paraSpacing", paraSpacing)
			.putInt("margin", margin)
			.apply();
	}

	public ReadingSettings copy() {
		ReadingSettings s = new ReadingSettings();
		s.copyFrom(this);
		return s;
	}

	public void copyFrom(ReadingSettings o) {
		theme = o.theme;
		font = o.font;
		textZoom = o.textZoom;
		lineHeight = o.lineHeight;
		paraSpacing = o.paraSpacing;
		margin = o.margin;
	}

	/* The theme in force: Auto resolved to light or dark by the system. */
	public String effectiveTheme(Context context) {
		if (!THEME_AUTO.equals(theme))
			return theme;
		int night = context.getResources().getConfiguration().uiMode
			& android.content.res.Configuration.UI_MODE_NIGHT_MASK;
		return night == android.content.res.Configuration.UI_MODE_NIGHT_YES ? THEME_DARK : THEME_LIGHT;
	}

	public boolean isDark(Context context) {
		return THEME_DARK.equals(effectiveTheme(context));
	}

	/* The page background for the theme, for anything drawn behind the text. */
	public int backgroundColor(Context context) {
		String t = effectiveTheme(context);
		if (THEME_DARK.equals(t))
			return 0xff0f1115;
		if (THEME_SEPIA.equals(t))
			return SEPIA_BG;
		return 0xffffffff;
	}

	/* The text colour that goes with backgroundColor. */
	public int textColor(Context context) {
		String t = effectiveTheme(context);
		if (THEME_DARK.equals(t))
			return 0xffe3e6ea;
		if (THEME_SEPIA.equals(t))
			return 0xff5f4a30;
		return 0xff1f2328;
	}

	/* Font size for MuPDF's reflowed documents, from the text size percentage. */
	public int layoutEm(int defaultEm) {
		return Math.max(5, Math.round(defaultEm * textZoom / 100f));
	}

	private static String hex(int color) {
		return String.format("#%06x", color & 0xffffff);
	}

	private static String[] bundled(String font) {
		for (String[] b : BUNDLED)
			if (b[0].equals(font))
				return b;
		return null;
	}

	private static String[][] stylesOf(String[] bundled) {
		return "all".equals(bundled[2]) ? STYLES : new String[][] { STYLES[0] };
	}

	/* MuPDF loads fonts only from real files, so copy the bundled ones out of
	 * the APK once. Returns the folder, or null if that failed. */
	private static File fontDir(Context context) {
		File dir = new File(context.getFilesDir(), "fonts");
		if (!dir.isDirectory() && !dir.mkdirs())
			return null;
		AssetManager assets = context.getAssets();
		for (String[] b : BUNDLED) {
			for (String[] st : stylesOf(b)) {
				String name = b[1] + "-" + st[0] + ".ttf";
				File f = new File(dir, name);
				if (f.length() > 0)
					continue;
				try (InputStream in = assets.open("fonts/" + name); OutputStream out = new FileOutputStream(f)) {
					byte[] buf = new byte[16384];
					int n;
					while ((n = in.read(buf)) > 0)
						out.write(buf, 0, n);
				} catch (IOException x) {
					Log.e("MuPDF", "Cannot copy font " + name + ": " + x);
					f.delete();
					return null;
				}
			}
		}
		return dir;
	}

	/* User stylesheet for MuPDF's reflowed documents (EPUB, FB2, MOBI, HTML).
	 * The book's own CSS stays in force for everything not set here; the
	 * light theme keeps the book's colours, and the default font its fonts.
	 * MuPDF only reads this when a document is opened. */
	public String reflowCss(Context context) {
		String theme = effectiveTheme(context);
		boolean dark = THEME_DARK.equals(theme);
		StringBuilder css = new StringBuilder();
		/* Elements books commonly give their own font or colour. */
		String text = "body, p, div, span, li, dd, dt, td, th, blockquote, h1, h2, h3, h4, h5, h6, em, i, b, strong, small, sup, sub";

		if (!THEME_LIGHT.equals(theme)) {
			String fg = dark ? "#e3e6ea" : SEPIA_FG;
			String link = dark ? "#58a6ff" : SEPIA_LINK;
			css.append("html, body { background-color: ").append(hex(backgroundColor(context))).append(" !important; }\n");
			css.append(text).append(" { color: ").append(fg).append(" !important; background-color: transparent !important; }\n");
			css.append("a, a * { color: ").append(link).append(" !important; }\n");
		}

		String family = null;
		String[] bundled = bundled(font);
		if (bundled != null) {
			File dir = fontDir(context);
			if (dir != null) {
				family = "\"Reader " + bundled[1] + "\"";
				String[][] styles = stylesOf(bundled);
				for (String[] st : styles) {
					String path = new File(dir, bundled[1] + "-" + st[0] + ".ttf").getAbsolutePath();
					if (styles.length == 1)
						css.append(String.format(Locale.ROOT,
							"@font-face { font-family: %s; src: url(\"%s\"); }\n", family, path));
					else
						css.append(String.format(Locale.ROOT,
							"@font-face { font-family: %s; font-style: %s; font-weight: %s; src: url(\"%s\"); }\n",
							family, st[1], st[2], path));
				}
			}
		} else if (FONT_MONO.equals(font)) {
			family = "monospace";
		}
		if (family != null) {
			css.append(text).append(" { font-family: ").append(family).append(" !important; }\n");
			css.append("pre, code, kbd, pre *, code * { font-family: monospace !important; }\n");
		}

		css.append(String.format(Locale.ROOT,
			"body { line-height: %.2f !important; margin-left: %dpx !important; margin-right: %dpx !important; }\n",
			lineHeight, margin, margin));
		css.append(String.format(Locale.ROOT,
			"p { margin-top: 0 !important; margin-bottom: %.2fem !important; }\n", paraSpacing));
		return css.toString();
	}

	public String toJson(Context context) {
		JSONObject o = new JSONObject();
		try {
			o.put("theme", effectiveTheme(context));
			o.put("font", font);
			o.put("lineHeight", lineHeight);
			o.put("paraSpacing", paraSpacing);
			o.put("margin", margin);
		} catch (JSONException x) {
			/* cannot happen with these value types */
		}
		return o.toString();
	}
}
