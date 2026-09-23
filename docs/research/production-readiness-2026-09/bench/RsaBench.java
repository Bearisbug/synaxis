import javax.crypto.Cipher;
import java.security.*;
public class RsaBench { public static void main(String[] a) throws Exception {
  KeyPairGenerator g = KeyPairGenerator.getInstance("RSA"); g.initialize(1024); KeyPair kp = g.generateKeyPair();
  Cipher e = Cipher.getInstance("RSA"); e.init(Cipher.ENCRYPT_MODE, kp.getPublic()); byte[] ct = e.doFinal(new byte[16]);
  Cipher d = Cipher.getInstance("RSA"); d.init(Cipher.DECRYPT_MODE, kp.getPrivate());
  for (int r=0;r<3;r++){ long t=System.nanoTime(); int n=20000; for(int i=0;i<n;i++) d.doFinal(ct); double us=(System.nanoTime()-t)/1e3/n; if(r==2) System.out.printf("RSA-1024 PKCS1 decrypt: %.1f us/op%n", us);}
  long t=System.nanoTime(); for(int i=0;i<20;i++){KeyPairGenerator g2=KeyPairGenerator.getInstance("RSA"); g2.initialize(1024); g2.generateKeyPair();} System.out.printf("RSA-1024 keygen: %.1f ms/op%n",(System.nanoTime()-t)/1e6/20);
}}
