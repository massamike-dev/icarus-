import java.io.InputStream;
import java.nio.file.Path;
import java.security.CodeSigner;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Locale;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/** Verify every AAB payload entry before reporting its public signing certificate. */
class AabCertificateInfo {
    public static void main(String[] args) throws Exception {
        if (args.length != 1) throw new IllegalArgumentException("Expected one AAB path.");
        String fingerprint = null;
        int signedEntries = 0;
        boolean manifest = false;
        boolean config = false;
        try (JarFile jar = new JarFile(Path.of(args[0]).toFile(), true)) {
            var entries = jar.entries();
            byte[] buffer = new byte[65536];
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                if (entry.isDirectory()) continue;
                // Reading the complete entry makes JarVerifier validate its digest.
                try (InputStream input = jar.getInputStream(entry)) {
                    while (input.read(buffer) != -1) { }
                }
                String upper = entry.getName().toUpperCase(Locale.ROOT);
                if (upper.equals("META-INF/MANIFEST.MF") ||
                    upper.matches("META-INF/[^/]+\\.(SF|RSA|DSA|EC)")) continue;
                CodeSigner[] signers = entry.getCodeSigners();
                if (signers == null || signers.length != 1)
                    throw new SecurityException("An AAB payload entry is unsigned or has multiple signers.");
                var certificate = signers[0].getSignerCertPath().getCertificates().get(0);
                if (!(certificate instanceof X509Certificate x509))
                    throw new SecurityException("Expected an X.509 signing certificate.");
                x509.checkValidity();
                String found = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(x509.getEncoded()));
                if (fingerprint != null && !fingerprint.equals(found))
                    throw new SecurityException("AAB payload entries have different signers.");
                fingerprint = found;
                signedEntries++;
                manifest |= entry.getName().equals("base/manifest/AndroidManifest.xml");
                config |= entry.getName().equals("BundleConfig.pb");
            }
        }
        if (fingerprint == null || !manifest || !config)
            throw new SecurityException("Signed AAB manifest or configuration is missing.");
        System.out.println("CERTIFICATE_SHA256=" + fingerprint);
        System.out.println("SIGNED_PAYLOAD_ENTRIES=" + signedEntries);
    }
}
