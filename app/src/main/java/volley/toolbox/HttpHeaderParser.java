/*
 * Copyright (C) 2011 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package volley.toolbox;

import volley.Cache;
import volley.NetworkResponse;

import org.apache.http.impl.cookie.DateParseException;
import org.apache.http.impl.cookie.DateUtils;
import org.apache.http.protocol.HTTP;

import java.util.Map;

/**
 * Utility methods for parsing HTTP headers.
 * stale [steɪl]：不新鲜的
 * revalidate：重新验证
 */
public class HttpHeaderParser {

    public static final String DATE = "Date";// 服务器时间
    /* 缓存控制字段 */
    public static final String CACHE_CONTROL = "Cache-Control";
    /* 缓存前必须先确认有效性 */
    public static final String NO_CACHE = "no-cache";
    /* 不缓存请求或相应的任何内容 */
    public static final String NO_STORE = "no-store";
    /* 相应的最大Age值 */
    public static final String MAX_AGE = "max-age=";
    /* 指示客户端愿意接受一个过期的响应，同时在后台异步检查一个新的响应。秒值表示客户机愿意接受过期响应的时间 */
    public static final String STALE_WHILE_REVALIDATE = "stale-while-revalidate=";
    /* 可缓存但必须再向源服务器进行确认 */
    public static final String MUST_REVALIDATE = "must-revalidate";
    /* 要求中间缓存服务器对缓存响应的有效性再再进行确认 */
    public static final String PROXY_REVALIDATE = "proxy-revalidate";
    /* 缓存期望失效时间 */
    public static final String EXPIRES = "Expires";
    /* 文件上次被修改时间 */
    public static final String LAST_MODIFIED = "Last-Modified";
    /* 文件是否被修改标记字段 */
    public static final String E_TAG = "ETag";

    /**
     * Extracts a {@link Cache.Entry} from a {@link NetworkResponse}.
     *
     * @param response The network response to parse headers from
     * @return a cache entry for the given response, or null if the response is not cacheable.
     */
    public static Cache.Entry parseCacheHeaders(NetworkResponse response) {
        long now = System.currentTimeMillis();

        Map<String, String> headers = response.headers;

        long serverDate = 0;
        long lastModified = 0;
        long serverExpires = 0;
        long softExpire = 0;
        long finalExpire = 0;
        long maxAge = 0;
        long staleWhileRevalidate = 0;
        boolean hasCacheControl = false;
        boolean mustRevalidate = false;

        String serverEtag = null;
        String headerValue;

        headerValue = headers.get(DATE);
        if (headerValue != null) {
            serverDate = parseDateAsEpoch(headerValue);
        }

        headerValue = headers.get(CACHE_CONTROL);
        if (headerValue != null) {
            hasCacheControl = true;
            String[] tokens = headerValue.split(",");
            for (int i = 0; i < tokens.length; i++) {
                String token = tokens[i].trim();
                if (token.equals(NO_CACHE) || token.equals(NO_STORE)) {
                    return null;
                } else if (token.startsWith(MAX_AGE)) {
                    try {
                        maxAge = Long.parseLong(token.substring(8));
                    } catch (Exception e) {
                    }
                } else if (token.startsWith(STALE_WHILE_REVALIDATE)) {
                    try {
                        staleWhileRevalidate = Long.parseLong(token.substring(23));
                    } catch (Exception e) {
                    }
                } else if (token.equals(MUST_REVALIDATE) || token.equals(PROXY_REVALIDATE)) {
                    mustRevalidate = true;
                }
            }
        }

        headerValue = headers.get(EXPIRES);
        if (headerValue != null) {
            serverExpires = parseDateAsEpoch(headerValue);
        }

        headerValue = headers.get(LAST_MODIFIED);
        if (headerValue != null) {
            lastModified = parseDateAsEpoch(headerValue);
        }

        serverEtag = headers.get(E_TAG);

        // Cache-Control takes precedence over an Expires header, even if both exist and Expires
        // is more restrictive.
        if (hasCacheControl) {
            /* 有cache-control字段的情况下 说明它的优先级比其他缓存字段要高
             * softExpire = 现在开始 + 缓存最大能存活的时间
              * finalExpire = softExpire 或者 softExpire + stale-while-revalidate 的时间
              * */
            softExpire = now + maxAge * 1000;
            finalExpire = mustRevalidate
                    ? softExpire
                    : softExpire + staleWhileRevalidate * 1000;
        } else if (serverDate > 0 && serverExpires >= serverDate) {
            // Default semantic for Expire header in HTTP specification is softExpire.
            /*
            * softExpire = 现在开始 + （缓存失效时间与服务器时间的时间差）
            * finalExpire = softExpire
            * */
            softExpire = now + (serverExpires - serverDate);
            finalExpire = softExpire;
        }

        Cache.Entry entry = new Cache.Entry();
        entry.data = response.data;
        entry.etag = serverEtag;
        entry.softTtl = softExpire;
        entry.ttl = finalExpire;
        entry.serverDate = serverDate;
        entry.lastModified = lastModified;
        entry.responseHeaders = headers;

        return entry;
    }

    /**
     * Parse date in RFC1123 format, and return its value as epoch
     */
    public static long parseDateAsEpoch(String dateStr) {
        try {
            // Parse date in RFC1123 format if this header contains one
            return DateUtils.parseDate(dateStr).getTime();
        } catch (DateParseException e) {
            // Date in invalid format, fallback to 0
            return 0;
        }
    }

    /**
     * Retrieve a charset from headers
     *
     * @param headers        An {@link Map} of headers
     * @param defaultCharset Charset to return if none can be found
     * @return Returns the charset specified in the Content-Type of this header,
     * or the defaultCharset if none can be found.
     */
    public static String parseCharset(Map<String, String> headers, String defaultCharset) {
        String contentType = headers.get(HTTP.CONTENT_TYPE);
        if (contentType != null) {
            String[] params = contentType.split(";");
            for (int i = 1; i < params.length; i++) {
                String[] pair = params[i].trim().split("=");
                if (pair.length == 2) {
                    if (pair[0].equals("charset")) {
                        return pair[1];
                    }
                }
            }
        }

        return defaultCharset;
    }

    /**
     * Returns the charset specified in the Content-Type of this header,
     * or the HTTP default (ISO-8859-1) if none can be found.
     */
    public static String parseCharset(Map<String, String> headers) {
        return parseCharset(headers, HTTP.DEFAULT_CONTENT_CHARSET);
    }
}
