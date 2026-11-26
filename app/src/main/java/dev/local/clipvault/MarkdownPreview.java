package dev.local.clipvault;

import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
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
import org.commonmark.node.SourceSpan;
import org.commonmark.parser.IncludeSourceSpans;
import org.commonmark.parser.Parser;
import org.commonmark.renderer.NodeRenderer;
import org.commonmark.renderer.html.HtmlRenderer;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;
import java.util.function.IntPredicate;
import java.util.function.Supplier;

final class MarkdownPreview extends WebView {
    private static final List<Extension> EXTENSIONS =
            Arrays.asList(TaskListItemsExtension.create(), AutolinkExtension.create());
    private static final Parser PARSER = Parser.builder().extensions(EXTENSIONS)
            .includeSourceSpans(IncludeSourceSpans.BLOCKS).build();
    private static final String TASK_CLASS = "vault-task";
    private static final String TASK_SCHEME = "clipsave-task";
    private static final ExecutorService RENDERING = Executors.newSingleThreadExecutor();

    private static final String TASK_SCRIPT = """
            let pending = null;
            document.addEventListener('change', event => {
                const box = event.target;
                if (!box.hasAttribute('data-task')) return;
                if (pending) {
                    box.checked = !box.checked;
                    return;
                }
                pending = box;
                location.href = 'clipsave-task://toggle/' + box.dataset.task + '/' + Number(box.checked);
            });
            function finishTask(saved) {
                if (!pending) return;
                if (!saved) pending.checked = !pending.checked;
                pending = null;
            }
            """;

    interface TaskToggle {
        void change(int position, boolean checked, Consumer<Boolean> complete);
    }

    private Future<?> pendingRender;
    private int renderVersion;
    private boolean destroyed;
    private String taskToken;
    private Set<Integer> taskPositions = Collections.emptySet();

    @android.annotation.SuppressLint("SetJavaScriptEnabled")
    MarkdownPreview(Context context, Consumer<String> openLink, TaskToggle toggle) {
        super(context);
        setBackgroundColor(Color.TRANSPARENT);
        getSettings().setBlockNetworkLoads(true);
        getSettings().setCacheMode(WebSettings.LOAD_NO_CACHE);
        getSettings().setAllowFileAccess(false);
        getSettings().setAllowContentAccess(false);
        getSettings().setDefaultTextEncodingName("UTF-8");
        // Only the generated page's nonce-protected script runs. No JavaScript bridge is exposed.
        getSettings().setJavaScriptEnabled(true);
        setWebViewClient(new WebViewClient() {
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest request) {
                if (destroyed || !request.isForMainFrame() || !request.hasGesture()) return true;
                Uri target = request.getUrl();
                if (TASK_SCHEME.equals(target.getScheme())) changeTask(target, toggle);
                else if (Vault.isWebLink(target.toString())) openLink.accept(target.toString());
                return true;
            }
        });
    }

    private void changeTask(Uri target, TaskToggle toggle) {
        List<String> path = target.getPathSegments();
        if (!"toggle".equals(target.getHost()) || path.size() != 3 || !path.get(0).equals(taskToken)) return;
        int position;
        try {
            position = Integer.parseInt(path.get(1));
        } catch (NumberFormatException invalid) {
            return;
        }
        String state = path.get(2);
        if (!taskPositions.contains(position) || !(state.equals("0") || state.equals("1"))) return;
        int version = renderVersion;
        toggle.change(position, state.equals("1"), saved -> {
            if (!destroyed && renderVersion == version) evaluateJavascript("finishTask(" + saved + ")", null);
        });
    }

    void render(String source, Map<String, byte[]> localImages, IntPredicate editable) {
        Context context = getContext();
        String ink = css(context.getColor(R.color.vault_text));
        String accent = css(context.getColor(R.color.vault_accent));
        String panel = css(context.getColor(R.color.vault_surface_soft));
        String token = UUID.randomUUID().toString();
        String head = "<!doctype html><html><head><meta charset=\"utf-8\">"
                + "<meta name=\"viewport\" content=\"width=device-width, initial-scale=1\">"
                + "<meta http-equiv=\"Content-Security-Policy\" content=\"default-src 'none'; "
                + "style-src 'unsafe-inline'; img-src data:; script-src 'nonce-" + token + "'\">"
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
                + "input[data-task]{cursor:pointer;accent-color:" + accent + "}"
                + "</style></head><body>";
        int version = ++renderVersion;
        taskToken = null;
        if (pendingRender != null) pendingRender.cancel(true);
        pendingRender = RENDERING.submit(() -> {
            String markup;
            Set<Integer> positions = new HashSet<>();
            try {
                markup = renderer(localImages, token, source, editable, positions).render(parseForPreview(source));
            } catch (RuntimeException | StackOverflowError error) {
                markup = "<p>無法預覽這段 Markdown。可以返回查看原文。</p>";
                positions.clear();
            }
            String html = head + markup + "<script nonce=\"" + token + "\">" + TASK_SCRIPT + "</script></body></html>";
            post(() -> {
                if (!destroyed && version == renderVersion) {
                    taskToken = token;
                    taskPositions = positions;
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
        return renderer(Collections.emptyMap(), "", source, ignored -> false, Collections.emptySet())
                .render(parseForPreview(source));
    }

    static String updateTask(String source, int position, boolean checked) {
        if (!isTaskMarker(source, position)) return source;
        char previous = source.charAt(position + 1);
        if ((previous != ' ') == checked) return source;
        return source.substring(0, position + 1) + (checked ? "x" : " ") + source.substring(position + 2);
    }

    private static boolean isTaskMarker(String source, int position) {
        if (position < 0 || position > source.length() - 3) return false;
        char state = source.charAt(position + 1);
        return source.charAt(position) == '[' && source.charAt(position + 2) == ']'
                && (state == ' ' || state == 'x' || state == 'X');
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

    private static HtmlRenderer renderer(Map<String, byte[]> images, String token, String source,
                                         IntPredicate editable, Set<Integer> positions) {
        Map<String, String> encoded = new HashMap<>();
        return HtmlRenderer.builder().escapeHtml(true).sanitizeUrls(true)
                .nodeRendererFactory(context -> new NodeRenderer() {
                    @Override public Set<Class<? extends Node>> getNodeTypes() {
                        return Collections.singleton(TaskListItemMarker.class);
                    }

                    @Override public void render(Node node) {
                        TaskListItemMarker marker = (TaskListItemMarker) node;
                        List<SourceSpan> spans = marker.getParent().getSourceSpans();
                        int position = spans.isEmpty() ? -1 : spans.get(0).getInputIndex();
                        Map<String, String> attributes = new java.util.LinkedHashMap<>();
                        attributes.put("type", "checkbox");
                        if (isTaskMarker(source, position) && editable.test(position)) {
                            positions.add(position);
                            attributes.put("data-task", token + "/" + position);
                            attributes.put("aria-label", "切換待辦");
                        } else {
                            attributes.put("disabled", "");
                        }
                        if (marker.isChecked()) attributes.put("checked", "");
                        context.getWriter().tag("input", attributes, true);
                        context.getWriter().raw(" ");
                    }
                })
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
