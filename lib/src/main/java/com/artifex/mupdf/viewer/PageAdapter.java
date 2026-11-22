package com.artifex.mupdf.viewer;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Point;
import android.graphics.PointF;
import android.util.Log;
import android.util.SparseArray;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.os.AsyncTask;

public class PageAdapter extends BaseAdapter {
	private final String APP = "MuPDF";
	private final Context mContext;
	private final MuPDFCore mCore;
	private final SparseArray<PointF> mPageSizes = new SparseArray<PointF>();
	private       Bitmap mSharedHqBm1;
	private       Bitmap mSharedHqBm2;
	private       Bitmap mSecondaryHqBm1;
	private       Bitmap mSecondaryHqBm2;

	public PageAdapter(Context c, MuPDFCore core) {
		mContext = c;
		mCore = core;
	}

	public int getCount() {
		try {
			return mCore.countPages();
		} catch (RuntimeException e) {
			return 0;
		}
	}

	public Object getItem(int position) {
		return null;
	}

	public long getItemId(int position) {
		return 0;
	}

	public synchronized void releaseBitmaps()
	{
		//  recycle and release the shared bitmap.
		if (mSharedHqBm1!=null)
			mSharedHqBm1.recycle();
		mSharedHqBm1 = null;
		if (mSharedHqBm2!=null)
			mSharedHqBm2.recycle();
		mSharedHqBm2 = null;
		if (mSecondaryHqBm1!=null)
			mSecondaryHqBm1.recycle();
		mSecondaryHqBm1 = null;
		if (mSecondaryHqBm2!=null)
			mSecondaryHqBm2.recycle();
		mSecondaryHqBm2 = null;
	}

	/** The HQ bitmap pair every page view is created with. */
	public synchronized Bitmap[] getPrimaryHqBitmaps() {
		return new Bitmap[] { mSharedHqBm1, mSharedHqBm2 };
	}

	/** A second HQ bitmap pair, so a second visible page can be sharp at the
	 * same time as the current one. Made on first use, at the given size. */
	public synchronized Bitmap[] getSecondaryHqBitmaps(int width, int height) {
		if (width <= 0 || height <= 0)
			return new Bitmap[] { null, null };
		if (mSecondaryHqBm1 == null || mSecondaryHqBm1.isRecycled()
				|| mSecondaryHqBm1.getWidth() != width || mSecondaryHqBm1.getHeight() != height) {
			mSecondaryHqBm1 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
			mSecondaryHqBm2 = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888);
		}
		return new Bitmap[] { mSecondaryHqBm1, mSecondaryHqBm2 };
	}

	public void refresh() {
		mPageSizes.clear();
	}

	public synchronized View getView(final int position, View convertView, ViewGroup parent) {
		final PageView pageView;
		if (convertView == null) {
			if (mSharedHqBm1 == null || mSharedHqBm1.getWidth() != parent.getWidth() || mSharedHqBm1.getHeight() != parent.getHeight())
			{
				if (parent.getWidth() > 0 && parent.getHeight() > 0) {
					mSharedHqBm1 = Bitmap.createBitmap(parent.getWidth(), parent.getHeight(), Bitmap.Config.ARGB_8888);
					mSharedHqBm2 = Bitmap.createBitmap(parent.getWidth(), parent.getHeight(), Bitmap.Config.ARGB_8888);
				} else {
					mSharedHqBm1 = null;
					mSharedHqBm2 = null;
				}
			}

			pageView = new PageView(mContext, mCore, new Point(parent.getWidth(), parent.getHeight()), mSharedHqBm1, mSharedHqBm2);
		} else {
			pageView = (PageView) convertView;
		}

		PointF pageSize = mPageSizes.get(position);
		if (pageSize != null) {
			// We already know the page size. Set it up
			// immediately
			pageView.setPage(position, pageSize);
		} else {
			// Page size as yet unknown. Blank it for now, and
			// start a background task to find the size
			pageView.blank(position);
			AsyncTask<Void,Void,PointF> sizingTask = new AsyncTask<Void,Void,PointF>() {
				@Override
				protected PointF doInBackground(Void... arg0) {
					try {
						return mCore.getPageSize(position);
					} catch (RuntimeException e) {
						return null;
					}
				}

				@Override
				protected void onPostExecute(PointF result) {
					super.onPostExecute(result);
					// We now know the page size
					mPageSizes.put(position, result);
					// Check that this view hasn't been reused for
					// another page since we started
					if (pageView.getPage() == position)
						pageView.setPage(position, result);
				}
			};

			sizingTask.execute((Void)null);
		}
		return pageView;
	}
}
