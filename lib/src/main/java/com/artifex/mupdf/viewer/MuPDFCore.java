package com.artifex.mupdf.viewer;

import com.artifex.mupdf.fitz.Cookie;
import com.artifex.mupdf.fitz.DisplayList;
import com.artifex.mupdf.fitz.Document;
import com.artifex.mupdf.fitz.Link;
import com.artifex.mupdf.fitz.Matrix;
import com.artifex.mupdf.fitz.Outline;
import com.artifex.mupdf.fitz.Page;
import com.artifex.mupdf.fitz.Point;
import com.artifex.mupdf.fitz.Quad;
import com.artifex.mupdf.fitz.Rect;
import com.artifex.mupdf.fitz.RectI;
import com.artifex.mupdf.fitz.SeekableInputStream;
import com.artifex.mupdf.fitz.StructuredText;
import com.artifex.mupdf.fitz.android.AndroidDrawDevice;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.PointF;
import android.util.Log;

import java.util.ArrayList;

public class MuPDFCore
{
	private final String APP = "MuPDF";

	private final int MAXIMUM_OUTLINE_ITEMS = 1000;
	private final int MAXIMUM_OUTLINE_DEPTH = 4;

	private int resolution;
	private Document doc;
	private Outline[] outline;
	private int pageCount = -1;
	private boolean reflowable = false;
	private int currentPage;
	private Page page;
	private float pageWidth;
	private float pageHeight;
	private DisplayList displayList;
	private StructuredText pageText;

	/* Default to "A Format" pocket book size. */
	private int layoutW = 312;
	private int layoutH = 504;
	private int layoutEM = 10;

	private boolean outlineTruncated;

	private MuPDFCore(Document doc) {
		this.doc = doc;
		doc.layout(layoutW, layoutH, layoutEM);
		pageCount = doc.countPages();
		reflowable = doc.isReflowable();
		resolution = 160;
		currentPage = -1;
	}

	public MuPDFCore(byte buffer[], String magic) {
		this(Document.openDocument(buffer, magic));
	}

	public MuPDFCore(SeekableInputStream stm, String magic) {
		this(Document.openDocument(stm, magic));
	}

	public String getTitle() {
		return doc.getMetaData(Document.META_INFO_TITLE);
	}

	public int countPages() {
		return pageCount;
	}

	public boolean isReflowable() {
		return reflowable;
	}

	public synchronized int layout(int oldPage, int w, int h, int em) {
		if (w != layoutW || h != layoutH || em != layoutEM) {
			System.out.println("LAYOUT: " + w + "," + h);
			layoutW = w;
			layoutH = h;
			layoutEM = em;
			long mark = doc.makeBookmark(doc.locationFromPageNumber(oldPage));
			doc.layout(layoutW, layoutH, layoutEM);
			currentPage = -1;
			pageCount = doc.countPages();
			outline = null;
			try {
				outline = doc.loadOutline();
			} catch (Exception ex) {
				/* ignore error */
			}
			return doc.pageNumberFromLocation(doc.findBookmark(mark));
		}
		return oldPage;
	}

	private synchronized void gotoPage(int pageNum) {
		/* TODO: page cache */
		if (pageNum > pageCount-1)
			pageNum = pageCount-1;
		else if (pageNum < 0)
			pageNum = 0;
		if (pageNum != currentPage) {
			if (page != null)
				page.destroy();
			page = null;
			if (displayList != null)
				displayList.destroy();
			displayList = null;
			if (pageText != null)
				pageText.destroy();
			pageText = null;
			page = null;
			pageWidth = 0;
			pageHeight = 0;
			currentPage = -1;

			if (doc != null) {
				page = doc.loadPage(pageNum);
				Rect b = page.getBounds();
				pageWidth = b.x1 - b.x0;
				pageHeight = b.y1 - b.y0;
			}

			currentPage = pageNum;
		}
	}

	public synchronized PointF getPageSize(int pageNum) {
		gotoPage(pageNum);
		return new PointF(pageWidth, pageHeight);
	}

	public synchronized void onDestroy() {
		if (displayList != null)
			displayList.destroy();
		displayList = null;
		if (pageText != null)
			pageText.destroy();
		pageText = null;
		if (page != null)
			page.destroy();
		page = null;
		if (doc != null)
			doc.destroy();
		doc = null;
	}

	public synchronized void drawPage(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		gotoPage(pageNum);

		if (displayList == null && page != null)
			try {
				displayList = page.toDisplayList();
			} catch (Exception ex) {
				displayList = null;
			}

		if (displayList == null || page == null)
			return;

		float zoom = resolution / 72;
		Matrix ctm = new Matrix(zoom, zoom);
		RectI bbox = new RectI(page.getBounds().transform(ctm));
		float xscale = (float)pageW / (float)(bbox.x1-bbox.x0);
		float yscale = (float)pageH / (float)(bbox.y1-bbox.y0);
		ctm.scale(xscale, yscale);

		AndroidDrawDevice dev = new AndroidDrawDevice(bm, patchX, patchY);
		try {
			displayList.run(dev, ctm, cookie);
			dev.close();
		} finally {
			dev.destroy();
		}
	}

	public synchronized void updatePage(Bitmap bm, int pageNum,
			int pageW, int pageH,
			int patchX, int patchY,
			int patchW, int patchH,
			Cookie cookie) {
		drawPage(bm, pageNum, pageW, pageH, patchX, patchY, patchW, patchH, cookie);
	}

	public synchronized Link[] getPageLinks(int pageNum) {
		gotoPage(pageNum);
		return page != null ? page.getLinks() : null;
	}

	public synchronized int resolveLink(Link link) {
		return doc.pageNumberFromLocation(doc.resolveLink(link));
	}

	public synchronized Quad[][] searchPage(int pageNum, String text) {
		gotoPage(pageNum);
		return page.search(text);
	}

	/* Analysing a page into text is expensive, so hang on to the result for
	 * the page we are on: dragging a selection asks for it on every move. */
	private StructuredText getPageText(int pageNum) {
		gotoPage(pageNum);
		if (pageText == null && page != null)
			pageText = page.toStructuredText();
		return pageText;
	}

	/* The two ends of the word under a point, or null when the point is not
	 * on a line of text. We walk the text ourselves rather than ask for a
	 * snapped selection, which wants a dragged range and does something
	 * unhelpful when handed the same point twice. */
	public synchronized PointF[] snapWord(int pageNum, PointF p) {
		StructuredText text = getPageText(pageNum);
		if (text == null)
			return null;

		StructuredText.TextBlock blocks[] = text.getBlocks();
		if (blocks == null)
			return null;

		for (StructuredText.TextBlock block : blocks) {
			if (block == null || block.lines == null)
				continue;
			for (StructuredText.TextLine line : block.lines) {
				if (line == null || line.chars == null || line.chars.length == 0)
					continue;
				if (line.bbox == null || !line.bbox.contains(p.x, p.y))
					continue;

				/* The nearest character on the line, so a press between two
				 * glyphs still picks a word. */
				StructuredText.TextChar chars[] = line.chars;
				int hit = -1;
				float best = Float.MAX_VALUE;
				for (int i = 0; i < chars.length; i++) {
					if (chars[i] == null || chars[i].quad == null)
						continue;
					Quad q = chars[i].quad;
					float dx = (q.ul_x + q.ur_x) / 2 - p.x;
					float dy = (q.ul_y + q.ll_y) / 2 - p.y;
					float d = dx * dx + dy * dy;
					if (d < best) {
						best = d;
						hit = i;
					}
				}
				if (hit < 0 || chars[hit].isWhitespace())
					continue;

				int from = hit, to = hit;
				while (from > 0 && chars[from-1] != null && !chars[from-1].isWhitespace())
					from--;
				while (to+1 < chars.length && chars[to+1] != null && !chars[to+1].isWhitespace())
					to++;

				Quad a = chars[from].quad;
				Quad b = chars[to].quad;
				return new PointF[] {
					new PointF(a.ul_x, (a.ul_y + a.ll_y) / 2),
					new PointF(b.ur_x, (b.ur_y + b.lr_y) / 2)
				};
			}
		}
		return null;
	}

	/* The quads to paint for a selection running between two points. */
	public synchronized Quad[] highlightSelection(int pageNum, PointF a, PointF b) {
		StructuredText text = getPageText(pageNum);
		if (text == null)
			return null;
		return text.highlight(new Point(a.x, a.y), new Point(b.x, b.y));
	}

	public synchronized String copySelection(int pageNum, PointF a, PointF b) {
		StructuredText text = getPageText(pageNum);
		if (text == null)
			return null;
		return text.copy(new Point(a.x, a.y), new Point(b.x, b.y));
	}

	public synchronized boolean hasOutline() {
		if (outline == null) {
			try {
				outline = doc.loadOutline();
			} catch (Exception ex) {
				/* ignore error */
			}
		}
		return outline != null;
	}

	private void flattenOutlineNodes(ArrayList<OutlineActivity.Item> result, Outline list[], String indent, int depth) {
		for (Outline node : list) {
			if (node.title != null) {
				int page = doc.pageNumberFromLocation(doc.resolveLink(node));
				if (result.size() >= MAXIMUM_OUTLINE_ITEMS)
					outlineTruncated = true;
				else
					result.add(new OutlineActivity.Item(indent + node.title, page));
			}
			if (node.down != null)
			{
				if (depth >= MAXIMUM_OUTLINE_DEPTH || result.size() >= MAXIMUM_OUTLINE_ITEMS)
					outlineTruncated = true;
				else
					flattenOutlineNodes(result, node.down, indent + "    ", depth + 1);
			}
		}
	}

	public synchronized ArrayList<OutlineActivity.Item> getOutline() {
		outlineTruncated = false;
		ArrayList<OutlineActivity.Item> result = new ArrayList<OutlineActivity.Item>();
		flattenOutlineNodes(result, outline, "", 0);
		return result;
	}

	public synchronized boolean wasOutlineTruncated() {
		return outlineTruncated;
	}

	public synchronized boolean needsPassword() {
		return doc.needsPassword();
	}

	public synchronized boolean authenticatePassword(String password) {
		boolean authenticated = doc.authenticatePassword(password);
		pageCount = doc.countPages();
		reflowable = doc.isReflowable();
		return authenticated;
	}
}
