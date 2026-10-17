package dev.boombox;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.entity.Player;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.Deflater;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * Собирает ресурспак (модель бумбокса + звуки), раздаёт его по HTTP и отправляет игрокам.
 */
public final class PackManager {

    private static final LocalDateTime FIXED_TIME = LocalDateTime.of(2024, 1, 1, 0, 0, 0);
    private static final String[] BUNDLED = {
            "assets/boombox/models/item/boombox.json",
            "assets/boombox/textures/item/boombox.png"
    };

    private final BoomboxPlugin plugin;
    /** Постоянный UUID пака: новая версия заменяет старую, а не добавляется рядом. */
    private final UUID packId = UUID.nameUUIDFromBytes("boombox-resource-pack".getBytes(StandardCharsets.UTF_8));

    private final Set<UUID> loaded = ConcurrentHashMap.newKeySet();
    private volatile Path zip;
    private volatile String sha1 = "";
    private HttpServer http;
    private ExecutorService executor;

    public PackManager(BoomboxPlugin plugin) {
        this.plugin = plugin;
    }

    // ---------------------------------------------------------------- сборка

    /** Собирает zip. Блокирующий. Возвращает true, если содержимое изменилось. */
    public synchronized boolean build() throws IOException {
        FileConfiguration cfg = plugin.getConfig();
        Path base = plugin.getDataFolder().toPath();
        Path outDir = base.resolve("pack");
        Files.createDirectories(outDir);

        TreeMap<String, byte[]> mem = new TreeMap<>();
        TreeMap<String, Path> files = new TreeMap<>();

        // 1) модель и текстура по умолчанию (из jar)
        for (String res : BUNDLED) {
            try (InputStream in = plugin.getResource("pack/" + res)) {
                if (in != null) {
                    mem.put(res, in.readAllBytes());
                }
            }
        }

        // 2) pack.mcmeta
        int format = cfg.getInt("resource-pack.pack-format", 34);
        String desc = cfg.getString("resource-pack.description", "Boombox");
        String mcmeta = "{\"pack\":{\"pack_format\":" + format + ",\"description\":" + jsonString(desc) + "}}";
        mem.put("pack.mcmeta", mcmeta.getBytes(StandardCharsets.UTF_8));

        // 3) модель базового предмета с привязкой custom_model_data
        Material mat = Material.matchMaterial(cfg.getString("item.material", "PAPER"));
        if (mat == null || !mat.isItem()) {
            mat = Material.PAPER;
        }
        String matName = mat.name().toLowerCase(Locale.ROOT);
        String parent = cfg.getString("item.model-parent", "minecraft:item/generated");
        int cmd = cfg.getInt("item.custom-model-data", 7001);
        String baseModel = "{\n"
                + "  \"parent\": " + jsonString(parent) + ",\n"
                + "  \"textures\": {\"layer0\": \"minecraft:item/" + matName + "\"},\n"
                + "  \"overrides\": [\n"
                + "    {\"predicate\": {\"custom_model_data\": " + cmd + "}, \"model\": \"boombox:item/boombox\"}\n"
                + "  ]\n"
                + "}\n";
        mem.put("assets/minecraft/models/item/" + matName + ".json", baseModel.getBytes(StandardCharsets.UTF_8));

        // 4) звуки: sounds.json + сами .ogg
        int dist = (int) Math.ceil(cfg.getDouble("music.radius", 24.0));
        List<TrackManager.Track> tracks = plugin.tracks().list();
        StringBuilder sj = new StringBuilder("{\n");
        for (int i = 0; i < tracks.size(); i++) {
            TrackManager.Track t = tracks.get(i);
            sj.append("  \"").append(t.id()).append("\": {\"sounds\": [{\"name\": \"boombox:")
                    .append(t.id()).append("\", \"stream\": true, \"attenuation_distance\": ")
                    .append(dist).append("}]}");
            sj.append(i + 1 < tracks.size() ? ",\n" : "\n");
            files.put("assets/boombox/sounds/" + t.id() + ".ogg", t.file());
        }
        sj.append("}\n");
        mem.put("assets/boombox/sounds.json", sj.toString().getBytes(StandardCharsets.UTF_8));

        // 5) твои файлы из pack-extra перекрывают всё вышеперечисленное (своя модель, текстуры...)
        Path extra = base.resolve("pack-extra");
        Files.createDirectories(extra);
        List<Path> extraFiles;
        try (Stream<Path> walk = Files.walk(extra)) {
            extraFiles = walk.filter(p -> Files.isRegularFile(p)).collect(Collectors.toList());
        }
        for (Path p : extraFiles) {
            String name = extra.relativize(p).toString().replace('\\', '/');
            files.put(name, p);
        }

        // 6) пишем zip (порядок и время фиксированы -> одинаковый SHA-1 для одинакового содержимого)
        Path tmp = Files.createTempFile(outDir, "build-", ".tmp");
        try (ZipOutputStream zos = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(tmp)))) {
            zos.setLevel(Deflater.BEST_SPEED);
            TreeSet<String> names = new TreeSet<>(mem.keySet());
            names.addAll(files.keySet());
            for (String n : names) {
                ZipEntry ze = new ZipEntry(n);
                ze.setTimeLocal(FIXED_TIME);
                zos.putNextEntry(ze);
                Path f = files.get(n);
                if (f != null) {
                    Files.copy(f, zos);
                } else {
                    zos.write(mem.get(n));
                }
                zos.closeEntry();
            }
        } catch (IOException e) {
            Files.deleteIfExists(tmp);
            throw e;
        }

        String hash = sha1(tmp);
        Path finalZip = outDir.resolve("boombox-" + hash.substring(0, 12) + ".zip");
        Files.move(tmp, finalZip, StandardCopyOption.REPLACE_EXISTING);
        // копия с постоянным именем — для ручной заливки на внешний хостинг
        Files.copy(finalZip, outDir.resolve("boombox-pack.zip"), StandardCopyOption.REPLACE_EXISTING);

        boolean changed = !hash.equals(this.sha1);
        this.zip = finalZip;
        this.sha1 = hash;

        try (Stream<Path> s = Files.list(outDir)) {
            for (Path p : s.collect(Collectors.toList())) {
                String n = p.getFileName().toString();
                if (n.startsWith("boombox-") && n.endsWith(".zip") && !n.equals("boombox-pack.zip") && !p.equals(finalZip)) {
                    try {
                        Files.deleteIfExists(p);
                    } catch (IOException ignored) {
                        // файл может быть занят раздачей — удалится при следующей сборке
                    }
                }
            }
        }

        plugin.getLogger().info("Ресурспак собран: " + finalZip.getFileName() + " (SHA-1 " + hash + ", треков: " + tracks.size() + ")");
        return changed;
    }

    /** Собирает пак в фоне. Если пак изменился и включено resend-on-change — пересылает игрокам. */
    public void rebuildAsync(boolean notifyPlayers, Runnable after) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            boolean changed = false;
            try {
                changed = build();
            } catch (Exception e) {
                plugin.getLogger().log(Level.SEVERE, "Не удалось собрать ресурспак", e);
            }
            boolean fChanged = changed;
            Bukkit.getScheduler().runTask(plugin, () -> {
                if (notifyPlayers && fChanged && plugin.getConfig().getBoolean("resource-pack.resend-on-change", true)) {
                    sendToAll();
                }
                if (after != null) {
                    after.run();
                }
            });
        });
    }

    private static String sha1(Path f) throws IOException {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-1");
            try (InputStream in = Files.newInputStream(f)) {
                byte[] buf = new byte[8192];
                int r;
                while ((r = in.read(buf)) != -1) {
                    md.update(buf, 0, r);
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
    }

    private static String jsonString(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ---------------------------------------------------------------- отправка игрокам

    /** Игроки, у которых ресурспак загружен (по PlayerResourcePackStatusEvent). */
    public void markLoaded(UUID id) {
        loaded.add(id);
    }

    public void markUnloaded(UUID id) {
        loaded.remove(id);
    }

    /** Можно ли включать этому игроку музыку (пак загружен либо проверка отключена в конфиге). */
    public boolean isReady(Player p) {
        return !plugin.getConfig().getBoolean("resource-pack.require-loaded", true) || loaded.contains(p.getUniqueId());
    }

    /** Адрес пака или null, если не настроен. */
    public String url() {
        FileConfiguration cfg = plugin.getConfig();
        String external = cfg.getString("resource-pack.external-url", "").trim();
        if (!external.isEmpty()) {
            return external;
        }
        String baseUrl = cfg.getString("resource-pack.server.public-url", "").trim();
        if (baseUrl.isEmpty() || sha1.isEmpty()) {
            return null;
        }
        while (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        return baseUrl + "/" + sha1 + ".zip";
    }

    /** Отправляет пак игроку (не удаляя другие его серверные паки). */
    public boolean sendTo(Player player) {
        String url = url();
        if (url == null || sha1.isEmpty()) {
            return false;
        }
        try {
            ResourcePackInfo info = ResourcePackInfo.resourcePackInfo()
                    .id(packId)
                    .uri(URI.create(url))
                    .hash(sha1)
                    .build();
            ResourcePackRequest request = ResourcePackRequest.resourcePackRequest()
                    .packs(info)
                    .prompt(plugin.mm(plugin.getConfig().getString("resource-pack.prompt", "")))
                    .required(plugin.getConfig().getBoolean("resource-pack.required", false))
                    .replace(false)
                    .build();
            player.sendResourcePacks(request);
            return true;
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("Некорректный адрес ресурспака: " + url);
            return false;
        }
    }

    public void sendToAll() {
        if (url() == null) {
            return;
        }
        for (Player p : Bukkit.getOnlinePlayers()) {
            sendTo(p);
        }
    }

    // ---------------------------------------------------------------- HTTP-раздача

    public void startServer() {
        FileConfiguration cfg = plugin.getConfig();
        if (!cfg.getString("resource-pack.external-url", "").isBlank()) {
            plugin.getLogger().info("Используется внешняя ссылка на ресурспак, встроенный HTTP-сервер не нужен.");
            return;
        }
        if (!cfg.getBoolean("resource-pack.server.enabled", true)) {
            return;
        }
        if (cfg.getString("resource-pack.server.public-url", "").isBlank()) {
            plugin.getLogger().warning("resource-pack.server.public-url не задан — игроки не получат ресурспак! "
                    + "Впиши http://ТВОЙ_IP:порт или используй resource-pack.external-url.");
        }
        int port = cfg.getInt("resource-pack.server.port", 8123);
        try {
            http = HttpServer.create(new InetSocketAddress(port), 0);
            http.createContext("/", this::handle);
            executor = Executors.newFixedThreadPool(4, r -> {
                Thread t = new Thread(r, "Boombox-Pack-HTTP");
                t.setDaemon(true);
                return t;
            });
            http.setExecutor(executor);
            http.start();
            plugin.getLogger().info("HTTP-раздача ресурспака запущена на порту " + port);
        } catch (IOException | RuntimeException | LinkageError e) {
            http = null;
            plugin.getLogger().log(Level.SEVERE, "Не удалось запустить HTTP-сервер на порту " + port
                    + " (" + e.getMessage() + "). Смени порт или используй resource-pack.external-url.");
        }
    }

    public void stopServer() {
        if (http != null) {
            http.stop(0);
            http = null;
        }
        if (executor != null) {
            executor.shutdownNow();
            executor = null;
        }
    }

    public void restartServer() {
        stopServer();
        startServer();
    }

    private void handle(HttpExchange ex) throws IOException {
        try {
            Path f = zip;
            if (f == null || !Files.exists(f)) {
                ex.sendResponseHeaders(404, -1);
                return;
            }
            ex.getResponseHeaders().set("Content-Type", "application/zip");
            if ("HEAD".equalsIgnoreCase(ex.getRequestMethod())) {
                ex.sendResponseHeaders(200, -1);
                return;
            }
            ex.sendResponseHeaders(200, Files.size(f));
            try (OutputStream os = ex.getResponseBody()) {
                Files.copy(f, os);
            }
        } finally {
            ex.close();
        }
    }
}
