/*
 * Copyright (c) 2017-2020 duxiaoman All Rights Reserved.
 * PROPRIETARY/CONFIDENTIAL. Use is subject to license terms.
 * Author Email: wangzongyao@duxiaoman.com
 */

package org.apache.dubbo.remoting.transport.netty4;

/**
 * @author wangzongyao on 2022/7/6
 */
public class HeartbeatDoc {

    /**
     * 在一个时间段内，如果没有任何连接相关的活动，TCP Keep-alive会开始作用，每隔一个时间间隔，发送一个探测报文，
     * 该探测报文包含的数据非常少，如果连续几个探测报文都没有得到响应，则认为当前的 TCP 连接已经死亡，系统内核将错误信息通知给上层应用程序。
     *
     * 这个过程会涉及到操作系统提供的3个变量（通过sysctl 命令查看）：
     *
     * #保活时间，默认2小时
     * net.ipv4.tcp_keepalive_time = 7200
     * #探测时间间隔72s
     * net.ipv4.tcp_keepalive_intvl = 75
     * #探测次数9次
     * net.ipv4.tcp_keepalive_probes = 9
     * 根据系统提供的参数默认值，如果使用 TCP 自身的 keep-Alive 机制，在 Linux 系统中，最少需要经过2个多小时才可以发现一个“死亡”连接。
     *
     * 另外Keep alive默认是关闭的，需要开启KeepAlive的应用必须在TCP的socket中单独开启。
     *
     * 除了TCPKeepAlive间隔较长，另外KeepAlive机制只是在网络层保证了连接的可用性，如果网络层是通的，应用层不通了也是应该认为链接不可用了。
     *
     * 因此，应用层也需要提供心跳机制来保证应用直接的链接可用性检测，Dubbo的心跳机制就是从应用层来解决连接可用性的问题。
     *
     *
     * @author wangzongyao on 2022/7/6
     *
     * @see ReadTimeoutHandler
     * @see WriteTimeoutHandler
     */

}