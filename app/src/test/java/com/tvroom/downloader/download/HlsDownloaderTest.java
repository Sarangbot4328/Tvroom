package com.tvroom.downloader.download;

import java.net.ServerSocket;
import java.net.Socket;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import com.tvroom.downloader.web.CaptureState;
import org.json.JSONObject;
import org.junit.Test;
import java.io.File;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import static org.junit.Assert.*;

public class HlsDownloaderTest {
    @Test(timeout = 15000) public void downloadsAaaSegmentsUsingManifestKeyAndCapturedReferer() throws Exception {
        byte[] plain = new byte[188 * 4];
        for (int i = 0; i < 4; i++) { plain[i * 188] = 0x47; plain[i * 188 + 3] = 0x10; }
        byte[] key = new byte[16];
        byte[] keyEnvelope = HlsKeyDecoderTest.envelope(key);
        Cipher cipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
        cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new IvParameterSpec(new byte[16]));
        byte[] encrypted = cipher.doFinal(plain);
        ServerSocket server = new ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"));
        String base = "http://127.0.0.1:" + server.getLocalPort();
        Thread serving = new Thread(() -> {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    BufferedReader reader = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.US_ASCII));
                    String request = reader.readLine();
                    String path = request.split(" ")[1];
                    boolean refererOk = false;
                    for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                        if (line.equalsIgnoreCase("Referer: https://player.example/")) refererOk = true;
                    }
                    byte[] body;
                    if ("/index.m3u8".equals(path)) {
                        body = ("#EXTM3U\n#EXT-X-TARGETDURATION:8\n#EXT-X-KEY:METHOD=AES-128,URI=\"key.bin\",IV=0x00000000000000000000000000000000\n"
                            + "#EXTINF:8,\nindex0.aaa\n#EXTINF:8,\nindex1.aaa\n#EXT-X-ENDLIST\n").getBytes(StandardCharsets.UTF_8);
                    } else if ("/key.bin".equals(path)) body = keyEnvelope;
                    else body = encrypted;
                    java.io.OutputStream out = socket.getOutputStream();
                    out.write(("HTTP/1.1 " + (refererOk ? "200 OK" : "403 Forbidden")
                        + "\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    out.write(body); out.flush();
                } catch (java.io.IOException error) {
                    if (!server.isClosed()) throw new RuntimeException(error);
                }
            }
        });
        serving.setDaemon(true);
        serving.start();
        File directory = Files.createTempDirectory("hls-aaa-test").toFile();
        try {
            JSONObject json = new JSONObject().put("id", "test").put("title", "test")
                .put("pageUrl", "https://tvroom31.org/video/movie/main")
                .put("keyHex", "ffffffffffffffffffffffffffffffff")
                .put("streamReferers", new JSONObject().put(base + "/index.m3u8", "https://player.example/"));
            HlsDownloader downloader = new HlsDownloader(CaptureState.Snapshot.fromJson(json.toString()),
                (message, percent) -> { }, new HlsDownloader.Cancellation() {
                    public boolean cancelled() { return false; }
                    public void connection(HttpURLConnection c) { }
                });
            Method resolve = HlsDownloader.class.getDeclaredMethod("resolvePlaylist", String.class);
            resolve.setAccessible(true);
            Object playlist = resolve.invoke(downloader, base + "/index.m3u8");
            Method download = HlsDownloader.class.getDeclaredMethod("downloadPlaylist", playlist.getClass(), File.class);
            download.setAccessible(true);
            download.invoke(downloader, playlist, directory);
            assertArrayEquals(plain, Files.readAllBytes(new File(directory, "seg_000000.ts").toPath()));
            assertArrayEquals(plain, Files.readAllBytes(new File(directory, "seg_000001.ts").toPath()));
        } finally {
            server.close();
            serving.join(1000);
            File[] files = directory.listFiles();
            if (files != null) for (File file : files) file.delete();
            directory.delete();
        }
    }
}
