package com.clarifi.app;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

@CapacitorPlugin(name = "ClariFiBackup")
public class ClariFiBackupPlugin extends Plugin {
    @PluginMethod
    public void saveBackup(PluginCall call) {
        String filename = sanitizeFilename(call.getString("filename"));
        String content = call.getString("content", "");

        try {
            String path = writeBackupFile(filename, content);
            JSObject ret = new JSObject();
            ret.put("ok", true);
            ret.put("filename", filename);
            ret.put("path", path);
            call.resolve(ret);
        } catch (Exception e) {
            call.reject(e.getMessage() == null ? "Could not export backup" : e.getMessage(), e);
        }
    }

    private String sanitizeFilename(String filename) {
        String fallback = "clarifi-backup.json";
        String cleaned = filename == null ? fallback : filename.replaceAll("[\\\\/:*?\"<>|]", "-").trim();
        return cleaned.isEmpty() ? fallback : cleaned;
    }

    private String writeBackupFile(String filename, String content) throws Exception {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ContentValues values = new ContentValues();
            values.put(MediaStore.Downloads.DISPLAY_NAME, filename);
            values.put(MediaStore.Downloads.MIME_TYPE, "application/json");
            values.put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS);

            ContentResolver resolver = getContext().getContentResolver();
            Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
            if (uri == null) {
                throw new IllegalStateException("Could not create file in Downloads");
            }

            try (OutputStream out = resolver.openOutputStream(uri)) {
                if (out == null) {
                    throw new IllegalStateException("Could not open Downloads file");
                }
                out.write(content.getBytes(StandardCharsets.UTF_8));
            }
            return "Downloads/" + filename;
        }

        File dir = getContext().getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            throw new IllegalStateException("Downloads folder is unavailable");
        }
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IllegalStateException("Could not create Downloads folder");
        }

        File file = new File(dir, filename);
        try (FileOutputStream out = new FileOutputStream(file)) {
            out.write(content.getBytes(StandardCharsets.UTF_8));
        }
        return file.getAbsolutePath();
    }
}
