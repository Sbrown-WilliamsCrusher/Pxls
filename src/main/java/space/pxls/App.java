package space.pxls;

import com.google.gson.Gson;
import com.mashape.unirest.http.Unirest;
import com.mashape.unirest.http.exceptions.UnirestException;
import org.eclipse.jetty.websocket.api.Session;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import spark.ResponseTransformer;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static spark.Spark.*;

public class App {
    private static Game game;

    private static WebSocketHandler handler;
    private static long startTime = System.currentTimeMillis();
    private static Set<String> torIps = ConcurrentHashMap.newKeySet();
    private static Set<String> bannedIps = ConcurrentHashMap.newKeySet();
    private static Set<String> trustedIps = ConcurrentHashMap.newKeySet();

    private static long lastSave;
    private static int captchaThreshold;

    private static Logger pixelLogger = LoggerFactory.getLogger("pixels");
    private static Logger appLogger = LoggerFactory.getLogger(App.class);

    public static void main(String[] args) {
        game = new Game(
                Integer.parseInt(getEnv("CANVAS_WIDTH", "1000")),
                Integer.parseInt(getEnv("CANVAS_HEIGHT", "1000")),
                Arrays.asList("#FFFFFF", "#E4E4E4", "#888888", "#222222", "#FFA7D1", "#E50000", "#E59500", "#A06A42", "#E5D900", "#94E044", "#02BE01", "#00D3DD", "#0083C7", "#0000EA", "#CF6EE4", "#820080"),
                Integer.parseInt(getEnv("COOLDOWN", "180")));

        loadBoard();
        banTorNodes();

        if (getReCaptchaSecret() == null) {
            appLogger.warn("No ReCaptcha key specified (env $CAPTCHA_KEY), proceeding WITHOUT AUTH");
        }
        captchaThreshold = Integer.parseInt(getEnv("CAPTCHA_THRESHOLD", "5"));

        handler = new WebSocketHandler();

        port(Integer.parseInt(getEnv("PORT", "4567")));
        webSocket("/ws", handler);
        staticFiles.location("/public");
        get("/boardinfo", App::boardInfo, new JsonTransformer());
        get("/boarddata", App::boardData);
        get("/users", (req, res) -> handler.getSessions().size());

        Runtime.getRuntime().addShutdownHook(new Thread(App::saveBoard));

        Scanner scanner = new Scanner(System.in);
        while (true) {
            String command = scanner.nextLine();
            handleCommand(command);
        }
    }

    private static void loadBoard() {
        try {
            game.loadBoard(getBoardFile());
        } catch (IOException e) {
            appLogger.error("Error while loading board", e);
        }
    }

    public static void saveBoard() {
        if (lastSave + 5000 < System.currentTimeMillis()) return;
        lastSave = System.currentTimeMillis();

        try {
            game.saveBoard(getBoardFile());
        } catch (IOException e) {
            appLogger.error("Error while saving board", e);
        }
    }

    public static String getReCaptchaSecret() {
        return getEnv("CAPTCHA_KEY", null);
    }

    private static Data.BoardInfo boardInfo(spark.Request req, spark.Response res) {
        res.type("application/json");
        return new Data.BoardInfo(game.getWidth(), game.getHeight(), game.getPalette());
    }

    private static byte[] boardData(spark.Request req, spark.Response res) {
        if (req.headers("Accept-Encoding") != null && req.headers("Accept-Encoding").contains("gzip")) {
            res.header("Content-Encoding", "gzip");
        }
        res.type("application/octet-stream");
        return game.getBoard();
    }

    private static void banTorNodes() {
        try {
            String ips = Unirest.get("http://check.torproject.org/cgi-bin/TorBulkExitList.py?ip=1.1.1.1&port=80").asString().getBody();

            for (String s : ips.split("\n")) {
                if (!s.startsWith("#")) {
                    torIps.add(s);
                }
            }
        } catch (UnirestException e) {
            appLogger.error("Error while banning Tor exit nodes", e);
        }
    }

    private static void handleCommand(String command) {
        try {
            String[] tokens = command.split(" ");
            appLogger.info("> {}", command);
            if (tokens[0].equalsIgnoreCase("cooldown")) {
                game.setCooldown(Integer.parseInt(tokens[1]));
                appLogger.info("Changed cooldown to {} seconds", tokens[1]);

                for (Session sess : handler.getSessions()) {
                    GameSessionData DATA = handler.getSessionData(sess);
                    handler.send(sess, new Data.ServerCooldown(game.getWaitTime(DATA.lastPlace)));
                }
            } else if (tokens[0].equalsIgnoreCase("alert")) {
                String rest = command.substring(tokens[0].length() + 1);
                handler.broadcast(new Data.ServerAlert(rest));

                appLogger.info("Alerted \"{}\" to clients", rest);
            } else if (tokens[0].equalsIgnoreCase("blank")) {
                int x1 = Integer.parseInt(tokens[1]);
                int y1 = Integer.parseInt(tokens[2]);
                int x2 = Integer.parseInt(tokens[3]);
                int y2 = Integer.parseInt(tokens[4]);

                Files.write(getBoardFile().getParent().resolve(getBoardFile().getFileName() + ".preblank." + System.currentTimeMillis()), game.getBoard());

                for (int xx = Math.min(x1, x2); xx <= Math.max(x2, x1); xx++) {
                    for (int yy = Math.min(y1, y2); yy <= Math.max(y2, y1); yy++) {
                        game.setPixel(xx, yy, (byte) 0);
                        logPixel("<blank operation>", xx, yy, 0);

                        /*BoardUpdate update = new BoardUpdate(xx, yy, 0);
                        handler.broadcast(update);*/
                    }
                }
            } else if (tokens[0].equalsIgnoreCase("trusted")) {
                for (String trustedIp : trustedIps) {
                    appLogger.info("Trusted IP: {}", trustedIp);
                }
            } else if (tokens[0].equalsIgnoreCase("trust")) {
                trustedIps.add(tokens[1]);
                appLogger.info("Trusting IP: {}", tokens[1]);
            } else if (tokens[0].equalsIgnoreCase("untrust")) {
                trustedIps.remove(tokens[1]);
                appLogger.info("Untrusting IP: {}", tokens[1]);
            } else if (tokens[0].equalsIgnoreCase("ban")) {
                bannedIps.add(tokens[1]);
                appLogger.info("Banning IP: {}", tokens[1]);
            } else if (tokens[0].equalsIgnoreCase("unban")) {
                bannedIps.remove(tokens[1]);
                appLogger.info("Unbanning IP: {}", tokens[1]);
            } else if (tokens[0].equalsIgnoreCase("captcha_threshold")) {
                captchaThreshold = Integer.parseInt(tokens[1]);
                appLogger.info("Changing captcha threshold to {}", captchaThreshold);
            }
        } catch (Exception e) {
            appLogger.error("Error while executing command {}", command, e);
        }
    }

    private static Path getStorageDir() {
        return Paths.get(getEnv("STORAGE", "."));
    }

    private static Path getBoardFile() {
        return getStorageDir().resolve("board.dat");
    }

    private static String getEnv(String key, String def) {
        String val = System.getenv(key);
        if (val != null) return val;
        return def;
    }

    public static void logPixel(String ip, int x, int y, int color) throws IOException {
        pixelLogger.info("{} at ({},{}) by {}", game.getPalette().get(color), x, y, ip);
    }

    public static Set<String> getTorIps() {
        return torIps;
    }

    public static Set<String> getBannedIps() {
        return bannedIps;
    }

    public static Set<String> getTrustedIps() {
        return trustedIps;
    }

    public static Game getGame() {
        return game;
    }

    public static int getCaptchaThreshold() {
        return captchaThreshold;
    }

    public static Logger getLogger() {
        return appLogger;
    }

    public static class JsonTransformer implements ResponseTransformer {
        private Gson gson = new Gson();

        @Override
        public String render(Object model) {
            return gson.toJson(model);
        }
    }
}
