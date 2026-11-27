package dev.local.clipvault;

import android.graphics.Color;
import android.graphics.Rect;
import android.text.TextUtils;
import android.util.LruCache;
import android.view.Gravity;
import android.view.View;
import android.webkit.WebView;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.io.IOException;
import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.function.Consumer;

final class LinkPreviewCards {
    private static final int PUBLIC_FETCHES = 2;
    private static final ExecutorService FETCH = Executors.newFixedThreadPool(PUBLIC_FETCHES + 1);

    private record Response(LinkPreview.Result value) {}
    private static final class Request {
        Future<?> future;
    }

    private final MainActivity activity;
    private final ArrayList<Card> cards = new ArrayList<>();
    private final Map<URI, Request> pending = new HashMap<>();
    private final LruCache<URI, Response> cache = new LruCache<>(8 * 1024 * 1024) {
        @Override protected int sizeOf(URI url, Response response) {
            return 16 * 1024 + (response.value() == null || response.value().thumbnail() == null
                    ? 0 : response.value().thumbnail().getAllocationByteCount());
        }
    };

    private Card playing;
    private Card privateCard;
    private Future<?> privateFetch;

    LinkPreviewCards(MainActivity activity) {
        this.activity = activity;
    }

    void bind(FrameLayout slot, Vault.Entry entry) {
        if (entry.sensitive() || entry.attachment()) return;
        URI url = Article.firstWebLink(entry.content(), entry.format());
        if (url == null) return;
        Card card = attach(slot, url, false);
        cards.add(card);
        Response cached = cache.get(url);
        if (pending.containsKey(url)) card.showLoading();
        else if (cached != null) card.showResponse(cached);
    }

    void revealPrivate(FrameLayout slot, Vault.Entry entry) {
        if (!slot.isAttachedToWindow()) return;
        URI url = entry.attachment() ? null : Article.firstWebLink(entry.content(), entry.format());
        if (url == null) {
            activity.toast("這張剪輯內文沒有可預覽的網址。");
            return;
        }
        hidePrivate();
        privateCard = attach(slot, url, true);
    }

    private Card attach(FrameLayout slot, URI url, boolean sensitive) {
        Card card = new Card(slot, url, sensitive);
        slot.addView(card);
        slot.setVisibility(View.VISIBLE);
        return card;
    }

    void clearCards() {
        stopPlaying();
        hidePrivate();
        cards.clear();
    }

    void background() {
        stopPlaying();
        hidePrivate();
        for (Request request : pending.values()) request.future.cancel(true);
        pending.clear();
    }

    boolean goBack() {
        if (playing != null) {
            stopPlaying();
            return true;
        }
        if (privateCard != null) {
            hidePrivate();
            return true;
        }
        return false;
    }

    void loadVisible() {
        Rect visible = new Rect();
        if (playing != null && !playing.media.getGlobalVisibleRect(visible)) stopPlaying();
        if (!activity.canAct()) return;
        for (Card card : cards) {
            if (!card.getGlobalVisibleRect(visible)) {
                if (card.image.getDrawable() != null) card.image.setImageDrawable(null);
                continue;
            }
            if (card.hasThumbnail && card.image.getDrawable() == null) {
                Response cached = cache.get(card.url);
                if (cached == null) requestPublic(card.url);
                else card.showResponse(cached);
            } else if (!card.loaded) requestPublic(card.url);
        }
    }

    private void requestPublic(URI url) {
        if (!activity.canAct() || pending.size() >= PUBLIC_FETCHES || pending.containsKey(url)) return;
        Request request = new Request();
        pending.put(url, request);
        request.future = fetch(url, response -> {
            if (!pending.remove(url, request)) return;
            cache.put(url, response);
            for (Card card : cards) {
                if (card.url.equals(url)) card.showResponse(response);
            }
            loadVisible();
        });
        for (Card card : cards) {
            if (card.url.equals(url)) card.showLoading();
        }
    }

    private void requestPrivate(Card card) {
        if (!activity.canAct() || privateCard != card || privateFetch != null) return;
        card.showLoading();
        privateFetch = fetch(card.url, response -> {
            if (privateCard != card) return;
            privateFetch = null;
            card.showResponse(response);
        });
    }

    private Future<?> fetch(URI url, Consumer<Response> complete) {
        return FETCH.submit(() -> {
            LinkPreview.Result data;
            try {
                data = LinkPreview.load(url);
            } catch (IOException | SecurityException error) {
                data = null;
            }
            Response response = new Response(data);
            activity.runOnUiThread(() -> complete.accept(response));
        });
    }

    private void hidePrivate() {
        if (privateCard == null) return;
        if (playing == privateCard) stopPlaying();
        if (privateFetch != null) privateFetch.cancel(true);
        privateFetch = null;
        FrameLayout slot = privateCard.slot;
        slot.removeAllViews();
        slot.setVisibility(View.GONE);
        privateCard = null;
    }

    private void stopPlaying() {
        if (playing == null) return;
        playing.stop();
        playing = null;
    }

    private void play(Card card) {
        if (!activity.canAct() || !card.isAttachedToWindow()) return;
        stopPlaying();
        playing = card;
        card.play();
    }

    private final class Card extends LinearLayout {
        final FrameLayout slot;
        final URI url;
        final String videoId;
        final FrameLayout media;
        final FrameLayout cover;
        final ImageView image;
        final TextView title;
        final Button retry;
        final Button stop;
        final TextView playerError;
        boolean loaded;
        boolean hasThumbnail;
        WebView player;

        Card(FrameLayout slot, URI url, boolean sensitive) {
            super(activity);
            this.slot = slot;
            this.url = url;
            videoId = LinkPreview.youtubeId(url);
            setOrientation(VERTICAL);
            setBackground(activity.panel(12));
            setPadding(activity.dp(8), activity.dp(8), activity.dp(8), activity.dp(4));

            int accent = activity.getColor(R.color.vault_accent);
            int surface = activity.getColor(R.color.vault_surface_soft);
            int muted = activity.getColor(R.color.vault_secondary_text);
            media = new FrameLayout(activity);
            media.setBackgroundColor(surface);
            cover = new FrameLayout(activity);
            image = new ImageView(activity);
            image.setScaleType(ImageView.ScaleType.CENTER_CROP);
            image.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
            cover.addView(image, new FrameLayout.LayoutParams(-1, -1));
            media.addView(cover, new FrameLayout.LayoutParams(-1, -1));
            media.setVisibility(videoId.isEmpty() ? View.GONE : View.VISIBLE);
            addView(media, activity.margins(-1, activity.dp(144), 0, 4));

            if (videoId.isEmpty()) {
                cover.setContentDescription("開啟內文連結");
                cover.setFocusable(true);
                cover.setOnClickListener(view -> activity.openLink(url.toString()));
            } else {
                Button play = activity.button("▶  播放 YouTube", accent,
                        activity.getColor(R.color.vault_on_accent), () -> LinkPreviewCards.this.play(this));
                play.setContentDescription("播放 YouTube");
                cover.addView(play, new FrameLayout.LayoutParams(-2, -2, Gravity.CENTER));
                cover.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
                cover.setOnClickListener(view -> LinkPreviewCards.this.play(this));
            }

            title = activity.text("", 15, activity.getColor(R.color.vault_text), true);
            title.setMaxLines(2);
            title.setEllipsize(TextUtils.TruncateAt.END);
            title.setVisibility(View.GONE);
            addView(title, activity.margins(-1, -2, 4, 0));
            TextView host = activity.text(url.getHost(), 13, muted, false);
            host.setMaxLines(1);
            host.setEllipsize(TextUtils.TruncateAt.END);
            addView(host, activity.margins(-1, -2, 4, 0));
            playerError = activity.text("無法載入播放器。可在 YouTube 開啟影片。", 13, muted, false);
            playerError.setVisibility(View.GONE);
            addView(playerError, activity.margins(-1, -2, 4, 0));

            LinearLayout actions = activity.row();
            retry = activity.button("載入預覽", Color.TRANSPARENT, accent,
                    () -> { if (sensitive) requestPrivate(this); else requestPublic(url); });
            retry.setVisibility(sensitive ? View.VISIBLE : View.GONE);
            actions.addView(retry, new LinearLayout.LayoutParams(0, -2, 1));
            String openLabel = videoId.isEmpty() ? "開啟內文連結" : "在 YouTube 開啟";
            actions.addView(activity.button(openLabel, Color.TRANSPARENT, accent,
                    () -> activity.openLink(url.toString())), new LinearLayout.LayoutParams(0, -2, 1));
            stop = activity.button("停止", Color.TRANSPARENT, accent, LinkPreviewCards.this::stopPlaying);
            stop.setVisibility(View.GONE);
            actions.addView(stop, new LinearLayout.LayoutParams(0, -2, 1));
            addView(actions);
            if (sensitive) {
                addView(activity.button("隱藏私密預覽", Color.TRANSPARENT, accent, LinkPreviewCards.this::hidePrivate));
            }
        }

        void showLoading() {
            retry.setVisibility(View.GONE);
            title.setText("正在載入預覽…");
            title.setVisibility(View.VISIBLE);
        }

        void showResponse(Response response) {
            loaded = true;
            LinkPreview.Result result = response.value();
            hasThumbnail = result != null && result.thumbnail() != null;
            image.setImageBitmap(hasThumbnail ? result.thumbnail() : null);
            media.setVisibility(!videoId.isEmpty() || hasThumbnail ? View.VISIBLE : View.GONE);
            if (result == null) {
                title.setText("無法載入網址預覽。");
                title.setVisibility(View.VISIBLE);
                retry.setText("重試");
                retry.setVisibility(View.VISIBLE);
                return;
            }
            retry.setVisibility(View.GONE);
            title.setText(result.title());
            title.setVisibility(result.title().isEmpty() ? View.GONE : View.VISIBLE);
        }

        void play() {
            cover.setVisibility(View.GONE);
            int height = Math.max(activity.dp(200), media.getWidth() * 9 / 16);
            media.getLayoutParams().height = height;
            media.requestLayout();
            stop.setVisibility(View.VISIBLE);
            player = YouTubePlayer.create(activity, videoId, () -> playerError.setVisibility(View.VISIBLE));
            media.addView(player, new FrameLayout.LayoutParams(-1, -1));
        }

        void stop() {
            if (player == null) return;
            media.removeView(player);
            player.stopLoading();
            player.destroy();
            player = null;
            cover.setVisibility(View.VISIBLE);
            media.getLayoutParams().height = activity.dp(144);
            media.requestLayout();
            stop.setVisibility(View.GONE);
            playerError.setVisibility(View.GONE);
        }
    }
}
