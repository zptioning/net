/*
 * Copyright (C) 2013 Square, Inc.
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
package okhttp3;

import java.net.InetSocketAddress;
import java.net.Proxy;

/**
 * The concrete route used by a connection to reach an abstract origin server. When creating a
 * connection the client has many options:
 *
 * <ul>
 *     <li><strong>HTTP proxy:</strong> a proxy server may be explicitly configured for the client.
 *         Otherwise the {@linkplain java.net.ProxySelector proxy selector} is used. It may return
 *         multiple proxies to attempt.
 *     <li><strong>IP address:</strong> whether connecting directly to an origin server or a proxy,
 *         opening a socket requires an IP address. The DNS server may return multiple IP addresses
 *         to attempt.
 * </ul>
 *
 * <p>Each route is a specific selection of these options.
 *
 * 连接使用的路由到抽象服务器。创建连接时，客户端有很多选择
 * 1、HTTP proxy(http代理)：已经为客户端配置了一个专门的代理服务器，否则会通过net.ProxySelector proxy selector尝试多个代理
 * 2、IP address(ip地址)：无论是通过直连还是通过代理，DNS服务器可能会尝试多个ip地址。
 * 每一个路由都是上述路由的一种格式
 * 所以我的理解就是OkHttp3中抽象出来的Route是描述网络数据包传输的路径，最主要还是描述直接与其建立TCP连接的目标端点。
 *
 */
public final class Route {
  final Address address;
  final Proxy proxy;
  final InetSocketAddress inetSocketAddress;

  public Route(Address address, Proxy proxy, InetSocketAddress inetSocketAddress) {
    if (address == null) {
      throw new NullPointerException("address == null");
    }
    if (proxy == null) {
      throw new NullPointerException("proxy == null");
    }
    if (inetSocketAddress == null) {
      throw new NullPointerException("inetSocketAddress == null");
    }
    this.address = address;
    this.proxy = proxy;
    this.inetSocketAddress = inetSocketAddress;
  }

  public Address address() {
    return address;
  }

  /**
   * Returns the {@link Proxy} of this route.
   *
   * <strong>Warning:</strong> This may disagree with {@link Address#proxy} when it is null. When
   * the address's proxy is null, the proxy selector is used.
   */
  public Proxy proxy() {
    return proxy;
  }

  public InetSocketAddress socketAddress() {
    return inetSocketAddress;
  }

  /**
   * Returns true if this route tunnels HTTPS through an HTTP proxy. See <a
   * href="http://www.ietf.org/rfc/rfc2817.txt">RFC 2817, Section 5.2</a>.
   * 即对于设置了HTTP代理，且安全的连接(SSL)需要请求代理服务器，
   * 建立一个到目标HTTP服务器的隧道连接，客户端与HTTP代理建立TCP连接，
   * 以此请求HTTP代理服务器在客户端与HTTP服务器之间进行数据的盲目转发
   */
  public boolean requiresTunnel() {
    //是HTTP请求，但是还有SSL
    return address.sslSocketFactory != null && proxy.type() == Proxy.Type.HTTP;
  }

  /**
   * Route通过代理服务器的信息proxy,
   * 及链接的目标地址Address来描述路由即Route，
   * 连接的目标地址inetSocketAddress根据代理类型的不同而有着不同的含义，
   * 这主要是通过不同代理协议的差异而造成的。对于无需代理的情况，
   * 连接的目标地址inetSocketAddress中包含HTTP服务器经过DNS域名解析的IP地址以及协议端口号；
   * 对于SOCKET代理其中包含HTTP服务器的域名及协议端口号；
   * 对于HTTP代理，其中则包含代理服务器经过域名解析的IP地址及端口号。
   *
   * @param obj
   * @return
   */

  @Override public boolean equals(Object obj) {
    if (obj instanceof Route) {
      Route other = (Route) obj;
      return address.equals(other.address)
          && proxy.equals(other.proxy)
          && inetSocketAddress.equals(other.inetSocketAddress);
    }
    return false;
  }

  @Override public int hashCode() {
    int result = 17;
    result = 31 * result + address.hashCode();
    result = 31 * result + proxy.hashCode();
    result = 31 * result + inetSocketAddress.hashCode();
    return result;
  }

  @Override public String toString() {
    return "Route{" + inetSocketAddress + "}";
  }
}
