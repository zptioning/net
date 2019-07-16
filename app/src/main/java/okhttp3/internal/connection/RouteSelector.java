/*
 * Copyright (C) 2012 Square, Inc.
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
package okhttp3.internal.connection;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.SocketAddress;
import java.net.SocketException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import okhttp3.Address;
import okhttp3.HttpUrl;
import okhttp3.Route;
import okhttp3.internal.Util;

/**
 * Selects routes to connect to an origin server. Each connection requires a choice of proxy server,
 * IP address, and TLS mode. Connections may also be recycled.
 * 这个类主要是选择连接到服务器的路由，每个连接应该是代理服务器/IP地址/TLS模式 三者中的一种。连接月可以被回收
 * 所以可以把RouteSelector理解为路由选择器.
 * <p>
 * (2)那为什么需要RouteSelector那？
 * 因为HTTP请求处理过程中所需的TCP连接建立过程，主要是找到一个Route，然后依据代理协议规则与特定目标建立TCP连接。对于无代理的情况，是与HTTP服务器建立TCP连接，对于SOCKS代理和http代理，是与代理服务器建立tcp连接，虽然都是与代理服务器建立tcp连接，但是SOCKS代理协议和http代理协议又有一定的区别。
 * 而且借助于域名做负均衡已经是网络中非常常见的手法了，因而，常常会有域名对应不同IP地址的情况。同时相同系统也可以设置多个代理，这使Route的选择变得非常复杂。
 * 在OKHTTP中，对Route连接有一定的错误处理机制。OKHTTP会逐个尝试找到Route建立TCP连接，直到找到可用的哪一个。这样对Route信息有良好的管理。OKHTTP中借助RouteSelector类管理所有路由信息，并帮助选择路由。
 *
 *
 * 1收集路由信息，2选择路由，3维护失败路由。
 */
public final class RouteSelector {
    private final Address address;
    private final RouteDatabase routeDatabase;

    /* The most recently attempted route. */
    private Proxy lastProxy;
    private InetSocketAddress lastInetSocketAddress;

    /* State for negotiating the next proxy to use. */
    private List<Proxy> proxies = Collections.emptyList();
    private int nextProxyIndex;

    /* State for negotiating the next socket address to use. */
    private List<InetSocketAddress> inetSocketAddresses = Collections.emptyList();
    private int nextInetSocketAddressIndex;

    /* State for negotiating failed routes */
    private final List<Route> postponedRoutes = new ArrayList<>();

    public RouteSelector(Address address, RouteDatabase routeDatabase) {
        this.address = address;
        this.routeDatabase = routeDatabase;

        resetNextProxy(address.url(), address.proxy());
    }

    /**
     * Returns true if there's another route to attempt. Every address has at least one route.
     */
    public boolean hasNext() {
        return hasNextInetSocketAddress()
                || hasNextProxy()
                || hasNextPostponed();
    }

    /**
     * 1、对于没有配置代理的情况，会对HTTP服务器的域名进行DNS域名解析，并为每个解析到的IP地址创建 连接的目标地址
     *
     * 2、对于SOCKS代理，直接以HTTP的服务器的域名以及协议端口创建 连接目标地址
     *
     * 3、对于HTTP代理，则会对HTTP代理服务器的域名进行DNS域名解析，并为每个解析到的IP地址创建 连接的目标地址
     *
     * 1、如果hasNextPostponed()，则return nextPostponed()。
     * 2、如果hasNextProxy()，则通过nextProxy()获取上一个代理，
     * 并用他去构造一个route，如果在失败链接的数据库里面有这个route，则最后通过递归调用next()，否则返回route
     * 3、如果hasNextInetSocketAddress()，则通过nextInetSocketAddress()获取上一个InetSocketAddress，
     * 并用他去构造一个route，如果在这个失败里面数据中有这个路由，然后继续通过递归调用next()方法，或者直接返回route。
     *
     *
     * @return
     * @throws IOException
     */
    public Route next() throws IOException {
        // Compute the next route to attempt.
        if (!hasNextInetSocketAddress()) {
            if (!hasNextProxy()) {
                if (!hasNextPostponed()) {
                    throw new NoSuchElementException();
                }
                return nextPostponed();
            }
            lastProxy = nextProxy();
        }
        lastInetSocketAddress = nextInetSocketAddress();

        Route route = new Route(address, lastProxy, lastInetSocketAddress);
        if (routeDatabase.shouldPostpone(route)) {
            postponedRoutes.add(route);
            // We will only recurse in order to skip previously failed routes. They will be tried last.
            return next();
        }

        return route;
    }

    /**
     * Clients should invoke this method when they encounter a connectivity failure on a connection
     * returned by this route selector.
     * 通过维护失败的路由信息，以避免浪费时间去连接一切不可用的路由。RouteSelector借助于RouteDatabase维护失败的路由信息。
     */
    public void connectFailed(Route failedRoute, IOException failure) {
        if (failedRoute.proxy().type() != Proxy.Type.DIRECT && address.proxySelector() != null) {
            // Tell the proxy selector when we fail to connect on a fresh connection.
            address.proxySelector().connectFailed(
                    address.url().uri(), failedRoute.proxy().address(), failure);
        }

        routeDatabase.failed(failedRoute);
    }

    /**
     * Prepares the proxy servers to try.
     * 收集路由主要分为两个步骤：第一步收集所有的代理；第二步则是收集特定的代理服务器选择所有的连接目标的地址。
     * 收集代理的过程正如上面的这段代码所示，有两种方式：
     * 一是外部通过address传入代理，此时代理集合将包含这唯一的代理。address的代理最终来源于OkHttpClient，我们可以在构造OkHttpClient时设置代理，来指定该client执行的所有请求特定的代理。
     * 二是，借助于ProxySelectory获得多个代理。ProxySelector最终也来源于OkHttpClient用户当然也可以对此进行配置。但通常情况下，使用系统默认收集的所有代理保存在列表proxies中
     * 为OkHttpClient配置Proxy或ProxySelector的场景大概是，需要让连接使用代理，但不使用系统的代理配置情况。
     * PS：proxies是在这时候被初始化的。inetSocketAddresses也是在这里被初始化，并且添加的第一个元素
     *
     */
    private void resetNextProxy(HttpUrl url, Proxy proxy) {
        if (proxy != null) {
            // If the user specifies a proxy, try that and only that.
            proxies = Collections.singletonList(proxy);
        } else {
            // Try each of the ProxySelector choices until one connection succeeds.
            List<Proxy> proxiesOrNull = address.proxySelector().select(url.uri());
            proxies = proxiesOrNull != null && !proxiesOrNull.isEmpty()
                    ? Util.immutableList(proxiesOrNull)
                    : Util.immutableList(Proxy.NO_PROXY);
        }
        nextProxyIndex = 0;
    }

    /**
     * Returns true if there's another proxy to try.
     */
    private boolean hasNextProxy() {
        return nextProxyIndex < proxies.size();
    }

    /**
     * Returns the next proxy to try. May be PROXY.NO_PROXY but never null.
     * 这个就是从proxies里面去一个出来，proxies是在构造函数里面方法resetNextProxy()来赋值的。
     */
    private Proxy nextProxy() throws IOException {
        if (!hasNextProxy()) {
            throw new SocketException("No route to " + address.url().host()
                    + "; exhausted proxy configurations: " + proxies);
        }
        Proxy result = proxies.get(nextProxyIndex++);
        resetNextInetSocketAddress(result);
        return result;
    }

    /**
     * Prepares the socket addresses to attempt for the current proxy or host.
     */
    private void resetNextInetSocketAddress(Proxy proxy) throws IOException {
        // Clear the addresses. Necessary if getAllByName() below throws!
        inetSocketAddresses = new ArrayList<>();

        String socketHost;
        int socketPort;
        if (proxy.type() == Proxy.Type.DIRECT || proxy.type() == Proxy.Type.SOCKS) {
            socketHost = address.url().host();
            socketPort = address.url().port();
        } else {
            SocketAddress proxyAddress = proxy.address();
            if (!(proxyAddress instanceof InetSocketAddress)) {
                throw new IllegalArgumentException(
                        "Proxy.address() is not an " + "InetSocketAddress: " + proxyAddress.getClass());
            }
            InetSocketAddress proxySocketAddress = (InetSocketAddress) proxyAddress;
            socketHost = getHostString(proxySocketAddress);
            socketPort = proxySocketAddress.getPort();
        }

        if (socketPort < 1 || socketPort > 65535) {
            throw new SocketException("No route to " + socketHost + ":" + socketPort
                    + "; port is out of range");
        }

        if (proxy.type() == Proxy.Type.SOCKS) {
            inetSocketAddresses.add(InetSocketAddress.createUnresolved(socketHost, socketPort));
        } else {
            // Try each address for best behavior in mixed IPv4/IPv6 environments.
            List<InetAddress> addresses = address.dns().lookup(socketHost);
            for (int i = 0, size = addresses.size(); i < size; i++) {
                InetAddress inetAddress = addresses.get(i);
                inetSocketAddresses.add(new InetSocketAddress(inetAddress, socketPort));
            }
        }

        nextInetSocketAddressIndex = 0;
    }

    /**
     * Obtain a "host" from an {@link InetSocketAddress}. This returns a string containing either an
     * actual host name or a numeric IP address.
     */
    // Visible for testing
    static String getHostString(InetSocketAddress socketAddress) {
        InetAddress address = socketAddress.getAddress();
        if (address == null) {
            // The InetSocketAddress was specified with a string (either a numeric IP or a host name). If
            // it is a name, all IPs for that name should be tried. If it is an IP address, only that IP
            // address should be tried.
            return socketAddress.getHostName();
        }
        // The InetSocketAddress has a specific address: we should only try that address. Therefore we
        // return the address and ignore any host name that may be available.
        return address.getHostAddress();
    }

    /**
     * Returns true if there's another socket address to try.
     */
    private boolean hasNextInetSocketAddress() {
        return nextInetSocketAddressIndex < inetSocketAddresses.size();
    }

    /**
     * Returns the next socket address to try.
     * 这个就是从inetSocketAddresses里面取一个出来，proxies是在构造函数里面方法resetNextProxy()来赋值的。
     */
    private InetSocketAddress nextInetSocketAddress() throws IOException {
        if (!hasNextInetSocketAddress()) {
            throw new SocketException("No route to " + address.url().host()
                    + "; exhausted inet socket addresses: " + inetSocketAddresses);
        }
        return inetSocketAddresses.get(nextInetSocketAddressIndex++);
    }

    /**
     * Returns true if there is another postponed route to try.
     */
    private boolean hasNextPostponed() {
        return !postponedRoutes.isEmpty();
    }

    /**
     * Returns the next postponed route to try.
     * postponedRoutes是一个list，里面存放的是之前失败链接的路由，目的是在前所有不符合的情况，把之前失败的路由再试一次。
     */
    private Route nextPostponed() {
        return postponedRoutes.remove(0);
    }
}
