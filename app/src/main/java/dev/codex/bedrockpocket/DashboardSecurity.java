package dev.codex.bedrockpocket;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.math.BigInteger;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Date;
import java.util.Enumeration;
import java.util.List;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.security.KeyStore;

/** Password storage and ephemeral local TLS identity for the opt-in LAN dashboard. */
final class DashboardSecurity {
    static final String PREFS = "lan_dashboard";
    private static final int PBKDF2_ITERATIONS = 160000;
    private static final int HASH_BITS = 256;
    private static final SecureRandom RANDOM = new SecureRandom();

    private DashboardSecurity() {}

    static final class TlsMaterial {
        final SSLContext context;
        final X509Certificate certificate;
        TlsMaterial(SSLContext context, X509Certificate certificate) { this.context=context;this.certificate=certificate; }
    }

    static void setPassword(Context context, char[] password) throws Exception {
        try {
            byte[] salt = new byte[16]; RANDOM.nextBytes(salt);
            byte[] hash = derive(password, salt);
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
                    .putString("password_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
                    .putString("password_hash", Base64.encodeToString(hash, Base64.NO_WRAP)).apply();
        } finally { java.util.Arrays.fill(password, '\0'); }
    }

    static boolean hasPassword(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        return prefs.contains("password_salt") && prefs.contains("password_hash");
    }

    static void clearPassword(Context context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove("password_salt")
                .remove("password_hash").putBoolean("enabled", false).apply();
    }

    static boolean verifyPassword(Context context, char[] password) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        try {
            byte[] salt = Base64.decode(prefs.getString("password_salt", ""), Base64.NO_WRAP);
            byte[] expected = Base64.decode(prefs.getString("password_hash", ""), Base64.NO_WRAP);
            byte[] actual = derive(password, salt);
            return MessageDigest.isEqual(expected, actual);
        } catch (Exception e) { return false; }
        finally { java.util.Arrays.fill(password, '\0'); }
    }

    private static byte[] derive(char[] password, byte[] salt) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password, salt, PBKDF2_ITERATIONS, HASH_BITS);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    static TlsMaterial createTlsMaterial() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
        generator.initialize(2048, RANDOM);
        KeyPair pair = generator.generateKeyPair();
        X509Certificate certificate = selfSigned(pair);
        KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
        store.load(null, null);
        char[] keyPassword = "bedrockpocket-local".toCharArray();
        store.setKeyEntry("lan-dashboard", pair.getPrivate(), keyPassword, new java.security.cert.Certificate[]{certificate});
        KeyManagerFactory managers = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(store, keyPassword);
        SSLContext context = SSLContext.getInstance("TLS");
        context.init(managers.getKeyManagers(), null, RANDOM);
        return new TlsMaterial(context, certificate);
    }

    static String certificateFingerprint(X509Certificate certificate) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded());
        StringBuilder result = new StringBuilder();
        for (byte value : digest) { if (result.length() > 0) result.append(':'); result.append(String.format(java.util.Locale.ROOT, "%02X", value & 0xff)); }
        return result.toString();
    }

    static List<InetAddress> localAddresses() {
        ArrayList<InetAddress> result = new ArrayList<>();
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                try { if (!network.isUp() || network.isLoopback()) continue; } catch (Exception ignored) { continue; }
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (isPrivateAddress(address) && !address.isLoopbackAddress() && !address.isLinkLocalAddress()) result.add(address);
                }
            }
        } catch (Exception ignored) {}
        result.sort((a,b) -> (a instanceof Inet4Address ? 0 : 1) - (b instanceof Inet4Address ? 0 : 1));
        return result;
    }

    static InetAddress primaryLanAddress() {
        InetAddress fallback = null;
        try {
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces != null && interfaces.hasMoreElements()) {
                NetworkInterface network = interfaces.nextElement();
                try { if (!network.isUp() || network.isLoopback()) continue; } catch (Exception ignored) { continue; }
                String name = network.getName().toLowerCase(java.util.Locale.ROOT);
                boolean wifi = name.startsWith("wlan") || name.startsWith("wifi") || name.contains("wireless");
                Enumeration<InetAddress> addresses = network.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    InetAddress address = addresses.nextElement();
                    if (!isPrivateAddress(address) || address.isLoopbackAddress() || address.isLinkLocalAddress()) continue;
                    if (wifi && address instanceof Inet4Address) return address;
                    if (fallback == null || (address instanceof Inet4Address && !(fallback instanceof Inet4Address))) fallback = address;
                }
            }
        } catch (Exception ignored) {}
        return fallback;
    }

    static boolean isPrivateAddress(InetAddress address) {
        if (address == null) return false;
        if (address.isLoopbackAddress() || address.isLinkLocalAddress() || address.isSiteLocalAddress()) return true;
        if (address instanceof Inet6Address) return (address.getAddress()[0] & 0xfe) == 0xfc;
        return false;
    }

    private static X509Certificate selfSigned(KeyPair pair) throws Exception {
        javax.security.auth.x500.X500Principal name = new javax.security.auth.x500.X500Principal("CN=BedrockPocket LAN Dashboard");
        byte[] algorithm = sequence(oid(1,2,840,113549,1,1,11), tagged(0x05, new byte[0])); // sha256WithRSAEncryption
        byte[] validity = sequence(utcTime(new Date(System.currentTimeMillis() - 3600000L)), utcTime(new Date(System.currentTimeMillis() + 5L*365*24*60*60*1000)));
        BigInteger serial = new BigInteger(160, RANDOM).abs().add(BigInteger.ONE);
        byte[] san = subjectAltNames();
        byte[] extension = sequence(oid(2,5,29,17), tagged(0x04, san));
        byte[] extensions = tagged(0xa3, sequence(extension));
        byte[] tbs = sequence(tagged(0xa0, integer(BigInteger.valueOf(2))), integer(serial), algorithm,
                name.getEncoded(), validity, name.getEncoded(), pair.getPublic().getEncoded(), extensions);
        Signature signer = Signature.getInstance("SHA256withRSA");
        signer.initSign(pair.getPrivate(), RANDOM); signer.update(tbs);
        byte[] encoded = sequence(tbs, algorithm, tagged(0x03, concat(new byte[]{0}, signer.sign())));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new java.io.ByteArrayInputStream(encoded));
    }

    private static byte[] subjectAltNames() throws Exception {
        ArrayList<byte[]> names = new ArrayList<>();
        names.add(tagged(0x82, "localhost".getBytes(StandardCharsets.US_ASCII)));
        names.add(tagged(0x87, InetAddress.getByName("127.0.0.1").getAddress()));
        for (InetAddress address : localAddresses()) names.add(tagged(0x87, address.getAddress()));
        return sequence(names.toArray(new byte[0][]));
    }

    private static byte[] utcTime(Date date) throws Exception {
        java.text.SimpleDateFormat format = new java.text.SimpleDateFormat("yyMMddHHmmss'Z'", java.util.Locale.US);
        format.setTimeZone(java.util.TimeZone.getTimeZone("UTC"));
        return tagged(0x17, format.format(date).getBytes(StandardCharsets.US_ASCII));
    }
    private static byte[] integer(BigInteger value) { return tagged(0x02, value.toByteArray()); }
    private static byte[] sequence(byte[]... values) { return tagged(0x30, concat(values)); }
    private static byte[] oid(long... values) {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        out.write((int)(values[0]*40+values[1]));
        for (int i=2;i<values.length;i++) { long v=values[i]; byte[] part=new byte[10]; int n=0; part[n++]=(byte)(v&0x7f); while((v>>=7)>0)part[n++]=(byte)(0x80|(v&0x7f)); for(int j=n-1;j>=0;j--)out.write(part[j]); }
        return tagged(0x06,out.toByteArray());
    }
    private static byte[] tagged(int tag, byte[] value) {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();out.write(tag);int n=value.length;
        if(n<128)out.write(n);else{int count=0,temp=n;while(temp>0){count++;temp>>=8;}out.write(0x80|count);for(int i=count-1;i>=0;i--)out.write((n>>(8*i))&0xff);}
        out.write(value,0,value.length);return out.toByteArray();
    }
    private static byte[] concat(byte[]... values) {
        java.io.ByteArrayOutputStream out=new java.io.ByteArrayOutputStream();for(byte[] value:values)out.write(value,0,value.length);return out.toByteArray();
    }
}
