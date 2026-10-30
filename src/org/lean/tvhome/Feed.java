package org.lean.tvhome;

import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.util.JsonReader;
import android.util.JsonToken;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Top Shelf content, the way tvOS apps fill it: trending films and shows with full-screen
 * artwork and a title logo. Lists come from public Stremio catalogs (Cinemeta, Streaming
 * Catalogs for Netflix and Prime Video, Kitsu for anime) and pictures from their image CDN.
 * Everything is cached on disk, so the shelf still shows something when the network is down.
 */
final class Feed {
    private Feed() {}

    /** One title in a shelf. */
    static final class Item {
        final String id, type, name, line, bgUrl, logoUrl;

        Item(String id, String type, String name, String line, String bgUrl, String logoUrl) {
            this.id = id;
            this.type = type;
            this.name = name;
            this.line = line;
            this.bgUrl = bgUrl;
            this.logoUrl = logoUrl;
        }
    }

    /** Decoded pictures of an item. {@code logo} is null when the title has none. */
    static final class Pics {
        final Bitmap bg, soft, logo;

        Pics(Bitmap bg, Bitmap soft, Bitmap logo) {
            this.bg = bg;
            this.soft = soft;
            this.logo = logo;
        }
    }

    interface Listener<T> {
        void done(T value);
    }

    private static final String SC = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/catalog/";
    private static final String CINEMETA = "https://v3-cinemeta.strem.io/catalog/";
    private static final String KITSU = "https://anime-kitsu.strem.fun/catalog/anime/kitsu-anime-trending.json";
    private static final long FRESH_MS = 12 * 3600 * 1000L;
    private static final int PER_LIST = 12;

    private static final ExecutorService net = Executors.newSingleThreadExecutor(r -> daemon(r, "feed-net"));
    private static final ExecutorService disk = Executors.newSingleThreadExecutor(r -> daemon(r, "feed-disk"));
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static final Map<String, List<Item>> lists = new HashMap<>();
    private static final Map<String, Boolean> fetching = new HashMap<>();
    /** The last few decoded items; a full-screen picture is about 2 MB. */
    private static final Map<String, Pics> pics = new LinkedHashMap<String, Pics>(8, 0.75f, true) {
        @Override
        protected boolean removeEldestEntry(Map.Entry<String, Pics> e) {
            return size() > 6;
        }
    };

    private static Thread daemon(Runnable r, String name) {
        Thread t = new Thread(r, name);
        t.setDaemon(true);
        t.setPriority(Thread.MIN_PRIORITY + 1);
        return t;
    }

    // ---- which catalog for which app ------------------------------------------------------------

    /** The catalog that fits an app, or null for apps that are not about films and shows. */
    static String source(Context c, String pkg) {
        String p = pkg.toLowerCase();
        if (p.startsWith("com.netflix")) return "nfx";
        if (p.startsWith("com.amazon.amazonvideo") || p.contains("primevideo")) return "amp";
        if (p.startsWith("com.disney")) return "dnp";
        if (p.startsWith("com.hbo") || p.startsWith("com.wbd")) return "hbm";
        if (p.startsWith("com.apple.atve")) return "atp";
        if (p.contains("anime") || p.contains("crunchyroll")) return "anime";
        // Tools, stores, browsers and YouTube get the app's own colours instead.
        String[] not = {"youtube", "smarttube", "browser", "downloader", "vending", "settings", "store",
            "unlinked", "drive_hunter", "file", "pipup", "adbappcontrol", "music", "spotify", "twitch", "tvhome"};
        for (String n : not) if (p.contains(n)) return null;
        try {
            ApplicationInfo ai = c.getPackageManager().getApplicationInfo(pkg, 0);
            if ((ai.flags & ApplicationInfo.FLAG_SYSTEM) != 0 && !p.contains("kodi")) return null;
            int cat = ai.category;
            if (cat != ApplicationInfo.CATEGORY_VIDEO && cat != ApplicationInfo.CATEGORY_UNDEFINED) return null;
        } catch (Exception e) {
            return null;
        }
        return "top";
    }

    private static String[] urls(String source) {
        switch (source) {
            case "top":
                return new String[] {CINEMETA + "movie/top.json", CINEMETA + "series/top.json"};
            case "anime":
                return new String[] {KITSU};
            default:
                return new String[] {SC + "movie/" + source + ".json", SC + "series/" + source + ".json"};
        }
    }

    // ---- lists --------------------------------------------------------------------------------

    /**
     * The titles for a catalog: answers at once from memory or disk (possibly an empty list) and
     * fetches a fresh copy in the background when the saved one is old; {@code updated} then
     * gets the new list on the main thread.
     */
    static List<Item> list(Context c, String source, Listener<List<Item>> updated) {
        List<Item> l = lists.get(source);
        File f = listFile(c, source);
        if (l == null) {
            l = read(f);
            lists.put(source, l);
        }
        boolean stale = !f.exists() || System.currentTimeMillis() - f.lastModified() > FRESH_MS;
        if (stale && !Boolean.TRUE.equals(fetching.get(source))) {
            fetching.put(source, true);
            Context app = c.getApplicationContext();
            net.execute(() -> {
                List<Item> fresh = fetch(source);
                if (!fresh.isEmpty()) write(f, fresh);
                main.post(() -> {
                    fetching.remove(source);
                    if (fresh.isEmpty()) return;
                    lists.put(source, fresh);
                    if (updated != null) updated.done(fresh);
                });
            });
        }
        return l;
    }

    private static File listFile(Context c, String source) {
        File d = new File(c.getFilesDir(), "shelf");
        d.mkdirs();
        return new File(d, source + ".tsv");
    }

    private static List<Item> fetch(String source) {
        List<List<Item>> parts = new ArrayList<>();
        for (String u : urls(source)) {
            try {
                parts.add(parse(open(u)));
            } catch (Exception e) {
                parts.add(new ArrayList<>());
            }
        }
        // Films and shows take turns.
        List<Item> out = new ArrayList<>();
        for (int i = 0; i < PER_LIST; i++) {
            for (List<Item> p : parts) if (i < p.size()) out.add(p.get(i));
        }
        return out;
    }

    private static InputStream open(String u) throws IOException {
        for (int hop = 0; hop < 4; hop++) {
            HttpURLConnection h = (HttpURLConnection) new URL(u).openConnection();
            h.setConnectTimeout(8000);
            h.setReadTimeout(12000);
            h.setInstanceFollowRedirects(true);
            h.setRequestProperty("User-Agent", "LeanTVHome/2");
            int code = h.getResponseCode();
            if (code >= 300 && code < 400 && h.getHeaderField("Location") != null) {
                // Redirects across hosts or schemes are not followed automatically.
                u = new URL(new URL(u), h.getHeaderField("Location")).toString();
                h.disconnect();
                continue;
            }
            if (code != 200) {
                h.disconnect();
                throw new IOException("HTTP " + code);
            }
            return h.getInputStream();
        }
        throw new IOException("too many redirects");
    }

    /** Reads the first titles of a catalog that have a background picture. */
    private static List<Item> parse(InputStream in) throws IOException {
        List<Item> out = new ArrayList<>();
        try (JsonReader r = new JsonReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            r.beginObject();
            while (r.hasNext()) {
                if (!r.nextName().equals("metas")) {
                    r.skipValue();
                    continue;
                }
                r.beginArray();
                while (r.hasNext() && out.size() < PER_LIST) {
                    Item it = meta(r);
                    if (it != null) out.add(it);
                }
                // The rest is not needed; closing the stream ends the download.
                return out;
            }
        }
        return out;
    }

    private static Item meta(JsonReader r) throws IOException {
        String id = null, imdb = null, type = "movie", name = null, year = "", bg = null, logo = null;
        List<String> genres = new ArrayList<>();
        r.beginObject();
        while (r.hasNext()) {
            String k = r.nextName();
            if (r.peek() == JsonToken.NULL) {
                r.skipValue();
                continue;
            }
            switch (k) {
                case "id": id = r.nextString(); break;
                case "imdb_id": imdb = r.nextString(); break;
                case "type": type = r.nextString(); break;
                case "name": name = r.nextString(); break;
                case "releaseInfo": year = r.nextString(); break;
                case "background": bg = r.nextString(); break;
                case "logo": logo = r.nextString(); break;
                case "genres":
                case "genre":
                    if (r.peek() == JsonToken.BEGIN_ARRAY && genres.isEmpty()) {
                        r.beginArray();
                        while (r.hasNext()) {
                            if (r.peek() == JsonToken.STRING) genres.add(r.nextString());
                            else r.skipValue();
                        }
                        r.endArray();
                    } else {
                        r.skipValue();
                    }
                    break;
                default: r.skipValue();
            }
        }
        r.endObject();
        if (imdb == null && id != null && id.startsWith("tt")) imdb = id;
        if (name == null || (bg == null && imdb == null)) return null;
        if (imdb != null) {
            // The same CDN for every catalog: 1280 x 720 backdrops and transparent title logos.
            bg = "https://images.metahub.space/background/medium/" + imdb + "/img";
            logo = "https://images.metahub.space/logo/medium/" + imdb + "/img";
        }
        String kind = type.equals("series") || type.equals("anime") ? "Series" : "Movie";
        StringBuilder line = new StringBuilder(kind);
        if (!year.isEmpty()) line.append("  ·  ").append(year.replace("–", "-"));
        for (int i = 0; i < Math.min(2, genres.size()); i++) line.append(i == 0 ? "  ·  " : ", ").append(genres.get(i));
        return new Item(imdb != null ? imdb : id, type, name, line.toString(), bg, logo);
    }

    private static List<Item> read(File f) {
        List<Item> out = new ArrayList<>();
        if (!f.exists()) return out;
        try (BufferedReader b = new BufferedReader(new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8))) {
            String s;
            while ((s = b.readLine()) != null) {
                String[] p = s.split("\t", -1);
                if (p.length == 6) out.add(new Item(p[0], p[1], p[2], p[3], p[4], p[5].isEmpty() ? null : p[5]));
            }
        } catch (IOException ignored) {
            // A damaged file is fetched again.
        }
        return out;
    }

    private static void write(File f, List<Item> items) {
        StringBuilder s = new StringBuilder();
        for (Item it : items) {
            s.append(clean(it.id)).append('\t').append(clean(it.type)).append('\t').append(clean(it.name)).append('\t')
                .append(clean(it.line)).append('\t').append(clean(it.bgUrl)).append('\t')
                .append(it.logoUrl == null ? "" : clean(it.logoUrl)).append('\n');
        }
        File tmp = new File(f.getPath() + ".tmp");
        try (OutputStream o = new FileOutputStream(tmp)) {
            o.write(s.toString().getBytes(StandardCharsets.UTF_8));
        } catch (IOException e) {
            return;
        }
        tmp.renameTo(f);
    }

    private static String clean(String s) {
        return s.replace('\t', ' ').replace('\n', ' ');
    }

    // ---- pictures -----------------------------------------------------------------------------

    /** Forgets the decoded pictures; they come back from the disk cache when next shown. */
    static void trim() {
        pics.clear();
    }

    /** Decoded pictures if they are in memory, else null. */
    static Pics cached(Item it) {
        return pics.get(it.id);
    }

    /** Loads (downloading if needed) an item's pictures; {@code done} runs on the main thread, with null on failure. */
    static void load(Context c, Item it, int screenW, int screenH, Listener<Pics> done) {
        Pics p = pics.get(it.id);
        if (p != null) {
            done.done(p);
            return;
        }
        File dir = new File(c.getCacheDir(), "shelf");
        File bgFile = new File(dir, safe(it.id) + ".jpg"), logoFile = new File(dir, safe(it.id) + ".png");
        Runnable decode = () -> {
            Pics out = decode(bgFile, logoFile, screenW, screenH);
            main.post(() -> {
                if (out != null) pics.put(it.id, out);
                done.done(out);
            });
        };
        if (bgFile.exists()) {
            disk.execute(decode);
            return;
        }
        net.execute(() -> {
            dir.mkdirs();
            prune(dir);
            boolean ok = download(it.bgUrl, bgFile);
            if (ok && it.logoUrl != null && !logoFile.exists() && !download(it.logoUrl, logoFile)) {
                // No logo: an empty file remembers that, and the name is drawn as text.
                try {
                    new FileOutputStream(logoFile).close();
                } catch (IOException ignored) {
                    // Tried again next time.
                }
            }
            if (ok) disk.execute(decode);
            else main.post(() -> done.done(null));
        });
    }

    /** Fetches the pictures of a few titles ahead of time, without decoding them. */
    static void prefetch(Context c, List<Item> items, int n) {
        File dir = new File(c.getCacheDir(), "shelf");
        for (int i = 0; i < Math.min(n, items.size()); i++) {
            Item it = items.get(i);
            net.execute(() -> {
                dir.mkdirs();
                File bg = new File(dir, safe(it.id) + ".jpg"), logo = new File(dir, safe(it.id) + ".png");
                if (!bg.exists() && download(it.bgUrl, bg) && it.logoUrl != null && !logo.exists()) download(it.logoUrl, logo);
            });
        }
    }

    private static String safe(String id) {
        return id.replaceAll("[^A-Za-z0-9_-]", "_");
    }

    /** Keeps the picture cache at about 120 files (roughly 25 MB). */
    private static void prune(File dir) {
        File[] files = dir.listFiles();
        if (files == null || files.length < 140) return;
        java.util.Arrays.sort(files, (a, b) -> Long.compare(a.lastModified(), b.lastModified()));
        for (int i = 0; i < files.length - 120; i++) files[i].delete();
    }

    private static boolean download(String u, File to) {
        File tmp = new File(to.getPath() + ".part");
        try (InputStream in = open(u); OutputStream o = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            long total = 0;
            while ((n = in.read(buf)) > 0) {
                o.write(buf, 0, n);
                total += n;
                if (total > 4_000_000) throw new IOException("too big");
            }
        } catch (IOException e) {
            tmp.delete();
            return false;
        }
        return tmp.renameTo(to);
    }

    private static Pics decode(File bgFile, File logoFile, int sw, int sh) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(bgFile.getPath(), o);
        if (o.outWidth <= 0) {
            bgFile.delete();
            return null;
        }
        o.inJustDecodeBounds = false;
        o.inSampleSize = o.outWidth >= 2560 ? 2 : 1;
        o.inPreferredConfig = Bitmap.Config.RGB_565;
        o.inMutable = true;
        Bitmap bg = BitmapFactory.decodeFile(bgFile.getPath(), o);
        if (bg == null) return null;
        bgFile.setLastModified(System.currentTimeMillis());
        scrim(bg);
        Bitmap logo = null;
        if (logoFile.exists() && logoFile.length() > 0) {
            BitmapFactory.Options lo = new BitmapFactory.Options();
            lo.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(logoFile.getPath(), lo);
            lo.inJustDecodeBounds = false;
            lo.inSampleSize = lo.outWidth > sw * 0.6f ? 2 : 1;
            logo = BitmapFactory.decodeFile(logoFile.getPath(), lo);
        }
        return new Pics(bg, soft(bg), logo);
    }

    /**
     * Darkens the picture where text and the Dock sit, baked into the bitmap so drawing it stays a
     * single cheap layer: a little at the top corners, more toward the bottom.
     */
    static void scrim(Bitmap b) {
        Canvas cv = new Canvas(b);
        int w = b.getWidth(), h = b.getHeight();
        Paint p = new Paint();
        p.setDither(true);
        p.setShader(new LinearGradient(0, h * 0.45f, 0, h, 0x00000000, 0x9a000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, h * 0.45f, w, h, p);
        p.setShader(new RadialGradient(0, 0, w * 0.55f, 0x70000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w * 0.55f, w * 0.55f, p);
        p.setShader(new LinearGradient(0, 0, 0, h * 0.2f, 0x38000000, 0x00000000, Shader.TileMode.CLAMP));
        cv.drawRect(0, 0, w, h * 0.2f, p);
    }

    /** A tiny blurred copy: the Dock's frosted glass and the page behind the lower rows. */
    static Bitmap soft(Bitmap b) {
        Bitmap s = Bitmap.createScaledBitmap(b, 64, 36, true).copy(Bitmap.Config.ARGB_8888, true);
        return Art.blurSmall(s, 3);
    }
}
