package dev.local.clipvault;

import android.content.Context;
import android.graphics.Color;
import android.util.Base64;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import org.commonmark.Extension;
import org.commonmark.ext.autolink.AutolinkExtension;
import org.commonmark.ext.task.list.items.TaskListItemMarker;
import org.commonmark.ext.task.list.items.TaskListItemsExtension;
import org.commonmark.node.AbstractVisitor;
import org.commonmark.node.Image;
import org.commonmark.node.ListItem;
import org.commonmark.node.Node;
import org.commonmark.node.Paragraph;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.Supplier;

final class MarkdownPreview extends WebView {
    private static final List<Extension> EXTENSIONS =
            Arrays.asList(TaskListItemsExtension.create(), AutolinkExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS).build();
    private static final String TASK_CLASS = "vault-task";
    private static final HtmlRenderer RENDERER = renderer(Collections.emptyMap());
    private static final ExecutorService RENDERING = Executors.newSingleThreadExecutor();

    private Future<?> pendingRender;
    private int renderVersion;
    private boolean destroyed;

    MarkdownPreview(Context context, Consumer<String> openLink) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        getSettings().setBlockNetworkLoads(true);
        getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setDefaultTextEncodingName("UTF-8");
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                String url = request.getUrl().toString();
                if (!destroyed && request.isForMainFrame() && request.hasGesture()
                        && Vault.isWebLink(url)) openLink.accept(url);
                return true;
            }
        });
    }

    void render(String source) {
        render(() -> source, Collections.emptyMap());
    }

    void render(Supplier<String> source, Map<String, byte[]> localImages) {
        Context context = getContext();
        String ink = css(context.getColor(R.color.vault_text));
        String accent = css(context.getColor(R.color.vault_accent));
        String panel = css(context.getColor(R.color.vault_surface_soft));
        String head = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "style-src 'unsafe-inline'; img-src data:\">"
                + "<style>body{font:1rem/1.6 sans-serif;margin:8px 4px;overflow-wrap:anywhere;color:" + ink + "}"
                + "pre,blockquote{background:" + panel + ";padding:.7em 1em;margin:1em 0}"
                + "pre{overflow:auto}blockquote{border-inline-start:3px solid " + accent + "}"
                + "a{color:" + accent + "}code{font-family:monospace}img{max-width:100%;height:auto}"
                + "hr{border:0;border-top:1px solid " + accent + "}"
                + "ul,ol{margin:.65em 0}ul{padding-inline-start:1.65em}ol{padding-inline-start:2.25em}"
                + "li+li{margin-top:.2em}li>p{margin:.65em 0}"
                + "li>p:first-child{margin-top:0}li>p:last-child{margin-bottom:0}"
                + "li." + TASK_CLASS + "{position:relative;list-style:none}"
                + "li." + TASK_CLASS + ">input,li." + TASK_CLASS + ">p:first-child>input{"
                + "position:absolute;left:-1.25em;top:.35em;font:inherit;width:.9em;height:.9em;margin:0}"
                + "</style></head><body>";
        int version = ++renderVersion;
        if (pendingRender != null) pendingRender.cancel(true);
        pendingRender = RENDERING.submit(() -> {
            String markup;
            try {
                String markdown = source.get();
                HtmlRenderer htmlRenderer = localImages.isEmpty() ? RENDERER : renderer(localImages);
                markup = htmlRenderer.render(parseForPreview(markdown));
            } catch (RuntimeException | StackOverflowError error) {
                markup = "<p>無法預覽這段 Markdown。可以返回查看原文。</p>";
            }
            String html = head + markup + "</body></html>";
            post(() -> {
                if (!destroyed && version == renderVersion) {
                    loadDataWithBaseURL(null, html, "text/html", "UTF-8", null);
                }
            });
        });
    }

    @Override public void destroy() {
        destroyed = true;
        if (pendingRender != null) pendingRender.cancel(true);
        super.destroy();
    }

    static String renderMarkup(String source) {
        return RENDERER.render(parseForPreview(source));
    }

    private static Node parseForPreview(String source) {
        Node document = PARSER.parse(source);
        document.accept(new AbstractVisitor() {
            @Override public void visit(ListItem item) {
                Node marker = item.getFirstChild();
                if (marker instanceof TaskListItemMarker && marker.getNext() instanceof Paragraph paragraph) {
                    // Loose lists render a paragraph block. Its checkbox belongs on the first text line.
                    paragraph.prependChild(marker);
                }
                visitChildren(item);
            }
        });
        return document;
    }

    private static boolean hasTaskMarker(ListItem item) {
        Node first = item.getFirstChild();
        return first instanceof TaskListItemMarker
                || (first instanceof Paragraph && first.getFirstChild() instanceof TaskListItemMarker);
    }

    private static HtmlRenderer renderer(Map<String, byte[]> images) {
        Map<String, String> encoded = new HashMap<>();
        return HtmlRenderer.builder().extensions(EXTENSIONS).escapeHtml(true).sanitizeUrls(true)
                .attributeProviderFactory(context -> (node, tag, attributes) -> {
                    if (node instanceof ListItem item && tag.equals("li") && hasTaskMarker(item)) {
                        attributes.merge("class", TASK_CLASS, (existing, task) -> existing + " " + task);
                    }
                    if (!(node instanceof Image image) || !tag.equals("img")) return;
                    String destination = image.getDestination();
                    byte[] local = images.get(destination);
                    if (local == null) {
                        attributes.remove("src");
                    } else {
                        attributes.put("src", encoded.computeIfAbsent(destination, ignored ->
                                "data:image/webp;base64," + Base64.encodeToString(local, Base64.NO_WRAP)));
                    }
                }).build();
    }

    private static String css(int color) {
        return String.format(Locale.ROOT, "#%06x", color & 0xffffff);
    }
}
