package com.dsh.remote;

import android.util.Log;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * Local port forwarder: the workbench hardcodes local service endpoints such
 * as ws://127.0.0.1:9230 (the inspector / file-resource channel). On the phone
 * that loopback address is the phone itself, so we listen on 127.0.0.1:9230
 * and tunnel each connection through the VPN to the PC's own 9230 service.
 * Raw TCP piping: WebSocket and HTTP both work untouched, and the page needs
 * no rewriting at all.
 */
public final class LocalForwarder {

    private static final String TAG = "DSHForwarder";
    private static final int LOCAL_PORT = 9230;

    private static ServerSocket server;
    private static Thread thread;
    private static volatile boolean running;

    /** targetHost: the tailnet hostname of the PC (e.g. the discovered endpoint host). */
    public static synchronized void start(final String targetHost) {
        if (running) return;
        running = true;
        thread = new Thread(new Runnable() {
            @Override public void run() {
                try {
                    server = new ServerSocket(LOCAL_PORT, 32, InetAddress.getByName("127.0.0.1"));
                    Log.i(TAG, "listening 127.0.0.1:" + LOCAL_PORT + " -> " + targetHost + ":" + LOCAL_PORT);
                    while (running) {
                        final Socket client = server.accept();
                        new Thread(new Runnable() {
                            @Override public void run() {
                                pipe(client, targetHost);
                            }
                        }).start();
                    }
                } catch (Exception e) {
                    Log.w(TAG, "server failed: " + e.getMessage() + " (will self-heal)");
                    running = false; // let the next poll() re-arm us
                    try {
                        if (server != null) server.close();
                    } catch (Exception ignored) {}
                    server = null;
                }
            }
        }, "dsh-local-forwarder");
        thread.setDaemon(true);
        thread.start();
    }

    public static synchronized void stop() {
        running = false;
        try {
            if (server != null) server.close();
        } catch (Exception ignored) {}
        server = null;
    }

    private static void pipe(Socket client, String targetHost) {
        Socket upstream = null;
        try {
            client.setTcpNoDelay(true);
            InetAddress ip = InetAddress.getByName(targetHost); // resolves via MagicDNS in the tunnel
            // Connect through Tailscale Serve on 443 (TLS+SNI): it bypasses the
            // PC's firewall (which blocks raw listeners) and needs no open ports.
            javax.net.ssl.SSLSocketFactory f = (javax.net.ssl.SSLSocketFactory) javax.net.ssl.SSLSocketFactory.getDefault();
            javax.net.ssl.SSLSocket ssl = (javax.net.ssl.SSLSocket) f.createSocket();
            javax.net.ssl.SSLParameters sp = new javax.net.ssl.SSLParameters();
            sp.setServerNames(java.util.Collections.singletonList(new javax.net.ssl.SNIHostName(targetHost)));
            ssl.setSSLParameters(sp);
            ssl.connect(new java.net.InetSocketAddress(ip, 443), 6000);
            ssl.setTcpNoDelay(true);
            upstream = ssl;
            upstream.setTcpNoDelay(true);
            // The local service validates the handshake's Origin/Host (it only
            // trusts loopback), so rewrite those headers to 127.0.0.1 before
            // forwarding; everything after the header block flows raw.
            java.io.BufferedInputStream cin = new java.io.BufferedInputStream(client.getInputStream());
            java.io.ByteArrayOutputStream hs = new java.io.ByteArrayOutputStream();
            int b;
            while ((b = cin.read()) >= 0) {
                hs.write(b);
                if (hs.size() > 16384) break;
                byte[] arr = hs.toByteArray();
                int n = arr.length;
                if (n >= 4 && arr[n - 1] == 10 && arr[n - 2] == 13 && arr[n - 3] == 10 && arr[n - 4] == 13) break;
            }
            String head = hs.toString("ISO-8859-1");
            head = head.replaceAll("(?im)^Origin: .*$", "Origin: http://127.0.0.1:" + LOCAL_PORT);
            head = head.replaceAll("(?im)^Host: .*$", "Host: 127.0.0.1:" + LOCAL_PORT);
            // route through the bridge's /__insp endpoint (it reaches the PC's
            // loopback-only inspector): prefix the request path
            head = head.replaceFirst("^(GET|POST|PUT|HEAD) /", "$1 /__insp/");
            java.io.OutputStream uout = upstream.getOutputStream();
            uout.write(head.getBytes("ISO-8859-1"));
            uout.flush();
            pump(cin, uout);
            pump(upstream.getInputStream(), client.getOutputStream());
        } catch (Exception e) {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    private static void pump(final InputStream in, final OutputStream out) {
        Thread t = new Thread(new Runnable() {
            @Override public void run() {
                byte[] buf = new byte[16384];
                try {
                    int n;
                    while ((n = in.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        out.flush();
                    }
                } catch (Exception ignored) {
                } finally {
                    try { out.close(); } catch (Exception ignored) {}
                }
            }
        }, "dsh-forward-pump");
        t.setDaemon(true);
        t.start();
    }

    private static void closeQuietly(Socket s) {
        try {
            if (s != null) s.close();
        } catch (Exception ignored) {}
    }
}
