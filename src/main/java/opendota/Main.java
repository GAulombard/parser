package opendota;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URL;
import java.net.URLDecoder;
import java.rmi.server.Operation;
import java.util.LinkedHashMap;
import java.util.Map;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;
    
public class Main {

    public static void main(String[] args) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(Integer.valueOf(args.length > 0 ? args[0] : "5600")), 0);
        server.createContext("/", new MyHandler());
        server.createContext("/blob", new BlobHandler());
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool());
        server.start();
    }
    
    static class MyHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            t.sendResponseHeaders(200, 0);
            InputStream is = t.getRequestBody();
            OutputStream os = t.getResponseBody();
            try {
            	new Parse(is, os);
            }
            catch (Exception e)
            {
            	e.printStackTrace();
            }
            os.close();
        }
    }

    static class BlobHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange t) throws IOException {
            try {
                Map<String, String> query = splitQuery(t.getRequestURI());
                URL replayUrl = new URL(query.get("replay_url"));
                String cmd = String.format("curl --max-time 120 --fail -L %s | %s | curl -X POST --data-binary @- localhost:5600 | node processors/createParsedDataBlob.mjs",
                    replayUrl, 
                    replayUrl.toString().endsWith(".bz2") ? "bunzip2" : "cat"
                );
                System.err.println(cmd);
                // Download, unzip, parse, aggregate
                Process proc = new ProcessBuilder(new String[] {"bash", "-c", cmd})
                .start();
                ByteArrayOutputStream output = new ByteArrayOutputStream();
                ByteArrayOutputStream error = new ByteArrayOutputStream();
                copy(proc.getInputStream(), output);
                // Write error to console
                copy(proc.getErrorStream(), error);
                System.err.println(error.toString());
                int exitCode = proc.waitFor();
                if (exitCode != 0) {
                    // We can send 200 status here and no response if expected error (read the error string)
                    // Maybe we can pass the specific error info in the response headers
                    int status = 500;
                    if (error.toString().contains("curl: (28) Operation timed out")) {
                        // Parse took too long, maybe China replay?
                        status = 200;
                    }
                    if (error.toString().contains("bunzip2: Data integrity error when decompressing")) {
                        // Corrupted replay, don't retry
                        status = 200;
                    }
                    t.sendResponseHeaders(status, 0);
                    t.getResponseBody().close();
                } else {
                    t.sendResponseHeaders(200, output.size());
                    output.writeTo(t.getResponseBody());
                    t.getResponseBody().close();
                }
            } 
            catch(InterruptedException e) {
                e.printStackTrace();
            }
        }
    }

    public static Map<String, String> splitQuery(URI uri) throws UnsupportedEncodingException {
        Map<String, String> query_pairs = new LinkedHashMap<String, String>();
        String query = uri.getQuery();
        String[] pairs = query.split("&");
        for (String pair : pairs) {
            int idx = pair.indexOf("=");
            query_pairs.put(URLDecoder.decode(pair.substring(0, idx), "UTF-8"), URLDecoder.decode(pair.substring(idx + 1), "UTF-8"));
        }
        return query_pairs;
    }

    // buffer size used for reading and writing
    private static final int BUFFER_SIZE = 8192;

    /**
     * Reads all bytes from an input stream and writes them to an output stream.
    */
    private static long copy(InputStream source, OutputStream sink) throws IOException {
        long nread = 0L;
        byte[] buf = new byte[BUFFER_SIZE];
        int n;
        while ((n = source.read(buf)) > 0) {
            sink.write(buf, 0, n);
            nread += n;
        }
        return nread;
    }
}
