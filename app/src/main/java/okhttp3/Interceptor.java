/*
 * Copyright (C) 2014 Square, Inc.
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

import java.io.IOException;

/**
 * Observes, modifies, and potentially short-circuits requests going out and the corresponding
 * responses coming back in. Typically interceptors add, remove, or transform headers on the request
 * or response.
 * 1.1 先来看看Intercepor本身文档的含义：
 * 观察，修改以及可能短路的请求输出和响应请求的回来。
 * 通常情况下拦截器用来添加，移除或者转换请求或者回应的头部信息
 * 1.2 拦截器，就像水管一样，把一节一节的水管(拦截器)连起来，形成一个回路，
 * 实际上client到server也是如此，通过一个又一个的interceptor串起来，
 * 然后把数据发送到服务器，又能接受返回的数据，每一个拦截器(水管)都有自己的作用，分别处理不同东西，
 * 比如消毒，净化，去杂质，就像一层层过滤网一样。
 *
 */
public interface Interceptor {
  //负责拦截
  /*2.1 Interceptor是一个接口，主要是对请求和相应的过滤处理，
  其中有一个抽象方法即Response intercept(Chain chain) throws IOException负责具体的过滤。
  2.2 而在他的子类里面又调用了Chain，从而实现拦截器调用链(chain)，所以真正实现拦截作用的是其内部接口Chain
  */
  Response intercept(Chain chain) throws IOException;

  interface Chain {
    Request request();

    //负责分发、前行
    Response proceed(Request request) throws IOException;

    Connection connection();
  }
}
