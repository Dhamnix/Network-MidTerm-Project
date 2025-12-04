import java.io.*;
import java.net.*;

public class Client {

    public static void main(String[] args) throws Exception {

        BufferedReader console = new BufferedReader(new InputStreamReader(System.in));

        // فقط یک بار به پروکسی وصل می‌شویم
        Socket socket = new Socket("localhost", 9100);
        System.out.println("Client connected from local port " + socket.getLocalPort());

        InputStream in = socket.getInputStream();
        OutputStream out = socket.getOutputStream();

        while (true) {

            System.out.print("CMD> ");
            String cmd = console.readLine();
            if (cmd == null) break;

            // ارسال دستور به پروکسی (روی همان اتصال ثابت)
            out.write((cmd + "\n").getBytes());
            out.flush();

            // اگر exit گفتیم، دیگه منتظر پاسخ نمیشیم، حلقه رو می‌بندیم
            if (cmd.equalsIgnoreCase("exit")) {
                break;
            }

            // دریافت هدر
            String header = readLine(in);
            if (header == null) {
                System.out.println("No response from ProxyServer");
                break;
            }

            System.out.println(header);

            // LIST
            if (header.startsWith("OK LIST")) {
                while (true) {
                    String line = readLine(in);
                    if (line == null || line.equals("END")) break;
                    System.out.println(line);
                }
            }

            // DOWNLOAD
            else if (header.startsWith("OK FILE")) {
                String[] parts = header.split(" ");
                long size = Long.parseLong(parts[2]);
                String filename = cmd.substring(9).trim();

                receiveFile(in, filename, size);
                System.out.println("Download completed: " + filename);
            }

            // اگر ERROR بود، فقط همون هدر چاپ می‌شه
        }

        socket.close(); // اتصال ثابت کلاینت به پروکسی بسته می‌شود
        System.out.println("Client exited.");
    }


    private static void receiveFile(InputStream in, String file, long size) throws Exception {
        FileOutputStream fos = new FileOutputStream(file);
        byte[] buf = new byte[8192];
        long rem = size;

        while (rem > 0) {
            int read = in.read(buf, 0, (int) Math.min(rem, buf.length));
            if (read == -1) break;
            fos.write(buf, 0, read);
            rem -= read;
        }
        fos.close();
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
