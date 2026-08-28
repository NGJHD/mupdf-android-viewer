package com.artifex.mupdf.viewer;

import android.app.ListActivity;
import android.graphics.Insets;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.ListView;

import java.io.Serializable;
import java.util.ArrayList;

public class OutlineActivity extends ListActivity
{
	private final String APP = "MuPDF";

	public static class Item implements Serializable {
		public String title;
		public int page;
		public Item(String title, int page) {
			this.title = title;
			this.page = page;
		}
		public String toString() {
			return title;
		}
	}

	protected ArrayAdapter<Item> adapter;

	public void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
		Fullscreen.hideSystemBars(getWindow());

		adapter = new ArrayAdapter<Item>(this, android.R.layout.simple_list_item_1);
		setListAdapter(adapter);

		/* The bars are hidden, but a display cutout still eats into the screen.
		 * Inset the list so no entry hides underneath it. */
		if (Build.VERSION.SDK_INT >= 30) {
			getListView().setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
				public WindowInsets onApplyWindowInsets(View v, WindowInsets windowInsets)
				{
					Insets insets = windowInsets.getInsets(WindowInsets.Type.displayCutout());
					v.setPadding(insets.left, insets.top, insets.right, insets.bottom);
					return WindowInsets.CONSUMED;
				}
			});
		}

		int idx = getIntent().getIntExtra("PALLETBUNDLE", -1);
		Bundle bundle = Pallet.receiveBundle(idx);
		if (bundle != null) {
			int currentPage = bundle.getInt("POSITION");
			ArrayList<Item> outline = (ArrayList<Item>)bundle.getSerializable("OUTLINE");
			int found = -1;
			for (int i = 0; i < outline.size(); ++i) {
				Item item = outline.get(i);
				if (found < 0 && item.page >= currentPage)
					found = i;
				adapter.add(item);
			}
			if (found >= 0)
				setSelection(found);
		}
	}

	@Override
	public void onAttachedToWindow() {
		super.onAttachedToWindow();
		Fullscreen.hideSystemBars(getWindow());
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus)
			Fullscreen.hideSystemBars(getWindow());
	}

	protected void onListItemClick(ListView l, View v, int position, long id) {
		Item item = adapter.getItem(position);
		setResult(RESULT_FIRST_USER + item.page);
		finish();
	}
}
