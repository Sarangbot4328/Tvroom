package com.tvroom.downloader.ui;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import com.tvroom.downloader.R;
import java.io.File;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small shared cache and off-main-thread decoding; recycled rows never receive stale images. */
final class PlaylistThumbnails {
    private static final ExecutorService WORKERS = Executors.newFixedThreadPool(2);
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final LruCache<String, Bitmap> CACHE = new LruCache<String, Bitmap>(6 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    // Accessed only on the main thread. Weak references do not retain closed dialogs/activities.
    private static final Map<String, List<WeakReference<ImageView>>> PENDING = new HashMap<>();

    static void bind(ImageView view, String path) {
        view.setTag(null);
        view.setImageResource(R.drawable.ic_playlist_placeholder);
        if (path == null || path.isEmpty()) return;
        File file = new File(path);
        if (!file.isFile()) return;
        String key = path + ":" + file.length() + ":" + file.lastModified();
        view.setTag(key);
        Bitmap cached = CACHE.get(key);
        if (cached != null) { view.setImageBitmap(cached); return; }
        List<WeakReference<ImageView>> waiting = PENDING.get(key);
        if (waiting != null) {
            for (WeakReference<ImageView> existing : waiting) if (existing.get() == view) return;
            waiting.add(new WeakReference<>(view)); return;
        }
        waiting = new ArrayList<>(); waiting.add(new WeakReference<>(view)); PENDING.put(key, waiting);
        WORKERS.execute(() -> {
            Bitmap image = decode(path);
            MAIN.post(() -> {
                if (image != null) CACHE.put(key, image);
                List<WeakReference<ImageView>> targets = PENDING.remove(key);
                if (targets != null) for (WeakReference<ImageView> reference : targets) {
                    ImageView target = reference.get();
                    if (target != null && key.equals(target.getTag()) && image != null) target.setImageBitmap(image);
                }
            });
        });
    }

    private static Bitmap decode(String path) {
        try {
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(path, options);
            if (options.outWidth <= 0 || options.outHeight <= 0) return null;
            options.inSampleSize = 1;
            while (options.outWidth / options.inSampleSize > 512 || options.outHeight / options.inSampleSize > 512) options.inSampleSize *= 2;
            options.inJustDecodeBounds = false;
            return BitmapFactory.decodeFile(path, options);
        } catch (RuntimeException | OutOfMemoryError error) { return null; }
    }

    static void clear(ImageView view) { view.setTag(null); view.setImageResource(R.drawable.ic_playlist_placeholder); }
    private PlaylistThumbnails() { }
}
