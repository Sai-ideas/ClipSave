package dev.local.clipvault;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;

import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.jsoup.nodes.Element;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

final class LinkPreview {
    private static final int HTML_LIMIT = 512 * 1024;
    private static final int IMAGE_LIMIT = 768 * 1024;
    private static final int THUMBNAIL_SIDE = 640;
    private static final Pattern CHARSET = Pattern.compile("(?i)charset\\s*=\\s*[\"']?([^\\s;\"']+)");
    private static final Pattern VIDEO_ID = Pattern.compile("[A-Za-z0-9_-]{11}");
    private static final Set<String> YOUTUBE_HOSTS = Set.of("youtube.com", "www.youtube.com",
            "m.youtube.com", "music.youtube.com", "youtube-nocookie.com", "www.youtube-nocookie.com");
    private static final Set<String> VIDEO_PATHS = Set.of("shorts", "live", "embed", "v");
    private static final Set<String> IMAGE_TYPES = Set.of("image/jpeg", "image/png", "image/webp", "image/gif");

    record Result(String title, Bitmap thumbnail) {}
    record Metadata(String title, URI image) {}
    private record Resource(URI url, String contentType, byte[] bytes) {}

    private LinkPreview() {}

    static String youtubeId(URI uri) {
        String host = uri.getHost();
        if (host == null) return "";
        host = host.toLowerCase(Locale.ROOT);
        String[] path = uri.getPath().split("/");
        String id = "";
        if (host.equals("youtu.be")) {
            if (path.length == 2) id = path[1];
        } else if (YOUTUBE_HOSTS.contains(host)) {
            if (path.length == 2 && path[1].equals("watch") && uri.getRawQuery() != null) {
                for (String parameter : uri.getRawQuery().split("&")) {
                    if (parameter.startsWith("v=")) {
                        id = parameter.substring(2);
                        break;
                    }
                }
            } else if (path.length == 3 && VIDEO_PATHS.contains(path[1])) {
                id = path[2];
            }
        }
        return VIDEO_ID.matcher(id).matches() ? id : "";
    }

    static Result load(URI url) throws IOException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(12);
        Resource page = fetch(url, false, deadline);
        Metadata metadata = metadata(page.bytes(), page.contentType(), page.url());
        Bitmap thumbnail = null;
        if (metadata.image() != null) {
            try {
                thumbnail = decodeThumbnail(fetch(metadata.image(), true, deadline).bytes());
            } catch (IOException ignored) {
                // The page title remains usable when the thumbnail is unavailable.
            }
        }
        return new Result(metadata.title(), thumbnail);
    }

    static Metadata metadata(byte[] html, String contentType, URI url) throws IOException {
        Matcher charset = CHARSET.matcher(contentType);
        String encoding = null;
        if (charset.find()) {
            try {
                encoding = Charset.forName(charset.group(1)).name();
            } catch (IllegalArgumentException ignored) {}
        }
        Document document = Jsoup.parse(new ByteArrayInputStream(html), encoding, url.toString());
        String title = meta(document, "og:title", "twitter:title");
        if (title.isEmpty()) title = document.title();
        title = title.replaceAll("\\s+", " ").trim();
        if (title.codePointCount(0, title.length()) > 300) title = title.substring(0, title.offsetByCodePoints(0, 300));
        String thumbnail = meta(document, "og:image:secure_url", "og:image", "twitter:image", "twitter:image:src");
        URI image = null;
        if (!thumbnail.isEmpty()) {
            try {
                URI candidate = url.resolve(thumbnail);
                if (isHttp(candidate) && (!isHttps(url) || isHttps(candidate))) image = candidate;
            } catch (IllegalArgumentException ignored) {}
        }
        return new Metadata(title, image);
    }

    private static String meta(Document document, String... keys) {
        var elements = document.select("meta[content]");
        for (String key : keys) {
            for (Element element : elements) {
                if (key.equalsIgnoreCase(element.attr("property")) || key.equalsIgnoreCase(element.attr("name"))) {
                    String value = element.attr("content").trim();
                    if (!value.isEmpty()) return value;
                }
            }
        }
        return "";
    }

    private static Resource fetch(URI url, boolean image, long deadline) throws IOException {
        for (int redirects = 0; redirects <= 4; redirects++) {
            int timeout = timeout(deadline);
            checkPublicHost(url);
            HttpURLConnection connection = (HttpURLConnection) url.toURL().openConnection();
            try {
                connection.setInstanceFollowRedirects(false);
                connection.setUseCaches(false);
                connection.setConnectTimeout(timeout);
                connection.setReadTimeout(timeout);
                connection.setRequestProperty("Accept", image
                        ? "image/webp,image/png,image/jpeg,image/gif" : "text/html,application/xhtml+xml");
                int status = connection.getResponseCode();
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    String location = connection.getHeaderField("Location");
                    if (location == null || location.isEmpty()) throw new IOException("Missing redirect location");
                    URI next = url.resolve(location);
                    if (isHttps(url) && !isHttps(next)) throw new IOException("Insecure redirect");
                    url = next;
                    continue;
                }
                if (status < 200 || status >= 300) throw new IOException("Preview request failed");
                String type = connection.getContentType();
                if (type == null) type = "";
                String mime = type.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
                if (image ? !IMAGE_TYPES.contains(mime)
                        : !(mime.equals("text/html") || mime.equals("application/xhtml+xml") || mime.isEmpty())) {
                    throw new IOException("Unsupported preview media type");
                }
                int limit = image ? IMAGE_LIMIT : HTML_LIMIT;
                if (image && connection.getContentLengthLong() > limit) throw new IOException("Large thumbnail");
                try (InputStream input = connection.getInputStream()) {
                    byte[] bytes = read(input, limit, deadline);
                    return new Resource(url, type, bytes);
                }
            } catch (IllegalArgumentException malformed) {
                throw new IOException("Invalid preview URL", malformed);
            } finally {
                connection.disconnect();
            }
        }
        throw new IOException("Too many redirects");
    }

    private static byte[] read(InputStream input, int limit, long deadline) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buffer = new byte[8192];
        while (out.size() < limit) {
            timeout(deadline);
            int count = input.read(buffer, 0, Math.min(buffer.length, limit - out.size()));
            if (count < 0) break;
            out.write(buffer, 0, count);
        }
        return out.toByteArray();
    }

    private static int timeout(long deadline) throws IOException {
        long remaining = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime());
        if (Thread.currentThread().isInterrupted() || remaining < 1) throw new IOException("Preview canceled or timed out");
        return (int) Math.min(5000, remaining);
    }

    private static boolean isHttps(URI url) {
        return "https".equalsIgnoreCase(url.getScheme());
    }

    private static boolean isHttp(URI url) {
        return (isHttps(url) || "http".equalsIgnoreCase(url.getScheme()))
                && url.getHost() != null && url.getRawUserInfo() == null;
    }

    private static void checkPublicHost(URI url) throws IOException {
        if (!isHttp(url)) throw new IOException("Invalid preview URL");
        for (InetAddress address : InetAddress.getAllByName(url.getHost())) {
            if (!isPublic(address)) throw new IOException("Non-public preview host");
        }
    }

    private static boolean isPublic(InetAddress address) {
        if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isMulticastAddress()) return false;
        byte[] bytes = address.getAddress();
        int first = Byte.toUnsignedInt(bytes[0]);
        int second = Byte.toUnsignedInt(bytes[1]);
        if (bytes.length == 16) return (first & 0xe0) == 0x20;
        return first != 0 && first < 224 && !(first == 100 && (second & 0xc0) == 64)
                && !(first == 198 && (second == 18 || second == 19));
    }

    private static Bitmap decodeThumbnail(byte[] bytes) {
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
        int side = Math.max(options.outWidth, options.outHeight);
        if (side <= 0 || side > 8192) return null;
        options.inSampleSize = 1;
        while (side / options.inSampleSize > THUMBNAIL_SIDE) options.inSampleSize *= 2;
        options.inJustDecodeBounds = false;
        return BitmapFactory.decodeByteArray(bytes, 0, bytes.length, options);
    }
}
