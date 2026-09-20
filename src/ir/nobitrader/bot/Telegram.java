package ir.nobitrader.bot;

import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

/** Tiny Telegram Bot API client (sendMessage only). */
public final class Telegram {

    private Telegram() {
    }

    /** fire-and-forget send; returns true when Telegram accepted the message */
    public static boolean send(String token, String chatId, String text) {
        if (token == null || token.trim().isEmpty() || chatId == null || chatId.trim().isEmpty()) {
            return false;
        }
        HttpURLConnection c = null;
        try {
            URL u = new URL("https://api.telegram.org/bot" + token.trim() + "/sendMessage");
            c = (HttpURLConnection) u.openConnection();
            c.setRequestMethod("POST");
            c.setConnectTimeout(6000);
            c.setReadTimeout(9000);
            String body = "{\"chat_id\":\"" + chatId.trim() + "\",\"text\":" + jsonStr(text) + "}";
            byte[] b = body.getBytes(StandardCharsets.UTF_8);
            c.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            c.setFixedLengthStreamingMode(b.length);
            c.setDoOutput(true);
            OutputStream os = c.getOutputStream();
            os.write(b);
            os.flush();
            os.close();
            return c.getResponseCode() == 200;
        } catch (Throwable t) {
            return false;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** minimal JSON string escaping */
    static String jsonStr(String s) {
        StringBuilder sb = new StringBuilder("\"");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            switch (ch) {
                case '"':
                    sb.append("\\\"");
                    break;
                case '\\':
                    sb.append("\\\\");
                    break;
                case '\n':
                    sb.append("\\n");
                    break;
                case '\r':
                    sb.append("\\r");
                    break;
                case '\t':
                    sb.append("\\t");
                    break;
                default:
                    if (ch < 0x20) {
                        sb.append(String.format("\\u%04x", (int) ch));
                    } else {
                        sb.append(ch);
                    }
            }
        }
        return sb.append('"').toString();
    }
}
