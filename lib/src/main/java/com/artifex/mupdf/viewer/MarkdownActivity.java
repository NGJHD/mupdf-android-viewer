package com.artifex.mupdf.viewer;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.Log;
import android.view.KeyEvent;
import android.view.View;
import android.view.Window;
import android.view.WindowInsets;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputMethodManager;
import android.webkit.JavascriptInterface;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ViewAnimator;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/* Shows Markdown in a WebView: markdown-it with tables, footnotes, task lists
 * and emoji, KaTeX for math, highlight.js for code and Mermaid for diagrams,
 * all bundled under assets/markdown. The page is served from a fake https
 * origin by this activity, so it has no file or network access of its own. */
public class MarkdownActivity extends Activity
{
	private static final String APP = "MuPDF";

	public static final String EXTRA_TITLE = "com.artifex.mupdf.viewer.MarkdownActivity.TITLE";

	/* Reserved by Android for exactly this use: it never resolves to a real
	 * server, and requests to it are answered from shouldInterceptRequest. */
	private static final String HOST = "appassets.androidplatform.net";
	private static final String PAGE_URL = "https://" + HOST + "/assets/markdown/index.html";

	private static final int MAX_SIZE = 16 * 1024 * 1024;
	private static final int FOLDER_REQUEST = 1;
	private static final int CONTENTS_REQUEST = 2;
	private static final String POSITIONS = "markdown_positions";

	private WebView mWebView;
	private View mRoot;
	private ViewAnimator mSwitcher;
	private EditText mSearchText;
	private TextView mSearchCount;
	private View mLowerBar;
	private View mBottomBar;
	private TextView mPosition;
	private SeekBar mSlider;
	private ImageButton mPrevHeading, mNextHeading;
	private boolean mSliderTracking;

	private Uri mUri;
	private String mDocTitle;
	private String mSource;
	private ReadingSettings mSettings;

	private boolean mPageReady;
	private boolean mRendered;
	private boolean mBarsVisible = true;
	private boolean mSearching;
	private boolean mAskedForFolder;
	private boolean mReturnToLibraryActivity;
	private int mInsetTop, mInsetBottom;
	private String[] mHeadingIds;

	@Override
	protected void onCreate(Bundle savedInstanceState) {
		super.onCreate(savedInstanceState);
		requestWindowFeature(Window.FEATURE_NO_TITLE);
		setContentView(R.layout.markdown_activity);

		mSettings = ReadingSettings.load(this);
		mUri = getIntent().getData();
		if (mUri == null) {
			finish();
			return;
		}
		mReturnToLibraryActivity = getIntent().getIntExtra(getPackageName() + ".ReturnToLibraryActivity", 0) != 0;
		mDocTitle = getIntent().getStringExtra(EXTRA_TITLE);
		if (mDocTitle == null)
			mDocTitle = queryDisplayName(mUri);
		if (mDocTitle == null)
			mDocTitle = mUri.getLastPathSegment();

		mRoot = findViewById(R.id.mdRoot);
		mWebView = findViewById(R.id.mdWebView);
		mSwitcher = findViewById(R.id.mdSwitcher);
		mSearchText = findViewById(R.id.mdSearchText);
		mSearchCount = findViewById(R.id.mdSearchCount);
		mLowerBar = findViewById(R.id.mdLowerBar);
		mBottomBar = findViewById(R.id.mdBottomBar);
		mPosition = findViewById(R.id.mdPosition);
		mSlider = findViewById(R.id.mdSlider);
		mPrevHeading = findViewById(R.id.mdPrevHeading);
		mNextHeading = findViewById(R.id.mdNextHeading);
		mPosition.setText("0%");
		((TextView) findViewById(R.id.mdTitle)).setText(mDocTitle);

		setupWebView();
		setupButtons();
		setupPositionBar();
		setupInsets();
		applyBackground();
		loadDocument();
	}

	private String queryDisplayName(Uri uri) {
		Cursor cursor = null;
		try {
			cursor = getContentResolver().query(uri, null, null, null, null);
			if (cursor != null && cursor.moveToFirst()) {
				int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
				if (idx >= 0)
					return cursor.getString(idx);
			}
		} catch (Exception x) {
			/* fall back to the uri */
		} finally {
			if (cursor != null)
				cursor.close();
		}
		return null;
	}

	/* ---- The page ---- */

	private void setupWebView() {
		WebSettings ws = mWebView.getSettings();
		ws.setJavaScriptEnabled(true);
		ws.setAllowFileAccess(false);
		ws.setAllowContentAccess(false);
		ws.setSupportZoom(false);
		ws.setTextZoom(mSettings.textZoom);
		/* Smart text selection makes the selection toolbar wait for the
		 * system's text classifier, which on some phones takes a second or
		 * two. Skip it so the toolbar comes up straight away. */
		if (Build.VERSION.SDK_INT >= 27)
			mWebView.setTextClassifier(android.view.textclassifier.TextClassifier.NO_OP);
		mWebView.addJavascriptInterface(new Bridge(), "Reader");
		mWebView.setWebViewClient(new Client());
		mWebView.setFindListener(new WebView.FindListener() {
			public void onFindResultReceived(int active, int count, boolean done) {
				if (mSearchText.getText().length() == 0)
					mSearchCount.setText("");
				else if (count == 0)
					mSearchCount.setText("0/0");
				else
					mSearchCount.setText((active + 1) + "/" + count);
			}
		});
		mWebView.loadUrl(PAGE_URL);
	}

	private void applyBackground() {
		int bg = mSettings.backgroundColor(this);
		mRoot.setBackgroundColor(bg);
		mWebView.setBackgroundColor(bg);
	}

	private void js(String script) {
		mWebView.evaluateJavascript(script, null);
	}

	private void loadDocument() {
		new Thread(new Runnable() {
			public void run() {
				String text = null;
				String error = null;
				try {
					text = readText(mUri);
				} catch (Exception x) {
					Log.e(APP, "Error reading markdown: " + x);
					error = x.getMessage() != null ? x.getMessage() : x.toString();
				}
				final String result = text, failure = error;
				runOnUiThread(new Runnable() {
					public void run() {
						if (result == null) {
							showCannotOpen(failure);
							return;
						}
						mSource = result;
						maybeRender();
					}
				});
			}
		}).start();
	}

	private String readText(Uri uri) throws IOException {
		InputStream in = getContentResolver().openInputStream(uri);
		if (in == null)
			throw new IOException(getString(R.string.cannot_open_document));
		try {
			ByteArrayOutputStream out = new ByteArrayOutputStream();
			byte[] buf = new byte[65536];
			int n;
			while ((n = in.read(buf)) > 0) {
				out.write(buf, 0, n);
				if (out.size() > MAX_SIZE)
					throw new IOException(getString(R.string.document_too_large));
			}
			String text = new String(out.toByteArray(), StandardCharsets.UTF_8);
			if (text.startsWith("﻿"))
				text = text.substring(1);
			return text;
		} finally {
			in.close();
		}
	}

	private void showCannotOpen(String reason) {
		new AlertDialog.Builder(this)
			.setTitle(R.string.cannot_open_document)
			.setMessage(reason)
			.setPositiveButton(R.string.dismiss, (d, w) -> finish())
			.setOnCancelListener(d -> finish())
			.show();
	}

	/* Render once both the page has loaded and the text has been read,
	 * whichever finishes last. */
	private void maybeRender() {
		if (!mPageReady || mSource == null || mRendered)
			return;
		mRendered = true;
		float ratio = getSharedPreferences(POSITIONS, MODE_PRIVATE).getFloat(mUri.toString(), 0);
		js("MdReader.applySettings(" + mSettings.toJson(this) + ");"
			+ "MdReader.render(" + JSONObject.quote(mSource) + "," + ratio + ");");
	}

	private void rerender() {
		if (mRendered)
			js("MdReader.render(" + JSONObject.quote(mSource) + ", MdReader.scrollRatio());");
	}

	private void applySettings(ReadingSettings s) {
		mWebView.getSettings().setTextZoom(s.textZoom);
		applyBackground();
		if (mPageReady)
			js("MdReader.applySettings(" + s.toJson(this) + ");");
	}

	@Override
	protected void onPause() {
		super.onPause();
		if (mRendered) {
			final String key = mUri.toString();
			mWebView.evaluateJavascript("MdReader.scrollRatio()", value -> {
				try {
					float ratio = Float.parseFloat(value);
					getSharedPreferences(POSITIONS, MODE_PRIVATE).edit().putFloat(key, ratio).apply();
				} catch (Exception x) {
					/* page already gone */
				}
			});
		}
	}

	@Override
	protected void onDestroy() {
		if (mWebView != null) {
			mWebView.removeJavascriptInterface("Reader");
			mWebView.destroy();
		}
		super.onDestroy();
	}

	/* Called from the page's script, on a binder thread. */
	private class Bridge {
		@JavascriptInterface
		public void onReady() {
			runOnUiThread(() -> {
				mPageReady = true;
				pushInsets();
				maybeRender();
			});
		}

		@JavascriptInterface
		public void onRendered() {
		}

		@JavascriptInterface
		public void onPosition(final float ratio, final int headings, final boolean hasPrev, final boolean hasNext) {
			runOnUiThread(() -> showPosition(ratio, headings, hasPrev, hasNext));
		}

		@JavascriptInterface
		public void onTap() {
			runOnUiThread(() -> toggleBars());
		}

		@JavascriptInterface
		public void copy(final String text) {
			runOnUiThread(() -> {
				ClipboardManager clipboard = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
				clipboard.setPrimaryClip(ClipData.newPlainText(mDocTitle, text));
				/* Android 13 and later show their own confirmation. */
				if (Build.VERSION.SDK_INT < 33)
					Toast.makeText(MarkdownActivity.this, R.string.code_copied, Toast.LENGTH_SHORT).show();
			});
		}

		@JavascriptInterface
		public void imagesMissing() {
			runOnUiThread(() -> offerFolderAccess(false));
		}

		@JavascriptInterface
		public void openLink(final String href) {
			runOnUiThread(() -> openRelativeLink(href));
		}
	}

	private class Client extends WebViewClient {
		@Override
		public WebResourceResponse shouldInterceptRequest(WebView view, WebResourceRequest request) {
			return serve(request.getUrl());
		}

		@Override
		@SuppressWarnings("deprecation")
		public boolean shouldOverrideUrlLoading(WebView view, String url) {
			return followLink(Uri.parse(url));
		}

		@Override
		public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
			return followLink(request.getUrl());
		}
	}

	/* Everything the page loads comes through here: our bundled assets, and
	 * pictures next to the document. Anything else is refused, so a document
	 * cannot reach the network or other files. */
	private WebResourceResponse serve(Uri url) {
		if (HOST.equals(url.getHost())) {
			String path = url.getPath();
			if (path != null && path.startsWith("/assets/") && !path.contains("..")) {
				try {
					return new WebResourceResponse(mimeFor(path), null, getAssets().open(path.substring(8)));
				} catch (IOException x) {
					/* fall through to not found */
				}
			} else if ("/doc/".equals(path)) {
				String rel = url.getQueryParameter("p");
				InputStream in = rel != null ? openRelative(rel) : null;
				if (in != null)
					return new WebResourceResponse(mimeFor(rel), null, in);
			}
		}
		return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", null,
			new ByteArrayInputStream(new byte[0]));
	}

	private static String mimeFor(String name) {
		String n = name.toLowerCase(Locale.ROOT);
		int q = n.indexOf('?');
		if (q >= 0)
			n = n.substring(0, q);
		if (n.endsWith(".html")) return "text/html";
		if (n.endsWith(".js")) return "application/javascript";
		if (n.endsWith(".css")) return "text/css";
		if (n.endsWith(".woff2")) return "font/woff2";
		if (n.endsWith(".ttf")) return "font/ttf";
		if (n.endsWith(".png")) return "image/png";
		if (n.endsWith(".jpg") || n.endsWith(".jpeg")) return "image/jpeg";
		if (n.endsWith(".gif")) return "image/gif";
		if (n.endsWith(".webp")) return "image/webp";
		if (n.endsWith(".svg")) return "image/svg+xml";
		if (n.endsWith(".bmp")) return "image/bmp";
		if (n.endsWith(".avif")) return "image/avif";
		if (n.endsWith(".md") || n.endsWith(".markdown")) return "text/markdown";
		if (n.endsWith(".pdf")) return "application/pdf";
		return "application/octet-stream";
	}

	/* Links to the web, mail and so on go to whichever app handles them. */
	private boolean followLink(Uri url) {
		if (HOST.equals(url.getHost()))
			return !PAGE_URL.equals(url.toString());
		String scheme = url.getScheme() != null ? url.getScheme().toLowerCase(Locale.ROOT) : "";
		if (scheme.equals("http") || scheme.equals("https") || scheme.equals("mailto")
				|| scheme.equals("tel") || scheme.equals("geo")) {
			try {
				startActivity(new Intent(Intent.ACTION_VIEW, url));
			} catch (Exception x) {
				Toast.makeText(this, getString(R.string.cannot_open_link, url.toString()), Toast.LENGTH_SHORT).show();
			}
		}
		return true;
	}

	/* ---- Files next to the document ---- */

	/* Android hands us the document alone, without its folder. A path
	 * relative to it can still be read when the document is a file, or when
	 * the user has granted the folder it sits in (or one above it). */
	private Uri resolveRelative(String rel) {
		int cut = rel.indexOf('#');
		if (cut >= 0)
			rel = rel.substring(0, cut);
		cut = rel.indexOf('?');
		if (cut >= 0)
			rel = rel.substring(0, cut);
		rel = Uri.decode(rel);
		if (rel.isEmpty())
			return null;

		if ("file".equals(mUri.getScheme()) && mUri.getPath() != null) {
			File dir = new File(mUri.getPath()).getParentFile();
			return dir != null ? Uri.fromFile(new File(dir, rel)) : null;
		}

		String target = relativeDocumentId(rel);
		if (target == null)
			return null;
		String authority = mUri.getAuthority();
		for (UriPermission p : getContentResolver().getPersistedUriPermissions()) {
			Uri tree = p.getUri();
			if (!p.isReadPermission() || authority == null || !authority.equals(tree.getAuthority()))
				continue;
			String treeId;
			try {
				treeId = DocumentsContract.getTreeDocumentId(tree);
			} catch (Exception x) {
				continue;
			}
			boolean inside = target.equals(treeId)
				|| (treeId.endsWith(":") ? target.startsWith(treeId) : target.startsWith(treeId + "/"));
			if (inside)
				return DocumentsContract.buildDocumentUriUsingTree(tree, target);
		}
		return null;
	}

	/* Document ids of the storage providers are "root:path/to/file". Work out
	 * the id of a path relative to our document's folder, or null when the
	 * provider does not use paths. */
	private String relativeDocumentId(String rel) {
		if (!"content".equals(mUri.getScheme()) || !DocumentsContract.isDocumentUri(this, mUri))
			return null;
		String docId = DocumentsContract.getDocumentId(mUri);
		int colon = docId.indexOf(':');
		if (colon < 0)
			return null;
		List<String> segments = new ArrayList<>();
		for (String s : docId.substring(colon + 1).split("/"))
			if (!s.isEmpty())
				segments.add(s);
		if (!segments.isEmpty())
			segments.remove(segments.size() - 1); /* the document itself */
		for (String s : rel.split("/")) {
			if (s.isEmpty() || s.equals("."))
				continue;
			if (s.equals("..")) {
				if (segments.isEmpty())
					return null;
				segments.remove(segments.size() - 1);
			} else {
				segments.add(s);
			}
		}
		return docId.substring(0, colon + 1) + TextUtils.join("/", segments);
	}

	private boolean canAskForFolder() {
		return Build.VERSION.SDK_INT >= 21 && relativeDocumentId("x") != null;
	}

	private InputStream openRelative(String rel) {
		Uri target = resolveRelative(rel);
		if (target == null)
			return null;
		try {
			return getContentResolver().openInputStream(target);
		} catch (Exception x) {
			return null;
		}
	}

	/* Ask once per document; a link to a sibling file asks again because the
	 * user explicitly went for it. */
	private void offerFolderAccess(boolean forLink) {
		if ((mAskedForFolder && !forLink) || !canAskForFolder())
			return;
		mAskedForFolder = true;
		new AlertDialog.Builder(this)
			.setTitle(R.string.folder_access_title)
			.setMessage(R.string.folder_access_message)
			.setPositiveButton(R.string.folder_access_allow, (d, w) -> {
				Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
				if (Build.VERSION.SDK_INT >= 26)
					intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, mUri);
				try {
					startActivityForResult(intent, FOLDER_REQUEST);
				} catch (Exception x) {
					Toast.makeText(this, R.string.not_supported, Toast.LENGTH_SHORT).show();
				}
			})
			.setNegativeButton(R.string.folder_access_not_now, null)
			.show();
	}

	@Override
	protected void onActivityResult(int requestCode, int resultCode, Intent data) {
		super.onActivityResult(requestCode, resultCode, data);
		if (requestCode == CONTENTS_REQUEST && resultCode >= RESULT_FIRST_USER && mHeadingIds != null) {
			int index = resultCode - RESULT_FIRST_USER;
			if (index < mHeadingIds.length)
				js("MdReader.scrollToId(" + JSONObject.quote(mHeadingIds[index]) + ");");
			return;
		}
		if (requestCode == FOLDER_REQUEST && resultCode == RESULT_OK && data != null && data.getData() != null) {
			try {
				getContentResolver().takePersistableUriPermission(data.getData(),
					Intent.FLAG_GRANT_READ_URI_PERMISSION);
			} catch (Exception x) {
				Log.e(APP, "Cannot keep folder permission: " + x);
			}
			rerender();
		}
	}

	private void openRelativeLink(String href) {
		Uri target = resolveRelative(href);
		if (target == null) {
			if (canAskForFolder())
				offerFolderAccess(true);
			else
				Toast.makeText(this, getString(R.string.cannot_open_link, href), Toast.LENGTH_SHORT).show();
			return;
		}
		String name = target.getLastPathSegment() != null ? target.getLastPathSegment() : href;
		int slash = name.lastIndexOf('/');
		if (slash >= 0)
			name = name.substring(slash + 1);
		String mime = mimeFor(name);
		Intent intent = new Intent(Intent.ACTION_VIEW);
		intent.setDataAndType(target, mime);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		intent.putExtra(EXTRA_TITLE, name);
		intent.setClass(this, "text/markdown".equals(mime) ? MarkdownActivity.class : DocumentActivity.class);
		try {
			startActivity(intent);
		} catch (Exception x) {
			Toast.makeText(this, getString(R.string.cannot_open_link, href), Toast.LENGTH_SHORT).show();
		}
	}

	/* ---- Toolbar ---- */

	private void setupButtons() {
		findViewById(R.id.mdShareButton).setOnClickListener(v -> share());
		findViewById(R.id.mdSearchButton).setOnClickListener(v -> searchModeOn());
		findViewById(R.id.mdSettingsButton).setOnClickListener(v ->
			ReadingSettingsDialog.show(this, mSettings, s -> applySettings(s)));
		findViewById(R.id.mdOutlineButton).setOnClickListener(v -> showContents());
		findViewById(R.id.mdSearchClose).setOnClickListener(v -> searchModeOff());
		findViewById(R.id.mdSearchBack).setOnClickListener(v -> {
			hideKeyboard();
			mWebView.findNext(false);
		});
		findViewById(R.id.mdSearchForward).setOnClickListener(v -> {
			hideKeyboard();
			mWebView.findNext(true);
		});
		mSearchText.addTextChangedListener(new TextWatcher() {
			public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
			public void onTextChanged(CharSequence s, int start, int before, int count) {}
			public void afterTextChanged(Editable s) {
				if (s.length() == 0) {
					mWebView.clearMatches();
					mSearchCount.setText("");
				} else {
					mWebView.findAllAsync(s.toString());
				}
			}
		});
		mSearchText.setOnEditorActionListener((v, actionId, event) -> {
			if (actionId == EditorInfo.IME_ACTION_SEARCH
					|| (event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER)) {
				hideKeyboard();
				mWebView.findNext(true);
				return true;
			}
			return false;
		});
	}

	private void showPosition(float ratio, int headings, boolean hasPrev, boolean hasNext) {
		int percent = Math.round(Math.max(0, Math.min(1, ratio)) * 100);
		mPosition.setText(percent + "%");
		if (!mSliderTracking)
			mSlider.setProgress(Math.round(ratio * mSlider.getMax()));
		int vis = headings > 0 ? View.VISIBLE : View.GONE;
		mPrevHeading.setVisibility(vis);
		mNextHeading.setVisibility(vis);
		setButtonEnabled(mPrevHeading, hasPrev);
		setButtonEnabled(mNextHeading, hasNext);
	}

	private static void setButtonEnabled(ImageButton button, boolean enabled) {
		button.setEnabled(enabled);
		button.setColorFilter(enabled ? 0xffffffff : 0xff808080);
	}

	private void setupPositionBar() {
		mPrevHeading.setOnClickListener(v -> js("MdReader.jumpHeading(-1);"));
		mNextHeading.setOnClickListener(v -> js("MdReader.jumpHeading(1);"));
		mSlider.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
			public void onProgressChanged(SeekBar bar, int progress, boolean fromUser) {
				if (!fromUser || !mRendered)
					return;
				float ratio = progress / (float) bar.getMax();
				mPosition.setText(Math.round(ratio * 100) + "%");
				js("MdReader.setScrollRatio(" + ratio + ");");
			}
			public void onStartTrackingTouch(SeekBar bar) {
				mSliderTracking = true;
			}
			public void onStopTrackingTouch(SeekBar bar) {
				mSliderTracking = false;
			}
		});
	}

	private void searchModeOn() {
		if (mSearching)
			return;
		mSearching = true;
		mSwitcher.setDisplayedChild(1);
		mSearchText.requestFocus();
		showKeyboard();
	}

	private void searchModeOff() {
		if (!mSearching)
			return;
		mSearching = false;
		hideKeyboard();
		mWebView.clearMatches();
		mSearchText.setText("");
		mSwitcher.setDisplayedChild(0);
	}

	private void showKeyboard() {
		InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.showSoftInput(mSearchText, 0);
	}

	private void hideKeyboard() {
		InputMethodManager imm = (InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE);
		if (imm != null)
			imm.hideSoftInputFromWindow(mSearchText.getWindowToken(), 0);
	}

	private void showContents() {
		if (!mRendered)
			return;
		mWebView.evaluateJavascript("MdReader.headings()", value -> {
			try {
				JSONObject result = new JSONObject((String) new JSONTokener(value).nextValue());
				JSONArray headings = result.getJSONArray("items");
				if (headings.length() == 0) {
					Toast.makeText(this, R.string.no_headings, Toast.LENGTH_SHORT).show();
					return;
				}
				/* The same contents screen as books and PDFs use, indented the
				 * same way, with the heading's index standing in for a page. */
				int top = 6;
				for (int i = 0; i < headings.length(); i++)
					top = Math.min(top, headings.getJSONObject(i).getInt("level"));
				mHeadingIds = new String[headings.length()];
				ArrayList<OutlineActivity.Item> items = new ArrayList<>();
				for (int i = 0; i < headings.length(); i++) {
					JSONObject h = headings.getJSONObject(i);
					mHeadingIds[i] = h.getString("id");
					StringBuilder sb = new StringBuilder();
					for (int l = top; l < h.getInt("level"); l++)
						sb.append("    ");
					items.add(new OutlineActivity.Item(sb.append(h.getString("text")).toString(), i));
				}
				Bundle bundle = new Bundle();
				bundle.putInt("POSITION", result.getInt("current"));
				bundle.putSerializable("OUTLINE", items);
				Intent intent = new Intent(this, OutlineActivity.class);
				intent.putExtra("PALLETBUNDLE", Pallet.sendBundle(bundle));
				startActivityForResult(intent, CONTENTS_REQUEST);
			} catch (Exception x) {
				Log.e(APP, "Cannot read headings: " + x);
			}
		});
	}

	private void share() {
		Intent intent = new Intent(Intent.ACTION_SEND);
		intent.setType("text/markdown");
		intent.putExtra(Intent.EXTRA_STREAM, mUri);
		if (mDocTitle != null)
			intent.putExtra(Intent.EXTRA_SUBJECT, mDocTitle);
		intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
		try {
			startActivity(Intent.createChooser(intent, getString(R.string.share_document)));
		} catch (Exception x) {
			Toast.makeText(this, R.string.not_supported, Toast.LENGTH_SHORT).show();
		}
	}

	/* ---- Toolbar and system bars come and go together on a tap ---- */

	private void setupInsets() {
		mRoot.setOnApplyWindowInsetsListener((v, insets) -> {
			/* Stable insets: the space the bars take when shown, even while
			 * they are hidden, so nothing jumps when they come and go. */
			if (Build.VERSION.SDK_INT >= 30) {
				android.graphics.Insets i = insets.getInsetsIgnoringVisibility(
					WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
				mInsetTop = i.top;
				mInsetBottom = i.bottom;
			} else {
				mInsetTop = insets.getStableInsetTop();
				mInsetBottom = insets.getStableInsetBottom();
			}
			mSwitcher.setPadding(0, mInsetTop, 0, 0);
			mBottomBar.setPadding(0, 0, 0, mInsetBottom);
			mSwitcher.post(this::pushInsets);
			return insets;
		});
	}

	/* Keep the start of the text clear of the toolbar, and the end clear of
	 * the navigation bar. */
	private void pushInsets() {
		if (!mPageReady)
			return;
		float density = getResources().getDisplayMetrics().density;
		int top = Math.round(Math.max(mSwitcher.getHeight(), mInsetTop) / density) + 12;
		int bottom = Math.round(Math.max(mBottomBar.getHeight(), mInsetBottom) / density) + 24;
		js("MdReader.setInsets(" + top + "," + bottom + ");");
	}

	private void applySystemBars() {
		if (mBarsVisible)
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

	private void toggleBars() {
		if (mSearching)
			return;
		mBarsVisible = !mBarsVisible;
		applySystemBars();
		if (mBarsVisible) {
			mSwitcher.setVisibility(View.VISIBLE);
			mSwitcher.setTranslationY(-mSwitcher.getHeight());
			mSwitcher.animate().translationY(0).setDuration(200).setListener(null);
			mLowerBar.setVisibility(View.VISIBLE);
			mLowerBar.setTranslationY(mLowerBar.getHeight());
			mLowerBar.animate().translationY(0).setDuration(200).setListener(null);
		} else {
			mSwitcher.animate().translationY(-mSwitcher.getHeight()).setDuration(200)
				.withEndAction(() -> {
					if (!mBarsVisible)
						mSwitcher.setVisibility(View.INVISIBLE);
				});
			mLowerBar.animate().translationY(mLowerBar.getHeight()).setDuration(200)
				.withEndAction(() -> {
					if (!mBarsVisible)
						mLowerBar.setVisibility(View.INVISIBLE);
				});
		}
	}

	@Override
	public void onBackPressed() {
		if (mSearching) {
			searchModeOff();
			return;
		}
		super.onBackPressed();
		if (mReturnToLibraryActivity) {
			Intent intent = getPackageManager().getLaunchIntentForPackage(getPackageName());
			if (intent != null)
				startActivity(intent);
		}
	}
}
