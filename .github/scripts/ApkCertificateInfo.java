import com.android.apksig.ApkVerifier;
import java.io.File;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.List;

/** Public signer fingerprints from Android's verifier API, independent of CLI formatting. */
class ApkCertificateInfo {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one APK path");
        ApkVerifier.Result result = new ApkVerifier.Builder(new File(args[0])).build().verify();
        if (!result.isVerified()) throw new IllegalStateException("APK signature verification failed");
        List<X509Certificate> certificates = result.getSignerCertificates();
        if (certificates.size() != 1) throw new IllegalStateException("Expected exactly one APK signer");
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        for (X509Certificate certificate : certificates) {
            System.out.println("CERTIFICATE_SHA256=" + HexFormat.of().formatHex(digest.digest(certificate.getEncoded())));
        }
    }
}
