import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

public class FileServer {

    public static void main(String[] args) {
        int port = 8000;               // پورت ثابت و مشخص
        String filesDir = "files";     // فولدر فایل‌ها

        try {
            Path dir = Paths.get(filesDir);
            if (!Files.exists(dir))
                Files.createDirectories(dir);

            InetAddress bindAddr = InetAddress.getByName("127.0.0.1");
            ServerSocket serverSocket = new ServerSocket(port, 50, bindAddr);

            System.out.println("FileServer running on port " + port);

            while (true) {
                Socket socket = serverSocket.accept();
                System.out.println("FileServer: connected " + socket.getRemoteSocketAddress());
                new Thread(() -> handle(socket, dir)).start();
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static void handle(Socket socket, Path filesDir) {
        try (
                Socket s = socket;
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream()
        ) {
            while (true) {
                String line = readLine(in);
                if (line == null) break;  // کلاینت قطع شد

                if (line.equalsIgnoreCase("list")) {
                    try (Stream<Path> files = Files.list(filesDir)) {
                        List<String> names = new ArrayList<>();
                        files.forEach(p -> names.add(p.getFileName().toString()));
                        writeLine(out, "OK LIST " + names.size());
                        for (String name : names) writeLine(out, name);
                        writeLine(out, "END");
                    }
                }
                else if (line.toUpperCase().startsWith("DOWNLOAD")) {
                    if (line.length() <= 9) {
                        writeLine(out, "ERROR Missing filename");
                        continue;
                    }

                    String file = line.substring(9).trim();
                    Path f = filesDir.resolve(file);

                    if (!Files.exists(f)) {
                        writeLine(out, "ERROR File not found");
                        continue;
                    }

                    long size = Files.size(f);
                    writeLine(out, "OK FILE " + size);

                    try (InputStream fis = Files.newInputStream(f)) {
                        fis.transferTo(out);
                        out.flush();
                    }
                }
                else {
                    writeLine(out, "ERROR Unknown command: " + line);
                }
            }

        } catch (Exception e) {
            e.printStackTrace();
        }
    }


    private static void writeLine(OutputStream out, String s) throws Exception {
        out.write((s + "\n").getBytes());
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
