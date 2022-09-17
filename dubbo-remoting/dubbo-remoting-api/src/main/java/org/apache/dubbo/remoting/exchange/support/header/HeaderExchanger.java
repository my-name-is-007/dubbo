/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.dubbo.remoting.exchange.support.header;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.RemotingServer;
import org.apache.dubbo.remoting.Transporters;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;
import org.apache.dubbo.remoting.exchange.ExchangeServer;
import org.apache.dubbo.remoting.exchange.Exchanger;
import org.apache.dubbo.remoting.transport.DecodeHandler;

/**
 * Exchanger接口的默认实现
 */
public class HeaderExchanger implements Exchanger {

    public static final String NAME = "header";

    /**
     *
     * <p>
     *     获取传输层客户端, 再包装为 HeaderExchangeClient:
     *         HeaderExchangeClient ---> NettyClient
     * </p>
     * <p>
     *     交换层 交给 传输层 的handler参数为: ↓
     *         DecodeHandler ---> HeaderExchangeHandler ---> DubboProtocol#requestHandler
     * </p>
     *
     * @param url 服务器url
     * @param handler 用于处理服务端响应, 例如 DubboProtocol#requestHandler
     * @see org.apache.dubbo.remoting.transport.netty4.NettyClient
     */
    @Override
    public ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        //解码器
        DecodeHandler decodeHandler = new DecodeHandler(new HeaderExchangeHandler(handler));

        //传输层Client: 默认 NettyClient,
        Client client = Transporters.connect(url, decodeHandler);

        return new HeaderExchangeClient(client, true);
    }


    /**
     * 创建 传输层服务端: HeaderExchangeServer ---> NettyServer,
     *
     * 给 传输层的 handler参数: DecodeHandler ---> HeaderExchangeHandler ---> DubboProtocol#requestHandler
     *
     * @param handler DubboProtocol#requestHandler
     */
    @Override
    public ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        //解码器
        DecodeHandler decodeHandler = new DecodeHandler(new HeaderExchangeHandler(handler));

        //NettyServer
        RemotingServer server = Transporters.bind(url, decodeHandler);

        return new HeaderExchangeServer(server);
    }

}
