package dev.boombox;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.file.Path;

/**
 * Читает из .ogg: Vorbis ли это, число каналов, частоту и длительность.
 * Minecraft умеет играть только Ogg Vorbis (Opus внутри .ogg — не работает).
 */
public record OggInfo(boolean vorbis, int channels, int sampleRate, double seconds) {

    public static OggInfo read(Path file) throws IOException {
        try (RandomAccessFile raf = new RandomAccessFile(file.toFile(), "r")) {
            long len = raf.length();

            byte[] head = new byte[(int) Math.min(len, 512)];
            raf.readFully(head);

            boolean vorbis = false;
            int channels = 0;
            int rate = 0;
            for (int i = 0; i + 16 < head.length; i++) {
                if (head[i] == 1 && head[i + 1] == 'v' && head[i + 2] == 'o' && head[i + 3] == 'r'
                        && head[i + 4] == 'b' && head[i + 5] == 'i' && head[i + 6] == 's') {
                    vorbis = true;
                    channels = head[i + 11] & 0xFF;
                    rate = (head[i + 12] & 0xFF) | (head[i + 13] & 0xFF) << 8
                            | (head[i + 14] & 0xFF) << 16 | (head[i + 15] & 0xFF) << 24;
                    break;
                }
            }

            double seconds = 0;
            int tailLen = (int) Math.min(len, 70000);
            byte[] tail = new byte[tailLen];
            raf.seek(len - tailLen);
            raf.readFully(tail);
            for (int i = tail.length - 27; i >= 0; i--) {
                // последняя страница Ogg: "OggS", версия 0, флаг EOS (0x04)
                if (tail[i] == 'O' && tail[i + 1] == 'g' && tail[i + 2] == 'g' && tail[i + 3] == 'S'
                        && tail[i + 4] == 0 && (tail[i + 5] & 0x04) != 0) {
                    long granule = 0;
                    for (int b = 7; b >= 0; b--) {
                        granule = (granule << 8) | (tail[i + 6 + b] & 0xFFL);
                    }
                    if (granule > 0 && rate > 0) {
                        seconds = granule / (double) rate;
                    }
                    break;
                }
            }
            return new OggInfo(vorbis, channels, rate, seconds);
        }
    }
}
