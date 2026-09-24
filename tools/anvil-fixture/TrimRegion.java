import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.InflaterInputStream;

/**
 * Copies a few chunks of a vanilla region file into a new region file, byte for byte: the chunk
 * payloads (length, compression type, compressed NBT) are not decoded and re-encoded, only moved to
 * consecutive sectors behind a rewritten header. Keeps the chunks named on the command line, plus,
 * with {@code +proto}, the first chunk of the file whose Status is not minecraft:full. Run through
 * gen.sh, which puts the vanilla server's classes on the class path for the Status lookup.
 *
 * <pre>
 *   java TrimRegion.java &lt;in.mca&gt; &lt;out.mca&gt; &lt;cx,cz&gt;... [+proto]
 * </pre>
 */
public final class TrimRegion {
    static final int SECTOR = 4096;

    public static void main(String[] args) throws IOException {
        byte[] in = Files.readAllBytes(Path.of(args[0]));
        ByteBuffer header = ByteBuffer.wrap(in, 0, 2 * SECTOR);
        int[] locations = new int[1024];
        int[] timestamps = new int[1024];
        for (int i = 0; i < 1024; i++) locations[i] = header.getInt();
        for (int i = 0; i < 1024; i++) timestamps[i] = header.getInt();

        Set<Integer> keep = new LinkedHashSet<>();
        boolean proto = false;
        for (int a = 2; a < args.length; a++) {
            if (args[a].equals("+proto")) {
                proto = true;
                continue;
            }
            String[] xz = args[a].split(",");
            int index = (Integer.parseInt(xz[1]) & 31) * 32 + (Integer.parseInt(xz[0]) & 31);
            if (locations[index] == 0) throw new IllegalArgumentException("no chunk " + args[a] + " in " + args[0]);
            keep.add(index);
        }
        if (proto) {
            for (int i = 0; i < 1024; i++) {
                if (locations[i] == 0 || keep.contains(i)) continue;
                String status = read(in, locations[i]).getStringOr("Status", "");
                if (!status.equals("minecraft:full")) {
                    System.out.println("proto chunk " + (i & 31) + "," + (i >> 5) + " Status=" + status);
                    keep.add(i);
                    break;
                }
            }
        }

        List<byte[]> payloads = new ArrayList<>();
        int[] outLocations = new int[1024];
        int sector = 2;
        for (int index : keep) {
            int offset = (locations[index] >>> 8) * SECTOR;
            int length = ByteBuffer.wrap(in, offset, 4).getInt();
            int sectors = (4 + length + SECTOR - 1) / SECTOR;
            byte[] payload = new byte[sectors * SECTOR];
            System.arraycopy(in, offset, payload, 0, 4 + length);
            payloads.add(payload);
            outLocations[index] = (sector << 8) | sectors;
            sector += sectors;
        }

        ByteBuffer outHeader = ByteBuffer.allocate(2 * SECTOR);
        for (int i = 0; i < 1024; i++) outHeader.putInt(outLocations[i]);
        for (int i = 0; i < 1024; i++) outHeader.putInt(outLocations[i] == 0 ? 0 : timestamps[i]);
        try (RandomAccessFile out = new RandomAccessFile(args[1], "rw")) {
            out.setLength(0);
            out.write(outHeader.array());
            for (byte[] payload : payloads) out.write(payload);
        }
    }

    static CompoundTag read(byte[] file, int location) throws IOException {
        int offset = (location >>> 8) * SECTOR;
        int length = ByteBuffer.wrap(file, offset, 4).getInt();
        if (file[offset + 4] != 2) throw new IOException("expected zlib, got compression " + file[offset + 4]);
        var raw = new ByteArrayInputStream(file, offset + 5, length - 1);
        try (var data = new DataInputStream(new InflaterInputStream(raw))) {
            return NbtIo.read(data);
        }
    }
}
