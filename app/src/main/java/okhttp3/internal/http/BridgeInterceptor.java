/*
 *  Licensed to the Apache Software Foundation (ASF) under one or more
 *  contributor license agreements.  See the NOTICE file distributed with
 *  this work for additional information regarding copyright ownership.
 *  The ASF licenses this file to You under the Apache License, Version 2.0
 *  (the "License"); you may not use this file except in compliance with
 *  the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */
package okhttp3.internal.http;

import java.io.IOException;
import java.util.List;
import okhttp3.Cookie;
import okhttp3.CookieJar;
import okhttp3.Headers;
import okhttp3.Interceptor;
import okhttp3.MediaType;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import okhttp3.internal.Version;
import okio.GzipSource;
import okio.Okio;

import static okhttp3.internal.Util.hostHeader;

/**
 * Bridges from application code to network code. First it builds a network request from a user
 * request. Then it proceeds to call the network. Finally it builds a user response from the network
 * response.
 * 1、请求从应用层数据类型类型转化为网络调用层的数据类型。
 * 2、将网络层返回的数据类型 转化为 应用层数据类型。
 * 3、补充：Keep－Alive 连接：
 */
public final class BridgeInterceptor implements Interceptor {
  private final CookieJar cookieJar;

  public BridgeInterceptor(CookieJar cookieJar) {
    this.cookieJar = cookieJar;
  }

  /**
   *   // 主要方法，其他略
   *    // 此拦截器较为简单，其中有两点比较重要，1、cookie的处理 2Gzip
   *
   *    读了源码发现这个interceptor比较简单，可以分为发送请求和响应两个阶段来看:
   * 1.在发送阶段BridgeInterceptor补全了一些header包括Content-Type、Content-Length、Transfer-Encoding、Host、Connection、Accept-Encoding、User-Agent。
   * 2.如果需要gzip压缩则进行gzip压缩
   * 3.加载Cookie
   * 4.随后创建新的request并交付给后续的interceptor来处理，以获取响应。
   * 5.首先保存Cookie
   * 6.如果服务器返回的响应content是以gzip压缩过的，则会先进行解压缩，移除响应中的header Content-Encoding和Content-Length，构造新的响应返回。
   * 7 否则直接返回response
   * @param chain
   * @return
   * @throws IOException
   */
  @Override public Response intercept(Chain chain) throws IOException {
    Request userRequest = chain.request();
    Request.Builder requestBuilder = userRequest.newBuilder();
    RequestBody body = userRequest.body();
    if (body != null) {
      MediaType contentType = body.contentType();
      if (contentType != null) {
        requestBuilder.header("Content-Type", contentType.toString());
      }
      long contentLength = body.contentLength();
      if (contentLength != -1) {
        requestBuilder.header("Content-Length", Long.toString(contentLength));
        requestBuilder.removeHeader("Transfer-Encoding");
      } else {
        requestBuilder.header("Transfer-Encoding", "chunked");
        requestBuilder.removeHeader("Content-Length");
      }
    }
    if (userRequest.header("Host") == null) {
      requestBuilder.header("Host", hostHeader(userRequest.url(), false));
    }
    if (userRequest.header("Connection") == null) {
      requestBuilder.header("Connection", "Keep-Alive");
    }
    // If we add an "Accept-Encoding: gzip" header field we're responsible for also decompressing
    // the transfer stream.
    boolean transparentGzip = false;
    if (userRequest.header("Accept-Encoding") == null && userRequest.header("Range") == null) {
      transparentGzip = true;
      requestBuilder.header("Accept-Encoding", "gzip");
    }
    // 所以返回的cookies不能为空，否则这里会报空指针
    List<Cookie> cookies = cookieJar.loadForRequest(userRequest.url());
    if (!cookies.isEmpty()) {
      // 创建Okhpptclitent时候配置的cookieJar，
      requestBuilder.header("Cookie", cookieHeader(cookies));
    }
    if (userRequest.header("User-Agent") == null) {
      requestBuilder.header("User-Agent", Version.userAgent());
    }

    //  以上为请求前的头处理
    Response networkResponse = chain.proceed(requestBuilder.build());
    // 以下是请求完成，拿到返回后的头处理
    // 响应header， 如果没有自定义配置cookie不会解析
    HttpHeaders.receiveHeaders(cookieJar, userRequest.url(), networkResponse.headers());
    Response.Builder responseBuilder = networkResponse.newBuilder()
            .request(userRequest);
    // 前面解析完header后，判断服务器是否支持gzip压缩格式，如果支持将交给Okio处理
    if (transparentGzip
            && "gzip".equalsIgnoreCase(networkResponse.header("Content-Encoding"))
            && HttpHeaders.hasBody(networkResponse)) {
      GzipSource responseBody = new GzipSource(networkResponse.body().source());
      Headers strippedHeaders = networkResponse.headers().newBuilder()
              .removeAll("Content-Encoding")
              .removeAll("Content-Length")
              .build();
      responseBuilder.headers(strippedHeaders);
      // 处理完成后，重新生成一个response
      responseBuilder.body(new RealResponseBody(strippedHeaders, Okio.buffer(responseBody)));
    }
    return responseBuilder.build();
  }

  /** Returns a 'Cookie' HTTP request header with all cookies, like {@code a=b; c=d}. */
  private String cookieHeader(List<Cookie> cookies) {
    StringBuilder cookieHeader = new StringBuilder();
    for (int i = 0, size = cookies.size(); i < size; i++) {
      if (i > 0) {
        cookieHeader.append("; ");
      }
      Cookie cookie = cookies.get(i);
      cookieHeader.append(cookie.name()).append('=').append(cookie.value());
    }
    return cookieHeader.toString();
  }
}
