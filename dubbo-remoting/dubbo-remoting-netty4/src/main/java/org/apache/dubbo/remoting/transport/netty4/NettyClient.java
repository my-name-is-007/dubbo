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
package org.apache.dubbo.remoting.transport.netty4;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.utils.ConfigUtils;
import org.apache.dubbo.common.utils.NetUtils;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Constants;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.transport.AbstractClient;
import org.apache.dubbo.remoting.utils.UrlUtils;

import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.PooledByteBufAllocator;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.SocketChannel;
import io.netty.handler.proxy.Socks5ProxyHandler;
import io.netty.handler.timeout.IdleStateHandler;

import java.net.InetSocketAddress;

import static java.util.concurrent.TimeUnit.MILLISECONDS;
import static org.apache.dubbo.common.constants.CommonConstants.SSL_ENABLED_KEY;
import static org.apache.dubbo.remoting.transport.netty4.NettyEventLoopFactory.eventLoopGroup;
import static org.apache.dubbo.remoting.transport.netty4.NettyEventLoopFactory.socketChannelClass;

/**
 * ?????????AbstractClient????????????netty4???????????????????????????.
 *
 * ????????? 4????????????: decoder???encoder???client-idle-handler???handler, ???????????????: ???
 *
 * ??????:
 *     NettyClientHandler ---> IdleStateHandler ---> NettyCodecAdapter#InternalEncoder(DubboCountCodec), ???????????????????????? Netty Handler, ?????????Netty???????????????,
 * ?????? NettyClientHandler ???????????? Dubbo handler,  ???: ???
 *     NettyClient ---> MultiMessageHandler ---> HeartbeatHandler ---> AllChannelHandler ---> DecodeHandler ---> HeaderExchangeHandler ---> DubboProtocol#requestHandler
 *
 * ??????:
 *     NettyCodecAdapter#InternalDecoder(DubboCountCodec) ---> IdleStateHandler ---> NettyClientHandler, NettyClientHandler ???: ???
 *     NettyClient ---> MultiMessageHandler ---> HeartbeatHandler ---> AllChannelHandler ---> DecodeHandler ---> HeaderExchangeHandler ---> DubboProtocol#requestHandler
 *
 * ?????? ??????????????? DubboProtocol#requestHandler, ??????????????????, HeaderExchangeHandler ???????????????????????????, ????????? DefaultFuture??????, ????????? DubboProtocol#requestHandler. ???????????????????????????????????????, ????????????.
 *
 * ?????????handler??????????????????,
 */
public class NettyClient extends AbstractClient {

    private static final Logger logger = LoggerFactory.getLogger(NettyClient.class);

    /** ????????????, ???????????????????????????. **/
    private static final EventLoopGroup NIO_EVENT_LOOP_GROUP = eventLoopGroup(Constants.DEFAULT_IO_THREADS, "NettyClientWorker");

    private static final String SOCKS_PROXY_HOST = "socksProxyHost";

    private static final String SOCKS_PROXY_PORT = "socksProxyPort";

    private static final String DEFAULT_SOCKS_PROXY_PORT = "1080";

    /** ??????????????????. **/
    private Bootstrap bootstrap;

    /**
     * ??????, Netty??????Channel, ??? doConnect()?????????????????????,
     * ??????????????????, ??????????????? NettyChannel
     *
     * current channel. Each successful invocation of {@link NettyClient#doConnect()} will
     * replace this with new channel and close old channel.
     * <b>volatile, please copy reference to use.</b>
     */
    private volatile Channel channel;

    /**
     * <p>
     *     ??????????????? wrapChannelHandler ??? handler ????????????
     *     wrapChannelHandler ??????SPI??????????????? Dispatcher(?????? AllDispatcher),
     *     ????????? Dispatcher ????????????????????????Handler(AllDispatcher???????????? AllChannelHandler), ??????????????????????????????
     * </p>
     * <p>
     *     ???????????????, ???????????? handler ?????????????????????: <br/>
     *         MultiMessageHandler ---> HeartbeatHandler ---> Dispatcher??????Handler ---> DecodeHandler ---> HeaderExchangeHandler ---> DubboProtocol#requestHandler <br/>
     *     ??? Dispatcher ????????? AllDispatcher, ?????? handler??????????????????:
     *         MultiMessageHandler ---> HeartbeatHandler ---> AllChannelHandler ---> DecodeHandler ---> HeaderExchangeHandler ---> DubboProtocol#requestHandler <br/>
     * </p>
     * ??????: ????????????????????????????????????, ??? doConnect()???, ???????????? NettyCodecAdapter
     *
     * @param handler DecodeHandler ---> HeaderExchangeHandler ---> DubboProtocol#requestHandler
     * @throws RemotingException
     */
    public NettyClient(final URL url, final ChannelHandler handler) throws RemotingException {
    	super(url, wrapChannelHandler(url, handler));
    }

    @Override
    protected void doOpen() throws Throwable {
        //Netty ??? ???????????????
        final NettyClientHandler nettyClientHandler = new NettyClientHandler(getUrl(), this);
        bootstrap = new Bootstrap();
        bootstrap.group(NIO_EVENT_LOOP_GROUP)
                .option(ChannelOption.SO_KEEPALIVE, true)
                .option(ChannelOption.TCP_NODELAY, true)
                .option(ChannelOption.ALLOCATOR, PooledByteBufAllocator.DEFAULT)
                .channel(socketChannelClass());

        bootstrap.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Math.max(3000, getConnectTimeout()));
        bootstrap.handler(new ChannelInitializer<SocketChannel>() {
            @Override
            protected void initChannel(SocketChannel ch) throws Exception {
                int heartbeatInterval = UrlUtils.getHeartbeat(getUrl());

                if (getUrl().getParameter(SSL_ENABLED_KEY, false)) {
                    ch.pipeline().addLast("negotiation", SslHandlerInitializer.sslClientHandler(getUrl(), nettyClientHandler));
                }

                /**
                 * ?????? ?????????: ??????, ????????? ????????????
                 * ?????? ?????????: ??????, ????????? ????????????
                 * ?????? ?????????: ???????????? ??? OK,
                 *
                 * ?????? ????????????,
                 */
                NettyCodecAdapter adapter = new NettyCodecAdapter(getCodec(), getUrl(), NettyClient.this);
                ch.pipeline()
                    .addLast("decoder", adapter.getDecoder())
                    .addLast("encoder", adapter.getEncoder())
                    .addLast("client-idle-handler", new IdleStateHandler(heartbeatInterval, 0, 0, MILLISECONDS))
                    .addLast("handler", nettyClientHandler);

                String socksProxyHost = ConfigUtils.getProperty(SOCKS_PROXY_HOST);
                if(socksProxyHost != null) {
                    int socksProxyPort = Integer.parseInt(ConfigUtils.getProperty(SOCKS_PROXY_PORT, DEFAULT_SOCKS_PROXY_PORT));
                    Socks5ProxyHandler socks5ProxyHandler = new Socks5ProxyHandler(new InetSocketAddress(socksProxyHost, socksProxyPort));
                    ch.pipeline().addFirst(socks5ProxyHandler);
                }
            }
        });
    }

    @Override
    protected void doConnect() throws Throwable {
        long start = System.currentTimeMillis();
        ChannelFuture future = bootstrap.connect(getConnectAddress());
        try {
            boolean ret = future.awaitUninterruptibly(getConnectTimeout(), MILLISECONDS);

            if (ret && future.isSuccess()) {
                Channel newChannel = future.channel();
                try {
                    //??????channel??????null?????????
                    Channel oldChannel = NettyClient.this.channel;
                    if (oldChannel != null) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close old netty channel " + oldChannel + " on create new netty channel " + newChannel);
                            }
                            oldChannel.close();
                        } finally {
                            NettyChannel.removeChannelIfDisconnected(oldChannel);
                        }
                    }
                } finally {
                    if (NettyClient.this.isClosed()) {
                        try {
                            if (logger.isInfoEnabled()) {
                                logger.info("Close new netty channel " + newChannel + ", because the client closed.");
                            }
                            newChannel.close();
                        } finally {
                            NettyClient.this.channel = null;
                            NettyChannel.removeChannelIfDisconnected(newChannel);
                        }
                    } else {
                        //????????????????????? channel??????
                        NettyClient.this.channel = newChannel;
                    }
                }
            } else if (future.cause() != null) {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + ", error message is:" + future.cause().getMessage(), future.cause());
            } else {
                throw new RemotingException(this, "client(url: " + getUrl() + ") failed to connect to server "
                        + getRemoteAddress() + " client-side timeout "
                        + getConnectTimeout() + "ms (elapsed: " + (System.currentTimeMillis() - start) + "ms) from netty client "
                        + NetUtils.getLocalHost() + " using dubbo version " + Version.getVersion());
            }
        } finally {
            // just add new valid channel to NettyChannel's cache
            if (!isConnected()) {
                //future.cancel(true);
            }
        }
    }

    @Override
    protected void doDisConnect() throws Throwable {
        try {
            NettyChannel.removeChannelIfDisconnected(channel);
        } catch (Throwable t) {
            logger.warn(t.getMessage());
        }
    }

    @Override
    protected void doClose() throws Throwable {
        // can't shutdown nioEventLoopGroup because the method will be invoked when closing one channel but not a client,
        // but when and how to close the nioEventLoopGroup ?
        // nioEventLoopGroup.shutdownGracefully();
    }

    @Override
    protected org.apache.dubbo.remoting.Channel getChannel() {
        Channel c = channel;
        if (c == null) {
            return null;
        }
        return NettyChannel.getOrAddChannel(c, getUrl(), this);
    }

    Channel getNettyChannel() {
        return channel;
    }

    @Override
    public boolean canHandleIdle() {
        return true;
    }
}
