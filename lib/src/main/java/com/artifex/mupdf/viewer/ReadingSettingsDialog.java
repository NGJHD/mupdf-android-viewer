package com.artifex.mupdf.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;

import java.util.Locale;

/* The reading settings popup: theme, font, text size and spacing. Changes
 * go to the listener straight away, so a document that can follow them live
 * shows them behind the dialog. Save keeps them; Dismiss (or Back) puts the
 * settings back as they were and tells the listener so. */
public class ReadingSettingsDialog
{
	public interface Listener {
		void onChanged(ReadingSettings settings);
	}

	private final Activity mActivity;
	private final ReadingSettings mSettings;
	private final Listener mListener;
	private final LinearLayout mContent;
	private final ReadingSettings mOriginal;
	private Runnable mOnSaved;
	private boolean mSaved;

	public static void show(Activity activity, ReadingSettings settings, Listener listener) {
		new ReadingSettingsDialog(activity, settings, listener).show();
	}

	/* onSaved runs after Save, for documents that cannot follow every change
	 * live and apply them all at once instead. */
	public static void show(Activity activity, ReadingSettings settings, Listener listener, Runnable onSaved) {
		ReadingSettingsDialog d = new ReadingSettingsDialog(activity, settings, listener);
		d.mOnSaved = onSaved;
		d.show();
	}

	private ReadingSettingsDialog(Activity activity, ReadingSettings settings, Listener listener) {
		mActivity = activity;
		mSettings = settings;
		mListener = listener;
		mOriginal = settings.copy();
		mContent = new LinearLayout(activity);
		mContent.setOrientation(LinearLayout.VERTICAL);
		int pad = dp(20);
		mContent.setPadding(pad, dp(8), pad, 0);
	}

	private int dp(int v) {
		return (int) TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
			mActivity.getResources().getDisplayMetrics());
	}

	private void changed() {
		mListener.onChanged(mSettings);
	}

	private TextView label(String text) {
		TextView tv = new TextView(mActivity);
		tv.setText(text);
		tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
		tv.setPadding(0, dp(12), 0, dp(4));
		return tv;
	}

	/* "Name: value", and when the value is not the default, what the
	 * default is, so a reader can find their way back to it. */
	private String describe(String name, String value, String current, String defValue, String defName) {
		String text = value != null ? name + ": " + value : name;
		if (!current.equals(defValue))
			text += "   (" + mActivity.getString(R.string.default_value, defName) + ")";
		return text;
	}

	private interface Choice {
		void chose(String value);
	}

	/* separatorBefore: index of the choice to set apart with a divider, or -1. */
	private void addChoices(int labelRes, String[] values, int[] names, String current,
			String defValue, int separatorBefore, final Choice choice) {
		final String name = mActivity.getString(labelRes);
		String defName = "";
		for (int i = 0; i < values.length; i++)
			if (values[i].equals(defValue))
				defName = mActivity.getString(names[i]);
		final String fDefName = defName;
		final TextView label = label(describe(name, null, current, defValue, defName));
		mContent.addView(label);
		RadioGroup group = new RadioGroup(mActivity);
		group.setOrientation(RadioGroup.HORIZONTAL);
		for (int i = 0; i < values.length; i++) {
			if (i == separatorBefore) {
				View divider = new View(mActivity);
				divider.setBackgroundColor(0xff8a8a8a);
				RadioGroup.LayoutParams lp = new RadioGroup.LayoutParams(dp(2), dp(32));
				lp.setMargins(dp(6), 0, dp(16), 0);
				lp.gravity = Gravity.CENTER_VERTICAL;
				group.addView(divider, lp);
			}
			final String value = values[i];
			RadioButton b = new RadioButton(mActivity);
			b.setId(View.generateViewId());
			b.setText(names[i]);
			b.setPadding(0, 0, dp(12), 0);
			group.addView(b);
			if (value.equals(current))
				b.setChecked(true);
			b.setOnCheckedChangeListener((btn, checked) -> {
				if (checked) {
					label.setText(describe(name, null, value, defValue, fDefName));
					choice.chose(value);
				}
			});
		}
		mContent.addView(group);
	}

	private static int fontName(String font) {
		switch (font) {
		case ReadingSettings.FONT_ROBOTO: return R.string.font_roboto;
		case ReadingSettings.FONT_MERRIWEATHER: return R.string.font_merriweather;
		case ReadingSettings.FONT_LITERATA: return R.string.font_literata;
		case ReadingSettings.FONT_NOTO_SERIF: return R.string.font_noto_serif;
		case ReadingSettings.FONT_ALICE: return R.string.font_alice;
		case ReadingSettings.FONT_MONO: return R.string.font_mono;
		default: return R.string.font_default;
		}
	}

	private interface Slider {
		String format(int step);
		void moved(int step);
	}

	private void addSlider(int labelRes, int steps, int current, final int defStep, final Slider slider) {
		final String name = mActivity.getString(labelRes);
		final String defName = slider.format(defStep);
		final TextView label = label(describe(name, slider.format(current),
			String.valueOf(current), String.valueOf(defStep), defName));
		mContent.addView(label);
		SeekBar bar = new SeekBar(mActivity);
		bar.setMax(steps);
		bar.setProgress(current);
		bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
				label.setText(describe(name, slider.format(progress),
					String.valueOf(progress), String.valueOf(defStep), defName));
				if (fromUser)
					slider.moved(progress);
			}
			public void onStartTrackingTouch(SeekBar seekBar) {}
			public void onStopTrackingTouch(SeekBar seekBar) {}
		});
		mContent.addView(bar);
	}

	private void show() {
		final ReadingSettings s = mSettings;
		final ReadingSettings defaults = new ReadingSettings();

		addChoices(R.string.reading_theme,
			new String[] { ReadingSettings.THEME_AUTO, ReadingSettings.THEME_LIGHT, ReadingSettings.THEME_DARK, ReadingSettings.THEME_SEPIA },
			new int[] { R.string.theme_auto, R.string.theme_light, R.string.theme_dark, R.string.theme_sepia },
			s.theme, defaults.theme, 3, v -> { s.theme = v; changed(); });

		/* Too many fonts for one row of buttons, so a drop-down. */
		final String fontLabelName = mActivity.getString(R.string.reading_font);
		final String defFontName = mActivity.getString(fontName(defaults.font));
		final TextView fontLabel = label(describe(fontLabelName, null, s.font, defaults.font, defFontName));
		mContent.addView(fontLabel);
		final String[] fonts = ReadingSettings.FONTS;
		String[] fontNames = new String[fonts.length];
		int fontIndex = 0;
		for (int i = 0; i < fonts.length; i++) {
			fontNames[i] = mActivity.getString(fontName(fonts[i]));
			if (fonts[i].equals(s.font))
				fontIndex = i;
		}
		Spinner fontSpinner = new Spinner(mActivity);
		ArrayAdapter<String> fontAdapter = new ArrayAdapter<>(mActivity,
			android.R.layout.simple_spinner_item, fontNames);
		fontAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
		fontSpinner.setAdapter(fontAdapter);
		fontSpinner.setSelection(fontIndex, false);
		fontSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
			public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
				if (!fonts[position].equals(s.font)) {
					s.font = fonts[position];
					fontLabel.setText(describe(fontLabelName, null, s.font, defaults.font, defFontName));
					changed();
				}
			}
			public void onNothingSelected(AdapterView<?> parent) {}
		});
		mContent.addView(fontSpinner);

		/* Text size in steps of 10%. */
		addSlider(R.string.reading_text_size,
			(ReadingSettings.MAX_TEXT_ZOOM - ReadingSettings.MIN_TEXT_ZOOM) / 10,
			(s.textZoom - ReadingSettings.MIN_TEXT_ZOOM) / 10,
			(defaults.textZoom - ReadingSettings.MIN_TEXT_ZOOM) / 10,
			new Slider() {
				public String format(int step) {
					return (ReadingSettings.MIN_TEXT_ZOOM + step * 10) + "%";
				}
				public void moved(int step) {
					s.textZoom = ReadingSettings.MIN_TEXT_ZOOM + step * 10;
					changed();
				}
			});

		/* Line spacing in steps of 0.1. */
		addSlider(R.string.reading_line_spacing,
			Math.round((ReadingSettings.MAX_LINE_HEIGHT - ReadingSettings.MIN_LINE_HEIGHT) * 10),
			Math.round((s.lineHeight - ReadingSettings.MIN_LINE_HEIGHT) * 10),
			Math.round((defaults.lineHeight - ReadingSettings.MIN_LINE_HEIGHT) * 10),
			new Slider() {
				public String format(int step) {
					return String.format(Locale.ROOT, "%.1f", ReadingSettings.MIN_LINE_HEIGHT + step / 10f);
				}
				public void moved(int step) {
					s.lineHeight = ReadingSettings.MIN_LINE_HEIGHT + step / 10f;
					changed();
				}
			});

		/* Paragraph spacing in steps of 0.2em. */
		addSlider(R.string.reading_paragraph_spacing,
			Math.round((ReadingSettings.MAX_PARA_SPACING - ReadingSettings.MIN_PARA_SPACING) * 5),
			Math.round((s.paraSpacing - ReadingSettings.MIN_PARA_SPACING) * 5),
			Math.round((defaults.paraSpacing - ReadingSettings.MIN_PARA_SPACING) * 5),
			new Slider() {
				public String format(int step) {
					return String.format(Locale.ROOT, "%.1f", ReadingSettings.MIN_PARA_SPACING + step / 5f);
				}
				public void moved(int step) {
					s.paraSpacing = ReadingSettings.MIN_PARA_SPACING + step / 5f;
					changed();
				}
			});

		/* Side margins in steps of 4px. */
		addSlider(R.string.reading_margins,
			(ReadingSettings.MAX_MARGIN - ReadingSettings.MIN_MARGIN) / 4,
			(s.margin - ReadingSettings.MIN_MARGIN) / 4,
			(defaults.margin - ReadingSettings.MIN_MARGIN) / 4,
			new Slider() {
				public String format(int step) {
					return String.valueOf(ReadingSettings.MIN_MARGIN + step * 4);
				}
				public void moved(int step) {
					s.margin = ReadingSettings.MIN_MARGIN + step * 4;
					changed();
				}
			});

		ScrollView scroll = new ScrollView(mActivity);
		scroll.addView(mContent);

		AlertDialog dialog = new AlertDialog.Builder(mActivity)
			.setTitle(R.string.reading_settings)
			.setView(scroll)
			.setPositiveButton(R.string.save, (d, w) -> {
				mSaved = true;
				mSettings.save(mActivity);
				if (mOnSaved != null)
					mOnSaved.run();
			})
			.setNegativeButton(R.string.dismiss, null)
			.create();
		/* Dismiss, Back or a tap outside: put everything back. */
		dialog.setOnDismissListener(d -> {
			if (mSaved)
				return;
			mSettings.copyFrom(mOriginal);
			mListener.onChanged(mSettings);
		});
		/* Keep the page visible behind the dialog so changes can be judged. */
		dialog.getWindow().setDimAmount(0.1f);
		dialog.getWindow().setGravity(Gravity.BOTTOM);
		dialog.show();
	}
}
