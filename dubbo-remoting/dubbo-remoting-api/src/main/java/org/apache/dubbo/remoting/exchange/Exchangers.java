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
package org.apache.dubbo.remoting.exchange;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.support.ExchangeHandlerDispatcher;
import org.apache.dubbo.remoting.exchange.support.Replier;
import org.apache.dubbo.remoting.transport.ChannelHandlerAdapter;

/**
 * Exchanger facade. (API, Static, ThreadSafe)
 *
 * 跟 Transporters 的 设计意图 是 一样 的
 * @see org.apache.dubbo.remoting.Transporters
 */
public class Exchangers {

    // check duplicate jar package
    static { Version.checkDuplicate(Exchangers.class); }

    private Exchangers() { }

    /**
     * 根据 URL, 通过SPI 获取 Exchanger(默认 HeaderExchanger),
     * 然后 Exchanger创建 HeaderExchangeServer 服务端
     * @param handler DubboProtocol#requestHandler, 用于处理服务端响应
     * @see org.apache.dubbo.remoting.exchange.support.header.HeaderExchanger
     */
    public static ExchangeServer bind(URL url, ExchangeHandler handler) throws RemotingException {
        if (url == null) { throw new IllegalArgumentException("url == null"); }
        if (handler == null) { throw new IllegalArgumentException("handler == null"); }

        //编解码在上层DubboProtocol已经设置了
        url = url.addParameterIfAbsent(Constants.CODEC_KEY, "exchange");

        /**
         * 获取 Exchanger, 默认为 HeaderExchanger,
         *
         * @see org.apache.dubbo.remoting.exchange.support.header.HeaderExchanger#bind(URL, ExchangeHandler)
         */
        return getExchanger(url).bind(url, handler);
    }

    /**
     * 根据 URL, 通过SPI 获取 Exchanger(默认 HeaderExchanger),
     * 然后 Exchanger创建 ExchangeClient 客户端
     * @param handler DubboProtocol#requestHandler, 用于处理服务端响应
     * @see org.apache.dubbo.remoting.exchange.support.header.HeaderExchanger
     */
    public static ExchangeClient connect(URL url, ExchangeHandler handler) throws RemotingException {
        if (url == null) { throw new IllegalArgumentException("url == null"); }
        if (handler == null) { throw new IllegalArgumentException("handler == null"); }

        //编解码在上层DubboProtocol已经设置了
        url = url.addParameterIfAbsent(Constants.CODEC_KEY, "exchange");

        /**
         * 获取 Exchanger, 默认为 HeaderExchanger,
         *
         * @see org.apache.dubbo.remoting.exchange.support.header.HeaderExchanger#connect(URL, ExchangeHandler)
         */
        return getExchanger(url).connect(url, handler);
    }

    public static ExchangeServer bind(String url, Replier<?> replier) throws RemotingException {
        return bind(URL.valueOf(url), replier);
    }

    public static ExchangeServer bind(URL url, Replier<?> replier) throws RemotingException {
        return bind(url, new ChannelHandlerAdapter(), replier);
    }

    public static ExchangeServer bind(String url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return bind(URL.valueOf(url), handler, replier);
    }

    public static ExchangeServer bind(URL url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return bind(url, new ExchangeHandlerDispatcher(replier, handler));
    }

    public static ExchangeServer bind(String url, ExchangeHandler handler) throws RemotingException {
        return bind(URL.valueOf(url), handler);
    }

    public static ExchangeClient connect(String url) throws RemotingException {
        return connect(URL.valueOf(url));
    }

    public static ExchangeClient connect(URL url) throws RemotingException {
        return connect(url, new ChannelHandlerAdapter(), null);
    }

    public static ExchangeClient connect(String url, Replier<?> replier) throws RemotingException {
        return connect(URL.valueOf(url), new ChannelHandlerAdapter(), replier);
    }

    public static ExchangeClient connect(URL url, Replier<?> replier) throws RemotingException {
        return connect(url, new ChannelHandlerAdapter(), replier);
    }

    public static ExchangeClient connect(String url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return connect(URL.valueOf(url), handler, replier);
    }

    public static ExchangeClient connect(URL url, ChannelHandler handler, Replier<?> replier) throws RemotingException {
        return connect(url, new ExchangeHandlerDispatcher(replier, handler));
    }

    public static ExchangeClient connect(String url, ExchangeHandler handler) throws RemotingException {
        return connect(URL.valueOf(url), handler);
    }

    //exchanger
    public static Exchanger getExchanger(URL url) {
        String type = url.getParameter(Constants.EXCHANGER_KEY, Constants.DEFAULT_EXCHANGER);
        return getExchanger(type);
    }

    public static Exchanger getExchanger(String type) {
        return ExtensionLoader.getExtensionLoader(Exchanger.class).getExtension(type);
    }

}
