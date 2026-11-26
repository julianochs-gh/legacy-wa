package org.legacywa;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;

import android.content.ContentResolver;
import android.content.Context;
import android.content.res.AssetFileDescriptor;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.media.ExifInterface;
import android.media.MediaMetadataRetriever;
import android.media.ThumbnailUtils;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.webkit.MimeTypeMap;

/** Preparing attachments for upload and storing downloads. */
final class Media {
    private Media() {}

    static final class Prepared {
        byte[] data;      // in-memory payload (images, recordings) or null
        Uri uri;          // stream from here when data == null
        long length;
        byte[] thumb;
        String kind;      // image, video, audio, document
        String mime;
        String name;
        int width, height, seconds;
    }

    static Prepared image(Context ctx, Uri uri, File cameraFile) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        decode(cr, uri, o);
        if (o.outWidth <= 0) throw new IllegalArgumentException("Bild konnte nicht gelesen werden");
        int sample = 1;
        while (Math.max(o.outWidth, o.outHeight) / sample > 2400) sample *= 2;
        BitmapFactory.Options o2 = new BitmapFactory.Options();
        o2.inSampleSize = sample;
        Bitmap b = decode(cr, uri, o2);
        if (b == null) throw new IllegalArgumentException("Bild konnte nicht gelesen werden");
        float scale = 1600f / Math.max(b.getWidth(), b.getHeight());
        int rot = orientation(cr, uri, cameraFile);
        if (scale < 1f || rot != 0) {
            Matrix m = new Matrix();
            if (scale < 1f) m.postScale(scale, scale);
            if (rot != 0) m.postRotate(rot);
            Bitmap r = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            if (r != b) b.recycle();
            b = r;
        }
        Prepared p = new Prepared();
        p.kind = "image";
        p.mime = "image/jpeg";
        p.width = b.getWidth();
        p.height = b.getHeight();
        p.data = jpeg(b, 80);
        p.length = p.data.length;
        p.thumb = thumb(b);
        b.recycle();
        return p;
    }

    static Prepared video(Context ctx, Uri uri) throws Exception {
        Prepared p = file(ctx, uri);
        String path = dataPath(ctx, uri);
        if (path != null) {
            Bitmap t = ThumbnailUtils.createVideoThumbnail(path, MediaStore.Images.Thumbnails.MINI_KIND);
            if (t != null) {
                p.thumb = thumb(t);
                t.recycle();
            }
            try {
                MediaMetadataRetriever mr = new MediaMetadataRetriever();
                mr.setDataSource(path);
                p.seconds = (int) (parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)) / 1000);
                p.width = (int) parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
                p.height = (int) parseLong(mr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
                mr.release();
            } catch (Throwable ignored) {
            }
        }
        // WhatsApp only plays MP4 videos; anything else goes as a file.
        p.kind = "video/mp4".equals(p.mime) && p.thumb != null ? "video" : "document";
        return p;
    }

    static Prepared file(Context ctx, Uri uri) throws Exception {
        ContentResolver cr = ctx.getContentResolver();
        Prepared p = new Prepared();
        p.uri = uri;
        p.kind = "document";
        p.mime = cr.getType(uri);
        p.length = -1;
        Cursor c = null;
        try {
            c = cr.query(uri, null, null, null, null);
            if (c != null && c.moveToFirst()) {
                int ni = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (ni >= 0) p.name = c.getString(ni);
                if (si >= 0 && !c.isNull(si)) p.length = c.getLong(si);
            }
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        if (p.name == null) p.name = uri.getLastPathSegment();
        if (p.length <= 0) {
            try {
                AssetFileDescriptor afd = cr.openAssetFileDescriptor(uri, "r");
                p.length = afd.getLength();
                afd.close();
            } catch (Exception ignored) {
            }
        }
        if (p.length <= 0 && "file".equals(uri.getScheme())) p.length = new File(uri.getPath()).length();
        if (p.length <= 0) {
            // unknown size: read into memory
            p.data = Api.readAll(cr.openInputStream(uri));
            p.length = p.data.length;
        }
        if (p.mime == null && p.name != null) {
            int dot = p.name.lastIndexOf('.');
            if (dot >= 0) p.mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(p.name.substring(dot + 1).toLowerCase());
        }
        if (p.mime == null) p.mime = "application/octet-stream";
        return p;
    }

    static Prepared recording(File f, String mime, int seconds) throws Exception {
        Prepared p = new Prepared();
        p.kind = "audio";
        p.mime = mime;
        p.seconds = seconds;
        p.data = Api.readAll(new java.io.FileInputStream(f));
        p.length = p.data.length;
        p.name = f.getName();
        return p;
    }

    static InputStream open(Context ctx, Prepared p) throws Exception {
        if (p.data != null) return new java.io.ByteArrayInputStream(p.data);
        return ctx.getContentResolver().openInputStream(p.uri);
    }

    // ------------------------------------------------------------ helpers

    private static Bitmap decode(ContentResolver cr, Uri uri, BitmapFactory.Options o) throws Exception {
        InputStream in = cr.openInputStream(uri);
        try {
            return BitmapFactory.decodeStream(in, null, o);
        } finally {
            in.close();
        }
    }

    private static int orientation(ContentResolver cr, Uri uri, File file) {
        try {
            String path = file != null ? file.getAbsolutePath() : ("file".equals(uri.getScheme()) ? uri.getPath() : null);
            if (path != null) {
                int o = new ExifInterface(path).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1);
                return o == 6 ? 90 : o == 3 ? 180 : o == 8 ? 270 : 0;
            }
            Cursor c = cr.query(uri, new String[] {MediaStore.Images.ImageColumns.ORIENTATION}, null, null, null);
            if (c != null) {
                try {
                    if (c.moveToFirst()) return c.getInt(0);
                } finally {
                    c.close();
                }
            }
        } catch (Exception ignored) {
        }
        return 0;
    }

    private static String dataPath(Context ctx, Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getPath();
        Cursor c = null;
        try {
            c = ctx.getContentResolver().query(uri, new String[] {MediaStore.MediaColumns.DATA}, null, null, null);
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (Exception ignored) {
        } finally {
            if (c != null) c.close();
        }
        return null;
    }

    static byte[] jpeg(Bitmap b, int quality) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, quality, out);
        return out.toByteArray();
    }

    static byte[] thumb(Bitmap b) {
        float s = 96f / Math.max(b.getWidth(), b.getHeight());
        Bitmap t = Bitmap.createScaledBitmap(b, Math.max(1, (int) (b.getWidth() * s)), Math.max(1, (int) (b.getHeight() * s)), true);
        byte[] j = jpeg(t, 60);
        if (t != b) t.recycle();
        return j;
    }

    private static long parseLong(String s) {
        try {
            return s == null ? 0 : Long.parseLong(s.trim());
        } catch (Exception e) {
            return 0;
        }
    }

    /** Where a downloaded attachment is stored on the SD card. */
    static File downloadTarget(String kind, String id, String mime, String fileName) {
        String dir = "image".equals(kind) || "sticker".equals(kind) ? Environment.DIRECTORY_PICTURES
                : "video".equals(kind) ? Environment.DIRECTORY_MOVIES
                : "audio".equals(kind) || "ptt".equals(kind) ? Environment.DIRECTORY_MUSIC
                : Environment.DIRECTORY_DOWNLOADS;
        File base = new File(Environment.getExternalStoragePublicDirectory(dir), "LegacyWA");
        String name = fileName != null && fileName.length() > 0 ? fileName.replace('/', '_') : id.replace('/', '_') + ext(mime, kind);
        return new File(base, name);
    }

    static String cleanMime(String mime) {
        if (mime == null) return "*/*";
        int semi = mime.indexOf(';');
        return (semi >= 0 ? mime.substring(0, semi) : mime).trim();
    }

    private static String ext(String mime, String kind) {
        String m = cleanMime(mime);
        if ("image/jpeg".equals(m)) return ".jpg";
        if ("audio/ogg".equals(m)) return ".ogg";
        if ("audio/mp4".equals(m)) return ".m4a";
        String e = MimeTypeMap.getSingleton().getExtensionFromMimeType(m);
        if (e != null) return "." + e;
        if ("video".equals(kind)) return ".mp4";
        if ("image".equals(kind)) return ".jpg";
        return ".bin";
    }
}
