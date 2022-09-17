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
import org.apache.dubbo.common.timer.HashedWheelTimer;
import org.apache.dubbo.common.utils.Assert;
import org.apache.dubbo.common.utils.NamedThreadFactory;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Client;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.exchange.ExchangeChannel;
import org.apache.dubbo.remoting.exchange.ExchangeClient;
import org.apache.dubbo.remoting.exchange.ExchangeHandler;

import java.net.InetSocketAddress;
import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

import static org.apache.dubbo.remoting.Constants.HEARTBEAT_CHECK_TICK;
import static org.apache.dubbo.remoting.Constants.LEAST_HEARTBEAT_DURATION;
import static org.apache.dubbo.remoting.Constants.TICKS_PER_WHEEL;
import static org.apache.dubbo.remoting.utils.UrlUtils.getHeartbeat;
import static org.apache.dubbo.remoting.utils.UrlUtils.getIdleTimeout;

/**
 * 默认的 MessageClient, 基于协议头的信息交互客户端类，同样它是Client、Channel的适配器
 *
 * 所有的实现方法都是调用了client和channel属性的方法,
 *
 * 该类主要的作用就是增加了心跳功能: 对于长连接, 一些拔网线等物理层的断开, 会导致TCP的FIN消息来不及发送, 对方收不到断开事件,
 *     那么就需要 发送心跳包 来检测 连接是否断开,
 *     consumer 和 provider断开, 处理措施不一样, 会分别做出重连 和 关闭通道 的 操作.
 *
 * 关于心跳实现可参考 https://blog.csdn.net/silence1144/article/details/109855545
 */
public class HeaderExchangeClient implements ExchangeClient {

    /** 被 装饰/委派 的 Client, 如 NettyClient. **/
    private final Client client;

    /** 被 装饰/委派 的 channel: HeaderExchangeChannel. **/
    private final ExchangeChannel channel;

    /** 时间轮. **/
    private static final HashedWheelTimer IDLE_CHECK_TIMER = new HashedWheelTimer(new NamedThreadFactory("dubbo-client-idleCheck", true),
            1, TimeUnit.SECONDS, TICKS_PER_WHEEL);

    /** 心跳检测任务. **/
    private HeartbeatTimerTask heartBeatTimerTask;

    /** 重连任务. **/
    private ReconnectTimerTask reconnectTimerTask;

    public HeaderExchangeClient(Client client, boolean startTimer) {
        Assert.notNull(client, "Client can't be null");
        this.client = client;

        //创建信息交换通道
        this.channel = new HeaderExchangeChannel(client);

        //开启 心跳检测、重连任务
        if (startTimer) {
            URL url = client.getUrl();

            //开启重连任务
            startReconnectTask(url);

            //开启心跳(但不会开启), 心跳实际上转到了 IdleStateHandler
            startHeartBeatTask(url);
        }
    }

    /** 开启心跳: 但是 2.7.x版本中已经不再使用. **/
    private void startHeartBeatTask(URL url) {
        //此处判断不通过, 不会开启心跳
        if (!client.canHandleIdle()) {
            AbstractTimerTask.ChannelProvider cp = () -> Collections.singletonList(HeaderExchangeClient.this);
            //心跳的时长: DubboProtocol#initClient 设置了心跳时长
            int heartbeat = getHeartbeat(url);
            long heartbeatTick = calculateLeastDuration(heartbeat);
            this.heartBeatTimerTask = new HeartbeatTimerTask(cp, heartbeatTick, heartbeat);
            IDLE_CHECK_TIMER.newTimeout(heartBeatTimerTask, heartbeatTick, TimeUnit.MILLISECONDS);
        }
    }

    private void startReconnectTask(URL url) {
        if (shouldReconnect(url)) {
            AbstractTimerTask.ChannelProvider cp = () -> Collections.singletonList(HeaderExchangeClient.this);
            int idleTimeout = getIdleTimeout(url);
            long heartbeatTimeoutTick = calculateLeastDuration(idleTimeout);
            this.reconnectTimerTask = new ReconnectTimerTask(cp, heartbeatTimeoutTick, idleTimeout);
            IDLE_CHECK_TIMER.newTimeout(reconnectTimerTask, heartbeatTimeoutTick, TimeUnit.MILLISECONDS);
        }
    }

    /** 委派给 channel. **/
    @Override
    public CompletableFuture<Object> request(Object request, int timeout, ExecutorService executor) throws RemotingException {
        return channel.request(request, timeout, executor);
    }

    @Override
    public CompletableFuture<Object> request(Object request) throws RemotingException {
        return channel.request(request);
    }

    @Override
    public URL getUrl() {
        return channel.getUrl();
    }

    @Override
    public InetSocketAddress getRemoteAddress() {
        return channel.getRemoteAddress();
    }

    @Override
    public CompletableFuture<Object> request(Object request, int timeout) throws RemotingException {
        return channel.request(request, timeout);
    }

    @Override
    public CompletableFuture<Object> request(Object request, ExecutorService executor) throws RemotingException {
        return channel.request(request, executor);
    }

    @Override
    public ChannelHandler getChannelHandler() {
        return channel.getChannelHandler();
    }

    @Override
    public boolean isConnected() {
        return channel.isConnected();
    }

    @Override
    public InetSocketAddress getLocalAddress() {
        return channel.getLocalAddress();
    }

    @Override
    public ExchangeHandler getExchangeHandler() {
        return channel.getExchangeHandler();
    }

    @Override
    public void send(Object message) throws RemotingException {
        channel.send(message);
    }

    @Override
    public void send(Object message, boolean sent) throws RemotingException {
        channel.send(message, sent);
    }

    @Override
    public boolean isClosed() {
        return channel.isClosed();
    }

    @Override
    public void close() {
        doClose();
        channel.close();
    }

    @Override
    public void close(int timeout) {
        // Mark the client into the closure process
        startClose();
        doClose();
        channel.close(timeout);
    }

    @Override
    public void startClose() {
        channel.startClose();
    }

    @Override
    public void reset(URL url) {
        client.reset(url);
        // FIXME, should cancel and restart timer tasks if parameters in the new URL are different?
    }

    @Override
    @Deprecated
    public void reset(org.apache.dubbo.common.Parameters parameters) {
        reset(getUrl().addParameters(parameters.getParameters()));
    }

    @Override
    public void reconnect() throws RemotingException {
        client.reconnect();
    }

    @Override
    public Object getAttribute(String key) {
        return channel.getAttribute(key);
    }

    @Override
    public void setAttribute(String key, Object value) {
        channel.setAttribute(key, value);
    }

    @Override
    public void removeAttribute(String key) {
        channel.removeAttribute(key);
    }

    @Override
    public boolean hasAttribute(String key) {
        return channel.hasAttribute(key);
    }

    private void doClose() {
        if (heartBeatTimerTask != null) {
            heartBeatTimerTask.cancel();
        }

        if (reconnectTimerTask != null) {
            reconnectTimerTask.cancel();
        }
    }

    /**
     * Each interval cannot be less than 1000ms.
     */
    private long calculateLeastDuration(int time) {
        if (time / HEARTBEAT_CHECK_TICK <= 0) {
            return LEAST_HEARTBEAT_DURATION;
        } else {
            return time / HEARTBEAT_CHECK_TICK;
        }
    }

    private boolean shouldReconnect(URL url) {
        return url.getParameter(Constants.RECONNECT_KEY, true);
    }

    @Override
    public String toString() {
        return "HeaderExchangeClient [channel=" + channel + "]";
    }
}
