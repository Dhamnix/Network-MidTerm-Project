import java.io.*;
import java.net.*;
import java.util.concurrent.*;

public class ProxyServer {

    private static ConcurrentHashMap<Integer, NATEntry> nat = new ConcurrentHashMap<>();

    static class NATEntry {
        InetAddress clientIP;
        int clientPort;
        int proxyPort;

        NATEntry(InetAddress ip, int cport, int pport) {
            clientIP = ip;
            clientPort = cport;
            proxyPort = pport;
        }
    }

    public static void main(String[] args) throws Exception {

        int proxyPort = 9100;   // پورت ثابت
        String fileHost = "localhost";
        int filePort = 8000;    // به فایل‌سرور وصل می‌شود

        ServerSocket serverSocket = new ServerSocket(proxyPort);
        System.out.println("ProxyServer running on port " + proxyPort);

        while (true) {
            Socket client = serverSocket.accept();
            new Thread(() -> handleClient(client, fileHost, filePort)).start();
        }
    }

    private static void handleClient(Socket client, String fileHost, int filePort) {
        try (
                Socket c = client;
                InputStream cin = c.getInputStream();
                OutputStream cout = c.getOutputStream()
        ) {
            String request = readLine(cin);
            if (request == null) return;

            Socket fs = new Socket(fileHost, filePort);
            int proxyEphemeral = fs.getLocalPort();

            NATEntry e = new NATEntry(c.getInetAddress(), c.getPort(), proxyEphemeral);
            nat.put(proxyEphemeral, e);

            OutputStream fsOut = fs.getOutputStream();
            InputStream fsIn = fs.getInputStream();

            fsOut.write((request + "\n").getBytes());
            fsOut.flush();

            String header = readLine(fsIn);
            cout.write((header + "\n").getBytes());
            cout.flush();

            if (header.startsWith("OK FILE ")) {
                long size = Long.parseLong(header.split(" ")[2]);
                transfer(fsIn, cout, size);
            }
            else if (header.startsWith("OK LIST ")) {
                while (true) {
                    String line = readLine(fsIn);
                    cout.write((line + "\n").getBytes());
                    cout.flush();
                    if (line.equals("END")) break;
                }
            }

            fs.close();
            nat.remove(proxyEphemeral);

        } catch (Exception ex) {
            ex.printStackTrace();
        }
    }

    private static void transfer(InputStream in, OutputStream out, long size) throws Exception {
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
