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

    private static void handleClient(Socket client, String clientKey,
                                     String fileHost, int filePort) {

        Integer proxyEphemeral = null;

        try (
                Socket c  = client;
                Socket fs = new Socket(fileHost, filePort) // فقط یک بار برای این کلاینت
        ) {
            InputStream  cin  = c.getInputStream();
            OutputStream cout = c.getOutputStream(); // فعلاً استفاده خاصی اینجا نداریم
            InputStream  fsIn = fs.getInputStream();
            OutputStream fsOut = fs.getOutputStream();

            proxyEphemeral = fs.getLocalPort();

            // ثبت در NAT: این پورت پروکسی → مخصوص این کلاینت است
            NATEntry entry = new NATEntry(clientKey, c.getPort(), proxyEphemeral);
            nat.put(proxyEphemeral, entry);

            System.out.println("Proxy: client " + clientKey +
                    " mapped to proxy port " + proxyEphemeral + " towards FileServer");

            // 👈 بعد از کانکت شدن و ثبت در NAT، جدول NAT را چاپ کن
            printNatTable();

            while (true) {
                String request = readLine(cin);
                if (request == null) {
                    System.out.println("Proxy: client " + clientKey + " closed connection");
                    break;
                }

                if (request.equalsIgnoreCase("exit")) {
                    System.out.println("Proxy: client " + clientKey + " requested exit");
                    break;
                }

                // ارسال request به FileServer روی همین اتصال ثابت
                fsOut.write((request + "\n").getBytes());
                fsOut.flush();

                // دریافت هدر از FileServer
                String header = readLine(fsIn);
                sendLineFromFsToClient(fs, header);  // via NAT

                if (header == null) {
                    System.out.println("Proxy: FileServer closed connection for client " + clientKey);
                    break;
                }

                // DOWNLOAD
                if (header.startsWith("OK FILE ")) {
                    long size = Long.parseLong(header.split(" ")[2]);
                    transferFromFsToClient(fs, size);  // محتوی فایل
                }

                // LIST
                else if (header.startsWith("OK LIST ")) {
                    while (true) {
                        String line = readLine(fsIn);
                        sendLineFromFsToClient(fs, line);
                        if (line == null || line.equals("END")) break;
                    }
                }

                // ERROR یا چیزهای دیگر فقط همان header را پاس می‌دهیم
            }

        } catch (Exception ex) {
            ex.printStackTrace();
        } finally {
            // حذف از NAT و لیست کلاینت‌ها
            if (proxyEphemeral != null) {
                nat.remove(proxyEphemeral);
            }
            clients.remove(clientKey);

            System.out.println("Proxy: client " + clientKey + " disconnected and NAT entry removed");

            // 👈 بعد از دیسکانکت شدن و حذف از NAT، جدول NAT را چاپ کن
            printNatTable();
        }
    }

    // ارسال یک خط از FileServer به کلاینت با استفاده از NAT
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

        InputStream  in  = fs.getInputStream();
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
    // چاپ جدول NAT
    private static void printNatTable() {
        System.out.println("\n========= NAT TABLE =========");

        if (nat.isEmpty()) {
            System.out.println("NAT EMPTY");
            System.out.println("=============================\n");
            return;
        }

        for (var entry : nat.entrySet()) {
            int proxyPort = entry.getKey();
            NATEntry ne = entry.getValue();

            System.out.println(
                    "ProxyPort: " + proxyPort +
                            "  =>  Client: " + ne.clientKey +
                            "  (ClientPort=" + ne.clientPort + ")"
            );
        }

        System.out.println("========= END NAT ==========\n");
    }
}
