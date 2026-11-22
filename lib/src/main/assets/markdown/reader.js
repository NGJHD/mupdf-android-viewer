/* Markdown viewer page. The app calls MdReader.* through evaluateJavascript
 * and listens through the "Reader" JavaScript interface. */
'use strict';

window.MdReader = (function () {
	/* Relative image paths are fetched from here and resolved by the app
	 * against the folder the document lives in. */
	var DOC_BASE = 'https://appassets.androidplatform.net/doc/?p=';
	var bridge = window.Reader || null;
	var content = document.getElementById('content');

	var source = '';
	var settings = { theme: 'light', font: 'sans', lineHeight: 1.6, paraSpacing: 1.0, margin: 16 };
	var mermaidTheme = null;
	var mermaidLoading = null;
	var reportedMissingImages = false;

	function escapeHtml(s) {
		return s.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
			.replace(/"/g, '&quot;').replace(/'/g, '&#39;');
	}

	function highlight(code, lang) {
		lang = (lang || '').trim().toLowerCase();
		if (lang === 'mermaid')
			return '<pre class="mermaid-src">' + escapeHtml(code) + '</pre>';
		if (lang === 'math' || lang === 'katex')
			return '<pre class="math-display">' + katex.renderToString(code,
				{ displayMode: true, throwOnError: false, output: 'html' }) + '</pre>';
		if (lang && window.hljs && hljs.getLanguage(lang)) {
			try {
				return hljs.highlight(code, { language: lang, ignoreIllegals: true }).value;
			} catch (e) { /* fall through to plain */ }
		}
		return '';
	}

	var md = window.markdownit({
		html: true,
		linkify: true,
		typographer: false,
		highlight: highlight
	})
		.use(window.markdownitFootnote)
		.use(window.markdownitTaskLists, { label: true })
		.use(window.markdownitDeflist)
		.use(window.markdownitEmoji)
		.use(window.texmath, {
			engine: window.katex,
			/* $..$, $$..$$ and the \(..\) \[..\] pairs LLMs like to use,
			 * plus \begin{..}..\end{..} environments. */
			delimiters: ['dollars', 'brackets', 'beg_end'],
			katexOptions: { throwOnError: false, output: 'html' }
		});

	/* Raw HTML in a document is parsed by the browser as one piece, so a
	 * single unclosed <style>, <script>, <textarea>... turns the whole rest
	 * of the document into its text, which the sanitiser then throws away
	 * with it. These are all stripped anyway, so show them as text instead.
	 * An unbalanced <svg> or <math> similarly pulls what follows into a
	 * foreign namespace, so defuse those within an HTML block too. */
	var RAW_TEXT_TAGS = /<(\/?)(script|style|textarea|title|xmp|iframe|noembed|noframes|noscript|plaintext|template|select|option|object|embed|frameset|frame)\b/gi;

	function countTags(html, re) {
		var m = html.match(re);
		return m ? m.length : 0;
	}

	/* MathML and SVG element names. Outside a <math> or <svg> the sanitiser
	 * removes them together with everything inside, which for an unclosed
	 * one is the rest of the document. */
	var MATHML_TAGS = 'math|mi|mn|mo|ms|mtext|mspace|mrow|mfrac|msqrt|mroot|mstyle|merror|mpadded|mphantom|mfenced|menclose|msub|msup|msubsup|munder|mover|munderover|mmultiscripts|mprescripts|mtable|mtr|mtd|mlabeledtr|maligngroup|malignmark|mglyph|maction|semantics|annotation|annotation-xml';
	var SVG_TAGS = 'svg|g|path|circle|ellipse|line|polyline|polygon|rect|text|tspan|textpath|defs|use|symbol|marker|pattern|mask|clippath|lineargradient|radialgradient|stop|filter|foreignobject|desc|metadata|switch|image|animate|animatetransform|animatemotion|set';

	function defuseNamespace(html, outer, names) {
		var open = countTags(html, new RegExp('<' + outer + '\\b', 'gi'));
		var close = countTags(html, new RegExp('</' + outer + '\\s*>', 'gi'));
		if (open > 0 && open === close)
			return html;
		return html.replace(new RegExp('<(/?)(' + names + ')(?=[\\s/>])', 'gi'), '&lt;$1$2');
	}

	function defuseHtml(html, isBlock) {
		html = html.replace(RAW_TEXT_TAGS, '&lt;$1$2');
		if (isBlock) {
			html = defuseNamespace(html, 'math', MATHML_TAGS);
			html = defuseNamespace(html, 'svg', SVG_TAGS);
		} else {
			/* Inline, a <math> or <svg> would span several tokens, so only
			 * the stray inner elements are defused. */
			html = html.replace(new RegExp('<(/?)(' + MATHML_TAGS.replace(/^math\|/, '') + ')(?=[\\s/>])', 'gi'), '&lt;$1$2');
		}
		return html;
	}

	md.renderer.rules.html_block = function (tokens, idx) {
		return defuseHtml(tokens[idx].content, true);
	};
	md.renderer.rules.html_inline = function (tokens, idx) {
		return defuseHtml(tokens[idx].content, false);
	};

	/* Inline styles may not pin anything over the screen: a document could
	 * otherwise lay a fake page over the real one. */
	DOMPurify.addHook('uponSanitizeAttribute', function (node, data) {
		if (data.attrName === 'style' && /position\s*:\s*(fixed|sticky)/i.test(data.attrValue))
			data.attrValue = data.attrValue.replace(/position\s*:\s*(fixed|sticky)/gi, 'position: static');
	});

	/* Keep the line a code block starts on, so the language label can go on it. */
	var defaultFence = md.renderer.rules.fence;
	md.renderer.rules.fence = function (tokens, idx, options, env, self) {
		var out = defaultFence(tokens, idx, options, env, self);
		var lang = (tokens[idx].info || '').trim().split(/\s+/)[0];
		if (lang && out.indexOf('<pre>') === 0)
			out = '<pre data-lang="' + escapeHtml(lang) + '">' + out.substring(5);
		return out;
	};

	/* A display math block (\[ .. \] or $$ .. $$ on lines of their own) is
	 * only recognised when it stands apart from the paragraph around it, but
	 * LLM output often runs it straight on from a line of text. Put blank
	 * lines around such blocks, leaving fenced code alone. */
	function separateDisplayMath(text) {
		var lines = text.split('\n');
		var out = [];
		var fence = null, closer = null, blankNext = false;
		for (var i = 0; i < lines.length; i++) {
			var line = lines[i];
			var t = line.trim();
			if (fence) {
				out.push(line);
				if (t.indexOf(fence) === 0 && /^[`~]+$/.test(t))
					fence = null;
				continue;
			}
			if (blankNext) {
				if (t !== '')
					out.push('');
				blankNext = false;
			}
			if (closer) {
				out.push(line);
				if (t === closer) {
					closer = null;
					blankNext = true;
				}
				continue;
			}
			var m = /^(`{3,}|~{3,})/.exec(t);
			if (m) {
				fence = m[1];
				out.push(line);
				continue;
			}
			if (t === '\\[' || t === '$$') {
				if (out.length && out[out.length - 1].trim() !== '')
					out.push('');
				closer = t === '$$' ? '$$' : '\\]';
			}
			out.push(line);
		}
		return out.join('\n');
	}

	/* YAML front matter (Obsidian, Jekyll, Hugo...) would otherwise come out
	 * as a rule followed by a huge heading. Show it folded away instead. */
	function frontMatter(text) {
		var m = /^---[ \t]*\r?\n([\s\S]*?)\r?\n(?:---|\.\.\.)[ \t]*(?:\r?\n|$)/.exec(text);
		if (!m)
			return { html: '', body: text };
		var yaml = m[1];
		var code;
		try {
			code = hljs.highlight(yaml, { language: 'yaml', ignoreIllegals: true }).value;
		} catch (e) {
			code = escapeHtml(yaml);
		}
		return {
			html: '<details class="front-matter"><summary>Properties</summary><pre><code>' + code + '</code></pre></details>',
			body: text.substring(m[0].length)
		};
	}

	function sanitize(html) {
		return DOMPurify.sanitize(html, {
			/* Inline <style> could restyle the whole page, and forms have no
			 * business in a document. */
			FORBID_TAGS: ['style', 'form', 'button', 'textarea', 'select'],
			FORBID_ATTR: ['onerror', 'onload', 'onclick'],
			ADD_ATTR: ['data-lang']
		});
	}

	function isRelative(url) {
		return url && !/^[a-z][a-z0-9+.-]*:/i.test(url) && url.charAt(0) !== '/' && url.charAt(0) !== '#';
	}

	function missingImage(img) {
		var span = document.createElement('span');
		span.className = 'img-missing';
		span.textContent = '🖼 ' + (img.getAttribute('alt') || img.getAttribute('data-src') || 'image');
		img.replaceWith(span);
	}

	function fixImages() {
		var imgs = content.querySelectorAll('img');
		for (var i = 0; i < imgs.length; i++) {
			var img = imgs[i];
			var src = img.getAttribute('src') || '';
			img.setAttribute('data-src', src);
			img.loading = 'lazy';
			var local = isRelative(src);
			if (local)
				img.src = DOC_BASE + encodeURIComponent(src);
			img.addEventListener('error', (function (img, local) {
				return function () {
					missingImage(img);
					if (local && !reportedMissingImages && bridge) {
						reportedMissingImages = true;
						bridge.imagesMissing();
					}
				};
			})(img, local));
		}
	}

	function wrap(el, className) {
		var div = document.createElement('div');
		div.className = className;
		el.parentNode.insertBefore(div, el);
		div.appendChild(el);
		return div;
	}

	function decorateCode() {
		var pres = content.querySelectorAll('pre');
		for (var i = 0; i < pres.length; i++) {
			var pre = pres[i];
			var code = pre.querySelector('code');
			if (!code || pre.classList.contains('mermaid-src') || pre.classList.contains('math-display'))
				continue;
			pre.classList.add('has-copy');
			var lang = pre.getAttribute('data-lang');
			if (lang) {
				var label = document.createElement('span');
				label.className = 'code-lang';
				label.textContent = lang;
				pre.appendChild(label);
			}
			var btn = document.createElement('span');
			btn.className = 'copy-btn';
			btn.setAttribute('role', 'button');
			btn.textContent = 'Copy';
			btn.addEventListener('click', (function (code, btn) {
				return function (e) {
					e.stopPropagation();
					if (bridge)
						bridge.copy(code.textContent);
					btn.textContent = 'Copied';
					setTimeout(function () { btn.textContent = 'Copy'; }, 1500);
				};
			})(code, btn));
			pre.appendChild(btn);
		}
	}

	function decorate() {
		var tables = content.querySelectorAll('table');
		for (var i = 0; i < tables.length; i++)
			wrap(tables[i], 'table-wrap');

		var headings = content.querySelectorAll('h1, h2, h3, h4, h5, h6');
		for (var j = 0; j < headings.length; j++)
			if (!headings[j].id)
				headings[j].id = 'h-' + j;

		decorateCode();
		fixImages();
	}

	function loadMermaid() {
		if (window.mermaid)
			return Promise.resolve();
		if (!mermaidLoading) {
			mermaidLoading = new Promise(function (resolve, reject) {
				var s = document.createElement('script');
				s.src = 'vendor/mermaid.min.js';
				s.onload = resolve;
				s.onerror = reject;
				document.head.appendChild(s);
			});
		}
		return mermaidLoading;
	}

	/* Diagrams are drawn only as they come near the screen: drawing one makes
	 * the browser measure the whole page, which in a long document is slow,
	 * and most diagrams in a long document are never looked at. One at a
	 * time, since Mermaid is not built for overlapping runs. */
	var diagramObserver = null;
	var diagramQueue = Promise.resolve();

	function drawDiagram(div) {
		diagramQueue = diagramQueue.then(function () {
			if (!div.isConnected)
				return;
			return loadMermaid().then(function () {
				var theme = settings.theme === 'dark' ? 'dark' : (settings.theme === 'sepia' ? 'neutral' : 'default');
				if (mermaidTheme !== theme) {
					mermaid.initialize({
						startOnLoad: false,
						securityLevel: 'strict',
						theme: theme,
						fontFamily: 'sans-serif'
					});
					mermaidTheme = theme;
				}
				return mermaid.run({ nodes: [div], suppressErrors: true });
			});
		}).catch(function (e) {
			console.log('mermaid: ' + e);
		});
		return diagramQueue;
	}

	function renderMermaid() {
		if (diagramObserver)
			diagramObserver.disconnect();
		var blocks = content.querySelectorAll('pre.mermaid-src');
		if (blocks.length === 0)
			return;
		diagramObserver = new IntersectionObserver(function (entries) {
			entries.forEach(function (entry) {
				if (entry.isIntersecting) {
					diagramObserver.unobserve(entry.target);
					drawDiagram(entry.target);
				}
			});
		}, { rootMargin: '1500px 0px' });
		for (var i = 0; i < blocks.length; i++) {
			/* Until drawn, the diagram shows as its source. */
			var div = document.createElement('div');
			div.className = 'mermaid';
			div.textContent = blocks[i].textContent;
			var w = document.createElement('div');
			w.className = 'mermaid-wrap';
			w.appendChild(div);
			blocks[i].replaceWith(w);
			diagramObserver.observe(div);
		}
	}

	/* Resolves once the diagrams already queued for drawing are in. */
	function diagramsSettled() {
		/* Visibility is reported after a frame has been drawn, so let two
		 * go by before looking at what got queued. */
		return new Promise(function (resolve) {
			requestAnimationFrame(function () {
				requestAnimationFrame(function () { setTimeout(resolve, 0); });
			});
		}).then(function () { return diagramQueue; });
	}

	function scrollRatio() {
		var max = document.documentElement.scrollHeight - window.innerHeight;
		return max > 0 ? window.scrollY / max : 0;
	}

	function setScrollRatio(r) {
		var max = document.documentElement.scrollHeight - window.innerHeight;
		window.scrollTo(0, Math.max(0, r * max));
	}

	function render(text, ratio) {
		source = text;
		reportedMissingImages = false;
		var fm = frontMatter(text);
		content.innerHTML = sanitize(fm.html + md.render(separateDisplayMath(fm.body)));
		decorate();
		if (ratio)
			setScrollRatio(ratio);
		renderMermaid();
		return diagramsSettled().then(function () {
			/* Diagrams on screen change the page height, so place the
			 * reading position again once they are in. */
			if (ratio)
				setScrollRatio(ratio);
			reportPosition();
			if (bridge)
				bridge.onRendered();
		});
	}

	function applySettings(s) {
		var oldTheme = settings.theme;
		for (var k in s)
			settings[k] = s[k];
		/* On the root element, so the theme's background covers the whole
		 * window and not just the document's height. */
		document.documentElement.className = 'theme-' + settings.theme + ' font-' + settings.font;
		var root = document.documentElement.style;
		root.setProperty('--line-height', String(settings.lineHeight));
		root.setProperty('--para', settings.paraSpacing + 'em');
		root.setProperty('--margin', settings.margin + 'px');
		var dark = settings.theme === 'dark';
		document.getElementById('hljs-light').disabled = dark;
		document.getElementById('hljs-dark').disabled = !dark;
		/* Diagrams are drawn in the theme's colours, so redraw them. */
		if (oldTheme !== settings.theme && source && content.querySelector('.mermaid-wrap'))
			render(source, scrollRatio());
	}

	function setInsets(top, bottom) {
		var root = document.documentElement.style;
		root.setProperty('--top-inset', top + 'px');
		root.setProperty('--bottom-inset', bottom + 'px');
	}

	/* The headings, and which one the reader is in: the last that has
	 * scrolled up to the top of the screen. */
	function headings() {
		var hs = content.querySelectorAll('h1, h2, h3, h4, h5, h6');
		var top = parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--top-inset')) || 0;
		var out = [];
		var current = 0;
		for (var i = 0; i < hs.length; i++) {
			out.push({ level: +hs[i].tagName.charAt(1), text: hs[i].textContent, id: hs[i].id });
			if (hs[i].getBoundingClientRect().top <= top + 8)
				current = i;
		}
		return JSON.stringify({ items: out, current: current });
	}

	function topInset() {
		return parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--top-inset')) || 0;
	}

	/* Next heading below the top of the screen; going back, the start of
	 * the current section, or the one before when already at its start. */
	function jumpHeading(direction) {
		var hs = content.querySelectorAll('h1, h2, h3, h4, h5, h6');
		var top = topInset();
		if (direction > 0) {
			for (var i = 0; i < hs.length; i++)
				if (hs[i].getBoundingClientRect().top > top + 8) {
					scrollToId(hs[i].id);
					return;
				}
		} else {
			for (var j = hs.length - 1; j >= 0; j--)
				if (hs[j].getBoundingClientRect().top < top - 8) {
					scrollToId(hs[j].id);
					return;
				}
		}
	}

	/* Tell the app where we are, at most every 100ms: how far through, and
	 * whether there are headings to jump to either way. Only the first and
	 * last headings are measured, so this stays cheap on long documents. */
	var positionTimer = null;
	function reportPosition() {
		positionTimer = null;
		if (!bridge)
			return;
		var hs = content.querySelectorAll('h1, h2, h3, h4, h5, h6');
		var top = topInset();
		var hasPrev = hs.length > 0 && hs[0].getBoundingClientRect().top < top - 8;
		var hasNext = hs.length > 0 && hs[hs.length - 1].getBoundingClientRect().top > top + 8;
		bridge.onPosition(scrollRatio(), hs.length, hasPrev, hasNext);
	}
	window.addEventListener('scroll', function () {
		if (!positionTimer)
			positionTimer = setTimeout(reportPosition, 100);
	}, { passive: true });

	function scrollToId(id) {
		var el = document.getElementById(id);
		if (!el)
			return;
		var top = el.getBoundingClientRect().top + window.scrollY
			- parseFloat(getComputedStyle(document.documentElement).getPropertyValue('--top-inset') || 0);
		window.scrollTo(0, Math.max(0, top));
		el.classList.remove('flash');
		void el.offsetWidth;
		el.classList.add('flash');
	}

	/* In-page links (footnotes, a table of contents written into the
	 * document) scroll here; everything else is the app's to handle. */
	document.addEventListener('click', function (e) {
		var a = e.target.closest ? e.target.closest('a') : null;
		if (a) {
			var href = a.getAttribute('href') || '';
			if (href.charAt(0) === '#') {
				e.preventDefault();
				var id = decodeURIComponent(href.substring(1));
				var target = document.getElementById(id) || document.getElementsByName(id)[0];
				if (target)
					scrollToId(target.id || id);
				else if (bridge)
					bridge.onTap();
			} else if (isRelative(href)) {
				/* Another file next to this one: the app opens it. */
				e.preventDefault();
				if (bridge)
					bridge.openLink(href);
			}
			return;
		}
		if (e.target.closest && e.target.closest('summary, input, label, .copy-btn'))
			return;
		var sel = window.getSelection();
		if (sel && !sel.isCollapsed)
			return;
		if (bridge)
			bridge.onTap();
	});

	return {
		render: render,
		applySettings: applySettings,
		setInsets: setInsets,
		headings: headings,
		scrollToId: scrollToId,
		jumpHeading: jumpHeading,
		scrollRatio: scrollRatio,
		setScrollRatio: setScrollRatio
	};
})();

if (window.Reader)
	window.Reader.onReady();
