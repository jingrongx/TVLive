package com.newslive.app;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 只绑 127.0.0.1 的极简 HTTP 服务，把 {@link HlsRelay} 落盘的分片喂给 ExoPlayer。
 *
 * 不做通用静态服务：只认 /relay/<token>/index.m3u8 与 /relay/<token>/<分片文件>，
 * token 在每次起播时随机生成，外部（含同机其他 App）猜不到路径。
 */
public class RelayServer {

    private static final String TAG = "RelayServer";

    private final ServerSocket serverSocket;
    private final Thread acceptThread;
    private final Map<String, HlsRelay> relays = new ConcurrentHashMap<>();
    private final Map<String, File> files = new ConcurrentHashMap<>();
    private volatile boolean running = true;

    public RelayServer() throws IOException {
        serverSocket = new ServerSocket(0, 16, InetAddress.getByName("127.0.0.1"));
        acceptThread = new Thread(new Runnable() {
            @Override public void run() { acceptLoop(); }
        }, "RelayServer");
        acceptThread.setDaemon(true);
        acceptThread.start();
        LogUtil.i(TAG, "relay http listening on " + serverSocket.getLocalPort());
    }

    public int getPort() { return serverSocket.getLocalPort(); }

    public String baseUrlFor(String token) {
        return "http://127.0.0.1:" + getPort() + "/relay/" + token + "/";
    }

    public void register(HlsRelay relay) {
        relays.put(relay.getToken(), relay);
    }

    public void unregister(String token) {
        if (token != null) relays.remove(token);
    }

    /** 暴露一个本地文件（录屏 MP4 回放用），走同一个 127.0.0.1 服务 */
    public void registerFile(String token, File file) {
        if (token != null && file != null) files.put(token, file);
    }

    public String fileUrlFor(String token) {
        return "http://127.0.0.1:" + getPort() + "/file/" + token;
    }

    public void shutdown() {
        running = false;
        try { serverSocket.close(); } catch (Exception ignore) { }
    }

    private void acceptLoop() {
        while (running) {
            try {
                final Socket client = serverSocket.accept();
                // 每连接独立线程：一个慢请求（大分片/客户端停读）不能堵住播放列表刷新，
                // 否则播放器会误判"列表不更新"而进入无限缓冲
                Thread t = new Thread(new Runnable() {
                    @Override public void run() {
                        try {
                            handle(client);
                        } catch (Exception e) {
                            LogUtil.w(TAG, "handle error: " + e);
                        } finally {
                            try { client.close(); } catch (Exception ignore) { }
                        }
                    }
                }, "RelayConn");
                t.setDaemon(true);
                t.start();
            } catch (Exception e) {
                if (running) LogUtil.w(TAG, "accept error: " + e);
                try { Thread.sleep(200); } catch (InterruptedException ie) { return; }
            }
        }
    }

    private void handle(Socket client) {
        try {
            client.setSoTimeout(8000);
            InputStream in = client.getInputStream();
            // 只读到请求头结束，最多 4KB，避免异常请求把线程拖住
            byte[] headBuf = new byte[4096];
            int len = 0;
            while (len < headBuf.length) {
                int b = in.read();
                if (b == -1) break;
                headBuf[len++] = (byte) b;
                if (len >= 4 && headBuf[len - 1] == '\n' && headBuf[len - 2] == '\r'
                        && headBuf[len - 3] == '\n' && headBuf[len - 4] == '\r') break;
            }
            if (len == 0) return;
            String head = new String(headBuf, 0, len, "UTF-8");
            String[] lines = head.split("\r\n");
            String requestLine = lines.length > 0 ? lines[0] : "";
            String[] parts = requestLine.split(" ");
            if (parts.length < 2) return;
            String path = parts[1];
            int q = path.indexOf('?');
            if (q >= 0) path = path.substring(0, q);

            if (path.startsWith("/file/")) {
                String ftoken = path.substring("/file/".length());
                File f = files.get(ftoken);
                if (f == null || !f.exists()) {
                    writeText(client.getOutputStream(), 404, "text/plain", "no file".getBytes("UTF-8"));
                    return;
                }
                String range = null;
                for (String hl : lines) {
                    if (hl.toLowerCase().startsWith("range:")) { range = hl.substring(6).trim(); break; }
                }
                serveFile(client.getOutputStream(), f, range, true);
                return;
            }

            if (!path.startsWith("/relay/")) {
                writeText(client.getOutputStream(), 404, "text/plain", "not found".getBytes("UTF-8"));
                return;
            }
            String rest = path.substring("/relay/".length());
            int slash = rest.indexOf('/');
            if (slash <= 0) {
                writeText(client.getOutputStream(), 404, "text/plain", "bad path".getBytes("UTF-8"));
                return;
            }
            String token = rest.substring(0, slash);
            String name = rest.substring(slash + 1);
            HlsRelay relay = relays.get(token);
            if (relay == null) {
                writeText(client.getOutputStream(), 404, "text/plain", "no relay".getBytes("UTF-8"));
                return;
            }

            if ("index.m3u8".equals(name)) {
                String text = relay.getPlaylistText();
                if (text == null || text.isEmpty()) {
                    writeText(client.getOutputStream(), 503, "text/plain", new byte[0]);
                    return;
                }
                writeText(client.getOutputStream(), 200,
                        "application/vnd.apple.mpegurl", text.getBytes("UTF-8"));
                return;
            }

            File f = relay.getFile(name);
            if (f == null) {
                writeText(client.getOutputStream(), 404, "text/plain", "no file".getBytes("UTF-8"));
                return;
            }
            String range = null;
            for (String hl : lines) {
                if (hl.toLowerCase().startsWith("range:")) { range = hl.substring(6).trim(); break; }
            }
            serveFile(client.getOutputStream(), f, range, name.endsWith(".mp4") || name.endsWith(".m4s"));
        } catch (Exception e) {
            LogUtil.w(TAG, "serve error: " + e);
        }
    }

    private void serveFile(OutputStream rawOut, File f, String rangeHeader, boolean mp4) throws IOException {
        long size = f.length();
        long start = 0, end = size - 1;
        boolean partial = false;
        if (rangeHeader != null && rangeHeader.startsWith("bytes=")) {
            try {
                String r = rangeHeader.substring(6);
                int dash = r.indexOf('-');
                if (dash >= 0) {
                    String a = r.substring(0, dash).trim();
                    String b = r.substring(dash + 1).trim();
                    if (!a.isEmpty()) start = Long.parseLong(a);
                    if (!b.isEmpty()) end = Long.parseLong(b);
                    if (end >= size) end = size - 1;
                    if (start <= end) partial = true;
                }
            } catch (Exception ignore) { }
        }
        long length = partial ? (end - start + 1) : size;
        StringBuilder h = new StringBuilder();
        h.append("HTTP/1.1 ").append(partial ? "206 Partial Content" : "200 OK").append("\r\n");
        h.append("Content-Type: ").append(mp4 ? "video/mp4" : "video/mp2t").append("\r\n");
        h.append("Content-Length: ").append(length).append("\r\n");
        h.append("Accept-Ranges: bytes\r\n");
        if (partial) {
            h.append("Content-Range: bytes ").append(start).append('-').append(end)
                    .append('/').append(size).append("\r\n");
        }
        h.append("Cache-Control: no-cache\r\n");
        h.append("Connection: close\r\n\r\n");

        BufferedOutputStream out = new BufferedOutputStream(rawOut, 32 * 1024);
        out.write(h.toString().getBytes("UTF-8"));
        FileInputStream fis = new FileInputStream(f);
        try {
            long skipped = 0;
            while (skipped < start) {
                long s = fis.skip(start - skipped);
                if (s <= 0) break;
                skipped += s;
            }
            byte[] buf = new byte[32 * 1024];
            long remain = length;
            while (remain > 0) {
                int n = fis.read(buf, 0, (int) Math.min(buf.length, remain));
                if (n == -1) break;
                out.write(buf, 0, n);
                remain -= n;
            }
            out.flush();
        } finally {
            try { fis.close(); } catch (Exception ignore) { }
        }
    }

    private void writeText(OutputStream out, int code, String type, byte[] body) throws IOException {
        String head = "HTTP/1.1 " + code + " " + (code == 200 ? "OK" : code == 206 ? "Partial" : "Error")
                + "\r\nContent-Type: " + type
                + "\r\nContent-Length: " + body.length
                + "\r\nCache-Control: no-cache\r\nConnection: close\r\n\r\n";
        out.write(head.getBytes("UTF-8"));
        out.write(body);
        out.flush();
    }
}
