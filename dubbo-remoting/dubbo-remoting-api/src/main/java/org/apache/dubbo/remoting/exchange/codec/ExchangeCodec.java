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
package org.apache.dubbo.remoting.exchange.codec;

import org.apache.dubbo.common.Version;
import org.apache.dubbo.common.io.Bytes;
import org.apache.dubbo.common.io.StreamUtils;
import org.apache.dubbo.common.logger.Logger;
import org.apache.dubbo.common.logger.LoggerFactory;
import org.apache.dubbo.common.serialize.Cleanable;
import org.apache.dubbo.common.serialize.ObjectInput;
import org.apache.dubbo.common.serialize.ObjectOutput;
import org.apache.dubbo.common.serialize.Serialization;
import org.apache.dubbo.common.utils.StringUtils;
import org.apache.dubbo.remoting.Channel;
import org.apache.dubbo.remoting.RemotingException;
import org.apache.dubbo.remoting.buffer.ChannelBuffer;
import org.apache.dubbo.remoting.buffer.ChannelBufferInputStream;
import org.apache.dubbo.remoting.buffer.ChannelBufferOutputStream;
import org.apache.dubbo.remoting.exchange.Request;
import org.apache.dubbo.remoting.exchange.Response;
import org.apache.dubbo.remoting.exchange.support.DefaultFuture;
import org.apache.dubbo.remoting.telnet.codec.TelnetCodec;
import org.apache.dubbo.remoting.transport.CodecSupport;
import org.apache.dubbo.remoting.transport.ExceedPayloadLimitException;

import java.io.IOException;
import java.io.InputStream;

/**
 * Dubbo协议内容如下: ↓
 * <ul>
 *     <li>0-7位和8-15位：Magic High和Magic Low，类似java字节码文件里的魔数，用来判断是不是dubbo协议的数据包，就是一个固定的数字</li>
 *     <li>16位：Req/Res：请求还是响应标识</li>
 *     <li>17位：2way：单向还是双向</li>
 *     <li>18位：Event：是否是事件</li>
 *     <li>19-23位：Serialization 编号</li>
 *     <li>24-31位：status状态</li>
 *     <li>32-95位：id编号</li>
 *     <li>96-127位：body长度</li>
 *     <li>128以后：body数据: 版本信息、服务名、方法名、参数 等信息</li>
 * </ul>
 *
 * 该协议中前65位是协议头, 后面的都是协议体数据.
 *
 * 注意: 在编解码中, 协议头是通过 Codec 编解码, 而 body部分 是用 Serialization 序列化 和 反序列化,
 *
 * <a href="https://image-static.segmentfault.com/259/211/2592111080-5c1c4b0283f11_fix732" />
 *
 *
 * <pre>
 *     {@code
 *         ChannelBufferOutputStream bos = new ChannelBufferOutputStream(channelBuffer);
 *         ObjectOutput out = serialization.serialize(channel.getUrl(), bos);
 *     }
 * </pre>
 * <p>
 *     ChannelBufferOutputStream 继承 {@link java.io.OutputStream}, 将方法的实现均委派至 {@link ChannelBuffer},
 *     serialization.serialize(channel.getUrl(), bos) 是一个 SPI方法, 默认实现为 Hessian2ObjectOutput,
 *     其实现委派至 Hessian
 * </p>
 *
 */
public class ExchangeCodec extends TelnetCodec {

    /** 协议头长度：16字节 = 128Bits. **/
    protected static final int HEADER_LENGTH = 16;
    // magic header.

    /** MAGIC二进制：1101101010111011，十进制：55995. **/
    protected static final short MAGIC = (short) 0xdabb;

    /** Magic High，也就是0-7位：11011010. **/
    protected static final byte MAGIC_HIGH = Bytes.short2bytes(MAGIC)[0];

    /** Magic Low  8-15位 ：10111011. **/
    protected static final byte MAGIC_LOW = Bytes.short2bytes(MAGIC)[1];
    // message flag.

    /** 128 二进制：10000000. **/
    protected static final byte FLAG_REQUEST = (byte) 0x80;

    /** 64 二进制：1000000. **/
    protected static final byte FLAG_TWOWAY = (byte) 0x40;

    /** 32 二进制：100000. **/
    protected static final byte FLAG_EVENT = (byte) 0x20;

    /** 31 二进制：11111. **/
    protected static final int SERIALIZATION_MASK = 0x1f;

    private static final Logger logger = LoggerFactory.getLogger(ExchangeCodec.class);

    public Short getMagicCode() { return MAGIC; }

    @Override
    public void encode(Channel channel, ChannelBuffer buffer, Object msg) throws IOException {
        //Request类型，对请求消息编码
        if (msg instanceof Request) {
            encodeRequest(channel, buffer, (Request) msg);
        } else if (msg instanceof Response) {
            //Response类型，对响应消息编码
            encodeResponse(channel, buffer, (Response) msg);
        } else {
            //直接让父类( Telnet ) 处理，目前是 Telnet 命令的结果
            super.encode(channel, buffer, msg);
        }
    }

    /**
     * 对请求进行编码
     */
    protected void encodeRequest(Channel channel, ChannelBuffer buffer, Request req) throws IOException {
        Serialization serialization = getSerialization(channel);
        //创建消息头字节数组: 16 字节
        byte[] header = new byte[HEADER_LENGTH];

        //2字节16位(0~1号槽位)魔数: 标识 是否为 dubbo协议的数据包
        Bytes.short2bytes(MAGIC, header);

        //1字节8位(2号槽位)消息标志位: 1位请求还是响应 + 1位双向还是单向 + 1位心跳还是正常请求 + 5位序列化Id
        header[2] = (byte) (FLAG_REQUEST | serialization.getContentTypeId());

        if (req.isTwoWay()) {
            header[2] |= FLAG_TWOWAY;
        }
        if (req.isEvent()) {
            header[2] |= FLAG_EVENT;
        }

        //注意: 3号槽位 表示响应的状态, 因为这里是请求, 所以这里跳过了

        //8字节64位(4~11号槽位)消息ID: 每一个请求的唯一识别id
        Bytes.long2bytes(req.getId(), header, 4);

        //获取 buffer 当前的写位置
        int savedWriteIndex = buffer.writerIndex();
        //更新 writerIndex: 当前位置 + 16字节: 为消息头预留 16 个字节的空间
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);

        ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
        //创建序列化器，比如 Hessian2ObjectOutput
        ObjectOutput out = serialization.serialize(channel.getUrl(), bos);

        if (req.isEvent()) {
            //对 事件数据 进行 序列化操作
            encodeEventData(channel, out, req.getData());
        } else {
            //对 请求数据 进行 序列化操作: 子类 DubboCodec 重写了此方法
            encodeRequestData(channel, out, req.getData(), req.getVersion());
        }
        out.flushBuffer();
        if (out instanceof Cleanable) {
            ((Cleanable) out).cleanup();
        }
        bos.flush();
        bos.close();
        //获取写入的字节数: 也就是消息体长度
        int len = bos.writtenBytes();
        checkPayload(channel, len);

        //将消息体长度写入到消息头中
        Bytes.int2bytes(len, header, 12);

        //移动 buffer指针 至 savedWriteIndex, 准备 写消息头
        buffer.writerIndex(savedWriteIndex);

        //写入消息头
        buffer.writeBytes(header);

        //设置 buffer下标: 原写下标 + 消息头长度 + 消息体长度
        buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
    }
    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        //子类DubboCodec重写
        encodeRequestData(out, data);
    }

    protected void encodeResponse(Channel channel, ChannelBuffer buffer, Response res) throws IOException {
        int savedWriteIndex = buffer.writerIndex();
        try {
            Serialization serialization = getSerialization(channel);

            //创建消息头字节数组: 16 字节
            byte[] header = new byte[HEADER_LENGTH];

            //2字节16位(0~1号槽位)魔数: 标识 是否为 dubbo协议的数据包
            Bytes.short2bytes(MAGIC, header);

            //1字节8位(3号槽位)消息标志位: 1位请求还是响应 + 1位双向还是单向 + 1位心跳还是正常请求 + 5位序列化Id
            header[2] = serialization.getContentTypeId();
            if (res.isHeartbeat()) {
                header[2] |= FLAG_EVENT;
            }

            //3号槽位表示响应状态: 请求编码时可没设置这个.
            byte status = res.getStatus();
            header[3] = status;

            //8字节64位(4~11号槽位)消息ID: 每一个请求的唯一识别id
            Bytes.long2bytes(res.getId(), header, 4);

            //更新 writerIndex: 当前位置 + 16字节: 为消息头预留 16 个字节的空间
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH);

            ChannelBufferOutputStream bos = new ChannelBufferOutputStream(buffer);
            //创建序列化器，比如 Hessian2ObjectOutput
            ObjectOutput out = serialization.serialize(channel.getUrl(), bos);

            //编码响应数据或者错误消息
            if (status == Response.OK) {
                //对心跳响应结果进行序列化
                if (res.isHeartbeat()) {
                    encodeEventData(channel, out, res.getResult());
                } else {
                    //对调用结果进行序列化
                    encodeResponseData(channel, out, res.getResult(), res.getVersion());
                }
            } else {
                //对错误信息进行序列化
                out.writeUTF(res.getErrorMessage());
            }
            out.flushBuffer();
            if (out instanceof Cleanable) {
                ((Cleanable) out).cleanup();
            }
            bos.flush();
            bos.close();

            //获取写入的字节数: 也就是消息体长度
            int len = bos.writtenBytes();
            checkPayload(channel, len);
            //将消息体长度写入到消息头中
            Bytes.int2bytes(len, header, 12);

            //移动 buffer指针 至 savedWriteIndex, 准备 写消息头
            buffer.writerIndex(savedWriteIndex);

            //写入消息头
            buffer.writeBytes(header);
            //设置 buffer下标: 原写下标 + 消息头长度 + 消息体长度
            buffer.writerIndex(savedWriteIndex + HEADER_LENGTH + len);
        } catch (Throwable t) {
            // clear buffer
            buffer.writerIndex(savedWriteIndex);
            // send error message to Consumer, otherwise, Consumer will wait till timeout.
            if (!res.isEvent() && res.getStatus() != Response.BAD_RESPONSE) {
                Response r = new Response(res.getId(), res.getVersion());
                r.setStatus(Response.BAD_RESPONSE);

                if (t instanceof ExceedPayloadLimitException) {
                    logger.warn(t.getMessage(), t);
                    try {
                        r.setErrorMessage(t.getMessage());
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + t.getMessage() + ", cause: " + e.getMessage(), e);
                    }
                } else {
                    // FIXME log error message in Codec and handle in caught() of IoHanndler?
                    logger.warn("Fail to encode response: " + res + ", send bad_response info instead, cause: " + t.getMessage(), t);
                    try {
                        r.setErrorMessage("Failed to send response: " + res + ", cause: " + StringUtils.toString(t));
                        channel.send(r);
                        return;
                    } catch (RemotingException e) {
                        logger.warn("Failed to send bad_response info back: " + res + ", cause: " + e.getMessage(), e);
                    }
                }
            }

            // Rethrow exception
            if (t instanceof IOException) {
                throw (IOException) t;
            } else if (t instanceof RuntimeException) {
                throw (RuntimeException) t;
            } else if (t instanceof Error) {
                throw (Error) t;
            } else {
                throw new RuntimeException(t.getMessage(), t);
            }
        }
    }
    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data, String version) throws IOException {
        //子类DubboCodec重写
        encodeResponseData(out, data);
    }

    /** 该方法是解码前的一些核对过程: 检测是否为dubbo协议、是否有拆包现象等, 具体的解码在下面. **/
    @Override
    public Object decode(Channel channel, ChannelBuffer buffer) throws IOException {
        int readable = buffer.readableBytes();
        //读取前16字节的协议头数据，如果数据不满16字节，则读取全部
        byte[] header = new byte[Math.min(readable, HEADER_LENGTH)];
        buffer.readBytes(header);
        //解码
        return decode(channel, buffer, readable, header);
    }
    @Override
    protected Object decode(Channel channel, ChannelBuffer buffer, int readable, byte[] header) throws IOException {
        //检查魔数是否相等
        if (readable > 0 && header[0] != MAGIC_HIGH || readable > 1 && header[1] != MAGIC_LOW) {
            int length = header.length;
            if (header.length < readable) {
                header = Bytes.copyOf(header, readable);
                buffer.readBytes(header, length, readable - length);
            }
            for (int i = 1; i < header.length - 1; i++) {
                if (header[i] == MAGIC_HIGH && header[i + 1] == MAGIC_LOW) {
                    buffer.readerIndex(buffer.readerIndex() - header.length + i);
                    header = Bytes.copyOf(header, i);
                    break;
                }
            }
            //通过 telnet 命令行发送的数据包不包含消息头: 调用 TelnetCodec 的 decode 方法对数据包进行解码
            return super.decode(channel, buffer, readable, header);
        }

        //可读数据量 少于消息头长度: 返回 DecodeResult.NEED_MORE_INPUT
        if (readable < HEADER_LENGTH) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        //从消息头中获取消息体长度
        int len = Bytes.bytes2int(header, 12);
        //检测消息体长度是否超出限制, 超出则抛出异常
        checkPayload(channel, len);

        //理论上完整消息的长度: 消息体长度 + 消息头长度
        int tt = len + HEADER_LENGTH;

        //消息实际长度 < 理论上完整消息的长度: 需要更多的输入. 上面消息头做了一次判断, 这里还要做
        if (readable < tt) {
            return DecodeResult.NEED_MORE_INPUT;
        }

        //buffer中读取 len长度的数据: len为消息体的长度, 其实就是从buffer中把消息体给他读出来
        ChannelBufferInputStream is = new ChannelBufferInputStream(buffer, len);

        try {
            //子类进行了重写, 要看DubboCodec, 别特么看错了啊
            return decodeBody(channel, is, header);
        } finally {
            if (is.available() > 0) {
                try {
                    if (logger.isWarnEnabled()) {
                        logger.warn("Skip input stream " + is.available());
                    }
                    StreamUtils.skipUnusedStream(is);
                } catch (IOException e) {
                    logger.warn(e.getMessage(), e);
                }
            }
        }
    }
    /** 注意: 子类进行了重写, 要看DubboCodec, 别特么看错了啊. **/
    protected Object decodeBody(Channel channel, InputStream is, byte[] header) throws IOException {
        //请求响应标识、序列化Id
        byte flag = header[2], proto = (byte) (flag & SERIALIZATION_MASK);

        //请求Id
        long id = Bytes.bytes2long(header, 4);

        //响应
        if ((flag & FLAG_REQUEST) == 0) {
            //创建响应对象
            Response res = new Response(id);
            //事件
            if ((flag & FLAG_EVENT) != 0) {
                res.setEvent(true);
            }

            //响应状态
            byte status = header[3];
            res.setStatus(status);
            try {
                ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
                if (status == Response.OK) {
                    //响应正常: 解码 心跳 / 事件 / 正常数据
                    Object data;
                    if (res.isHeartbeat()) {
                        data = decodeHeartbeatData(channel, in);
                    } else if (res.isEvent()) {
                        data = decodeEventData(channel, in);
                    } else {
                        //ObjectInput 直接读取
                        data = decodeResponseData(channel, in, getRequestData(id));
                    }
                    res.setResult(data);
                } else {
                    res.setErrorMessage(in.readUTF());
                }
            } catch (Throwable t) {
                res.setStatus(Response.CLIENT_ERROR);
                res.setErrorMessage(StringUtils.toString(t));
            }
            return res;
        }

        //请求
        Request req = new Request(id);
        req.setVersion(Version.getProtocolVersion());

        //是否双向
        req.setTwoWay((flag & FLAG_TWOWAY) != 0);

        //是否事件
        if ((flag & FLAG_EVENT) != 0) {
            req.setEvent(true);
        }
        try {
            //反序列化
            ObjectInput in = CodecSupport.deserialize(channel.getUrl(), is, proto);
            Object data;
            if (req.isHeartbeat()) {
                data = decodeHeartbeatData(channel, in);
            } else if (req.isEvent()) {
                data = decodeEventData(channel, in);
            } else {
                //ObjectInput 直接读取
                data = decodeRequestData(channel, in);
            }
            req.setData(data);
        } catch (Throwable t) {
            // bad request
            req.setBroken(true);
            req.setData(t);
        }
        return req;
    }

    protected Object getRequestData(long id) {
        DefaultFuture future = DefaultFuture.getFuture(id);
        if (future == null) {
            return null;
        }
        Request req = future.getRequest();
        if (req == null) {
            return null;
        }
        return req.getData();
    }



    @Override
    protected Object decodeData(ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    @Deprecated
    protected Object decodeHeartbeatData(ObjectInput in) throws IOException {
        return decodeEventData(null, in);
    }

    protected Object decodeRequestData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    protected Object decodeResponseData(ObjectInput in) throws IOException {
        try {
            return in.readObject();
        } catch (ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Read object failed.", e));
        }
    }

    @Override
    protected void encodeData(ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    private void encodeEventData(ObjectOutput out, Object data) throws IOException {
        out.writeEvent(data);
    }

    @Deprecated
    protected void encodeHeartbeatData(ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    protected void encodeRequestData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    protected void encodeResponseData(ObjectOutput out, Object data) throws IOException {
        out.writeObject(data);
    }

    @Override
    protected Object decodeData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(channel, in);
    }

    protected Object decodeEventData(Channel channel, ObjectInput in) throws IOException {
        try {
            return in.readEvent();
        } catch (IOException | ClassNotFoundException e) {
            throw new IOException(StringUtils.toString("Decode dubbo protocol event failed.", e));
        }
    }

    @Deprecated
    protected Object decodeHeartbeatData(Channel channel, ObjectInput in) throws IOException {
        return decodeEventData(channel, in);
    }

    protected Object decodeRequestData(Channel channel, ObjectInput in) throws IOException {
        return decodeRequestData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in) throws IOException {
        return decodeResponseData(in);
    }

    protected Object decodeResponseData(Channel channel, ObjectInput in, Object requestData) throws IOException {
        return decodeResponseData(channel, in);
    }

    @Override
    protected void encodeData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(channel, out, data);
    }

    private void encodeEventData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeEventData(out, data);
    }

    @Deprecated
    protected void encodeHeartbeatData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeHeartbeatData(out, data);
    }

    protected void encodeRequestData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeRequestData(out, data);
    }

    protected void encodeResponseData(Channel channel, ObjectOutput out, Object data) throws IOException {
        encodeResponseData(out, data);
    }

}
