package dev.boombox;

import org.bukkit.Bukkit;
import org.bukkit.configuration.file.FileConfiguration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** Библиотека треков: папка plugins/Boombox/music. */
public final class TrackManager {

    public record Track(String id, String name, Path file, double seconds, int channels) {
        /** Ключ звукового события из ресурспака. */
        public String soundKey() {
            return "boombox:" + id;
        }
    }

    private static final Set<String> CONVERTIBLE = Set.of(
            "mp3", "wav", "flac", "m4a", "aac", "opus", "wma", "mp4", "webm", "aiff", "aif");

    // а..я (32 буквы подряд в Unicode), ё — отдельно
    private static final String[] RU = {
            "a", "b", "v", "g", "d", "e", "zh", "z", "i", "y", "k", "l", "m", "n", "o", "p",
            "r", "s", "t", "u", "f", "h", "ts", "ch", "sh", "sch", "", "y", "", "e", "yu", "ya"};

    private final BoomboxPlugin plugin;
    private final Path musicDir;
    private volatile List<Track> tracks = List.of();

    public TrackManager(BoomboxPlugin plugin) {
        this.plugin = plugin;
        this.musicDir = plugin.getDataFolder().toPath().resolve("music");
    }

    public List<Track> list() {
        return tracks;
    }

    public Track get(String id) {
        for (Track t : tracks) {
            if (t.id().equals(id)) return t;
        }
        return null;
    }

    public Track findByName(String name) {
        for (Track t : tracks) {
            if (t.name().equals(name)) return t;
        }
        return null;
    }

    /** Сканирует папку в фоне, затем вызывает done в основном потоке. */
    public void reloadAsync(Runnable done) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                scan();
            } catch (RuntimeException e) {
                plugin.getLogger().log(Level.WARNING, "Ошибка при загрузке треков", e);
            }
            if (done != null) {
                Bukkit.getScheduler().runTask(plugin, done);
            }
        });
    }

    /** Блокирующее сканирование: конвертирует не-ogg и собирает список треков. */
    public synchronized void scan() {
        try {
            Files.createDirectories(musicDir);

            for (Path f : listFiles()) {
                String ext = ext(f.getFileName().toString());
                if (CONVERTIBLE.contains(ext)) {
                    try {
                        convert(f);
                    } catch (RuntimeException e) {
                        plugin.getLogger().log(Level.WARNING, "Не удалось обработать " + f.getFileName(), e);
                    }
                }
            }

            double fallback = plugin.getConfig().getDouble("music.default-track-seconds", 180.0);
            List<Track> result = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (Path f : listFiles()) {
                String fileName = f.getFileName().toString();
                if (!ext(fileName).equals("ogg")) continue;

                String base = stripExt(fileName);
                OggInfo info;
                try {
                    info = OggInfo.read(f);
                } catch (IOException e) {
                    plugin.getLogger().warning("Не удалось прочитать " + fileName + ": " + e.getMessage());
                    continue;
                }
                if (!info.vorbis()) {
                    plugin.getLogger().warning(fileName + ": это не Ogg Vorbis (возможно Opus) — Minecraft такое не играет. "
                            + "Конвертируй: ffmpeg -i in.ogg -c:a libvorbis -q:a 4 out.ogg");
                    continue;
                }
                if (info.channels() > 1) {
                    plugin.getLogger().info(fileName + ": стерео — будет играть без затухания по расстоянию (для затухания нужен моно).");
                }

                String id = slug(base);
                String unique = id;
                int n = 2;
                while (!used.add(unique)) {
                    unique = id + "_" + n++;
                }
                double seconds = info.seconds() > 0 ? info.seconds() : fallback;
                result.add(new Track(unique, base, f, seconds, info.channels()));
            }
            result.sort(Comparator.comparing(t -> t.name().toLowerCase(Locale.ROOT)));
            this.tracks = List.copyOf(result);
            plugin.getLogger().info("Треков загружено: " + result.size());
        } catch (IOException e) {
            plugin.getLogger().log(Level.WARNING, "Ошибка чтения папки music", e);
        }
    }

    private List<Path> listFiles() throws IOException {
        try (Stream<Path> s = Files.list(musicDir)) {
            return s.filter(p -> Files.isRegularFile(p)).sorted().collect(Collectors.toList());
        }
    }

    private boolean convert(Path in) {
        FileConfiguration c = plugin.getConfig();
        if (!c.getBoolean("ffmpeg.enabled", true)) {
            plugin.getLogger().warning(in.getFileName() + ": конвертация выключена (ffmpeg.enabled=false). Положи готовый .ogg.");
            return false;
        }
        String base = stripExt(in.getFileName().toString());
        Path out = musicDir.resolve(base + ".ogg");
        if (Files.exists(out)) {
            plugin.getLogger().warning(in.getFileName() + ": рядом уже есть " + out.getFileName() + ", пропускаю конвертацию.");
            return false;
        }

        List<String> cmd = new ArrayList<>();
        cmd.add(c.getString("ffmpeg.path", "ffmpeg"));
        cmd.addAll(List.of("-nostdin", "-y", "-loglevel", "error", "-i", in.toAbsolutePath().toString(), "-vn", "-map_metadata", "-1"));
        if (c.getBoolean("ffmpeg.mono", true)) {
            cmd.addAll(List.of("-ac", "1"));
        }
        cmd.addAll(List.of("-c:a", "libvorbis", "-q:a", "4", out.toAbsolutePath().toString()));

        Path logFile = null;
        try {
            logFile = Files.createTempFile(musicDir, "ffmpeg-", ".log");
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.redirectErrorStream(true);
            pb.redirectOutput(logFile.toFile());
            Process proc = pb.start();
            proc.getOutputStream().close(); // ffmpeg не должен ждать ввода
            if (!proc.waitFor(5, TimeUnit.MINUTES)) {
                proc.destroyForcibly();
                Files.deleteIfExists(out);
                plugin.getLogger().warning("ffmpeg завис на " + in.getFileName() + " (больше 5 минут), пропускаю.");
                return false;
            }
            if (proc.exitValue() != 0) {
                Files.deleteIfExists(out);
                plugin.getLogger().warning("ffmpeg не смог сконвертировать " + in.getFileName() + ": "
                        + Files.readString(logFile, StandardCharsets.UTF_8).trim());
                return false;
            }
            Path originals = musicDir.resolve("_originals");
            Files.createDirectories(originals);
            Files.move(in, originals.resolve(in.getFileName()), StandardCopyOption.REPLACE_EXISTING);
            plugin.getLogger().info("Сконвертировано в ogg: " + in.getFileName());
            return true;
        } catch (IOException e) {
            plugin.getLogger().warning("Не удалось запустить ffmpeg (" + e.getMessage() + "). Установи ffmpeg или положи готовый .ogg (Vorbis).");
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            if (logFile != null) {
                try {
                    Files.deleteIfExists(logFile);
                } catch (IOException ignored) {
                    // не критично
                }
            }
        }
    }

    /** Скачивает файл по прямой ссылке в папку music. Блокирующий — вызывать из async. */
    public Path download(String urlStr, String requestedName) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(urlStr);
        } catch (IllegalArgumentException e) {
            throw new IOException("некорректная ссылка");
        }
        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new IOException("нужна ссылка http:// или https://");
        }

        Files.createDirectories(musicDir);
        HttpClient client = HttpClient.newBuilder()
                .followRedirects(HttpClient.Redirect.NORMAL)
                .connectTimeout(Duration.ofSeconds(15))
                .build();
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofMinutes(3))
                .header("User-Agent", "Boombox-Plugin")
                .GET()
                .build();
        HttpResponse<InputStream> response = client.send(request, HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() / 100 != 2) {
            response.body().close();
            throw new IOException("сервер ответил HTTP " + response.statusCode());
        }

        long max = plugin.getConfig().getLong("music.max-download-mb", 50L) * 1024L * 1024L;
        Path part = Files.createTempFile(musicDir, "dl-", ".part");
        try (InputStream in = response.body(); OutputStream out = Files.newOutputStream(part)) {
            byte[] buf = new byte[16384];
            long total = 0;
            int r;
            while ((r = in.read(buf)) != -1) {
                total += r;
                if (total > max) {
                    throw new IOException("файл больше лимита " + (max / 1024 / 1024) + " МБ");
                }
                out.write(buf, 0, r);
            }
        } catch (IOException e) {
            Files.deleteIfExists(part);
            throw e;
        }

        String urlPath = uri.getPath() == null ? "" : uri.getPath();
        String fromUrl = ext(urlPath);
        String ext = sniff(part);
        if (ext == null && (fromUrl.equals("ogg") || CONVERTIBLE.contains(fromUrl))) {
            ext = fromUrl;
        }
        if (ext == null) {
            Files.deleteIfExists(part);
            throw new IOException("не удалось определить формат (нужен ogg, mp3, wav или flac)");
        }

        String base;
        if (requestedName != null && !requestedName.isBlank()) {
            base = requestedName;
        } else {
            int slash = urlPath.lastIndexOf('/');
            String last = slash >= 0 ? urlPath.substring(slash + 1) : urlPath;
            base = last.isBlank() ? "track" : stripExt(last);
        }
        base = base.replaceAll("[\\\\/:*?\"<>|]", "").trim();
        if (base.isEmpty()) base = "track";

        Path target = musicDir.resolve(base + "." + ext);
        Files.move(part, target, StandardCopyOption.REPLACE_EXISTING);
        return target;
    }

    private static String sniff(Path f) throws IOException {
        byte[] h = new byte[12];
        int n;
        try (InputStream in = Files.newInputStream(f)) {
            n = in.readNBytes(h, 0, h.length);
        }
        if (n < 4) return null;
        if (h[0] == 'O' && h[1] == 'g' && h[2] == 'g' && h[3] == 'S') return "ogg";
        if (h[0] == 'I' && h[1] == 'D' && h[2] == '3') return "mp3";
        if ((h[0] & 0xFF) == 0xFF && (h[1] & 0xE0) == 0xE0) return "mp3";
        if (h[0] == 'R' && h[1] == 'I' && h[2] == 'F' && h[3] == 'F') return "wav";
        if (h[0] == 'f' && h[1] == 'L' && h[2] == 'a' && h[3] == 'C') return "flac";
        return null;
    }

    static String ext(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot + 1).toLowerCase(Locale.ROOT);
    }

    static String stripExt(String name) {
        int dot = name.lastIndexOf('.');
        return dot <= 0 ? name : name.substring(0, dot);
    }

    /** "Кино - Группа крови" -> "kino_-_gruppa_krovi" (допустимый ресурс-айди). */
    static String slug(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toLowerCase(Locale.ROOT).toCharArray()) {
            if (c >= '\u0430' && c <= '\u044F') {
                sb.append(RU[c - '\u0430']);
            } else if (c == '\u0451') {
                sb.append('e');
            } else if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9') || c == '_' || c == '-') {
                sb.append(c);
            } else if (c == ' ' || c == '.') {
                sb.append('_');
            }
        }
        String r = sb.toString().replaceAll("_+", "_").replaceAll("^[_-]+|[_-]+$", "");
        return r.isEmpty() ? "track" : r;
    }

    public static String fmt(double seconds) {
        int s = (int) Math.round(seconds);
        return (s / 60) + ":" + String.format("%02d", s % 60);
    }
}
