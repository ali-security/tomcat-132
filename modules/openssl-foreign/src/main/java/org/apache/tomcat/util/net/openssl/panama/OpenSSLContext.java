/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.tomcat.util.net.openssl.panama;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SegmentAllocator;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Cleaner;
import java.lang.ref.Cleaner.Cleanable;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.SSLSessionContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509KeyManager;
import javax.net.ssl.X509TrustManager;

import static org.apache.tomcat.util.openssl.openssl_h.*;
import static org.apache.tomcat.util.openssl.openssl_h_Compatibility.*;
import static org.apache.tomcat.util.openssl.openssl_h_Macros.*;
import org.apache.juli.logging.Log;
import org.apache.juli.logging.LogFactory;
import org.apache.tomcat.util.file.ConfigFileLoader;
import org.apache.tomcat.util.file.ConfigurationSource.Resource;
import org.apache.tomcat.util.net.AbstractEndpoint;
import org.apache.tomcat.util.net.Constants;
import org.apache.tomcat.util.net.SSLHostConfig;
import org.apache.tomcat.util.net.SSLHostConfig.CertificateVerification;
import org.apache.tomcat.util.net.SSLHostConfigCertificate;
import org.apache.tomcat.util.net.SSLHostConfigCertificate.Type;
import org.apache.tomcat.util.net.openssl.OpenSSLConf;
import org.apache.tomcat.util.net.openssl.OpenSSLConfCmd;
import org.apache.tomcat.util.res.StringManager;

public class OpenSSLContext implements org.apache.tomcat.util.net.SSLContext {

    private static final Log log = LogFactory.getLog(OpenSSLContext.class);

    private static final StringManager netSm = StringManager.getManager(AbstractEndpoint.class);
    private static final StringManager sm = StringManager.getManager(OpenSSLContext.class);

    private static final Cleaner cleaner = Cleaner.create();

    private static final String defaultProtocol = "TLS";

    private static final int SSL_AIDX_RSA     = 0;
    private static final int SSL_AIDX_DSA     = 1;
    private static final int SSL_AIDX_ECC     = 3;
    private static final int SSL_AIDX_MAX     = 4;

    public static final int SSL_PROTOCOL_NONE  = 0;
    public static final int SSL_PROTOCOL_SSLV2 = (1<<0);
    public static final int SSL_PROTOCOL_SSLV3 = (1<<1);
    public static final int SSL_PROTOCOL_TLSV1 = (1<<2);
    public static final int SSL_PROTOCOL_TLSV1_1 = (1<<3);
    public static final int SSL_PROTOCOL_TLSV1_2 = (1<<4);
    public static final int SSL_PROTOCOL_TLSV1_3 = (1<<5);
    public static final int SSL_PROTOCOL_ALL = (SSL_PROTOCOL_TLSV1 | SSL_PROTOCOL_TLSV1_1 | SSL_PROTOCOL_TLSV1_2 |
            SSL_PROTOCOL_TLSV1_3);

    private static final String BEGIN_KEY = "-----BEGIN PRIVATE KEY-----\n";
    private static final Object END_KEY = "\n-----END PRIVATE KEY-----";

    private static final byte[] HTTP_11_PROTOCOL =
            new byte[] { 'h', 't', 't', 'p', '/', '1', '.', '1' };

    private static final byte[] DEFAULT_SESSION_ID_CONTEXT =
            new byte[] { 'd', 'e', 'f', 'a', 'u', 'l', 't' };

    static final CertificateFactory X509_CERT_FACTORY;
    static {
        try {
            X509_CERT_FACTORY = CertificateFactory.getInstance("X.509");
        } catch (CertificateException e) {
            throw new IllegalStateException(sm.getString("openssl.X509FactoryError"), e);
        }
    }

    private static final MethodHandle openSSLCallbackVerifyHandle;
    private static final MethodHandle openSSLCallbackPasswordHandle;
    private static final MethodHandle openSSLCallbackCertVerifyHandle;
    private static final MethodHandle openSSLCallbackAlpnSelectProtoHandle;
    private static final MethodHandle openSSLCallbackTmpDHHandle;

    private static final FunctionDescriptor openSSLCallbackVerifyFunctionDescriptor =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final FunctionDescriptor openSSLCallbackPasswordFunctionDescriptor =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.JAVA_INT,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final FunctionDescriptor openSSLCallbackCertVerifyFunctionDescriptor =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS, ValueLayout.ADDRESS);
    private static final FunctionDescriptor openSSLCallbackAlpnSelectProtoFunctionDescriptor =
            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.ADDRESS,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS);
    private static final FunctionDescriptor openSSLCallbackTmpDHFunctionDescriptor =
            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.JAVA_INT);

    static {
        MethodHandles.Lookup lookup = MethodHandles.lookup();
        try {
            openSSLCallbackVerifyHandle = lookup.findStatic(OpenSSLContext.class, "openSSLCallbackVerify",
                    MethodType.methodType(int.class, int.class, MemorySegment.class));
            openSSLCallbackPasswordHandle = lookup.findStatic(OpenSSLContext.class, "openSSLCallbackPassword",
                    MethodType.methodType(int.class, MemorySegment.class, int.class, int.class, MemorySegment.class));
            openSSLCallbackCertVerifyHandle = lookup.findStatic(OpenSSLContext.class, "openSSLCallbackCertVerify",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class));
            openSSLCallbackAlpnSelectProtoHandle = lookup.findStatic(OpenSSLContext.class, "openSSLCallbackAlpnSelectProto",
                    MethodType.methodType(int.class, MemorySegment.class, MemorySegment.class,
                            MemorySegment.class, MemorySegment.class, int.class, MemorySegment.class));
            openSSLCallbackTmpDHHandle = lookup.findStatic(OpenSSLContext.class, "openSSLCallbackTmpDH",
                    MethodType.methodType(MemorySegment.class, MemorySegment.class, int.class, int.class));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static final boolean OPENSSL_3 = (OpenSSL_version_num() >= 0x3000000fL);

    private final SSLHostConfig sslHostConfig;
    private final SSLHostConfigCertificate certificate;
    private final boolean alpn;
    private final int minTlsVersion;
    private final int maxTlsVersion;

    private OpenSSLSessionContext sessionContext;
    private String enabledProtocol;
    private boolean initialized = false;

    private boolean noOcspCheck = false;

    // Password callback
    private final MemorySegment openSSLCallbackPassword;

    private static final ConcurrentHashMap<Long, ContextState> states = new ConcurrentHashMap<>();

    static ContextState getState(MemorySegment ctx) {
        return states.get(Long.valueOf(ctx.address()));
    }

    private final ContextState state;
    private final Arena contextArena;
    private final Cleanable cleanable;

    private static String[] getCiphers(MemorySegment sslCtx) {
        MemorySegment sk = SSL_CTX_get_ciphers(sslCtx);
        int len = OPENSSL_sk_num(sk);
        if (len <= 0) {
            return null;
        }
        ArrayList<String> ciphers = new ArrayList<>(len);
        for (int i = 0; i < len; i++) {
            MemorySegment cipher = OPENSSL_sk_value(sk, i);
            MemorySegment cipherName = SSL_CIPHER_get_name(cipher);
            ciphers.add(cipherName.getString(0));
        }
        return ciphers.toArray(new String[0]);
    }

    public OpenSSLContext(SSLHostConfigCertificate certificate, List<String> negotiableProtocols)
            throws SSLException {

        // Check that OpenSSL was initialized
        if (!OpenSSLStatus.isInitialized()) {
            try {
                OpenSSLLibrary.init();
            } catch (Exception e) {
                throw new SSLException(e);
            }
        }

        this.sslHostConfig = certificate.getSSLHostConfig();
        this.certificate = certificate;
        contextArena = Arena.ofAuto();

        MemorySegment sslCtx = MemorySegment.NULL;
        MemorySegment confCtx = MemorySegment.NULL;
        List<byte[]> negotiableProtocolsBytes = null;
        boolean success = false;
        try {
            // Create OpenSSLConfCmd context if used
            OpenSSLConf openSslConf = sslHostConfig.getOpenSslConf();
            if (openSslConf != null) {
                try {
                    if (log.isDebugEnabled()) {
                        log.debug(sm.getString("openssl.makeConf"));
                    }
                    confCtx = SSL_CONF_CTX_new();
                    long errCode = ERR_get_error();
                    if (errCode != 0) {
                        try (var localArena = Arena.ofConfined()) {
                            var buf = localArena.allocate(ValueLayout.JAVA_BYTE, 128);
                            ERR_error_string(errCode, buf);
                            log.error(sm.getString("openssl.errorLoadingCertificate", buf.getString(0)));
                        }
                    }
                    SSL_CONF_CTX_set_flags(confCtx, SSL_CONF_FLAG_FILE() |
                            SSL_CONF_FLAG_SERVER() |
                            SSL_CONF_FLAG_CERTIFICATE() |
                            SSL_CONF_FLAG_SHOW_ERRORS());
                } catch (Exception e) {
                    throw new SSLException(sm.getString("openssl.errMakeConf"), e);
                }
            }

            // SSL protocol
            sslCtx = SSL_CTX_new(TLS_server_method());

            int protocol = SSL_PROTOCOL_NONE;
            for (String enabledProtocol : sslHostConfig.getEnabledProtocols()) {
                if (Constants.SSL_PROTO_SSLv2Hello.equalsIgnoreCase(enabledProtocol)) {
                    // NO-OP. OpenSSL always supports SSLv2Hello
                } else if (Constants.SSL_PROTO_SSLv2.equalsIgnoreCase(enabledProtocol)) {
                    protocol |= SSL_PROTOCOL_SSLV2;
                } else if (Constants.SSL_PROTO_SSLv3.equalsIgnoreCase(enabledProtocol)) {
                    protocol |= SSL_PROTOCOL_SSLV3;
                } else if (Constants.SSL_PROTO_TLSv1.equalsIgnoreCase(enabledProtocol)) {
                    protocol |= SSL_PROTOCOL_TLSV1;
                } else if (Constants.SSL_PROTO_TLSv1_1.equalsIgnoreCase(enabledProtocol)) {
                    protocol |= SSL_PROTOCOL_TLSV1_1;
                } else if (Constants.SSL_PROTO_TLSv1_2.equalsIgnoreCase(enabledProtocol)) {
                    protocol |= SSL_PROTOCOL_TLSV1_2;
                } else if (Constants.SSL_PROTO_TLSv1_3.equalsIgnoreCase(enabledProtocol)) {
                    protocol |= SSL_PROTOCOL_TLSV1_3;
                } else if (Constants.SSL_PROTO_ALL.equalsIgnoreCase(enabledProtocol)) {
                    protocol |= SSL_PROTOCOL_ALL;
                } else {
                    // Should not happen since filtering to build
                    // enabled protocols removes invalid values.
                    throw new Exception(netSm.getString(
                            "endpoint.apr.invalidSslProtocol", enabledProtocol));
                }
            }
            // Set maximum and minimum protocol versions
            int prot = SSL2_VERSION();
            if ((protocol & SSL_PROTOCOL_TLSV1_3) > 0) {
                prot = TLS1_3_VERSION();
            } else if ((protocol & SSL_PROTOCOL_TLSV1_2) > 0) {
                prot = TLS1_2_VERSION();
            } else if ((protocol & SSL_PROTOCOL_TLSV1_1) > 0) {
                prot = TLS1_1_VERSION();
            } else if ((protocol & SSL_PROTOCOL_TLSV1) > 0) {
                prot = TLS1_VERSION();
            } else if ((protocol & SSL_PROTOCOL_SSLV3) > 0) {
                prot = SSL3_VERSION();
            }
            maxTlsVersion = prot;
            SSL_CTX_set_max_proto_version(sslCtx, prot);
            if (prot == TLS1_3_VERSION() && (protocol & SSL_PROTOCOL_TLSV1_2) > 0) {
                prot = TLS1_2_VERSION();
            }
            if (prot == TLS1_2_VERSION() && (protocol & SSL_PROTOCOL_TLSV1_1) > 0) {
                prot = TLS1_1_VERSION();
            }
            if (prot == TLS1_1_VERSION() && (protocol & SSL_PROTOCOL_TLSV1) > 0) {
                prot = TLS1_VERSION();
            }
            if (prot == TLS1_VERSION() && (protocol & SSL_PROTOCOL_SSLV3) > 0) {
                prot = SSL3_VERSION();
            }
            minTlsVersion = prot;
            SSL_CTX_set_min_proto_version(sslCtx, prot);

            // Disable compression, usually unsafe
            SSL_CTX_set_options(sslCtx, SSL_OP_NO_COMPRESSION());

            // Disallow a session from being resumed during a renegotiation,
            // so that an acceptable cipher suite can be negotiated.
            SSL_CTX_set_options(sslCtx, SSL_OP_NO_SESSION_RESUMPTION_ON_RENEGOTIATION());

            SSL_CTX_set_options(sslCtx, SSL_OP_SINGLE_DH_USE());
            SSL_CTX_set_options(sslCtx, SSL_OP_SINGLE_ECDH_USE());

            // Default session context id and cache size
            SSL_CTX_sess_set_cache_size(sslCtx, 256);

            // Session cache is disabled by default
            SSL_CTX_set_session_cache_mode(sslCtx, SSL_SESS_CACHE_OFF());

            // Longer session timeout
            SSL_CTX_set_timeout(sslCtx, 14400);

            // From SSLContext.make, possibly set ssl_callback_ServerNameIndication
            // From SSLContext.make, possibly set ssl_callback_ClientHello
            // Probably not needed

            // Set int pem_password_cb(char *buf, int size, int rwflag, void *u) callback
            openSSLCallbackPassword =
                    Linker.nativeLinker().upcallStub(openSSLCallbackPasswordHandle,
                    openSSLCallbackPasswordFunctionDescriptor, contextArena);
            SSL_CTX_set_default_passwd_cb(sslCtx, openSSLCallbackPassword);

            alpn = (negotiableProtocols != null && negotiableProtocols.size() > 0);
            if (alpn) {
                negotiableProtocolsBytes = new ArrayList<>(negotiableProtocols.size() + 1);
                for (String negotiableProtocol : negotiableProtocols) {
                    negotiableProtocolsBytes.add(negotiableProtocol.getBytes(StandardCharsets.ISO_8859_1));
                }
                negotiableProtocolsBytes.add(HTTP_11_PROTOCOL);
            }

            success = true;
        } catch(Exception e) {
            throw new SSLException(sm.getString("openssl.errorSSLCtxInit"), e);
        } finally {
            state = new ContextState(sslCtx, confCtx, negotiableProtocolsBytes);
            /*
             * When an SSLHostConfig is replaced at runtime, it is not possible to
             * call destroy() on the associated OpenSSLContext since it is likely
             * that there will be in-progress connections using the OpenSSLContext.
             * A reference chain has been deliberately established (see
             * OpenSSLSessionContext) to ensure that the OpenSSLContext remains
             * ineligible for GC while those connections are alive. Once those
             * connections complete, the OpenSSLContext will become eligible for GC
             * and the memory session will ensure that the associated native
             * resources are cleaned up.
             */
            cleanable = cleaner.register(this, state);

            if (!success) {
                destroy();
            }
        }
    }


    public String getEnabledProtocol() {
        return enabledProtocol;
    }


    public void setEnabledProtocol(String protocol) {
        enabledProtocol = (protocol == null) ? defaultProtocol : protocol;
    }


    @Override
    public void destroy() {
        cleanable.clean();
    }


    private boolean checkConf(OpenSSLConf conf) throws Exception {
        boolean result = true;
        OpenSSLConfCmd cmd;
        String name;
        String value;
        int rc;
        for (OpenSSLConfCmd command : conf.getCommands()) {
            cmd = command;
            name = cmd.getName();
            value = cmd.getValue();
            if (name == null) {
                log.error(sm.getString("opensslconf.noCommandName", value));
                result = false;
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("opensslconf.checkCommand", name, value));
            }
            try (var localArena = Arena.ofConfined()) {
                // rc = SSLConf.check(confCtx, name, value);
                if (name.equals("NO_OCSP_CHECK")) {
                    rc = 1;
                } else {
                    int code = SSL_CONF_cmd_value_type(state.confCtx, localArena.allocateFrom(name));
                    rc = 1;
                    long errCode = ERR_get_error();
                    if (errCode != 0) {
                        var buf = localArena.allocate(ValueLayout.JAVA_BYTE, 128);
                        ERR_error_string(errCode, buf);
                        log.error(sm.getString("opensslconf.checkFailed", buf.getString(0)));
                        rc = 0;
                    }
                    if (code == SSL_CONF_TYPE_UNKNOWN()) {
                        log.error(sm.getString("opensslconf.typeUnknown", name));
                        rc = 0;
                    }
                    if (code == SSL_CONF_TYPE_FILE()) {
                        // Check file
                        File file = new File(value);
                        if (!file.isFile() && !file.canRead()) {
                            log.error(sm.getString("opensslconf.badFile", name, value));
                            rc = 0;
                        }
                    }
                    if (code == SSL_CONF_TYPE_DIR()) {
                        // Check dir
                        File file = new File(value);
                        if (!file.isDirectory()) {
                            log.error(sm.getString("opensslconf.badDirectory", name, value));
                            rc = 0;
                        }
                    }
                }
            } catch (Exception e) {
                log.error(sm.getString("opensslconf.checkFailed", e.getLocalizedMessage()));
                return false;
            }
            if (rc <= 0) {
                log.error(sm.getString("opensslconf.failedCommand", name, value,
                        Integer.toString(rc)));
                result = false;
            } else if (log.isDebugEnabled()) {
                log.debug(sm.getString("opensslconf.resultCommand", name, value,
                        Integer.toString(rc)));
            }
        }
        if (!result) {
            log.error(sm.getString("opensslconf.checkFailed"));
        }
        return result;
    }


    private boolean applyConf(OpenSSLConf conf) throws Exception {
        boolean result = true;
        // SSLConf.assign(confCtx, sslCtx);
        SSL_CONF_CTX_set_ssl_ctx(state.confCtx, state.sslCtx);
        OpenSSLConfCmd cmd;
        String name;
        String value;
        int rc;
        for (OpenSSLConfCmd command : conf.getCommands()) {
            cmd = command;
            name = cmd.getName();
            value = cmd.getValue();
            if (name == null) {
                log.error(sm.getString("opensslconf.noCommandName", value));
                result = false;
                continue;
            }
            if (log.isDebugEnabled()) {
                log.debug(sm.getString("opensslconf.applyCommand", name, value));
            }
            try (var localArena = Arena.ofConfined()) {
                // rc = SSLConf.apply(confCtx, name, value);
                if (name.equals("NO_OCSP_CHECK")) {
                    noOcspCheck = Boolean.valueOf(value);
                    rc = 1;
                } else {
                    rc = SSL_CONF_cmd(state.confCtx, localArena.allocateFrom(name),
                            localArena.allocateFrom(value));
                    long errCode = ERR_get_error();
                    if (rc <= 0 || errCode != 0) {
                        var buf = localArena.allocate(ValueLayout.JAVA_BYTE, 128);
                        ERR_error_string(errCode, buf);
                        log.error(sm.getString("opensslconf.commandError", name, value, buf.getString(0)));
                        rc = 0;
                    }
                }
            } catch (Exception e) {
                log.error(sm.getString("opensslconf.applyFailed"));
                return false;
            }
            if (rc <= 0) {
                log.error(sm.getString("opensslconf.failedCommand", name, value,
                        Integer.toString(rc)));
                result = false;
            } else if (log.isDebugEnabled()) {
                log.debug(sm.getString("opensslconf.resultCommand", name, value,
                        Integer.toString(rc)));
            }
        }
        // rc = SSLConf.finish(confCtx);
        rc = SSL_CONF_CTX_finish(state.confCtx);
        if (rc <= 0) {
            log.error(sm.getString("opensslconf.finishFailed", Integer.toString(rc)));
            result = false;
        }
        if (!result) {
            log.error(sm.getString("opensslconf.applyFailed"));
        }
        return result;
    }

    private static final int OPTIONAL_NO_CA = 3;

    /**
     * Setup the SSL_CTX.
     *
     * @param kms Must contain a KeyManager of the type
     *            {@code OpenSSLKeyManager}
     * @param tms Must contain a TrustManager of the type
     *            {@code X509TrustManager}
     * @param sr Is not used for this implementation.
     */
    @Override
    public synchronized void init(KeyManager[] kms, TrustManager[] tms, SecureRandom sr) {
        if (initialized) {
            log.warn(sm.getString("openssl.doubleInit"));
            return;
        }
        boolean success = true;
        try (var localArena = Arena.ofConfined()) {
            if (sslHostConfig.getInsecureRenegotiation()) {
                SSL_CTX_set_options(state.sslCtx, SSL_OP_ALLOW_UNSAFE_LEGACY_RENEGOTIATION());
            } else {
                SSL_CTX_clear_options(state.sslCtx, SSL_OP_ALLOW_UNSAFE_LEGACY_RENEGOTIATION());
            }

            // Use server's preference order for ciphers (rather than
            // client's)
            if (sslHostConfig.getHonorCipherOrder()) {
                SSL_CTX_set_options(state.sslCtx, SSL_OP_CIPHER_SERVER_PREFERENCE());
            } else {
                SSL_CTX_clear_options(state.sslCtx, SSL_OP_CIPHER_SERVER_PREFERENCE());
            }

            // Disable compression if requested
            if (sslHostConfig.getDisableCompression()) {
                SSL_CTX_set_options(state.sslCtx, SSL_OP_NO_COMPRESSION());
            } else {
                SSL_CTX_clear_options(state.sslCtx, SSL_OP_NO_COMPRESSION());
            }

            // Disable TLS Session Tickets (RFC4507) to protect perfect forward secrecy
            if (sslHostConfig.getDisableSessionTickets()) {
                SSL_CTX_set_options(state.sslCtx, SSL_OP_NO_TICKET());
            } else {
                SSL_CTX_clear_options(state.sslCtx, SSL_OP_NO_TICKET());
            }

            // List the ciphers that the client is permitted to negotiate
            if (minTlsVersion <= TLS1_2_VERSION()) {
                if (SSL_CTX_set_cipher_list(state.sslCtx,
                        localArena.allocateFrom(sslHostConfig.getCiphers())) <= 0) {
                    log.warn(sm.getString("engine.failedCipherList", sslHostConfig.getCiphers()));
                }
            }
            if (maxTlsVersion >= TLS1_3_VERSION() && (sslHostConfig.getCiphers() != SSLHostConfig.DEFAULT_TLS_CIPHERS)) {
                if (SSL_CTX_set_ciphersuites(state.sslCtx,
                        localArena.allocateFrom(sslHostConfig.getCiphers())) <= 0) {
                    log.warn(sm.getString("engine.failedCipherSuite", sslHostConfig.getCiphers()));
                }
            }

            // If there is no certificate file must be using a KeyStore so a KeyManager is required.
            // If there is a certificate file a KeyManager is helpful but not strictly necessary.
            certificate.setCertificateKeyManager(
                    OpenSSLUtil.chooseKeyManager(kms, certificate.getCertificateFile() == null));

            success = addCertificate(certificate, localArena);

            // SSLContext.setVerify(state.ctx, value, sslHostConfig.getCertificateVerificationDepth());
            // Client certificate verification
            int value = 0;
            switch (sslHostConfig.getCertificateVerification()) {
            case NONE:
                value = SSL_VERIFY_NONE();
                break;
            case OPTIONAL:
                value = SSL_VERIFY_PEER();
                break;
            case OPTIONAL_NO_CA:
                value = OPTIONAL_NO_CA;
                break;
            case REQUIRED:
                value = SSL_VERIFY_FAIL_IF_NO_PEER_CERT();
                break;
            }

            // Set int verify_callback(int preverify_ok, X509_STORE_CTX *x509_ctx) callback
            var openSSLCallbackVerify =
                    Linker.nativeLinker().upcallStub(openSSLCallbackVerifyHandle,
                    openSSLCallbackVerifyFunctionDescriptor, contextArena);
            // Leave this just in case but in Tomcat this is always set again by the engine
            SSL_CTX_set_verify(state.sslCtx, value, openSSLCallbackVerify);

            // Trust and certificate verification
            if (tms != null) {
                // Client certificate verification based on custom trust managers
                state.x509TrustManager = chooseTrustManager(tms);
                var openSSLCallbackCertVerify =
                        Linker.nativeLinker().upcallStub(openSSLCallbackCertVerifyHandle,
                                openSSLCallbackCertVerifyFunctionDescriptor, contextArena);
                SSL_CTX_set_cert_verify_callback(state.sslCtx, openSSLCallbackCertVerify, state.sslCtx);

                // Pass along the DER encoded certificates of the accepted client
                // certificate issuers, so that their subjects can be presented
                // by the server during the handshake to allow the client choosing
                // an acceptable certificate
                for (X509Certificate caCert : state.x509TrustManager.getAcceptedIssuers()) {
                    //SSLContext.addClientCACertificateRaw(state.ctx, caCert.getEncoded());
                    var rawCACertificate = localArena.allocateFrom(ValueLayout.JAVA_BYTE, caCert.getEncoded());
                    var rawCACertificatePointer = localArena.allocateFrom(ValueLayout.ADDRESS, rawCACertificate);
                    var x509CACert = d2i_X509(MemorySegment.NULL, rawCACertificatePointer, rawCACertificate.byteSize());
                    if (MemorySegment.NULL.equals(x509CACert)) {
                        logLastError(localArena, "openssl.errorLoadingCertificate");
                    } else if (SSL_CTX_add_client_CA(state.sslCtx, x509CACert) <= 0) {
                        logLastError(localArena, "openssl.errorAddingCertificate");
                    } else if (log.isDebugEnabled()) {
                        log.debug(sm.getString("openssl.addedClientCaCert", caCert.toString()));
                    }
                }
            } else {
                // Client certificate verification based on trusted CA files and dirs
                //SSLContext.setCACertificate(state.ctx,
                //        SSLHostConfig.adjustRelativePath(sslHostConfig.getCaCertificateFile()),
                //        SSLHostConfig.adjustRelativePath(sslHostConfig.getCaCertificatePath()));
                MemorySegment caCertificateFileNative = sslHostConfig.getCaCertificateFile() != null
                        ? localArena.allocateFrom(SSLHostConfig.adjustRelativePath(sslHostConfig.getCaCertificateFile())) : null;
                MemorySegment caCertificatePathNative = sslHostConfig.getCaCertificatePath() != null
                        ? localArena.allocateFrom(SSLHostConfig.adjustRelativePath(sslHostConfig.getCaCertificatePath())) : null;
                if ((sslHostConfig.getCaCertificateFile() != null || sslHostConfig.getCaCertificatePath() != null)
                        && SSL_CTX_load_verify_locations(state.sslCtx,
                                caCertificateFileNative == null ? MemorySegment.NULL : caCertificateFileNative,
                                        caCertificatePathNative == null ? MemorySegment.NULL : caCertificatePathNative) <= 0) {
                    logLastError(localArena, "openssl.errorConfiguringLocations");
                } else {
                    var caCerts = SSL_CTX_get_client_CA_list(state.sslCtx);
                    if (MemorySegment.NULL.equals(caCerts)) {
                        caCerts = SSL_load_client_CA_file(caCertificateFileNative == null ? MemorySegment.NULL : caCertificateFileNative);
                        if (!MemorySegment.NULL.equals(caCerts)) {
                            SSL_CTX_set_client_CA_list(state.sslCtx, caCerts);
                        }
                    } else {
                        if (SSL_add_file_cert_subjects_to_stack(caCerts,
                                caCertificateFileNative == null ? MemorySegment.NULL : caCertificateFileNative) <= 0) {
                            caCerts = MemorySegment.NULL;
                        }
                    }
                    if (MemorySegment.NULL.equals(caCerts)) {
                        log.warn(sm.getString("openssl.noCACerts"));
                    }
                }
            }

            if (state.negotiableProtocols != null && state.negotiableProtocols.size() > 0) {
                // int openSSLCallbackAlpnSelectProto(MemoryAddress ssl, MemoryAddress out, MemoryAddress outlen,
                //        MemoryAddress in, int inlen, MemoryAddress arg
                var openSSLCallbackAlpnSelectProto =
                        Linker.nativeLinker().upcallStub(openSSLCallbackAlpnSelectProtoHandle,
                        openSSLCallbackAlpnSelectProtoFunctionDescriptor, contextArena);
                SSL_CTX_set_alpn_select_cb(state.sslCtx, openSSLCallbackAlpnSelectProto, state.sslCtx);
            }

            // Apply OpenSSLConfCmd if used
            OpenSSLConf openSslConf = sslHostConfig.getOpenSslConf();
            if (openSslConf != null && !MemorySegment.NULL.equals(state.confCtx)) {
                // Check OpenSSLConfCmd if used
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("openssl.checkConf"));
                }
                try {
                    if (!checkConf(openSslConf)) {
                        log.error(sm.getString("openssl.errCheckConf"));
                    }
                } catch (Exception e) {
                    log.error(sm.getString("openssl.errCheckConf"), e);
                }
                if (log.isDebugEnabled()) {
                    log.debug(sm.getString("openssl.applyConf"));
                }
                try {
                    if (!applyConf(openSslConf)) {
                        log.error(sm.getString("openssl.errApplyConf"));
                    }
                } catch (Exception e) {
                    log.error(sm.getString("openssl.errApplyConf"), e);
                }
                // Reconfigure the enabled protocols
                long opts = SSL_CTX_get_options(state.sslCtx);
                List<String> enabled = new ArrayList<>();
                // Seems like there is no way to explicitly disable SSLv2Hello
                // in OpenSSL so it is always enabled
                enabled.add(Constants.SSL_PROTO_SSLv2Hello);
                if ((opts & SSL_OP_NO_TLSv1()) == 0) {
                    enabled.add(Constants.SSL_PROTO_TLSv1);
                }
                if ((opts & SSL_OP_NO_TLSv1_1()) == 0) {
                    enabled.add(Constants.SSL_PROTO_TLSv1_1);
                }
                if ((opts & SSL_OP_NO_TLSv1_2()) == 0) {
                    enabled.add(Constants.SSL_PROTO_TLSv1_2);
                }
                if ((opts & SSL_OP_NO_TLSv1_3()) == 0) {
                    enabled.add(Constants.SSL_PROTO_TLSv1_3);
                }
                if ((opts & SSL_OP_NO_SSLv2()) == 0) {
                    enabled.add(Constants.SSL_PROTO_SSLv2);
                }
                if ((opts & SSL_OP_NO_SSLv3()) == 0) {
                    enabled.add(Constants.SSL_PROTO_SSLv3);
                }
                sslHostConfig.setEnabledProtocols(
                        enabled.toArray(new String[0]));
                // Reconfigure the enabled ciphers
                sslHostConfig.setEnabledCiphers(getCiphers(state.sslCtx));
            }

            sessionContext = new OpenSSLSessionContext(this);
            // If client authentication is being used, OpenSSL requires that
            // this is set so always set it in case an app is configured to
            // require it
            sessionContext.setSessionIdContext(DEFAULT_SESSION_ID_CONTEXT);
            sslHostConfig.setOpenSslContext(state.sslCtx.address());
            initialized = true;
        } catch (Exception e) {
            log.warn(sm.getString("openssl.errorSSLCtxInit"), e);
            success = false;
        }
        if (!success) {
            destroy();
        }
    }


    public MemorySegment getSSLContext() {
        return state.sslCtx;
    }

    // DH *(*tmp_dh_callback)(SSL *ssl, int is_export, int keylength)
    public static MemorySegment openSSLCallbackTmpDH(MemorySegment ssl, int isExport, int keylength) {
        var pkey = SSL_get_privatekey(ssl);
        int type = (MemorySegment.NULL.equals(pkey)) ? EVP_PKEY_NONE()
                : (OPENSSL_3 ? EVP_PKEY_get_base_id(pkey) : EVP_PKEY_base_id(pkey));
        /*
         * OpenSSL will call us with either keylen == 512 or keylen == 1024
         * (see the definition of SSL_EXPORT_PKEYLENGTH in ssl_locl.h).
         * Adjust the DH parameter length according to the size of the
         * RSA/DSA private key used for the current connection, and always
         * use at least 1024-bit parameters.
         * Note: This may cause interoperability issues with implementations
         * which limit their DH support to 1024 bit - e.g. Java 7 and earlier.
         * In this case, SSLCertificateFile can be used to specify fixed
         * 1024-bit DH parameters (with the effect that OpenSSL skips this
         * callback).
         */
        int keylen = 0;
        if (type == EVP_PKEY_RSA() || type == EVP_PKEY_DSA()) {
            keylen = (OPENSSL_3 ? EVP_PKEY_get_bits(pkey) : EVP_PKEY_bits(pkey));
        }
        for (int i = 0; i < OpenSSLLibrary.dhParameters.length; i++) {
            if (keylen >= OpenSSLLibrary.dhParameters[i].min) {
                return OpenSSLLibrary.dhParameters[i].dh;
            }
        }
        return MemorySegment.NULL;
    }

    // int SSL_callback_alpn_select_proto(SSL* ssl, const unsigned char **out, unsigned char *outlen,
    //        const unsigned char *in, unsigned int inlen, void *arg)
    public static int openSSLCallbackAlpnSelectProto(MemorySegment ssl, MemorySegment out, MemorySegment outlen,
            MemorySegment in, int inlen, MemorySegment arg) {
        ContextState state = getState(arg);
        if (state == null) {
            log.warn(sm.getString("context.noSSL", Long.valueOf(arg.address())));
            return SSL_TLSEXT_ERR_NOACK();
        }
        try (var localArena = Arena.ofConfined()) {
            MemorySegment inSeg = in.reinterpret(inlen, localArena, null);
            byte[] advertisedBytes = inSeg.toArray(ValueLayout.JAVA_BYTE);
            for (byte[] negotiableProtocolBytes : state.negotiableProtocols) {
                for (int i = 0; i <= advertisedBytes.length - negotiableProtocolBytes.length; i++) {
                    if (advertisedBytes[i] == negotiableProtocolBytes[0]) {
                        for (int j = 0; j < negotiableProtocolBytes.length; j++) {
                            if (advertisedBytes[i + j] == negotiableProtocolBytes[j]) {
                                if (j == negotiableProtocolBytes.length - 1) {
                                    // Match
                                    MemorySegment outSeg = out.reinterpret(ValueLayout.ADDRESS.byteSize(), localArena, null);
                                    outSeg.set(ValueLayout.ADDRESS, 0, inSeg.asSlice(i));
                                    MemorySegment outlenSeg = outlen.reinterpret(ValueLayout.JAVA_BYTE.byteSize(), localArena, null);
                                    outlenSeg.set(ValueLayout.JAVA_BYTE, 0, (byte) negotiableProtocolBytes.length);
                                    return SSL_TLSEXT_ERR_OK();
                                }
                            } else {
                                break;
                            }
                        }
                    }
                }
            }
        }
        return SSL_TLSEXT_ERR_NOACK();
    }

    public static int openSSLCallbackVerify(int preverify_ok, MemorySegment /*X509_STORE_CTX*/ x509ctx) {
        return OpenSSLEngine.openSSLCallbackVerify(preverify_ok, x509ctx);
    }


    public static int openSSLCallbackCertVerify(MemorySegment /*X509_STORE_CTX*/ x509_ctx, MemorySegment param) {
        if (log.isDebugEnabled()) {
            log.debug("Certificate verification");
        }
        if (MemorySegment.NULL.equals(param)) {
            return 0;
        }
        ContextState state = getState(param);
        if (state == null) {
            log.warn(sm.getString("context.noSSL", Long.valueOf(param.address())));
            return 0;
        }
        MemorySegment ssl = X509_STORE_CTX_get_ex_data(x509_ctx, SSL_get_ex_data_X509_STORE_CTX_idx());
        MemorySegment /*STACK_OF(X509)*/ sk = X509_STORE_CTX_get0_untrusted(x509_ctx);
        int len = OPENSSL_sk_num(sk);
        byte[][] certificateChain = new byte[len][];
        try (var localArena = Arena.ofConfined()) {
            for (int i = 0; i < len; i++) {
                MemorySegment/*(X509*)*/ x509 = OPENSSL_sk_value(sk, i);
                MemorySegment bufPointer = localArena.allocateFrom(ValueLayout.ADDRESS, MemorySegment.NULL);
                int length = i2d_X509(x509, bufPointer);
                if (length < 0) {
                    certificateChain[i] = new byte[0];
                    continue;
                }
                MemorySegment buf = bufPointer.get(ValueLayout.ADDRESS, 0);
                certificateChain[i] = buf.reinterpret(length, localArena, null).toArray(ValueLayout.JAVA_BYTE);
                CRYPTO_free(buf, MemorySegment.NULL, 0); // OPENSSL_free macro
            }
            MemorySegment cipher = SSL_get_current_cipher(ssl);
            String authMethod = (MemorySegment.NULL.equals(cipher)) ? "UNKNOWN"
                    : getCipherAuthenticationMethod(SSL_CIPHER_get_auth_nid(cipher), SSL_CIPHER_get_kx_nid(cipher));
            X509Certificate[] peerCerts = certificates(certificateChain);
            try {
                state.x509TrustManager.checkClientTrusted(peerCerts, authMethod);
                return 1;
            } catch (Exception e) {
                log.debug(sm.getString("openssl.certificateVerificationFailed"), e);
            }
        }
        return 0;
    }

    private static final int NID_kx_rsa = 1037/*NID_kx_rsa()*/;
    //private static final int NID_kx_dhe = NID_kx_dhe();
    //private static final int NID_kx_ecdhe = NID_kx_ecdhe();

    //private static final int NID_auth_rsa = NID_auth_rsa();
    //private static final int NID_auth_dss = NID_auth_dss();
    //private static final int NID_auth_null = NID_auth_null();
    //private static final int NID_auth_ecdsa = NID_auth_ecdsa();

    //private static final int SSL_kRSA = 1;
    private static final int SSL_kDHr = 2;
    private static final int SSL_kDHd = 4;
    private static final int SSL_kEDH = 8;
    private static final int SSL_kDHE = SSL_kEDH;
    private static final int SSL_kKRB5 = 10;
    private static final int SSL_kECDHr = 20;
    private static final int SSL_kECDHe = 40;
    private static final int SSL_kEECDH = 80;
    private static final int SSL_kECDHE = SSL_kEECDH;
    //private static final int SSL_kPSK = 100;
    //private static final int SSL_kGOST = 200;
    //private static final int SSL_kSRP = 400;

    private static final int SSL_aRSA = 1;
    private static final int SSL_aDSS = 2;
    private static final int SSL_aNULL = 4;
    //private static final int SSL_aDH = 8;
    //private static final int SSL_aECDH = 10;
    //private static final int SSL_aKRB5 = 20;
    private static final int SSL_aECDSA = 40;
    //private static final int SSL_aPSK = 80;
    //private static final int SSL_aGOST94 = 100;
    //private static final int SSL_aGOST01 = 200;
    //private static final int SSL_aSRP = 400;

    private static final String SSL_TXT_RSA = "RSA";
    private static final String SSL_TXT_DH = "DH";
    private static final String SSL_TXT_DSS = "DSS";
    private static final String SSL_TXT_KRB5 = "KRB5";
    private static final String SSL_TXT_ECDH = "ECDH";
    private static final String SSL_TXT_ECDSA = "ECDSA";

    private static String getCipherAuthenticationMethod(int auth, int kx) {
        switch (kx) {
        case NID_kx_rsa:
            return SSL_TXT_RSA;
        case SSL_kDHr:
            return SSL_TXT_DH + "_" + SSL_TXT_RSA;
        case SSL_kDHd:
            return SSL_TXT_DH + "_" + SSL_TXT_DSS;
        case SSL_kDHE:
            switch (auth) {
            case SSL_aDSS:
                return "DHE_" + SSL_TXT_DSS;
            case SSL_aRSA:
                return "DHE_" + SSL_TXT_RSA;
            case SSL_aNULL:
                return SSL_TXT_DH + "_anon";
            default:
                return "UNKNOWN";
            }
        case SSL_kKRB5:
            return SSL_TXT_KRB5;
        case SSL_kECDHr:
            return SSL_TXT_ECDH + "_" + SSL_TXT_RSA;
        case SSL_kECDHe:
            return SSL_TXT_ECDH + "_" + SSL_TXT_ECDSA;
        case SSL_kECDHE:
            switch (auth) {
            case SSL_aECDSA:
                return "ECDHE_" + SSL_TXT_ECDSA;
            case SSL_aRSA:
                return "ECDHE_" + SSL_TXT_RSA;
            case SSL_aNULL:
                return SSL_TXT_ECDH + "_anon";
            default:
                return "UNKNOWN";
            }
        default:
            return "UNKNOWN";
        }
    }

    private static ThreadLocal<String> callbackPasswordTheadLocal = new ThreadLocal<>();

    public static int openSSLCallbackPassword(MemorySegment /*char **/ buf, int bufsiz, int verify, MemorySegment /*void **/ cb) {
        if (log.isDebugEnabled()) {
            log.debug("Return password for certificate");
        }
        String callbackPassword = callbackPasswordTheadLocal.get();
        if (callbackPassword != null && callbackPassword.length() > 0) {
            try (var localArena = Arena.ofConfined()) {
                MemorySegment callbackPasswordNative = localArena.allocateFrom(callbackPassword);
                if (callbackPasswordNative.byteSize() > bufsiz) {
                    // The password is too long
                    log.error(sm.getString("openssl.passwordTooLong"));
                } else {
                    MemorySegment bufSegment = buf.reinterpret(bufsiz, localArena, null);
                    bufSegment.copyFrom(callbackPasswordNative);
                    return (int) callbackPasswordNative.byteSize();
                }
            }
        }
        return 0;
    }


    private boolean addCertificate(SSLHostConfigCertificate certificate, Arena localArena) throws Exception {
        int index = getCertificateIndex(certificate);
        // Load Server key and certificate
        if (certificate.getCertificateFile() != null) {
            // Pick right key password
            String keyPassToUse = null;
            String keyPass = certificate.getCertificateKeyPassword();
            if (keyPass == null) {
                keyPass = certificate.getCertificateKeystorePassword();
            }
            String keyPassFile = certificate.getCertificateKeyPasswordFile();
            if (keyPassFile == null) {
                keyPassFile = certificate.getCertificateKeystorePasswordFile();
            }
            if (keyPassFile != null) {
                try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(
                                ConfigFileLoader.getSource().getResource(keyPassFile).getInputStream(),
                                StandardCharsets.UTF_8))) {
                    keyPassToUse = reader.readLine();
                } catch (IOException e) {
                    log.error(sm.getString("openssl.errorLoadingPassword", keyPassFile), e);
                    return false;
                }
            } else {
                keyPassToUse = keyPass;
            }
            // Set certificate
            //SSLContext.setCertificate(state.ctx,
            //        SSLHostConfig.adjustRelativePath(certificate.getCertificateFile()),
            //        SSLHostConfig.adjustRelativePath(certificate.getCertificateKeyFile()),
            //        certificate.getCertificateKeyPassword(), getCertificateIndex(certificate));
            byte[] certificateFileBytes = null;
            try (Resource resource = ConfigFileLoader.getSource().getResource(certificate.getCertificateFile())) {
                certificateFileBytes = resource.getInputStream().readAllBytes();
            } catch (IOException e) {
                log.error(sm.getString("openssl.errorLoadingCertificate", certificate.getCertificateFile()), e);
                return false;
            }
            MemorySegment certificateFileBytesNative = localArena.allocateFrom(ValueLayout.JAVA_BYTE, certificateFileBytes);
            MemorySegment certificateBIO = BIO_new(BIO_s_mem());
            try {
                if (BIO_write(certificateBIO, certificateFileBytesNative, certificateFileBytes.length) <= 0) {
                    log.error(sm.getString("openssl.errorLoadingCertificate", "[0]:" + certificate.getCertificateFile()));
                    return false;
                }
                MemorySegment cert = MemorySegment.NULL;
                MemorySegment key = MemorySegment.NULL;
                if (certificate.getCertificateFile().endsWith(".pkcs12")) {
                    // Load pkcs12
                    MemorySegment p12 = d2i_PKCS12_bio(certificateBIO, MemorySegment.NULL);
                    if (MemorySegment.NULL.equals(p12)) {
                        log.error(sm.getString("openssl.errorLoadingCertificate", "[1]:" + certificate.getCertificateFile()));
                        return false;
                    }
                    MemorySegment passwordAddress = MemorySegment.NULL;
                    int passwordLength = 0;
                    if (keyPassToUse != null && keyPassToUse.length() > 0) {
                        passwordAddress = localArena.allocateFrom(keyPassToUse);
                        passwordLength = (int) (passwordAddress.byteSize() - 1);
                    }
                    if (PKCS12_verify_mac(p12, passwordAddress, passwordLength) <= 0) {
                        // Bad password
                        log.error(sm.getString("openssl.errorLoadingCertificate", "[2]:" + certificate.getCertificateFile()));
                        PKCS12_free(p12);
                        return false;
                    }
                    MemorySegment certPointer = localArena.allocate(ValueLayout.ADDRESS);
                    MemorySegment keyPointer = localArena.allocate(ValueLayout.ADDRESS);
                    if (PKCS12_parse(p12, passwordAddress, keyPointer, certPointer, MemorySegment.NULL) <= 0) {
                        log.error(sm.getString("openssl.errorLoadingCertificate", "[3]:" + certificate.getCertificateFile()));
                        PKCS12_free(p12);
                        return false;
                    }
                    PKCS12_free(p12);
                    cert = certPointer.get(ValueLayout.ADDRESS, 0);
                    key = keyPointer.get(ValueLayout.ADDRESS, 0);
                } else {
                    String certificateKeyFileName = (certificate.getCertificateKeyFile() == null)
                            ? certificate.getCertificateFile() : certificate.getCertificateKeyFile();
                    // Load key
                    byte[] certificateKeyFileBytes = null;
                    try (Resource resource = ConfigFileLoader.getSource().getResource(certificateKeyFileName)) {
                        certificateKeyFileBytes = resource.getInputStream().readAllBytes();
                    } catch (IOException e) {
                        log.error(sm.getString("openssl.errorLoadingCertificate", certificateKeyFileName), e);
                        return false;
                    }
                    MemorySegment certificateKeyFileBytesNative = localArena.allocateFrom(ValueLayout.JAVA_BYTE, certificateKeyFileBytes);
                    MemorySegment keyBIO = BIO_new(BIO_s_mem());
                    try {
                        if (BIO_write(keyBIO, certificateKeyFileBytesNative, certificateKeyFileBytes.length) <= 0) {
                            log.error(sm.getString("openssl.errorLoadingCertificate", "[0]:" + certificateKeyFileName));
                            return false;
                        }
                        key = MemorySegment.NULL;
                        for (int i = 0; i < 3; i++) {
                            try {
                                callbackPasswordTheadLocal.set(keyPassToUse);
                                key = PEM_read_bio_PrivateKey(keyBIO, MemorySegment.NULL, openSSLCallbackPassword, MemorySegment.NULL);
                            } finally {
                                callbackPasswordTheadLocal.set(null);
                            }
                            if (!MemorySegment.NULL.equals(key)) {
                                break;
                            }
                            BIO_reset(keyBIO);
                        }
                    } finally {
                        BIO_free(keyBIO);
                    }
                    if (MemorySegment.NULL.equals(key)) {
                        if (!MemorySegment.NULL.equals(OpenSSLLibrary.enginePointer)) {
                            // This needs a real file
                            key = ENGINE_load_private_key(OpenSSLLibrary.enginePointer,
                                    localArena.allocateFrom(SSLHostConfig.adjustRelativePath(certificateKeyFileName)),
                                    MemorySegment.NULL, MemorySegment.NULL);
                        }
                    }
                    if (MemorySegment.NULL.equals(key)) {
                        log.error(sm.getString("openssl.errorLoadingCertificate", "[1]:" + certificateKeyFileName));
                        return false;
                    }
                    // Load certificate
                    try {
                        callbackPasswordTheadLocal.set(keyPassToUse);
                        cert = PEM_read_bio_X509_AUX(certificateBIO, MemorySegment.NULL, openSSLCallbackPassword, MemorySegment.NULL);
                    } finally {
                        callbackPasswordTheadLocal.set(null);
                    }
                    if (MemorySegment.NULL.equals(cert) &&
                            // Missing ERR_GET_REASON(ERR_peek_last_error())
                            /*int ERR_GET_REASON(unsigned long errcode) {
                             *    if (ERR_SYSTEM_ERROR(errcode))
                             *        return errcode & ERR_SYSTEM_MASK;
                             *    return errcode & ERR_REASON_MASK;
                             *}
                             *# define ERR_SYSTEM_ERROR(errcode)      (((errcode) & ERR_SYSTEM_FLAG) != 0)
                             *# define ERR_SYSTEM_FLAG                ((unsigned int)INT_MAX + 1)
                             *# define ERR_SYSTEM_MASK                ((unsigned int)INT_MAX)
                             *# define ERR_REASON_MASK                0X7FFFFF
                             */
                            ((ERR_peek_last_error() & 0X7FFFFF) == PEM_R_NO_START_LINE())) {
                        ERR_clear_error();
                        BIO_reset(certificateBIO);
                        cert = d2i_X509_bio(certificateBIO, MemorySegment.NULL);
                    }
                    if (MemorySegment.NULL.equals(cert)) {
                        log.error(sm.getString("openssl.errorLoadingCertificate", certificate.getCertificateFile()));
                        return false;
                    }
                }
                if (SSL_CTX_use_certificate(state.sslCtx, cert) <= 0) {
                    logLastError(localArena, "openssl.errorLoadingCertificate");
                    return false;
                }
                if (SSL_CTX_use_PrivateKey(state.sslCtx, key) <= 0) {
                    logLastError(localArena, "openssl.errorLoadingPrivateKey");
                    return false;
                }
                if (SSL_CTX_check_private_key(state.sslCtx) <= 0) {
                    logLastError(localArena, "openssl.errorPrivateKeyCheck");
                    return false;
                }
                // Try to read DH parameters from the (first) SSLCertificateFile
                if (index == SSL_AIDX_RSA) {
                    BIO_reset(certificateBIO);
                    if (!OPENSSL_3) {
                        var dh = PEM_read_bio_DHparams(certificateBIO, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                        if (!MemorySegment.NULL.equals(dh)) {
                            SSL_CTX_set_tmp_dh(state.sslCtx, dh);
                            DH_free(dh);
                        }
                    } else {
                        var pkey = PEM_read_bio_Parameters(certificateBIO, MemorySegment.NULL);
                        if (!MemorySegment.NULL.equals(pkey)) {
                            int numBits = EVP_PKEY_get_bits(pkey);
                            if (SSL_CTX_set0_tmp_dh_pkey(state.sslCtx, pkey) <= 0) {
                                EVP_PKEY_free(pkey);
                            } else {
                                log.debug(sm.getString("openssl.setCustomDHParameters", numBits, certificate.getCertificateFile()));
                            }
                        } else {
                            SSL_CTX_ctrl(state.sslCtx, SSL_CTRL_SET_DH_AUTO(), 1, MemorySegment.NULL);
                        }
                    }
                }
                // Similarly, try to read the ECDH curve name from SSLCertificateFile...
                BIO_reset(certificateBIO);
                if (!OPENSSL_3) {
                    var ecparams = PEM_read_bio_ECPKParameters(certificateBIO, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                    if (!MemorySegment.NULL.equals(ecparams)) {
                        int nid = EC_GROUP_get_curve_name(ecparams);
                        var eckey = EC_KEY_new_by_curve_name(nid);
                        SSL_CTX_set_tmp_ecdh(state.sslCtx, eckey);
                        EC_KEY_free(eckey);
                        EC_GROUP_free(ecparams);
                    }
                    // Set callback for DH parameters
                    var openSSLCallbackTmpDH = Linker.nativeLinker().upcallStub(openSSLCallbackTmpDHHandle,
                            openSSLCallbackTmpDHFunctionDescriptor, contextArena);
                    SSL_CTX_set_tmp_dh_callback(state.sslCtx, openSSLCallbackTmpDH);
                } else {
                    var d2i_ECPKParameters = SymbolLookup.loaderLookup().find("d2i_ECPKParameters").get();
                    var ecparams = PEM_ASN1_read_bio(d2i_ECPKParameters,
                            PEM_STRING_ECPARAMETERS(), certificateBIO, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                    if (!MemorySegment.NULL.equals(ecparams)) {
                        int curveNid = EC_GROUP_get_curve_name(ecparams);
                        var curveNidAddress = localArena.allocateFrom(ValueLayout.JAVA_INT, curveNid);
                        if (SSL_CTX_set1_groups(state.sslCtx, curveNidAddress, 1) <= 0) {
                            curveNid = 0;
                        }
                        if (log.isDebugEnabled()) {
                            log.debug(sm.getString("openssl.setECDHCurve", curveNid, certificate.getCertificateFile()));
                        }
                        EC_GROUP_free(ecparams);
                    }
                }
                // FIXME: Ideally these should be loaded in Java but still processed through OpenSSL
                // Set certificate chain file
                if (certificate.getCertificateChainFile() != null) {
                    var certificateChainFileNative =
                            localArena.allocateFrom(SSLHostConfig.adjustRelativePath(certificate.getCertificateChainFile()));
                    // SSLContext.setCertificateChainFile(state.ctx,
                    //        SSLHostConfig.adjustRelativePath(certificate.getCertificateChainFile()), false);
                    if (SSL_CTX_use_certificate_chain_file(state.sslCtx, certificateChainFileNative) <= 0) {
                        log.error(sm.getString("openssl.errorLoadingCertificate", certificate.getCertificateChainFile()));
                    }
                }
                // Set revocation
                //SSLContext.setCARevocation(state.ctx,
                //        SSLHostConfig.adjustRelativePath(
                //                sslHostConfig.getCertificateRevocationListFile()),
                //        SSLHostConfig.adjustRelativePath(
                //                sslHostConfig.getCertificateRevocationListPath()));
                MemorySegment certificateStore = SSL_CTX_get_cert_store(state.sslCtx);
                if (sslHostConfig.getCertificateRevocationListFile() != null) {
                    MemorySegment x509Lookup = X509_STORE_add_lookup(certificateStore, X509_LOOKUP_file());
                    var certificateRevocationListFileNative =
                            localArena.allocateFrom(SSLHostConfig.adjustRelativePath(sslHostConfig.getCertificateRevocationListFile()));
                    if (X509_LOOKUP_load_file(x509Lookup, certificateRevocationListFileNative,
                            X509_FILETYPE_PEM()) <= 0) {
                        log.error(sm.getString("openssl.errorLoadingCertificateRevocationList", sslHostConfig.getCertificateRevocationListFile()));
                    }
                }
                if (sslHostConfig.getCertificateRevocationListPath() != null) {
                    MemorySegment x509Lookup = X509_STORE_add_lookup(certificateStore, X509_LOOKUP_hash_dir());
                    var certificateRevocationListPathNative =
                            localArena.allocateFrom(SSLHostConfig.adjustRelativePath(sslHostConfig.getCertificateRevocationListPath()));
                    if (X509_LOOKUP_add_dir(x509Lookup, certificateRevocationListPathNative,
                            X509_FILETYPE_PEM()) <= 0) {
                        log.error(sm.getString("openssl.errorLoadingCertificateRevocationList", sslHostConfig.getCertificateRevocationListPath()));
                    }
                }
                X509_STORE_set_flags(certificateStore, X509_V_FLAG_CRL_CHECK() | X509_V_FLAG_CRL_CHECK_ALL());
            } finally {
                BIO_free(certificateBIO);
            }
        } else {
            String alias = certificate.getCertificateKeyAlias();
            X509KeyManager x509KeyManager = certificate.getCertificateKeyManager();
            if (alias == null) {
                alias = "tomcat";
            }
            X509Certificate[] chain = x509KeyManager.getCertificateChain(alias);
            if (chain == null) {
                alias = findAlias(x509KeyManager, certificate);
                chain = x509KeyManager.getCertificateChain(alias);
            }
            StringBuilder sb = new StringBuilder(BEGIN_KEY);
            sb.append(Base64.getMimeEncoder(64, new byte[] {'\n'}).encodeToString(x509KeyManager.getPrivateKey(alias).getEncoded()));
            sb.append(END_KEY);
            //SSLContext.setCertificateRaw(state.ctx, chain[0].getEncoded(),
            //        sb.toString().getBytes(StandardCharsets.US_ASCII),
            //        getCertificateIndex(certificate));
            var rawCertificate = localArena.allocateFrom(ValueLayout.JAVA_BYTE, chain[0].getEncoded());
            var rawCertificatePointer = localArena.allocateFrom(ValueLayout.ADDRESS, rawCertificate);
            var rawKey = localArena.allocateFrom(ValueLayout.JAVA_BYTE, sb.toString().getBytes(StandardCharsets.US_ASCII));
            var x509cert = d2i_X509(MemorySegment.NULL, rawCertificatePointer, rawCertificate.byteSize());
            if (MemorySegment.NULL.equals(x509cert)) {
                logLastError(localArena, "openssl.errorLoadingCertificate");
                return false;
            }
            MemorySegment keyBIO = BIO_new(BIO_s_mem());
            try {
                BIO_write(keyBIO, rawKey, (int) rawKey.byteSize());
                MemorySegment privateKeyAddress = PEM_read_bio_PrivateKey(keyBIO, MemorySegment.NULL, MemorySegment.NULL, MemorySegment.NULL);
                if (MemorySegment.NULL.equals(privateKeyAddress)) {
                    logLastError(localArena, "openssl.errorLoadingPrivateKey");
                    return false;
                }
                if (SSL_CTX_use_certificate(state.sslCtx, x509cert) <= 0) {
                    logLastError(localArena, "openssl.errorLoadingCertificate");
                    return false;
                }
                if (SSL_CTX_use_PrivateKey(state.sslCtx, privateKeyAddress) <= 0) {
                    logLastError(localArena, "openssl.errorLoadingPrivateKey");
                    return false;
                }
                if (SSL_CTX_check_private_key(state.sslCtx) <= 0) {
                    logLastError(localArena, "openssl.errorPrivateKeyCheck");
                    return false;
                }
                if (!OPENSSL_3) {
                    // Set callback for DH parameters
                    var openSSLCallbackTmpDH = Linker.nativeLinker().upcallStub(openSSLCallbackTmpDHHandle,
                            openSSLCallbackTmpDHFunctionDescriptor, contextArena);
                    SSL_CTX_set_tmp_dh_callback(state.sslCtx, openSSLCallbackTmpDH);
                } else {
                    BIO_reset(keyBIO);
                    var pkey = PEM_read_bio_Parameters(keyBIO, MemorySegment.NULL);
                    if (!MemorySegment.NULL.equals(pkey)) {
                        int numBits = EVP_PKEY_get_bits(pkey);
                        if (SSL_CTX_set0_tmp_dh_pkey(state.sslCtx, pkey) <= 0) {
                            EVP_PKEY_free(pkey);
                        } else {
                            log.debug(sm.getString("openssl.setCustomDHParameters", numBits, certificate.getCertificateFile()));
                        }
                    } else {
                        SSL_CTX_ctrl(state.sslCtx, SSL_CTRL_SET_DH_AUTO(), 1, MemorySegment.NULL);
                    }
                }
                for (int i = 1; i < chain.length; i++) {
                    //SSLContext.addChainCertificateRaw(state.ctx, chain[i].getEncoded());
                    var rawCertificateChain = localArena.allocateFrom(ValueLayout.JAVA_BYTE, chain[i].getEncoded());
                    var rawCertificateChainPointer = localArena.allocateFrom(ValueLayout.ADDRESS, rawCertificateChain);
                    var x509certChain = d2i_X509(MemorySegment.NULL, rawCertificateChainPointer, rawCertificateChain.byteSize());
                    if (MemorySegment.NULL.equals(x509certChain)) {
                        logLastError(localArena, "openssl.errorLoadingCertificate");
                        return false;
                    }
                    if (SSL_CTX_add0_chain_cert(state.sslCtx, x509certChain) <= 0) {
                        logLastError(localArena, "openssl.errorAddingCertificate");
                        return false;
                    }
                }
            } finally {
                BIO_free(keyBIO);
            }
        }
        return true;
    }


    private static int getCertificateIndex(SSLHostConfigCertificate certificate) {
        int result = -1;
        // If the type is undefined there will only be one certificate (enforced
        // in SSLHostConfig) so use the RSA slot.
        if (certificate.getType() == Type.RSA || certificate.getType() == Type.UNDEFINED) {
            result = SSL_AIDX_RSA;
        } else if (certificate.getType() == Type.EC) {
            result = SSL_AIDX_ECC;
        } else if (certificate.getType() == Type.DSA) {
            result = SSL_AIDX_DSA;
        } else {
            result = SSL_AIDX_MAX;
        }
        return result;
    }


    /*
     * Find a valid alias when none was specified in the config.
     */
    private static String findAlias(X509KeyManager keyManager,
            SSLHostConfigCertificate certificate) {

        Type type = certificate.getType();
        String result = null;

        List<Type> candidateTypes = new ArrayList<>();
        if (Type.UNDEFINED.equals(type)) {
            // Try all types to find an suitable alias
            candidateTypes.addAll(Arrays.asList(Type.values()));
            candidateTypes.remove(Type.UNDEFINED);
        } else {
            // Look for the specific type to find a suitable alias
            candidateTypes.add(type);
        }

        Iterator<Type> iter = candidateTypes.iterator();
        while (result == null && iter.hasNext()) {
            result = keyManager.chooseServerAlias(iter.next().toString(),  null,  null);
        }

        return result;
    }

    private static X509TrustManager chooseTrustManager(TrustManager[] managers) {
        for (TrustManager m : managers) {
            if (m instanceof X509TrustManager) {
                return (X509TrustManager) m;
            }
        }
        throw new IllegalStateException(sm.getString("openssl.trustManagerMissing"));
    }

    private static X509Certificate[] certificates(byte[][] chain) {
        X509Certificate[] peerCerts = new X509Certificate[chain.length];
        for (int i = 0; i < peerCerts.length; i++) {
            peerCerts[i] = new OpenSSLX509Certificate(chain[i]);
        }
        return peerCerts;
    }


    private static void logLastError(SegmentAllocator allocator, String string) {
        var buf = allocator.allocate(ValueLayout.JAVA_BYTE, 128);
        ERR_error_string(ERR_get_error(), buf);
        String err = buf.getString(0);
        log.error(sm.getString(string, err));
    }


    @Override
    public SSLSessionContext getServerSessionContext() {
        return sessionContext;
    }

    @Override
    public synchronized SSLEngine createSSLEngine() {
        return new OpenSSLEngine(cleaner, state.sslCtx, defaultProtocol, false, sessionContext,
                alpn, initialized,
                sslHostConfig.getCertificateVerificationDepth(),
                sslHostConfig.getCertificateVerification() == CertificateVerification.OPTIONAL_NO_CA,
                noOcspCheck);
    }

    @Override
    public SSLServerSocketFactory getServerSocketFactory() {
        throw new UnsupportedOperationException();
    }

    @Override
    public SSLParameters getSupportedSSLParameters() {
        throw new UnsupportedOperationException();
    }

    @Override
    public X509Certificate[] getCertificateChain(String alias) {
        X509Certificate[] chain = null;
        X509KeyManager x509KeyManager = certificate.getCertificateKeyManager();
        if (x509KeyManager != null) {
            if (alias == null) {
                alias = "tomcat";
            }
            chain = x509KeyManager.getCertificateChain(alias);
            if (chain == null) {
                alias = findAlias(x509KeyManager, certificate);
                chain = x509KeyManager.getCertificateChain(alias);
            }
        }

        return chain;
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        X509Certificate[] acceptedCerts = null;
        if (state.x509TrustManager != null) {
            acceptedCerts = state.x509TrustManager.getAcceptedIssuers();
        }
        return acceptedCerts;
    }


    private static class ContextState implements Runnable {

        private final Arena stateArena = Arena.ofShared();
        private final MemorySegment sslCtx;
        private final MemorySegment confCtx;
        private final List<byte[]> negotiableProtocols;

        private X509TrustManager x509TrustManager = null;

        private ContextState(MemorySegment sslCtx, MemorySegment confCtx, List<byte[]> negotiableProtocols) {
            states.put(Long.valueOf(sslCtx.address()), this);
            this.negotiableProtocols = negotiableProtocols;
            // Use another arena to avoid keeping a reference through segments
            // This also allows making further accesses to the main pointers safer
            this.sslCtx = sslCtx.reinterpret(ValueLayout.ADDRESS.byteSize(), stateArena,
                    new Consumer<MemorySegment>() {
                        @Override
                        public void accept(MemorySegment t) {
                            SSL_CTX_free(t);
                        }});
            if (!MemorySegment.NULL.equals(confCtx)) {
                this.confCtx = confCtx.reinterpret(ValueLayout.ADDRESS.byteSize(), stateArena,
                        new Consumer<MemorySegment>() {
                            @Override
                            public void accept(MemorySegment t) {
                                SSL_CONF_CTX_free(t);
                            }
                });
            } else {
                this.confCtx = null;
            }
        }

        @Override
        public void run() {
            states.remove(Long.valueOf(sslCtx.address()));
            stateArena.close();
        }
    }
}
