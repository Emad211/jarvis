package ir.emad.jarvis;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.widget.ImageView;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Loads a daemon-served image (a screenshot) into an ImageView, off the UI thread. */
final class Img {

    private static final ExecutorService POOL = Executors.newFixedThreadPool(2);

    private Img() {
    }

    static void load(ImageView view, String path) {
        if (path == null || path.isEmpty()) {
            return;
        }
        final String url = path.startsWith("http") ? path : Api.base() + path;
        view.setTag(url);
        POOL.execute(() -> {
            Bitmap bitmap = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(url).openConnection();
                c.setConnectTimeout(4000);
                c.setReadTimeout(15000);
                try (InputStream in = c.getInputStream()) {
                    bitmap = BitmapFactory.decodeStream(in);
                }
            } catch (Exception ignored) {
                // the row simply stays without a picture
            } finally {
                if (c != null) {
                    c.disconnect();
                }
            }
            final Bitmap out = bitmap;
            if (out != null) {
                view.post(() -> {
                    if (url.equals(view.getTag())) {
                        view.setImageBitmap(out);
                    }
                });
            }
        });
    }
}
