package com.artifex.mupdf.viewer;

import com.artifex.mupdf.fitz.SeekableInputStream;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.DialogInterface.OnCancelListener;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.Rect;
import android.graphics.drawable.ShapeDrawable;
import android.graphics.drawable.shapes.RectShape;
import android.net.Uri;
import android.os.Build;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.method.PasswordTransformationMethod;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.ActionMode;
import android.view.KeyEvent;
import android.view.Menu;
import android.view.MenuItem.OnMenuItemClickListener;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.animation.Animation;
import android.view.animation.TranslateAnimation;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.RelativeLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;

import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Locale;

public class DocumentActivity extends Activity
{
	private final String APP = "MuPDF";

	/* The core rendering instance */
	enum TopBarMode {Main, Search, More};

	private final float EXCLUSION_HEIGHT_FACTOR = 2.0f;

	private final int    OUTLINE_REQUEST=0;
	private final int    SAVE_PERMISSION_REQUEST=1;
	private final int    COPY_MENU_ITEM=1;
	private final int    SELECT_ALL_MENU_ITEM=2;
	private final int    SHARE_MENU_ITEM=3;
	private final int    WEB_SEARCH_MENU_ITEM=4;
	private static final String PREF_CONTINUOUS_SCROLL = "ContinuousScroll";
	private MuPDFCore    core;
	private String       mDocTitle;
	private String       mDocKey;
	private ReaderView   mDocView;
	private View         mButtonsView;
	private boolean      mButtonsVisible;
	private EditText     mPasswordView;
	private TextView     mDocNameView;
	private SeekBar      mPageSlider;
	private int          mPageSliderRes;
	private boolean      mPageSliderTracking;
	private TextView     mPageNumberView;
	private ImageButton  mSearchButton;
	private ImageButton  mOutlineButton;
	private ImageButton  mShareButton;
	private ViewAnimator mTopBarSwitcher;
	private LinearLayout mTopBar;
	private LinearLayout mActionBar;
	private LinearLayout mSearchBar;
	private LinearLayout mBottomBar;
	private ImageButton  mDownloadButton;
	private ImageButton  mScrollModeButton;
	private TopBarMode   mTopBarMode = TopBarMode.Main;
	private ImageButton  mSearchBack;
	private ImageButton  mSearchFwd;
	private ImageButton  mSearchClose;
	private EditText     mSearchText;
	private SearchTask   mSearchTask;
	private AlertDialog.Builder mAlertBuilder;
	private ActionMode mSelectionActionMode;
	private final Handler mHandler = new Handler();
	private boolean mAlertsActive= false;
	private AlertDialog mAlertDialog;
	private ArrayList<OutlineActivity.Item> mFlatOutline;
	private boolean mReturnToLibraryActivity = false;

	protected int mDisplayDPI;
	private static final int DEFAULT_LAYOUT_EM = 10;
	private int mLayoutEM = DEFAULT_LAYOUT_EM;
	/* Reading settings for reflowed documents, and the stylesheet the open
	 * document was laid out with (MuPDF only reads it on opening). */
	private ReadingSettings mReading;
	private String mAppliedCss;
	/* A position to return to after reopening the document for new settings. */
	private static final String EXTRA_BOOKMARK = "com.artifex.mupdf.viewer.DocumentActivity.BOOKMARK";
	private boolean mHasPendingBookmark;
	private long mPendingBookmark;
	private int mLayoutW = 312;
	private int mLayoutH = 504;

	protected Insets systemInsets = Insets.NONE;

	protected View mLayoutButton;

	private String toHex(byte[] digest) {
		StringBuilder builder = new StringBuilder(2 * digest.length);
		for (byte b : digest)
			builder.append(String.format("%02x", b));
		return builder.toString();
	}

        private MuPDFCore openBuffer(byte buffer[], String magic)
        {
                try
                {
                        core = new MuPDFCore(buffer, magic);
                }
                catch (Exception e)
                {
                        Log.e(APP, "Error opening document buffer: " + e);
                        return null;
                }
                return core;
	}

	private MuPDFCore openStream(SeekableInputStream stm, String magic)
	{
		try
		{
			core = new MuPDFCore(stm, magic);
		}
		catch (Exception e)
		{
			Log.e(APP, "Error opening document stream: " + e);
			return null;
		}
		return core;
	}

	/* Android and file managers disagree on what to call a markdown file
	 * (text/x-markdown, text/plain, application/octet-stream...) while MuPDF
	 * only recognises text/markdown, so go by the file name as well. */
	private static boolean isMarkdown(String mimetype, String name) {
		if ("text/markdown".equals(mimetype) || "text/x-markdown".equals(mimetype))
			return true;
		if (name == null)
			return false;
		name = name.toLowerCase(java.util.Locale.ROOT);
		return name.endsWith(".md") || name.endsWith(".markdown");
	}

	private MuPDFCore openCore(Uri uri, long size, String mimetype) throws IOException {
		ContentResolver cr = getContentResolver();

		Log.i(APP, "Opening document " + uri);

		InputStream is = cr.openInputStream(uri);
		byte[] buf = null;
		int used = -1;
		try {
			final int limit = 8 * 1024 * 1024;
			if (size < 0) { // size is unknown
				buf = new byte[limit];
				used = is.read(buf);
				boolean atEOF = is.read() == -1;
				if (used < 0 || (used == limit && !atEOF)) // no or partial data
					buf = null;
			} else if (size <= limit) { // size is known and below limit
				buf = new byte[(int) size];
				used = is.read(buf);
				if (used < 0 || used < size) // no or partial data
					buf = null;
			}
			if (buf != null && buf.length != used) {
				byte[] newbuf = new byte[used];
				System.arraycopy(buf, 0, newbuf, 0, used);
				buf = newbuf;
			}
		} catch (OutOfMemoryError e) {
			buf = null;
		} finally {
			is.close();
		}

		if (buf != null) {
			Log.i(APP, "  Opening document from memory buffer of size " + buf.length);
			return openBuffer(buf, mimetype);
		} else {
			Log.i(APP, "  Opening document from stream");
			return openStream(new ContentInputStream(cr, uri, size), mimetype);
		}
	}

	private void showCannotOpenDialog(String reason) {
		Resources res = getResources();
		AlertDialog alert = mAlertBuilder.create();
		setTitle(String.format(Locale.ROOT, res.getString(R.string.cannot_open_document_Reason), reason));
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						finish();
					}
				});
		alert.show();
	}

	/** Called when the activity is first created. */
	@Override
	public void onCreate(final Bundle savedInstanceState)
	{
		super.onCreate(savedInstanceState);

		requestWindowFeature(Window.FEATURE_NO_TITLE);
		applySystemBars();

		DisplayMetrics metrics = new DisplayMetrics();
		getWindowManager().getDefaultDisplay().getMetrics(metrics);
		mDisplayDPI = (int)metrics.densityDpi;

		mAlertBuilder = new AlertDialog.Builder(this);

		if (core == null) {
			if (savedInstanceState != null && savedInstanceState.containsKey("DocTitle")) {
				mDocTitle = savedInstanceState.getString("DocTitle");
			}
		}
		if (core == null) {
			Intent intent = getIntent();
			SeekableInputStream file;

			mReturnToLibraryActivity = intent.getIntExtra(getComponentName().getPackageName() + ".ReturnToLibraryActivity", 0) != 0;

			if (Intent.ACTION_VIEW.equals(intent.getAction())) {
				Uri uri = intent.getData();
				String mimetype = getIntent().getType();

				if (uri == null)  {
					showCannotOpenDialog("No document uri to open");
					return;
				}

				mDocKey = uri.toString();

				Log.i(APP, "OPEN URI " + uri.toString());
				Log.i(APP, "  MAGIC (Intent) " + mimetype);

				mDocTitle = null;
				long size = -1;
				Cursor cursor = null;

				try {
					cursor = getContentResolver().query(uri, null, null, null, null);
					if (cursor != null && cursor.moveToFirst()) {
						int idx;

						idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
						if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_STRING)
							mDocTitle = cursor.getString(idx);

						idx = cursor.getColumnIndex(OpenableColumns.SIZE);
						if (idx >= 0 && cursor.getType(idx) == Cursor.FIELD_TYPE_INTEGER)
							size = cursor.getLong(idx);

						if (size == 0)
							size = -1;
					}
				} catch (Exception x) {
					// Ignore any exception and depend on default values for title
					// and size (unless one was decoded
				} finally {
					if (cursor != null)
						cursor.close();
				}

				Log.i(APP, "  NAME " + mDocTitle);
				Log.i(APP, "  SIZE " + size);

				if (mimetype == null || mimetype.equals("application/octet-stream")) {
					mimetype = getContentResolver().getType(uri);
					Log.i(APP, "  MAGIC (Resolved) " + mimetype);
				}
				if (isMarkdown(mimetype, mDocTitle != null ? mDocTitle : uri.getLastPathSegment())) {
					/* Markdown has its own viewer, with math, diagrams and
					 * highlighted code. Pass the read grant on with it. */
					Log.i(APP, "  MAGIC (Markdown) handing over to MarkdownActivity");
					Intent md = new Intent(intent);
					md.setClass(this, MarkdownActivity.class);
					md.setDataAndType(uri, "text/markdown");
					md.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
					if (mDocTitle != null)
						md.putExtra(MarkdownActivity.EXTRA_TITLE, mDocTitle);
					startActivity(md);
					finish();
					return;
				}
				if (mimetype == null || mimetype.equals("application/octet-stream")) {
					mimetype = mDocTitle;
					Log.i(APP, "  MAGIC (Filename) " + mimetype);
				}

				mReading = ReadingSettings.load(this);
				mLayoutEM = mReading.layoutEm(DEFAULT_LAYOUT_EM);
				mAppliedCss = mReading.reflowCss(this);
				com.artifex.mupdf.fitz.Context.setUserCSS(mAppliedCss);
				if (intent.hasExtra(EXTRA_BOOKMARK)) {
					mHasPendingBookmark = true;
					mPendingBookmark = intent.getLongExtra(EXTRA_BOOKMARK, 0);
					intent.removeExtra(EXTRA_BOOKMARK);
				}

				try {
					core = openCore(uri, size, mimetype);
					SearchTaskResult.set(null);
				} catch (Exception x) {
					showCannotOpenDialog(x.toString());
					return;
				}
			}
			if (core != null && core.needsPassword()) {
				requestPassword(savedInstanceState);
				return;
			}
			if (core != null && core.countPages() == 0)
			{
				core = null;
			}
		}
		if (core == null)
		{
			AlertDialog alert = mAlertBuilder.create();
			alert.setTitle(R.string.cannot_open_document);
			alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.dismiss),
					new DialogInterface.OnClickListener() {
						public void onClick(DialogInterface dialog, int which) {
							finish();
						}
					});
			alert.setOnCancelListener(new OnCancelListener() {
				@Override
				public void onCancel(DialogInterface dialog) {
					finish();
				}
			});
			alert.show();
			return;
		}

		createUI(savedInstanceState);
	}

	public void requestPassword(final Bundle savedInstanceState) {
		mPasswordView = new EditText(this);
		mPasswordView.setInputType(EditorInfo.TYPE_TEXT_VARIATION_PASSWORD);
		mPasswordView.setTransformationMethod(new PasswordTransformationMethod());

		AlertDialog alert = mAlertBuilder.create();
		alert.setTitle(R.string.enter_password);
		alert.setView(mPasswordView);
		alert.setButton(AlertDialog.BUTTON_POSITIVE, getString(R.string.okay),
				new DialogInterface.OnClickListener() {
					public void onClick(DialogInterface dialog, int which) {
						if (core.authenticatePassword(mPasswordView.getText().toString())) {
							createUI(savedInstanceState);
						} else {
							requestPassword(savedInstanceState);
						}
					}
				});
		alert.setButton(AlertDialog.BUTTON_NEGATIVE, getString(R.string.cancel),
				new DialogInterface.OnClickListener() {

			public void onClick(DialogInterface dialog, int which) {
				finish();
			}
		});
		alert.show();
	}

	/* A new text size only needs a fresh layout, but MuPDF reads the rest of
	 * the reading settings (a stylesheet) only when it opens a document, so
	 * reopen it, keeping our place in the text. */
	private void applyReadingSettings() {
		if (core == null || mDocView == null)
			return;
		String css = mReading.reflowCss(this);
		if (!css.equals(mAppliedCss)) {
			try {
				getIntent().putExtra(EXTRA_BOOKMARK, core.bookmark(mDocView.getDisplayedViewIndex()));
			} catch (RuntimeException x) {
				/* reopen at the saved page instead */
			}
			recreate();
			return;
		}
		int em = mReading.layoutEm(DEFAULT_LAYOUT_EM);
		if (em != mLayoutEM) {
			mLayoutEM = em;
			relayoutDocument();
		}
	}

	public void relayoutDocument() {
		int loc = core.layout(mDocView.mCurrent, mLayoutW, mLayoutH, mLayoutEM);
		/* Reopened for new reading settings: go back to the same text. */
		if (mHasPendingBookmark) {
			mHasPendingBookmark = false;
			try {
				loc = core.pageForBookmark(mPendingBookmark);
			} catch (RuntimeException x) {
				/* keep the page we have */
			}
		}
		mFlatOutline = null;
		mChapterPages = null;
		mDocView.mHistory.clear();
		mDocView.refresh();
		mDocView.setDisplayedViewIndex(loc);
	}

	/* The system bars follow the toolbars: on screen together with them, so the
	 * page slider sits above the navigation bar, and gone when the document has
	 * the screen to itself. */
	protected void applySystemBars() {
		if (mButtonsVisible)
			Fullscreen.showSystemBars(getWindow());
		else
			Fullscreen.hideSystemBars(getWindow());
	}

	@Override
	public void onAttachedToWindow() {
		super.onAttachedToWindow();
		applySystemBars();
	}

	@Override
	public void onWindowFocusChanged(boolean hasFocus) {
		super.onWindowFocusChanged(hasFocus);
		if (hasFocus)
			applySystemBars();
	}

	protected void applyInsets(WindowInsets windowInsets) {
		systemInsets = Insets.NONE;
		Insets systemBarInsets = windowInsets.getInsets(WindowInsets.Type.systemBars());
		systemInsets = Insets.max(systemInsets, systemBarInsets);
		Insets cutoutInsets = windowInsets.getInsets(WindowInsets.Type.displayCutout());
		systemInsets = Insets.max(systemInsets, cutoutInsets);
		mTopBar.setPadding(0, systemInsets.top, 0, 0);
		mBottomBar.setPadding(0, 0, 0, systemInsets.bottom);
	}

	public void createUI(Bundle savedInstanceState) {
		if (core == null)
			return;

		// Now create the UI.
		// First create the document view
		mDocView = new ReaderView(this) {
			@Override
			protected void onMoveToChild(int i) {
				if (core == null)
					return;

				mPageNumberView.setText(String.format(Locale.ROOT, "%d / %d", i + 1, core.countPages()));
				mPageSlider.setMax((core.countPages() - 1) * mPageSliderRes);
				if (!mPageSliderTracking)
					mPageSlider.setProgress(i * mPageSliderRes);
				updateChapterButtons(i);
				super.onMoveToChild(i);
			}

			@Override
			protected void onTapMainDocArea() {
				if (!mButtonsVisible) {
					showButtons();
				} else {
					if (mTopBarMode == TopBarMode.Main)
						hideButtons();
				}
			}

			@Override
			protected void onSelectionStarted() {
				startSelectionActionMode();
			}

			/* Keep the floating toolbar beside the selection, and out of
			 * the way while a finger is still adjusting it. */
			@Override
			protected void onSelectionMoved(boolean dragging) {
				ActionMode mode = mSelectionActionMode;
				if (mode == null || Build.VERSION.SDK_INT < 23)
					return;
				/* Hidden while a finger is on it, and back the moment it
				 * lifts: a hide otherwise lasts its full two seconds. */
				mode.hide(dragging ? ViewConfiguration.getDefaultActionModeHideDuration() : 0);
				mode.invalidateContentRect();
			}

			@Override
			protected void onSelectionCleared() {
				/* Null the field first: finishing the mode calls back in here. */
				ActionMode mode = mSelectionActionMode;
				mSelectionActionMode = null;
				if (mode != null)
					mode.finish();
			}

			@Override
			protected void onDocMotion() {
				hideButtons();
			}

			@Override
			public void onSizeChanged(int w, int h, int oldw, int oldh) {
				if (core.isReflowable()) {
					mLayoutW = w * 72 / mDisplayDPI;
					mLayoutH = h * 72 / mDisplayDPI;
					relayoutDocument();
				} else {
					refresh();
				}
			}
		};
		mDocView.setAdapter(new PageAdapter(this, core));

		mSearchTask = new SearchTask(this, core) {
			@Override
			protected void onTextFound(SearchTaskResult result) {
				SearchTaskResult.set(result);
				mSearchText.setTextColor(Color.WHITE);
				// Ask the ReaderView to move to the resulting page
				mDocView.setDisplayedViewIndex(result.pageNumber);
				// Make the ReaderView act on the change to SearchTaskResult
				// via overridden onChildSetup method.
				mDocView.resetupChildren();
			}

			@Override
			protected void onTextNotFound() {
				mSearchText.setTextColor(0xffff8a80);
			}
		};

		// Make the buttons overlay, and store all its
		// controls in variables
		makeButtonsView();

		// Set up the page slider
		int smax = Math.max(core.countPages()-1,1);
		mPageSliderRes = ((10 + smax - 1)/smax) * 2;

		// Set the file-name text
		String docTitle = core.getTitle();
		if (docTitle != null)
			mDocNameView.setText(docTitle);
		else
			mDocNameView.setText(mDocTitle);

		// Activate the seekbar
		mPageSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onStopTrackingTouch(SeekBar seekBar) {
				mPageSliderTracking = false;
				mDocView.setDisplayedViewIndex((seekBar.getProgress()+mPageSliderRes/2)/mPageSliderRes);
			}

			public void onStartTrackingTouch(SeekBar seekBar) {
				/* Remember where we came from once, not once per page we scrub past. */
				mDocView.pushHistory();
				mPageSliderTracking = true;
			}

			public void onProgressChanged(SeekBar seekBar, int progress,
					boolean fromUser) {
				int page = (progress+mPageSliderRes/2)/mPageSliderRes;
				updatePageNumView(page);
				/* Follow the slider as it is dragged rather than waiting for the
				 * finger to lift. */
				if (fromUser && page != mDocView.getDisplayedViewIndex())
					mDocView.setDisplayedViewIndex(page);
			}
		});

		// Activate the search-preparing button
		mSearchButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				searchModeOn();
			}
		});

		mSearchClose.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				searchModeOff();
			}
		});

		// Search invoking buttons are disabled while there is no text specified
		mSearchBack.setEnabled(false);
		mSearchFwd.setEnabled(false);
		mSearchBack.setColorFilter(Color.argb(255, 128, 128, 128));
		mSearchFwd.setColorFilter(Color.argb(255, 128, 128, 128));

		// React to interaction with the text widget
		mSearchText.addTextChangedListener(new TextWatcher() {

			public void afterTextChanged(Editable s) {
				boolean haveText = s.toString().length() > 0;
				setButtonEnabled(mSearchBack, haveText);
				setButtonEnabled(mSearchFwd, haveText);

				// Remove any previous search results
				if (SearchTaskResult.get() != null && !mSearchText.getText().toString().equals(SearchTaskResult.get().txt)) {
					SearchTaskResult.set(null);
					mDocView.resetupChildren();
				}

				/* Search as the user types, once they pause, from the page
				 * they were on when they started typing. */
				mSearchText.setTextColor(Color.WHITE);
				mHandler.removeCallbacks(mLiveSearch);
				if (haveText) {
					if (mLiveSearchOrigin < 0)
						mLiveSearchOrigin = mDocView.getDisplayedViewIndex();
					mHandler.postDelayed(mLiveSearch, LIVE_SEARCH_DELAY);
				} else {
					mSearchTask.stop();
					mLiveSearchOrigin = -1;
				}
			}
			public void beforeTextChanged(CharSequence s, int start, int count,
					int after) {}
			public void onTextChanged(CharSequence s, int start, int before,
					int count) {}
		});

		//React to Done button on keyboard
		mSearchText.setOnEditorActionListener(new TextView.OnEditorActionListener() {
			public boolean onEditorAction(TextView v, int actionId, KeyEvent event) {
				if (actionId == EditorInfo.IME_ACTION_DONE)
					search(1);
				return false;
			}
		});

		mSearchText.setOnKeyListener(new View.OnKeyListener() {
			public boolean onKey(View v, int keyCode, KeyEvent event) {
				if (event.getAction() == KeyEvent.ACTION_DOWN && keyCode == KeyEvent.KEYCODE_ENTER)
					search(1);
				return false;
			}
		});

		// Activate search invoking buttons
		mSearchBack.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(-1);
			}
		});
		mSearchFwd.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				search(1);
			}
		});


		if (core.isReflowable()) {
			mLayoutButton.setVisibility(View.VISIBLE);
			mLayoutButton.setContentDescription(getString(R.string.reading_settings));
			mLayoutButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					ReadingSettingsDialog.show(DocumentActivity.this, mReading,
						s -> { /* applied when the dialog closes */ },
						() -> applyReadingSettings());
				}
			});
		}

		setupChapterButtons();

		if (core.hasOutline()) {
			mOutlineButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					boolean outlineTruncated = false;
					if (mFlatOutline == null)
					{
						mFlatOutline = core.getOutline();
						outlineTruncated = core.wasOutlineTruncated();
					}
					if (mFlatOutline != null) {
						Intent intent = new Intent(DocumentActivity.this, OutlineActivity.class);
						Bundle bundle = new Bundle();
						bundle.putInt("POSITION", mDocView.getDisplayedViewIndex());
						bundle.putSerializable("OUTLINE", mFlatOutline);
						intent.putExtra("PALLETBUNDLE", Pallet.sendBundle(bundle));
						startActivityForResult(intent, OUTLINE_REQUEST);
						if (outlineTruncated)
							Toast.makeText(DocumentActivity.this, "Outline too large, truncated", Toast.LENGTH_SHORT).show();
					}
				}
			});
		} else {
			mOutlineButton.setVisibility(View.GONE);
		}

		/* We can only hand a content: uri on to another app; anything else
		 * (a file: uri, or a document we were given as a stream) is not ours
		 * to share. */
		Uri shareUri = getIntent().getData();
		if (shareUri != null && ContentResolver.SCHEME_CONTENT.equals(shareUri.getScheme())) {
			mShareButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					shareDocument();
				}
			});
		} else {
			mShareButton.setVisibility(View.GONE);
		}

		if (shareUri != null && ContentResolver.SCHEME_CONTENT.equals(shareUri.getScheme())) {
			mDownloadButton.setOnClickListener(new View.OnClickListener() {
				public void onClick(View v) {
					saveDocumentToDownloads();
				}
			});
		} else {
			mDownloadButton.setVisibility(View.GONE);
		}

		// Reenstate last state if it was recorded
		SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);

		/* Scrolling mode is an app-wide choice, not per document. */
		mDocView.setContinuousScroll(prefs.getBoolean(PREF_CONTINUOUS_SCROLL, false));
		updateScrollModeButton();
		mScrollModeButton.setOnClickListener(new View.OnClickListener() {
			public void onClick(View v) {
				boolean continuous = !mDocView.isContinuousScroll();
				mDocView.setContinuousScroll(continuous);
				getPreferences(Context.MODE_PRIVATE).edit()
					.putBoolean(PREF_CONTINUOUS_SCROLL, continuous).apply();
				updateScrollModeButton();
				Toast.makeText(DocumentActivity.this,
					continuous ? R.string.scroll_mode_continuous : R.string.scroll_mode_paged,
					Toast.LENGTH_SHORT).show();
			}
		});

		mDocView.setDisplayedViewIndex(prefs.getInt("page"+mDocKey, 0));

		if (savedInstanceState == null || !savedInstanceState.getBoolean("ButtonsHidden", false))
			showButtons();

		if(savedInstanceState != null && savedInstanceState.getBoolean("SearchMode", false))
			searchModeOn();

		mTopBar.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
			public WindowInsets onApplyWindowInsets(View v, WindowInsets windowInsets)
			{
				applyInsets(windowInsets);
				return WindowInsets.CONSUMED;
			}
		});

		if (Build.VERSION.SDK_INT >= 29)
			mBottomBar.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
				public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop, int oldRight, int oldBottom) {
					View parent = (View) v.getParent();
					android.graphics.Rect exclusion;

					exclusion = new android.graphics.Rect(0, 0, v.getWidth(), v.getHeight());
					v.setSystemGestureExclusionRects(Collections.singletonList(exclusion));

					int extended_top = parent.getHeight() - (int) (EXCLUSION_HEIGHT_FACTOR * v.getHeight());
					exclusion = new android.graphics.Rect(0, extended_top, parent.getWidth(), parent.getHeight());
					parent.setSystemGestureExclusionRects(Collections.singletonList(exclusion));
				}
			});

		// Stick the document view and the buttons overlay into a parent view
		RelativeLayout layout = new RelativeLayout(this);
		layout.setBackgroundColor(Color.DKGRAY);
		layout.addView(mDocView);
		layout.addView(mButtonsView);
		setContentView(layout);
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		switch (requestCode) {
		case OUTLINE_REQUEST:
			if (resultCode >= RESULT_FIRST_USER && mDocView != null) {
				mDocView.pushHistory();
				mDocView.setDisplayedViewIndex(resultCode-RESULT_FIRST_USER);
			}
			break;
		}
		super.onActivityResult(requestCode, resultCode, data);
	}

	@Override
	protected void onSaveInstanceState(Bundle outState) {
		super.onSaveInstanceState(outState);

		if (mDocKey != null && mDocView != null) {
			if (mDocTitle != null)
				outState.putString("DocTitle", mDocTitle);

			// Store current page in the prefs against the file name,
			// so that we can pick it up each time the file is loaded
			// Other info is needed only for screen-orientation change,
			// so it can go in the bundle
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+mDocKey, mDocView.getDisplayedViewIndex());
			edit.apply();
		}

		if (!mButtonsVisible)
			outState.putBoolean("ButtonsHidden", true);

		if (mTopBarMode == TopBarMode.Search)
			outState.putBoolean("SearchMode", true);
	}

	@Override
	protected void onPause() {
		super.onPause();

		if (mSearchTask != null)
			mSearchTask.stop();

		if (mDocKey != null && mDocView != null) {
			SharedPreferences prefs = getPreferences(Context.MODE_PRIVATE);
			SharedPreferences.Editor edit = prefs.edit();
			edit.putInt("page"+mDocKey, mDocView.getDisplayedViewIndex());
			edit.apply();
		}
	}

	public void onDestroy()
	{
		if (mDocView != null) {
			mDocView.applyToChildren(new ReaderView.ViewMapper() {
				@Override
				public void applyToView(View view) {
					((PageView)view).releaseBitmaps();
				}
			});
		}
		if (core != null)
			core.onDestroy();
		core = null;
		super.onDestroy();
	}

	/* The button shows the mode in use: one page, or a stack of pages. */
	private void updateScrollModeButton() {
		mScrollModeButton.setImageResource(mDocView.isContinuousScroll()
			? R.drawable.ic_scroll_continuous_white_24dp
			: R.drawable.ic_scroll_paged_white_24dp);
	}

	private void setButtonEnabled(ImageButton button, boolean enabled) {
		button.setEnabled(enabled);
		button.setColorFilter(enabled ? Color.argb(255, 255, 255, 255) : Color.argb(255, 128, 128, 128));
	}


	private void showButtons() {
		if (core == null)
			return;
		if (!mButtonsVisible) {
			mButtonsVisible = true;
			applySystemBars();
			// Update page number text and slider
			int index = mDocView.getDisplayedViewIndex();
			updatePageNumView(index);
			mPageSlider.setMax((core.countPages()-1)*mPageSliderRes);
			mPageSlider.setProgress(index * mPageSliderRes);
			if (mTopBarMode == TopBarMode.Search) {
				mSearchText.requestFocus();
				showKeyboard();
			}

			Animation anim = new TranslateAnimation(0, 0, -(mTopBarSwitcher.getHeight() + systemInsets.top), 0);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mTopBarSwitcher.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {}
			});
			mTopBarSwitcher.startAnimation(anim);

			anim = new TranslateAnimation(0, 0, mBottomBar.getHeight() + systemInsets.bottom, 0);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mBottomBar.setVisibility(View.VISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mPageNumberView.setVisibility(View.VISIBLE);
				}
			});
			mBottomBar.startAnimation(anim);
		}
	}

	private void hideButtons() {
		if (mButtonsVisible) {
			mButtonsVisible = false;
			applySystemBars();
			hideKeyboard();

			Animation anim = new TranslateAnimation(0, 0, 0, -(mTopBarSwitcher.getHeight() + systemInsets.top));
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mTopBarSwitcher.setVisibility(View.INVISIBLE);
				}
			});
			mTopBarSwitcher.startAnimation(anim);

			anim = new TranslateAnimation(0, 0, 0, mBottomBar.getHeight() + systemInsets.bottom);
			anim.setDuration(200);
			anim.setAnimationListener(new Animation.AnimationListener() {
				public void onAnimationStart(Animation animation) {
					mPageNumberView.setVisibility(View.INVISIBLE);
				}
				public void onAnimationRepeat(Animation animation) {}
				public void onAnimationEnd(Animation animation) {
					mBottomBar.setVisibility(View.INVISIBLE);
				}
			});
			mBottomBar.startAnimation(anim);
		}
	}

	private void searchModeOn() {
		if (mTopBarMode != TopBarMode.Search) {
			mTopBarMode = TopBarMode.Search;
			//Focus on EditTextWidget
			mSearchText.requestFocus();
			showKeyboard();
			mActionBar.setVisibility(View.GONE);
			mSearchBar.setVisibility(View.VISIBLE);
		}
	}

	private void searchModeOff() {
		if (mTopBarMode == TopBarMode.Search) {
			mTopBarMode = TopBarMode.Main;
			hideKeyboard();
			mHandler.removeCallbacks(mLiveSearch);
			mLiveSearchOrigin = -1;
			if (mSearchTask != null)
				mSearchTask.stop();
			mActionBar.setVisibility(View.VISIBLE);
			mSearchBar.setVisibility(View.GONE);
			SearchTaskResult.set(null);
			// Make the ReaderView act on the change to mSearchTaskResult
			// via overridden onChildSetup method.
			mDocView.resetupChildren();
		}
	}

	/* The pages the table of contents points at, in order and without
	 * repeats: the stops for the previous/next chapter buttons. Worked out
	 * when first needed, and again after a re-layout moves them. */
	private int[] mChapterPages;
	private ImageButton mPrevChapterButton;
	private ImageButton mNextChapterButton;

	private int[] chapterPages() {
		if (mChapterPages == null) {
			java.util.TreeSet<Integer> pages = new java.util.TreeSet<>();
			try {
				if (core != null && core.hasOutline())
					for (OutlineActivity.Item item : core.getOutline())
						if (item.page >= 0)
							pages.add(item.page);
			} catch (RuntimeException x) {
				Log.e(APP, "Cannot read outline: " + x);
			}
			mChapterPages = new int[pages.size()];
			int i = 0;
			for (int page : pages)
				mChapterPages[i++] = page;
		}
		return mChapterPages;
	}

	/* The page of the next chapter after this one, or of the start of this
	 * chapter (or the one before, from its first page) going back; -1 if
	 * there is none that way. */
	private int chapterPage(int current, int direction) {
		int[] pages = chapterPages();
		if (direction > 0) {
			for (int p : pages)
				if (p > current)
					return p;
		} else {
			for (int i = pages.length - 1; i >= 0; i--)
				if (pages[i] < current)
					return pages[i];
		}
		return -1;
	}

	private void setupChapterButtons() {
		if (chapterPages().length == 0) {
			mPrevChapterButton.setVisibility(View.GONE);
			mNextChapterButton.setVisibility(View.GONE);
			return;
		}
		mPrevChapterButton.setOnClickListener(v -> goToChapter(-1));
		mNextChapterButton.setOnClickListener(v -> goToChapter(1));
		updateChapterButtons(mDocView.getDisplayedViewIndex());
	}

	private void goToChapter(int direction) {
		int page = chapterPage(mDocView.getDisplayedViewIndex(), direction);
		if (page < 0)
			return;
		mDocView.pushHistory();
		mDocView.setDisplayedViewIndex(page);
	}

	private void updateChapterButtons(int current) {
		if (mPrevChapterButton == null || mPrevChapterButton.getVisibility() != View.VISIBLE)
			return;
		setButtonEnabled(mPrevChapterButton, chapterPage(current, -1) >= 0);
		setButtonEnabled(mNextChapterButton, chapterPage(current, 1) >= 0);
	}

	private void updatePageNumView(int index) {
		if (core == null)
			return;
		mPageNumberView.setText(String.format(Locale.ROOT, "%d / %d", index + 1, core.countPages()));
	}

	/* A floating action mode hovers over the page. The classic one is a bar
	 * that shrinks the content, which relays out the document under the
	 * finger that is still drawing the selection. */
	private void startSelectionActionMode() {
		if (mSelectionActionMode != null)
			return;
		if (Build.VERSION.SDK_INT >= 23)
			mSelectionActionMode = mDocView.startActionMode(new FloatingSelectionCallback(),
				ActionMode.TYPE_FLOATING);
		else
			mSelectionActionMode = startActionMode(new SelectionCallback());
	}

	/* The same choices Android's own text views offer for read-only text:
	 * Copy, Select all, Share, Web search, and then whatever apps on the
	 * phone can act on text (Translate and the like). The floating toolbar
	 * shows the first few and puts the rest under its overflow button. */
	private boolean createSelectionMenu(Menu menu) {
		menu.add(0, COPY_MENU_ITEM, 0, android.R.string.copy)
			.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, SELECT_ALL_MENU_ITEM, 1, android.R.string.selectAll)
			.setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
		menu.add(0, SHARE_MENU_ITEM, 2, R.string.share_text)
			.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
		menu.add(0, WEB_SEARCH_MENU_ITEM, 3, R.string.web_search)
			.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);

		if (Build.VERSION.SDK_INT >= 23) {
			PackageManager pm = getPackageManager();
			Intent query = new Intent(Intent.ACTION_PROCESS_TEXT).setType("text/plain");
			int order = 10;
			for (android.content.pm.ResolveInfo info : pm.queryIntentActivities(query, 0)) {
				if (info.activityInfo == null || getPackageName().equals(info.activityInfo.packageName))
					continue;
				Intent intent = new Intent(Intent.ACTION_PROCESS_TEXT)
					.setType("text/plain")
					.setClassName(info.activityInfo.packageName, info.activityInfo.name)
					.putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true);
				menu.add(0, Menu.NONE, order++, info.loadLabel(pm))
					.setIntent(intent)
					.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
			}
		}
		return true;
	}

	private boolean selectionItemClicked(ActionMode mode, MenuItem item) {
		int id = item.getItemId();
		if (id == COPY_MENU_ITEM) {
			copySelectionToClipboard();
			mode.finish();
			return true;
		}
		if (id == SELECT_ALL_MENU_ITEM) {
			mDocView.selectAll();
			if (Build.VERSION.SDK_INT >= 23)
				mode.invalidateContentRect();
			return true;
		}

		String text = mDocView.getSelectedText();
		if (text == null || text.length() == 0) {
			Toast.makeText(this, R.string.no_text_selected, Toast.LENGTH_SHORT).show();
			return true;
		}
		Intent intent = null;
		if (id == SHARE_MENU_ITEM) {
			intent = Intent.createChooser(new Intent(Intent.ACTION_SEND)
				.setType("text/plain")
				.putExtra(Intent.EXTRA_TEXT, text), null);
		} else if (id == WEB_SEARCH_MENU_ITEM) {
			intent = new Intent(Intent.ACTION_WEB_SEARCH)
				.putExtra(android.app.SearchManager.QUERY, text);
		} else if (item.getIntent() != null && Build.VERSION.SDK_INT >= 23) {
			intent = new Intent(item.getIntent()).putExtra(Intent.EXTRA_PROCESS_TEXT, text);
		}
		if (intent == null)
			return false;
		try {
			startActivity(intent);
		} catch (Exception x) {
			Toast.makeText(this, R.string.not_supported, Toast.LENGTH_SHORT).show();
		}
		mode.finish();
		return true;
	}

	private void selectionModeDestroyed() {
		mSelectionActionMode = null;
		mDocView.clearSelection();
	}

	private class SelectionCallback implements ActionMode.Callback {
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			return createSelectionMenu(menu);
		}
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			return selectionItemClicked(mode, item);
		}
		public void onDestroyActionMode(ActionMode mode) {
			selectionModeDestroyed();
		}
	}

	/* Only ever instantiated on API 23 and up, where Callback2 exists. */
	private class FloatingSelectionCallback extends ActionMode.Callback2 {
		public boolean onCreateActionMode(ActionMode mode, Menu menu) {
			return createSelectionMenu(menu);
		}
		public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
			return false;
		}
		public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
			return selectionItemClicked(mode, item);
		}
		public void onDestroyActionMode(ActionMode mode) {
			selectionModeDestroyed();
		}
		/* Float the toolbar next to the selection rather than at the top
		 * of the screen. */
		@Override
		public void onGetContentRect(ActionMode mode, View view, android.graphics.Rect outRect) {
			android.graphics.Rect r = mDocView != null ? mDocView.getSelectionBounds() : null;
			if (r == null) {
				super.onGetContentRect(mode, view, outRect);
				return;
			}
			/* Selection bounds are in the document view's coordinates;
			 * the rect is wanted in those of the view given. */
			int[] from = new int[2], to = new int[2];
			mDocView.getLocationInWindow(from);
			view.getLocationInWindow(to);
			r.offset(from[0] - to[0], from[1] - to[1]);
			outRect.set(r);
		}
	}

	private void copySelectionToClipboard() {
		String text = mDocView.getSelectedText();
		if (text == null || text.length() == 0) {
			Toast.makeText(this, R.string.no_text_selected, Toast.LENGTH_SHORT).show();
			return;
		}
		ClipboardManager clipboard = (ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
		clipboard.setPrimaryClip(ClipData.newPlainText(mDocTitle, text));
		Toast.makeText(this, R.string.text_copied, Toast.LENGTH_SHORT).show();
	}

	/* Drop a copy of the document into the shared Downloads folder. */
	private void saveDocumentToDownloads() {
		final Uri uri = getIntent().getData();
		if (uri == null)
			return;

		/* Before scoped storage we have to ask before writing to Downloads. */
		if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this,
				Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
			ActivityCompat.requestPermissions(this,
				new String[] { Manifest.permission.WRITE_EXTERNAL_STORAGE }, SAVE_PERMISSION_REQUEST);
			return;
		}

		final String name = mDocTitle != null ? mDocTitle : "document.pdf";

		/* The document can be tens of megabytes, so copy it off the main thread. */
		new AsyncTask<Void, Void, String>() {
			@Override
			protected String doInBackground(Void... params) {
				return copyToDownloads(uri, name);
			}

			@Override
			protected void onPostExecute(String error) {
				if (error == null)
					Toast.makeText(DocumentActivity.this,
						getString(R.string.saved_to_downloads, name), Toast.LENGTH_LONG).show();
				else
					Toast.makeText(DocumentActivity.this, error, Toast.LENGTH_LONG).show();
			}
		}.execute();
	}

	/* Returns null when the copy worked, or a message describing what failed. */
	private String copyToDownloads(Uri uri, String name) {
		InputStream in = null;
		OutputStream out = null;
		Uri pending = null;
		try {
			in = getContentResolver().openInputStream(uri);
			if (in == null)
				return getString(R.string.cannot_save_document);

			if (Build.VERSION.SDK_INT >= 29) {
				ContentValues values = new ContentValues();
				values.put(MediaStore.Downloads.DISPLAY_NAME, name);
				String mimetype = getContentResolver().getType(uri);
				if (mimetype != null)
					values.put(MediaStore.Downloads.MIME_TYPE, mimetype);
				values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);
				values.put(MediaStore.Downloads.IS_PENDING, 1);
				pending = getContentResolver().insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
				if (pending == null)
					return getString(R.string.cannot_save_document);
				out = getContentResolver().openOutputStream(pending);
			} else {
				File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
				dir.mkdirs();
				out = new FileOutputStream(uniqueFile(dir, name));
			}
			if (out == null)
				return getString(R.string.cannot_save_document);

			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0)
				out.write(buf, 0, n);
			out.flush();

			if (pending != null) {
				out.close();
				out = null;
				ContentValues values = new ContentValues();
				values.put(MediaStore.Downloads.IS_PENDING, 0);
				getContentResolver().update(pending, values, null, null);
				pending = null;
			}
			return null;
		} catch (Exception x) {
			Log.e(APP, "Error saving document: " + x);
			/* Do not leave a half written entry behind. */
			if (pending != null)
				getContentResolver().delete(pending, null, null);
			return x.getMessage() != null ? x.getMessage() : getString(R.string.cannot_save_document);
		} finally {
			try { if (in != null) in.close(); } catch (IOException x) { /* ignore */ }
			try { if (out != null) out.close(); } catch (IOException x) { /* ignore */ }
		}
	}

	private File uniqueFile(File dir, String name) {
		File file = new File(dir, name);
		if (!file.exists())
			return file;
		String base = name, ext = "";
		int dot = name.lastIndexOf('.');
		if (dot > 0) {
			base = name.substring(0, dot);
			ext = name.substring(dot);
		}
		for (int i = 1; ; i++) {
			file = new File(dir, base + " (" + i + ")" + ext);
			if (!file.exists())
				return file;
		}
	}

	@Override
	public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
		super.onRequestPermissionsResult(requestCode, permissions, grantResults);
		if (requestCode == SAVE_PERMISSION_REQUEST && grantResults.length > 0
				&& grantResults[0] == PackageManager.PERMISSION_GRANTED)
			saveDocumentToDownloads();
	}

	/* Hand the document off to whatever the user picks from the share sheet:
	 * mail, a messenger, cloud storage, and so on. The receiving app gets a
	 * temporary read grant for our uri, so nothing has to be copied. */
	private void shareDocument() {
		Uri uri = getIntent().getData();
		if (uri == null)
			return;

		String mimetype = getIntent().getType();
		if (mimetype == null)
			mimetype = getContentResolver().getType(uri);
		if (mimetype == null)
			mimetype = "application/octet-stream";

		Intent intent = new Intent(Intent.ACTION_SEND);
		intent.setType(mimetype);
		intent.putExtra(Intent.EXTRA_STREAM, uri);
		if (mDocTitle != null)
			intent.putExtra(Intent.EXTRA_SUBJECT, mDocTitle);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

		try {
			startActivity(Intent.createChooser(intent, getString(R.string.share_document)));
		} catch (Exception x) {
			Log.e(APP, "Error sharing document: " + x);
			Toast.makeText(this, R.string.not_supported, Toast.LENGTH_SHORT).show();
		}
	}

	private void makeButtonsView() {
		mButtonsView = getLayoutInflater().inflate(R.layout.document_activity, null);
		mDocNameView = (TextView)mButtonsView.findViewById(R.id.docNameText);
		mPageSlider = (SeekBar)mButtonsView.findViewById(R.id.pageSlider);
		mPrevChapterButton = (ImageButton)mButtonsView.findViewById(R.id.prevChapterButton);
		mNextChapterButton = (ImageButton)mButtonsView.findViewById(R.id.nextChapterButton);
		mPageNumberView = (TextView)mButtonsView.findViewById(R.id.pageNumber);
		mSearchButton = (ImageButton)mButtonsView.findViewById(R.id.searchButton);
		mOutlineButton = (ImageButton)mButtonsView.findViewById(R.id.outlineButton);
		mShareButton = (ImageButton)mButtonsView.findViewById(R.id.shareButton);
		mTopBarSwitcher = (ViewAnimator)mButtonsView.findViewById(R.id.switcher);
		mTopBar = (LinearLayout)mButtonsView.findViewById(R.id.topBar);
		mActionBar = (LinearLayout)mButtonsView.findViewById(R.id.actionBar);
		mSearchBar = (LinearLayout)mButtonsView.findViewById(R.id.searchBar);
		mBottomBar = (LinearLayout)mButtonsView.findViewById(R.id.bottomBar);
		mSearchBack = (ImageButton)mButtonsView.findViewById(R.id.searchBack);
		mSearchFwd = (ImageButton)mButtonsView.findViewById(R.id.searchForward);
		mSearchClose = (ImageButton)mButtonsView.findViewById(R.id.searchClose);
		mSearchText = (EditText)mButtonsView.findViewById(R.id.searchText);
		mDownloadButton = (ImageButton)mButtonsView.findViewById(R.id.downloadButton);
		mLayoutButton = mButtonsView.findViewById(R.id.layoutButton);
		mScrollModeButton = (ImageButton)mButtonsView.findViewById(R.id.scrollModeButton);
		mTopBarSwitcher.setVisibility(View.INVISIBLE);
		mPageNumberView.setVisibility(View.INVISIBLE);
		mActionBar.setVisibility(View.VISIBLE);
		mTopBar.setVisibility(View.VISIBLE);
		mSearchBar.setVisibility(View.GONE);
		mBottomBar.setVisibility(View.INVISIBLE);
	}

	private void showKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(mSearchText, 0);
	}

	private void hideKeyboard() {
		InputMethodManager imm = (InputMethodManager)getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
	}

	private static final int LIVE_SEARCH_DELAY = 400;
	private int mLiveSearchOrigin = -1;
	private final Runnable mLiveSearch = new Runnable() {
		public void run() {
			if (mSearchTask == null || mLiveSearchOrigin < 0)
				return;
			String text = mSearchText.getText().toString();
			if (text.length() > 0)
				mSearchTask.go(text, 1, mLiveSearchOrigin, -1, true);
		}
	};

	private void search(int direction) {
		mHandler.removeCallbacks(mLiveSearch);
		mLiveSearchOrigin = -1;
		hideKeyboard();
		int displayPage = mDocView.getDisplayedViewIndex();
		SearchTaskResult r = SearchTaskResult.get();
		int searchPage = r != null ? r.pageNumber : -1;
		mSearchTask.go(mSearchText.getText().toString(), direction, displayPage, searchPage);
	}

	@Override
	public boolean onSearchRequested() {
		if (mButtonsVisible && mTopBarMode == TopBarMode.Search) {
			hideButtons();
		} else {
			showButtons();
			searchModeOn();
		}
		return super.onSearchRequested();
	}

	@Override
	public boolean onPrepareOptionsMenu(Menu menu) {
		if (mButtonsVisible && mTopBarMode != TopBarMode.Search) {
			hideButtons();
		} else {
			showButtons();
			searchModeOff();
		}
		return super.onPrepareOptionsMenu(menu);
	}

	@Override
	protected void onStart() {
		super.onStart();
	}

	@Override
	protected void onStop() {
		super.onStop();
	}

	@Override
	public void onBackPressed() {
		if (mDocView == null || (mDocView != null && !mDocView.popHistory())) {
			super.onBackPressed();
			if (mReturnToLibraryActivity) {
				Intent intent = getPackageManager().getLaunchIntentForPackage(getComponentName().getPackageName());
				startActivity(intent);
			}
		}
	}
}
