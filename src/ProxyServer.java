import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ProxyServer {

    // nat: کلید = پورت local پروکسی در ارتباط با FileServer
    private static ConcurrentHashMap<Integer, NATEntry> nat = new ConcurrentHashMap<>();

    // نگه‌داشتن کلاینت‌های فعال: key = "ip:port"
    private static ConcurrentHashMap<String, Socket> clients = new ConcurrentHashMap<>();

    static class NATEntry {
        String clientKey;  // مثلاً "127.0.0.1:54321"
        int clientPort;    // پورت کلاینت
        int proxyPort;     // پورت پروکسی در مسیر FileServer (localPort سوکت fs)

        NATEntry(String clientKey, int clientPort, int proxyPort) {
            this.clientKey = clientKey;
            this.clientPort = clientPort;
            this.proxyPort = proxyPort;
        }
    }

    private static String getClientKey(Socket s) {
        return s.getInetAddress().getHostAddress() + ":" + s.getPort();
    }

    public static void main(String[] args) throws Exception {

        int proxyPort = 9100;          // پورت ثابت پروکسی
        String fileHost = "localhost"; // IP فایل‌سرور
        int filePort = 8000;           // پورت فایل‌سرور

        ServerSocket serverSocket = new ServerSocket(proxyPort);
        System.out.println("ProxyServer running on port " + proxyPort);

        while (true) {
            Socket client = serverSocket.accept();
            String clientKey = getClientKey(client);
            clients.put(clientKey, client);

            System.out.println("Proxy: new client " + clientKey);

            new Thread(() -> handleClient(client, clientKey, fileHost, filePort)).start();
        }
    }

    private static void handleClient(Socket client, String clientKey, String fileHost, int filePort) {
        try (
                Socket c = client;
                InputStream cin = c.getInputStream();
                OutputStream cout = c.getOutputStream()
        ) {
            while (true) {
                String request = readLine(cin);
                if (request == null) break;

                // اگر کلاینت گفت exit، حلقه‌ی این کلاینت رو می‌بندیم
                if (request.equalsIgnoreCase("exit")) {
                    System.out.println("Proxy: client " + clientKey + " requested exit");
                    break;
                }

                Socket fs = null;
                Integer proxyEphemeral = null;

                try {
                    // اتصال به FileServer با یک پورت local تصادفی
                    fs = new Socket(fileHost, filePort);
                    proxyEphemeral = fs.getLocalPort();  // این میشه پورت پروکسی در NAT

                    System.out.println("Proxy: client " + clientKey +
                            " -> FileServer via proxy local port " + proxyEphemeral);

                    // ثبت در NAT: این پورت مربوط به این کلاینت است
                    nat.put(proxyEphemeral,
                            new NATEntry(clientKey, c.getPort(), proxyEphemeral));

                    OutputStream fsOut = fs.getOutputStream();
                    InputStream fsIn = fs.getInputStream();

                    // ارسال request به FileServer
                    fsOut.write((request + "\n").getBytes());
                    fsOut.flush();

                    // دریافت هدر
                    String header = readLine(fsIn);
                    sendLineFromFsToClient(fs, header);   // ارسال هدر با استفاده از NAT

                    if (header != null) {

                        // DOWNLOAD
                        if (header.startsWith("OK FILE ")) {
                            long size = Long.parseLong(header.split(" ")[2]);
                            transferFromFsToClient(fs, size);  // انتقال محتوی فایل
                        }

                        // LIST
                        else if (header.startsWith("OK LIST ")) {
                            while (true) {
                                String line = readLine(fsIn);
                                sendLineFromFsToClient(fs, line);
                                if (line == null || line.equals("END")) break;
                            }
                        }

                        // اگر ERROR یا هر چیز دیگری بود، فقط همان هدر فرستاده شد
                    }

                } finally {
                    // پاک کردن ورودی NAT مربوط به این ارتباط
                    if (proxyEphemeral != null) {
                        nat.remove(proxyEphemeral);
                    }
                    if (fs != null) {
                        try { fs.close(); } catch (IOException ignored) {}
                    }
                }
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            // کلاینت دیگر فعال نیست
            clients.remove(clientKey);
            System.out.println("Proxy: client " + clientKey + " disconnected");
        }
    }

    // ارسال یک خط (هدر یا خط LIST) از FileServer به کلاینت با استفاده از NAT
    private static void sendLineFromFsToClient(Socket fs, String line) throws IOException {
        if (line == null) return;

        int proxyPort = fs.getLocalPort();
        NATEntry e = nat.get(proxyPort);
        if (e == null) return;

        Socket client = clients.get(e.clientKey);
        if (client == null) return;

        OutputStream cout = client.getOutputStream();
        cout.write((line + "\n").getBytes());
        cout.flush();
    }

    // انتقال بدنه‌ی فایل از FileServer به کلاینت (DOWNLOAD)
    private static void transferFromFsToClient(Socket fs, long size) throws IOException {
        int proxyPort = fs.getLocalPort();
        NATEntry e = nat.get(proxyPort);
        if (e == null) return;

        Socket client = clients.get(e.clientKey);
        if (client == null) return;

        InputStream in = fs.getInputStream();
        OutputStream out = client.getOutputStream();

        byte[] buf = new byte[8192];
        long rem = size;

        while (rem > 0) {
            int read = in.read(buf, 0, (int) Math.min(buf.length, rem));
            if (read == -1) break;
            out.write(buf, 0, read);
            rem -= read;
        }
        out.flush();
    }

    private static String readLine(InputStream in) throws Exception {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') break;
            baos.write(b);
        }
        if (baos.size() == 0 && b == -1) return null;
        return baos.toString().trim();
    }
}
