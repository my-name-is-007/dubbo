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
package org.apache.dubbo.remoting.transport.dispatcher;

import org.apache.dubbo.common.URL;
import org.apache.dubbo.common.extension.ExtensionLoader;
import org.apache.dubbo.remoting.ChannelHandler;
import org.apache.dubbo.remoting.Dispatcher;
import org.apache.dubbo.remoting.exchange.support.header.HeartbeatHandler;
import org.apache.dubbo.remoting.transport.MultiMessageHandler;

/**
 * 老母猪戴胸罩, 真尼玛一套又一套 啊, 不过这种设计是很值得学习的
 *
 * 可以理解为是一个 handler的工厂, 会对传入的handler进行一次包装,
 *
 * 无论是 Client 还是 Server 都会做这样的处理: 也就是做了一些功能上的增强.
 *
 * 其包装逻辑大概是这样的:
 *     1. 先根据派发器包装一层: 大概率是 AllDispatcher 创建 的 AllChannelHandler
 *     2. 再包一层: HeartbeatHandler
 *     3. 再他妈包一层: MultiMessageHandler
 *
 * MultiMessageHandler ---> HeartbeatHandler ---> AllChannelHandler ---> handler
 *
 * @see org.apache.dubbo.remoting.transport.dispatcher.all.AllChannelHandler
 */
public class ChannelHandlers {

    private static ChannelHandlers INSTANCE = new ChannelHandlers();

    protected ChannelHandlers() { }

    public static ChannelHandler wrap(ChannelHandler handler, URL url) {
        return ChannelHandlers.getInstance().wrapInternal(handler, url);
    }

    protected static ChannelHandlers getInstance() { return INSTANCE; }

    static void setTestingChannelHandlers(ChannelHandlers instance) { INSTANCE = instance; }

    protected ChannelHandler wrapInternal(ChannelHandler handler, URL url) {
        return
            new MultiMessageHandler(
                new HeartbeatHandler(
                    ExtensionLoader.getExtensionLoader(Dispatcher.class).getAdaptiveExtension().dispatch(handler, url)
                )
            );
    }
}
