package dev.shared.rodrigo.proxy;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Lightweight, in-memory zero-configuration micro-proxy.
 * Supports both SOCKS5 and HTTP CONNECT protocols, explicitly binding
 * all upstream outbound connections to the designated local IP address.
 */
public class EmbeddedBinderProxy {

    private static final Logger LOGGER = Logger.getLogger(EmbeddedBinderProxy.class.getName());

    private final InetAddress bindAddress;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private ServerSocket serverSocket;
    private ExecutorService threadPool;
    private int boundPort = 0;

    public EmbeddedBinderProxy(InetAddress bindAddress) {
        this.bindAddress = bindAddress;
    }

    public synchronized void start() throws IOException {
        if (running.get()) {
            return;
        }

        // Bind strictly to localhost loopback with OS-assigned free ephemeral port
        this.serverSocket = new ServerSocket(0, 50, InetAddress.getByName("127.0.0.1"));
        this.boundPort = serverSocket.getLocalPort();
        this.threadPool = Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r, "InterfaceBinder-Worker");
            t.setDaemon(true);
            return t;
        });

        this.running.set(true);

        Thread acceptThread = new Thread(this::listenLoop, "InterfaceBinder-Acceptor");
        acceptThread.setDaemon(true);
        acceptThread.start();

        LOGGER.log(Level.INFO, () -> "[InterfaceBinder] Micro-proxy started on 127.0.0.1:" + boundPort
                + " binding upstream to " + bindAddress.getHostAddress());
    }

    public synchronized void stop() {
        if (!running.getAndSet(false)) {
            return;
        }

        try {
            if (serverSocket != null && !serverSocket.isClosed()) {
                serverSocket.close();
            }
        } catch (IOException ignored) {
            // Server socket closure error can be safely ignored
        }

        if (threadPool != null) {
            threadPool.shutdownNow();
            try {
                threadPool.awaitTermination(2, TimeUnit.SECONDS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        LOGGER.log(Level.INFO, "[InterfaceBinder] Micro-proxy stopped.");
    }

    public boolean isRunning() {
        return running.get();
    }

    public int getPort() {
        return boundPort;
    }

    public InetAddress getBindAddress() {
        return bindAddress;
    }

    private void listenLoop() {
        while (running.get()) {
            try {
                Socket clientSocket = serverSocket.accept();
                clientSocket.setTcpNoDelay(true);
                threadPool.submit(() -> handleClient(clientSocket));
            } catch (IOException e) {
                if (!running.get()) {
                    break;
                }
            }
        }
    }

    private void handleClient(Socket clientSocket) {
        try {
            InputStream in = new BufferedInputStream(clientSocket.getInputStream());
            OutputStream out = new BufferedOutputStream(clientSocket.getOutputStream());

            in.mark(1);
            int firstByte = in.read();
            in.reset();

            if (firstByte == 0x05) {
                handleSocks5(clientSocket, in, out);
            } else {
                handleHttp(clientSocket, in, out);
            }
        } catch (Exception ignored) {
            // Client connection terminated or handshake failed
            closeQuietly(clientSocket);
        }
    }

    private void handleSocks5(Socket clientSocket, InputStream in, OutputStream out) throws IOException {
        in.read(); // SOCKS version 0x05
        int nMethods = in.read();
        in.readNBytes(nMethods);

        // Reply: Version 5, Method 0 (NO_AUTHENTICATION_REQUIRED)
        out.write(new byte[]{0x05, 0x00});
        out.flush();

        // Read SOCKS5 Request
        in.read(); // request version
        int cmd = in.read(); // 0x01 = CONNECT
        in.read(); // reserved byte
        int atyp = in.read();

        String targetHost = parseSocksTargetHost(in, atyp);
        if (targetHost == null) {
            out.write(new byte[]{0x05, 0x08, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
            closeQuietly(clientSocket);
            return;
        }

        int portHigh = in.read();
        int portLow = in.read();
        int targetPort = ((portHigh & 0xFF) << 8) | (portLow & 0xFF);

        if (cmd != 0x01) { // Only CONNECT supported
            out.write(new byte[]{0x05, 0x07, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
            out.flush();
            closeQuietly(clientSocket);
            return;
        }

        Socket upstream = createBoundUpstream(targetHost, targetPort);
        if (upstream == null) {
            out.write(new byte[]{0x05, 0x04, 0x00, 0x01, 0, 0, 0, 0, 0, 0}); // Host unreachable
            out.flush();
            closeQuietly(clientSocket);
            return;
        }

        // Reply SOCKS5 Success
        out.write(new byte[]{0x05, 0x00, 0x00, 0x01, 0, 0, 0, 0, 0, 0});
        out.flush();

        startPiping(clientSocket, upstream);
    }

    private String parseSocksTargetHost(InputStream in, int atyp) throws IOException {
        if (atyp == 0x01) { // IPv4
            byte[] ipBytes = in.readNBytes(4);
            return InetAddress.getByAddress(ipBytes).getHostAddress();
        } else if (atyp == 0x03) { // Domain
            int len = in.read();
            byte[] domainBytes = in.readNBytes(len);
            return new String(domainBytes, StandardCharsets.UTF_8);
        } else if (atyp == 0x04) { // IPv6
            byte[] ipBytes = in.readNBytes(16);
            return InetAddress.getByAddress(ipBytes).getHostAddress();
        }
        return null;
    }

    private void handleHttp(Socket clientSocket, InputStream in, OutputStream out) throws IOException {
        String line = readLine(in);
        if (line == null || line.isEmpty()) {
            closeQuietly(clientSocket);
            return;
        }

        String[] parts = line.split(" ");
        if (parts.length < 2) {
            closeQuietly(clientSocket);
            return;
        }

        String method = parts[0];
        String destination = parts[1];

        if ("CONNECT".equalsIgnoreCase(method)) {
            handleHttpConnect(clientSocket, in, out, destination);
        } else {
            handleHttpPlain(clientSocket, in, out, line, destination);
        }
    }

    private void handleHttpConnect(Socket clientSocket, InputStream in, OutputStream out, String destination) throws IOException {
        String[] hostPort = destination.split(":");
        String targetHost = hostPort[0];
        int targetPort = hostPort.length > 1 ? Integer.parseInt(hostPort[1]) : 443;

        // Drain remaining client headers
        String header;
        while ((header = readLine(in)) != null && !header.isEmpty()) {
            // Drain remaining headers until empty line
        }

        Socket upstream = createBoundUpstream(targetHost, targetPort);
        if (upstream == null) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            closeQuietly(clientSocket);
            return;
        }

        out.write("HTTP/1.1 200 Connection Established\r\n\r\n".getBytes(StandardCharsets.UTF_8));
        out.flush();

        startPiping(clientSocket, upstream);
    }

    private void handleHttpPlain(Socket clientSocket, InputStream in, OutputStream out, String requestLine, String destination) throws IOException {
        HttpTarget target = parseHttpTarget(destination);
        ByteArrayOutputStream headerBuffer = new ByteArrayOutputStream();
        headerBuffer.write(requestLine.getBytes(StandardCharsets.UTF_8));
        headerBuffer.write("\r\n".getBytes(StandardCharsets.UTF_8));

        String headerLine;
        while ((headerLine = readLine(in)) != null && !headerLine.isEmpty()) {
            if (target.host == null && headerLine.toLowerCase().startsWith("host:")) {
                target = parseHostHeader(headerLine);
            }
            headerBuffer.write(headerLine.getBytes(StandardCharsets.UTF_8));
            headerBuffer.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        headerBuffer.write("\r\n".getBytes(StandardCharsets.UTF_8));

        if (target.host == null) {
            closeQuietly(clientSocket);
            return;
        }

        Socket upstream = createBoundUpstream(target.host, target.port);
        if (upstream == null) {
            out.write("HTTP/1.1 502 Bad Gateway\r\n\r\n".getBytes(StandardCharsets.UTF_8));
            out.flush();
            closeQuietly(clientSocket);
            return;
        }

        OutputStream upOut = upstream.getOutputStream();
        upOut.write(headerBuffer.toByteArray());
        upOut.flush();

        startPiping(clientSocket, upstream);
    }

    private static class HttpTarget {
        final String host;
        final int port;

        HttpTarget(String host, int port) {
            this.host = host;
            this.port = port;
        }
    }

    private static HttpTarget parseHttpTarget(String destination) {
        String host = null;
        int port = 80;
        if (destination.startsWith("http://")) {
            int start = 7;
            int slash = destination.indexOf('/', start);
            String hostPart = slash != -1 ? destination.substring(start, slash) : destination.substring(start);
            String[] split = hostPart.split(":");
            host = split[0];
            if (split.length > 1) {
                port = Integer.parseInt(split[1]);
            }
        }
        return new HttpTarget(host, port);
    }

    private static HttpTarget parseHostHeader(String headerLine) {
        String val = headerLine.substring(5).trim();
        String[] sp = val.split(":");
        String host = sp[0];
        int port = sp.length > 1 ? Integer.parseInt(sp[1]) : 80;
        return new HttpTarget(host, port);
    }

    private Socket createBoundUpstream(String host, int port) {
        try {
            Socket upstream = new Socket();
            upstream.setTcpNoDelay(true);
            upstream.setSoTimeout(30000);
            // CRUCIAL: Bind socket to local designated IP address
            upstream.bind(new InetSocketAddress(this.bindAddress, 0));
            upstream.connect(new InetSocketAddress(host, port), 15000);
            return upstream;
        } catch (IOException e) {
            LOGGER.log(Level.WARNING, () -> "[InterfaceBinder] Failed to connect upstream to " + host + ":" + port
                    + " via " + bindAddress.getHostAddress() + " - " + e.getMessage());
            return null;
        }
    }

    private void startPiping(Socket client, Socket upstream) {
        threadPool.submit(() -> pipe(client, upstream));
        threadPool.submit(() -> pipe(upstream, client));
    }

    private void pipe(Socket source, Socket destination) {
        try {
            InputStream in = source.getInputStream();
            OutputStream out = destination.getOutputStream();
            byte[] buffer = new byte[8192];
            int read;
            while (running.get() && (read = in.read(buffer)) != -1) {
                out.write(buffer, 0, read);
                out.flush();
            }
        } catch (IOException ignored) {
            // Stream disconnected or closed by peer
        } finally {
            closeQuietly(source);
            closeQuietly(destination);
        }
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                break;
            }
            if (c != '\r') {
                bos.write(c);
            }
        }
        if (c == -1 && bos.size() == 0) {
            return null;
        }
        return bos.toString(StandardCharsets.UTF_8);
    }

    private static void closeQuietly(Socket socket) {
        if (socket != null && !socket.isClosed()) {
            try {
                socket.close();
            } catch (IOException ignored) {
                // Socket closure failure can be safely ignored
            }
        }
    }
}
