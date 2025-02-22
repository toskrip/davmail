/*
 * DavMail POP/IMAP/SMTP/CalDav/LDAP Exchange Gateway
 * Copyright (C) 2009  Mickael Guessant
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU General Public License
 * as published by the Free Software Foundation; either version 2
 * of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package davmail.http;

import davmail.BundleMessage;
import davmail.Settings;
import davmail.ui.AcceptCertificateDialog;
import davmail.ui.tray.DavGatewayTray;

import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.awt.*;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.text.SimpleDateFormat;

/**
 * Custom Trust Manager, let user accept or deny.
 */
public class DavGatewayX509TrustManager implements X509TrustManager {
    private final X509TrustManager standardTrustManager;

    /**
     * Create a new custom X509 trust manager.
     *
     * @throws NoSuchAlgorithmException on error
     * @throws KeyStoreException        on error
     */
    public DavGatewayX509TrustManager() throws NoSuchAlgorithmException, KeyStoreException {
        TrustManagerFactory factory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        factory.init((KeyStore) null);
        TrustManager[] trustManagers = factory.getTrustManagers();
        if (trustManagers.length == 0) {
            throw new NoSuchAlgorithmException("No trust manager found");
        }
        this.standardTrustManager = (X509TrustManager) trustManagers[0];
    }

    public void checkServerTrusted(X509Certificate[] x509Certificates, String authType) throws CertificateException {
        try {
            // first try standard Trust Manager
            this.standardTrustManager.checkServerTrusted(x509Certificates, authType);
        } catch (CertificateException e) {
            if ((x509Certificates != null) && (x509Certificates.length > 0)) {
                userCheckServerTrusted(x509Certificates);
            } else {
                throw e;
            }
        }
    }

    public void checkClientTrusted(X509Certificate[] x509Certificates, String authType) throws CertificateException {
        this.standardTrustManager.checkClientTrusted(x509Certificates, authType);
    }

    public X509Certificate[] getAcceptedIssuers() {
        return this.standardTrustManager.getAcceptedIssuers();
    }

    protected void userCheckServerTrusted(final X509Certificate[] x509Certificates) throws CertificateException {
        String acceptedCertificateHash = Settings.getProperty("davmail.server.certificate.hash");
        String certificateHash = getFormattedHash(x509Certificates[0]);
        // if user already accepted a certificate,
        if (acceptedCertificateHash != null && !acceptedCertificateHash.isEmpty()
                && acceptedCertificateHash.equalsIgnoreCase(certificateHash)) {
            DavGatewayTray.debug(new BundleMessage("LOG_FOUND_ACCEPTED_CERTIFICATE", acceptedCertificateHash));
        } else {
            boolean isCertificateTrusted;
            if (Settings.getBooleanProperty("davmail.server") || GraphicsEnvironment.isHeadless()) {
                // headless or server mode
                isCertificateTrusted = isCertificateTrusted(x509Certificates[0]);
            } else {
                isCertificateTrusted = AcceptCertificateDialog.isCertificateTrusted(x509Certificates[0]);
            }
            if (!isCertificateTrusted) {
                throw new CertificateException("User rejected certificate");
            }
            // certificate accepted, store in settings
            Settings.saveProperty("davmail.server.certificate.hash", certificateHash);
        }
    }

    @SuppressWarnings({"UseOfSystemOutOrSystemErr"})
    protected boolean isCertificateTrusted(X509Certificate certificate) {
        BufferedReader inReader = new BufferedReader(new InputStreamReader(System.in));
        String answer = null;
        String yes = BundleMessage.format("UI_ANSWER_YES");
        String no = BundleMessage.format("UI_ANSWER_NO");
        StringBuilder buffer = new StringBuilder();
        buffer.append(BundleMessage.format("UI_SERVER_CERTIFICATE")).append(":\n");
        buffer.append(BundleMessage.format("UI_ISSUED_TO")).append(": ")
                .append(DavGatewayX509TrustManager.getRDN(certificate.getSubjectDN())).append('\n');
        buffer.append(BundleMessage.format("UI_ISSUED_BY")).append(": ")
                .append(getRDN(certificate.getIssuerDN())).append('\n');
        SimpleDateFormat formatter = new SimpleDateFormat("MM/dd/yyyy");
        String notBefore = formatter.format(certificate.getNotBefore());
        buffer.append(BundleMessage.format("UI_VALID_FROM")).append(": ").append(notBefore).append('\n');
        String notAfter = formatter.format(certificate.getNotAfter());
        buffer.append(BundleMessage.format("UI_VALID_UNTIL")).append(": ").append(notAfter).append('\n');
        buffer.append(BundleMessage.format("UI_SERIAL")).append(": ").append(getFormattedSerial(certificate)).append('\n');
        String sha1Hash = DavGatewayX509TrustManager.getFormattedHash(certificate);
        buffer.append(BundleMessage.format("UI_FINGERPRINT")).append(": ").append(sha1Hash).append('\n');
        buffer.append('\n');
        buffer.append(BundleMessage.format("UI_UNTRUSTED_CERTIFICATE")).append('\n');
        try {
            while (!yes.equals(answer) && !no.equals(answer)) {
                System.out.println(buffer);
                answer = inReader.readLine();
                if (answer == null) {
                    answer = no;
                }
                answer = answer.toLowerCase();
            }
        } catch (IOException e) {
            System.err.println(e+" "+e.getMessage());
        }
        return yes.equals(answer);
    }

    /**
     * Get rdn value from principal dn.
     *
     * @param principal security principal
     * @return rdn
     */
    public static String getRDN(Principal principal) {
        String dn = principal.getName();
        int start = dn.indexOf('=');
        int end = dn.indexOf(',');
        if (start >= 0 && end >= 0) {
            return dn.substring(start + 1, end);
        } else {
            return dn;
        }
    }

    /**
     * Build a formatted certificate serial string.
     *
     * @param certificate X509 certificate
     * @return formatted serial
     */
    public static String getFormattedSerial(X509Certificate certificate) {
        StringBuilder builder = new StringBuilder();
        String serial = certificate.getSerialNumber().toString(16);
        for (int i = 0; i < serial.length(); i++) {
            if (i > 0 && i % 2 == 0) {
                builder.append(' ');
            }
            builder.append(serial.charAt(i));
        }
        return builder.toString().toUpperCase();
    }

    /**
     * Build a formatted hash string.
     *
     * @param certificate X509 certificate
     * @return formatted hash
     */
    public static String getFormattedHash(X509Certificate certificate) {
        String sha1Hash;
        try {
            MessageDigest md = MessageDigest.getInstance("SHA1");
            byte[] digest = md.digest(certificate.getEncoded());
            sha1Hash = formatHash(digest);
        } catch (NoSuchAlgorithmException | CertificateEncodingException nsa) {
            sha1Hash = nsa.getMessage();
        }
        return sha1Hash;
    }

    /**
     * Format byte buffer to a hexadecimal hash string.
     *
     * @param buffer byte array
     * @return hexadecimal hash string
     */
    protected static String formatHash(byte[] buffer) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < buffer.length; i++) {
            if (i > 0) {
                builder.append(':');
            }
            builder.append(String.format("%02x", buffer[i] & 0xFF));

        }
        return builder.toString().toUpperCase();
    }
}
