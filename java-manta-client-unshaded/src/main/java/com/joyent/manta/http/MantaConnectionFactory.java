/*
 * Copyright (c) 2016-2017, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.manta.http;

import com.joyent.http.signature.ThreadLocalSigner;
import com.joyent.manta.client.MantaMBeanable;
import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.config.DefaultsConfigContext;
import com.joyent.manta.exception.ConfigurationException;
import com.joyent.manta.util.MantaVersion;
import org.apache.commons.lang3.ObjectUtils;
import org.apache.commons.lang3.Validate;
import org.apache.http.HttpHost;
import org.apache.http.client.config.RequestConfig;
import org.apache.http.config.ConnectionConfig;
import org.apache.http.config.Registry;
import org.apache.http.config.RegistryBuilder;
import org.apache.http.config.SocketConfig;
import org.apache.http.conn.DnsResolver;
import org.apache.http.conn.HttpClientConnectionManager;
import org.apache.http.conn.HttpConnectionFactory;
import org.apache.http.conn.ManagedHttpClientConnection;
import org.apache.http.conn.routing.HttpRoute;
import org.apache.http.conn.socket.ConnectionSocketFactory;
import org.apache.http.conn.socket.PlainConnectionSocketFactory;
import org.apache.http.impl.DefaultConnectionReuseStrategy;
import org.apache.http.impl.client.CloseableHttpClient;
import org.apache.http.impl.client.DefaultBackoffStrategy;
import org.apache.http.impl.client.DefaultConnectionKeepAliveStrategy;
import org.apache.http.impl.client.HttpClientBuilder;
import org.apache.http.impl.client.HttpClients;
import org.apache.http.impl.conn.ManagedHttpClientConnectionFactory;
import org.apache.http.impl.conn.PoolingHttpClientConnectionManager;
import org.apache.http.impl.io.DefaultHttpRequestWriterFactory;
import org.apache.http.impl.io.DefaultHttpResponseParserFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ProxySelector;
import java.net.URI;
import java.security.KeyPair;
import java.util.List;
import javax.management.DynamicMBean;

/**
 * Factory class that creates instances of
 * {@link org.apache.http.client.HttpClient} configured for use with
 * HTTP signature based authentication.
 *
 * Note: This class used to contain convenience methods for building
 * {@link org.apache.http.client.methods.HttpUriRequest} objects, those have been moved
 * to {@link MantaHttpRequestFactory}.
 *
 * @author <a href="https://github.com/dekobon">Elijah Zupancic</a>
 * @since 3.0.0
 */
public class MantaConnectionFactory implements Closeable, MantaMBeanable {
    /**
     * Logger instance.
     */
    private static final Logger LOGGER = LoggerFactory.getLogger(MantaConnectionFactory.class);

    /**
     * Default DNS resolver for all connections to the Manta.
     */
    private static final DnsResolver DNS_RESOLVER = new ShufflingDnsResolver();

    /**
     * User Agent string identifying Manta Client and Java version.
     */
    private static final String USER_AGENT = String.format(
            "Java-Manta-SDK/%s (Java/%s/%s)",
            MantaVersion.VERSION,
            System.getProperty("java.version"),
            System.getProperty("java.vendor"));

    /**
     * Configuration context that provides connection details.
     */
    private final ConfigContext config;

    /**
     * Apache HTTP Client connection builder helper.
     */
    private final HttpClientBuilder httpClientBuilder;

    /**
     * Connection manager instance that is associated with a single Manta client.
     */
    private final HttpClientConnectionManager connectionManager;

    /**
     * Create new instance using the passed configuration.
     *
     * @param config    configuration of the connection parameters
     * @param keyPair   cryptographic signing key pair used for HTTP signatures
     * @param signer    Signer configured to use the given keyPair
     */
    @Deprecated
    public MantaConnectionFactory(final ConfigContext config,
                                  final KeyPair keyPair,
                                  final ThreadLocalSigner signer) {
        this(config, null);
    }

    /**
     * Create a new instance based on a shared {@link HttpClientBuilder} and {@link HttpClientConnectionManager}.
     *
     * @param config                        configuration of the connection parameters
     * @param keyPair                       cryptographic signing key pair used for HTTP signatures
     * @param signer                        Signer configured to use the given keyPair
     * @param connectionFactoryConfigurator existing HttpClient objects to reuse
     */
    @Deprecated
    public MantaConnectionFactory(final ConfigContext config,
                                  final KeyPair keyPair,
                                  final ThreadLocalSigner signer,
                                  final MantaConnectionFactoryConfigurator connectionFactoryConfigurator) {
        this(config, connectionFactoryConfigurator);
    }

    /**
     * Create new instance using the passed configuration.
     *
     * @param config configuration of the connection parameters
     */
    public MantaConnectionFactory(final ConfigContext config) {
        this(config, null);
    }

    /**
     * Create new instance using the passed configuration.
     *
     * @param config configuration of the connection parameters
     * @param connectionFactoryConfigurator existing HttpClient objects to reuse
     */
    public MantaConnectionFactory(final ConfigContext config,
                                  final MantaConnectionFactoryConfigurator connectionFactoryConfigurator) {
        this.config = Validate.notNull(config, "Configuration context must not be null");

        if (connectionFactoryConfigurator != null) {
            this.connectionManager = null;
            this.httpClientBuilder = connectionFactoryConfigurator.getHttpClientBuilder();
        } else {
            this.connectionManager = buildConnectionManager();
            this.httpClientBuilder = createStandardBuilder();
        }

        configureHttpClientBuilderDefaults();
    }

    /**
     * Builds and configures a default connection factory instance.
     *
     * @return configured connection factory
     */
    protected HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection>
            buildHttpConnectionFactory() {
        return new ManagedHttpClientConnectionFactory(
                new DefaultHttpRequestWriterFactory(),
                new DefaultHttpResponseParserFactory());
    }

    /**
     * Builds a socket configuration customized for Manta.
     *
     * @return fully configured instance
     */
    protected SocketConfig buildSocketConfig() {
        final int socketTimeout = ObjectUtils.firstNonNull(
                config.getTcpSocketTimeout(),
                DefaultsConfigContext.DEFAULT_TCP_SOCKET_TIMEOUT);

        return SocketConfig.custom()
                /* Disable Nagle's algorithm for this connection.  Written data
                 * to the network is not buffered pending acknowledgement of
                 * previously written data.
                 */
                .setTcpNoDelay(true)
                /* Set a timeout on blocking Socket operations. */
                .setSoTimeout(socketTimeout)
                .setSoKeepAlive(true)
                .build();
    }

    /**
     * Builds and configures a {@link ConnectionConfig} instance.
     *
     * @return fully configured instance
     */
    protected ConnectionConfig buildConnectionConfig() {
        final int bufferSize = ObjectUtils.firstNonNull(
                config.getHttpBufferSize(),
                DefaultsConfigContext.DEFAULT_HTTP_BUFFER_SIZE);

        return ConnectionConfig.custom()
                .setBufferSize(bufferSize)
                .build();
    }

    /**
     * Configures a connection manager with all of the setting needed to connect
     * to Manta.
     *
     * @return fully configured connection manager
     */
    protected HttpClientConnectionManager buildConnectionManager() {
        final int maxConns = ObjectUtils.firstNonNull(
                config.getMaximumConnections(),
                DefaultsConfigContext.DEFAULT_MAX_CONNS);

        final ConnectionSocketFactory sslConnectionSocketFactory =
                new MantaSSLConnectionSocketFactory(this.config);

        final RegistryBuilder<ConnectionSocketFactory> registryBuilder =
                RegistryBuilder.create();

        final Registry<ConnectionSocketFactory> socketFactoryRegistry = registryBuilder
                .register("http", PlainConnectionSocketFactory.getSocketFactory())
                .register("https", sslConnectionSocketFactory)
                .build();

        HttpConnectionFactory<HttpRoute, ManagedHttpClientConnection> connFactory =
                buildHttpConnectionFactory();

        final PoolingHttpClientConnectionManager poolingConnectionManager =
                new PoolingHttpClientConnectionManager(socketFactoryRegistry,
                        connFactory,
                        DNS_RESOLVER);
        poolingConnectionManager.setDefaultMaxPerRoute(maxConns);
        poolingConnectionManager.setMaxTotal(maxConns);
        poolingConnectionManager.setDefaultSocketConfig(buildSocketConfig());
        poolingConnectionManager.setDefaultConnectionConfig(buildConnectionConfig());

        return poolingConnectionManager;
    }

    /**
     * Configures the builder class with all of the settings needed to connect to
     * Manta.
     *
     * @return configured instance
     */
    protected HttpClientBuilder createStandardBuilder() {
        final int maxConns = ObjectUtils.firstNonNull(
                config.getMaximumConnections(),
                DefaultsConfigContext.DEFAULT_MAX_CONNS);

        final int timeout = ObjectUtils.firstNonNull(
                config.getTimeout(),
                DefaultsConfigContext.DEFAULT_HTTP_TIMEOUT);

        final int connectionRequestTimeout = ObjectUtils.firstNonNull(
                config.getConnectionRequestTimeout(),
                DefaultsConfigContext.DEFAULT_CONNECTION_REQUEST_TIMEOUT);

        final RequestConfig requestConfig = RequestConfig.custom()
                .setAuthenticationEnabled(false)
                .setSocketTimeout(timeout)
                .setConnectionRequestTimeout(connectionRequestTimeout)
                .setContentCompressionEnabled(true)
                .build();

        final HttpClientBuilder builder = HttpClients.custom()
                .disableAuthCaching()
                .disableCookieManagement()
                .setUserAgent(USER_AGENT)
                .setConnectionReuseStrategy(new DefaultConnectionReuseStrategy())
                .setMaxConnTotal(maxConns)
                .setKeepAliveStrategy(new DefaultConnectionKeepAliveStrategy())
                .setDefaultRequestConfig(requestConfig)
                .setConnectionManagerShared(false)
                .setRequestExecutor(new MantaHttpRequestExecutor())
                .setConnectionBackoffStrategy(new DefaultBackoffStrategy());

        final HttpHost proxyHost = findProxyServer();

        if (proxyHost != null) {
            builder.setProxy(proxyHost);
        }

        return builder;
    }

    /**
     * Apply required configuration to an HttpClientBuilder that may have been created by us or provided externally.
     */
    private void configureHttpClientBuilderDefaults() {
        if (config.getRetries() > 0) {
            httpClientBuilder.setRetryHandler(new MantaHttpRequestRetryHandler(config));
            httpClientBuilder.setServiceUnavailableRetryStrategy(new MantaServiceUnavailableRetryStrategy(config));
        } else {
            LOGGER.info("Retry of failed requests is disabled");
            httpClientBuilder.disableAutomaticRetries();
        }

        // attach the connection manager if it was created by us
        // users providing a custom HttpClientBuilder are expected to wire this up themselves
        if (this.connectionManager != null) {
            httpClientBuilder.setConnectionManager(this.connectionManager);
        }
    }

    /**
     * Finds the host of the proxy server that was configured as part of the
     * JVM settings.
     *
     * @return proxy server as {@link HttpHost}, if no proxy then null
     */
    protected HttpHost findProxyServer() {
        final ProxySelector proxySelector = ProxySelector.getDefault();
        List<Proxy> proxies = proxySelector.select(URI.create(config.getMantaURL()));

        if (!proxies.isEmpty()) {
            /* The Apache HTTP Client doesn't understand the concept of multiple
             * proxies, so we use only the first one returned. */
            final Proxy proxy = proxies.get(0);

            switch (proxy.type()) {
                case DIRECT:
                    return null;
                case SOCKS:
                    throw new ConfigurationException("SOCKS proxies are unsupported");
                default:
                    // do nothing and fall through
            }

            if (proxy.address() instanceof InetSocketAddress) {
                InetSocketAddress sa = (InetSocketAddress) proxy.address();

                return new HttpHost(sa.getHostName(), sa.getPort());
            } else {
                String msg = String.format(
                        "Expecting proxy to be instance of InetSocketAddress. "
                        + " Actually: %s", proxy.address());
                throw new ConfigurationException(msg);
            }
        } else {
            return null;
        }
    }

    /**
     * Creates a new configured instance of {@link CloseableHttpClient} based
     * on the factory's configuration.
     *
     * @return new connection object instance
     */
    public CloseableHttpClient createConnection() {
        return httpClientBuilder.build();
    }

    @Override
    public DynamicMBean toMBean() {
        if (!(connectionManager instanceof PoolingHttpClientConnectionManager)) {
            return null;
        }

        return new PoolStatsMBean((PoolingHttpClientConnectionManager) connectionManager);
    }

    @Override
    public void close() throws IOException {
        if (connectionManager == null) {
            // user provided their own connectionManager in the httpClientBuilder
            return;
        }

        connectionManager.shutdown();
    }
}
