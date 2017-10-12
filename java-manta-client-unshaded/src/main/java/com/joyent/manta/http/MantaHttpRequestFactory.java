/*
 * Copyright (c) 2017, Joyent, Inc. All rights reserved.
 *
 * This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/.
 */
package com.joyent.manta.http;

import com.joyent.manta.config.ConfigContext;
import com.joyent.manta.exception.ConfigurationException;
import org.apache.commons.lang3.Validate;
import org.apache.http.Header;
import org.apache.http.HttpHeaders;
import org.apache.http.NameValuePair;
import org.apache.http.client.methods.HttpDelete;
import org.apache.http.client.methods.HttpGet;
import org.apache.http.client.methods.HttpHead;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.client.methods.HttpPut;
import org.apache.http.client.utils.URIBuilder;
import org.apache.http.message.BasicHeader;

import java.net.URISyntaxException;
import java.util.List;

/**
 * Helper class for creating {@link org.apache.http.client.methods.HttpRequestBase} objects,
 * a.k.a. {@link org.apache.http.client.methods.HttpUriRequest}.
 */
public class MantaHttpRequestFactory {

    /**
     * Default HTTP headers to send to all requests to Manta.
     */
    private static final Header[] HEADERS = {
            new BasicHeader(MantaHttpHeaders.ACCEPT_VERSION, "~1.0"),
            new BasicHeader(HttpHeaders.ACCEPT, "application/json, */*")
    };

    /**
     * Base URL for requests.
     */
    private final String url;

    /**
     * Build a new request factory based on a config's URL.
     * @param config the config from which to extract a URL
     */
    public MantaHttpRequestFactory(final ConfigContext config) {
        this(config.getMantaURL());
    }

    /**
     * Create an instance of the request factory based on the provided url.
     * @param url the base url
     */
    public MantaHttpRequestFactory(final String url) {
        this.url = url;
    }

    /**
     * Convenience method used for building DELETE operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpDelete delete(final String path) {
        final HttpDelete request = new HttpDelete(uriForPath(path));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building DELETE operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpDelete delete(final String path, final List<NameValuePair> params) {
        final HttpDelete request = new HttpDelete(uriForPath(path, params));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building GET operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpGet get(final String path) {
        final HttpGet request = new HttpGet(uriForPath(path));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building GET operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpGet get(final String path, final List<NameValuePair> params) {
        final HttpGet request = new HttpGet(uriForPath(path, params));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building HEAD operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpHead head(final String path) {
        final HttpHead request = new HttpHead(uriForPath(path));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building HEAD operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpHead head(final String path, final List<NameValuePair> params) {
        final HttpHead request = new HttpHead(uriForPath(path, params));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building POST operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPost post(final String path) {
        final HttpPost request = new HttpPost(uriForPath(path));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building POST operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPost post(final String path, final List<NameValuePair> params) {
        final HttpPost request = new HttpPost(uriForPath(path, params));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building PUT operations.
     * @param path path to resource
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPut put(final String path) {
        final HttpPut request = new HttpPut(uriForPath(path));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Convenience method used for building PUT operations.
     * @param path path to resource
     * @param params list of query parameters to use in operation
     * @return instance of configured {@link org.apache.http.client.methods.HttpRequestBase} object.
     */
    public HttpPut put(final String path, final List<NameValuePair> params) {
        final HttpPut request = new HttpPut(uriForPath(path, params));
        request.setHeaders(HEADERS);
        return request;
    }

    /**
     * Derives Manta URI for a given path.
     *
     * @param path full path
     * @return full URI as string of resource
     */
    protected String uriForPath(final String path) {
        Validate.notNull(path, "Path must not be null");

        if (path.startsWith("/")) {
            return String.format("%s%s", this.url, path);
        } else {
            return String.format("%s/%s", this.url, path);
        }
    }

    /**
     * Derives Manta URI for a given path with the passed query
     * parameters.
     *
     * @param path full path
     * @param params query parameters to add to the URI
     * @return full URI as string of resource
     */
    protected String uriForPath(final String path, final List<NameValuePair> params) {
        Validate.notNull(path, "Path must not be null");
        Validate.notNull(params, "Params must not be null");

        try {
            final URIBuilder uriBuilder = new URIBuilder(uriForPath(path));
            uriBuilder.addParameters(params);
            return uriBuilder.build().toString();
        } catch (final URISyntaxException e) {
            throw new ConfigurationException(String.format("Invalid path in URI: %s", path));
        }
    }
}
