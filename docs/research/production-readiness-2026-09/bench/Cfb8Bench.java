import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.ByteBuffer;
import java.security.SecureRandom;

public class Cfb8Bench {
    public static void main(String[] a) throws Exception {
        byte[] key = new byte[16]; new SecureRandom().nextBytes(key);
        SecretKeySpec k = new SecretKeySpec(key, "AES");
        String[] modes = {"AES/CFB8/NoPadding"};
        int[] chunkSizes = {64, 1024, 16384};
        for (boolean direct : new boolean[]{false, true}) for (int chunk : chunkSizes) for (int mode : new int[]{Cipher.ENCRYPT_MODE, Cipher.DECRYPT_MODE}) {
            Cipher c = Cipher.getInstance(modes[0]);
            c.init(mode, k, new IvParameterSpec(key));
            ByteBuffer buf = direct ? ByteBuffer.allocateDirect(chunk) : ByteBuffer.allocate(chunk);
            long total = 256L << 20; // 256 MiB warm + measure
            for (int round = 0; round < 3; round++) {
                long t0 = System.nanoTime(); long done = 0;
                while (done < total) { buf.clear(); ByteBuffer in = buf.duplicate(); c.update(in, buf.duplicate()); done += chunk; }
                double s = (System.nanoTime() - t0) / 1e9;
                if (round == 2) System.out.printf("%s chunk=%d %s: %.1f MiB/s, %.2f ms CPU per MiB%n", direct?"direct":"heap", chunk, mode==Cipher.ENCRYPT_MODE?"enc":"dec", total/1048576.0/s, s*1000/(total/1048576.0));
            }
        }
        System.out.println(System.getProperty("java.version") + " " + System.getProperty("os.arch"));
    }
}
