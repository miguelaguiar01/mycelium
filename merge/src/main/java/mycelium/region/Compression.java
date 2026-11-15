package mycelium.region;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.zip.Deflater;
import java.util.zip.DeflaterOutputStream;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;
import java.util.zip.InflaterInputStream;
import java.util.zip.Checksum;
import net.jpountz.lz4.LZ4BlockInputStream;
import net.jpountz.lz4.LZ4BlockOutputStream;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;

/**
 * The chunk compression types of a region file, with the ids Minecraft 26.3 uses.
 *
 * <p>26.3 writes {@link #ZLIB} by default and {@link #NONE} or {@link #LZ4} when
 * {@code region-file-compression} says so. It reads {@link #GZIP} but never writes it. Type 127,
 * a compression named by a string and supplied by a mod, is not supported.
 */
public enum Compression {
    GZIP(1),
    ZLIB(2),
    NONE(3),
    /** lz4-java's block stream framing ("LZ4Block" magic), not the LZ4 frame format. */
    LZ4(4);

    /** A chunk larger than this once decompressed is treated as corrupt instead of exhausting memory. */
    static final int MAX_DECOMPRESSED_BYTES = 1 << 28;

    /** Block size and checksum seed of lz4-java's block stream defaults, which the game uses. */
    private static final int LZ4_BLOCK_BYTES = 1 << 16;
    private static final int LZ4_CHECKSUM_SEED = 0x9747b28c;

    /**
     * The zlib level chunks are written with. Measured on 300 chunks of a played world: level 4
     * takes less than half the time of the default level 6, for output 5% larger. The game
     * compresses a chunk again whenever it saves it.
     */
    private static final int ZLIB_LEVEL = 4;

    private final int id;

    Compression(int id) {
        this.id = id;
    }

    /** The compression byte stored in front of the chunk. */
    public int id() {
        return id;
    }

    /** The type with this id, or null if 26.3 does not define it or it is not supported. */
    public static Compression fromId(int id) {
        for (Compression compression : values()) {
            if (compression.id == id) {
                return compression;
            }
        }
        return null;
    }

    public byte[] decompress(byte[] data, int offset, int length) throws IOException {
        if (this == NONE) {
            byte[] copy = new byte[length];
            System.arraycopy(data, offset, copy, 0, length);
            return copy;
        }
        ByteArrayInputStream raw = new ByteArrayInputStream(data, offset, length);
        try (InputStream in = switch (this) {
            case GZIP -> new GZIPInputStream(raw);
            case ZLIB -> new InflaterInputStream(raw);
            case LZ4 -> LZ4BlockInputStream.newBuilder()
                    .withDecompressor(LZ4Factory.safeInstance().safeDecompressor())
                    .withChecksum(lz4Checksum())
                    .build(raw);
            case NONE -> raw;
        }) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(8192, length * 4L), 1 << 20));
            byte[] buffer = new byte[8192];
            int read;
            while ((read = in.read(buffer)) != -1) {
                if (out.size() + read > MAX_DECOMPRESSED_BYTES) {
                    throw new IOException("chunk is larger than " + MAX_DECOMPRESSED_BYTES + " bytes when decompressed");
                }
                out.write(buffer, 0, read);
            }
            return out.toByteArray();
        }
    }

    /**
     * The pure-Java implementations are used throughout: they bounds-check untrusted input and
     * load no native code.
     */
    private static Checksum lz4Checksum() {
        return XXHashFactory.safeInstance().newStreamingHash32(LZ4_CHECKSUM_SEED).asChecksum();
    }

    public byte[] compress(byte[] data) throws IOException {
        if (this == NONE) {
            return data;
        }
        ByteArrayOutputStream raw = new ByteArrayOutputStream(Math.max(1024, data.length / 4));
        Deflater deflater = this == ZLIB ? new Deflater(ZLIB_LEVEL) : null;
        try (OutputStream out = switch (this) {
            case GZIP -> new GZIPOutputStream(raw);
            case ZLIB -> new DeflaterOutputStream(raw, deflater);
            case LZ4 -> new LZ4BlockOutputStream(raw, LZ4_BLOCK_BYTES, LZ4Factory.safeInstance().fastCompressor(),
                    lz4Checksum(), false);
            case NONE -> raw;
        }) {
            out.write(data);
        } finally {
            if (deflater != null) {
                deflater.end();
            }
        }
        return raw.toByteArray();
    }
}
