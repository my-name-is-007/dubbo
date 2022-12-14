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

import static org.apache.dubbo.common.constants.CommonConstants.HEARTBEAT_EVENT;

/**
 * 很多属性跟Request模型的属性一样, 并且含义也一样,
 *
 * 不过该模型多了很多的状态码, 关键的是id跟请求一一对应: 因为这个Id就是从Request拿出来的
 */
public class Response {

    public static final byte OK = 20;

    /** 客户端侧的超时状态码. **/
    public static final byte CLIENT_TIMEOUT = 30;

    /** 服务端侧超时的状态码. **/
    public static final byte SERVER_TIMEOUT = 31;

    /** 通道不活跃，返回未完成请求的状态码. **/
    public static final byte CHANNEL_INACTIVE = 35;

    /** 请求格式错误状态码. **/
    public static final byte BAD_REQUEST = 40;

    /** 响应格式错误状态码. **/
    public static final byte BAD_RESPONSE = 50;

    /** 服务找不到 状态码. **/
    public static final byte SERVICE_NOT_FOUND = 60;

    /** 服务错误状态码. **/
    public static final byte SERVICE_ERROR = 70;

    /** 内部服务器错误状态码. **/
    public static final byte SERVER_ERROR = 80;

    /** 客户端错误状态码. **/
    public static final byte CLIENT_ERROR = 90;

    /** 服务器端线程池耗尽并快速返回状态码. **/
    public static final byte SERVER_THREADPOOL_EXHAUSTED_ERROR = 100;

    /** 响应编号. **/
    private long mId = 0;

    /** dubbo 版本. **/
    private String mVersion;

    /** 状态. **/
    private byte mStatus = OK;

    /** 是否是事件. **/
    private boolean mEvent = false;

    /** 错误信息. **/
    private String mErrorMsg;

    /** 返回结果. **/
    private Object mResult;

    public Response() { }
    public Response(long id) { mId = id; }
    public Response(long id, String version) {
        mId = id;
        mVersion = version;
    }

    public long getId() {
        return mId;
    }

    public void setId(long id) {
        mId = id;
    }

    public String getVersion() {
        return mVersion;
    }

    public void setVersion(String version) {
        mVersion = version;
    }

    public byte getStatus() {
        return mStatus;
    }

    public void setStatus(byte status) {
        mStatus = status;
    }

    public boolean isEvent() {
        return mEvent;
    }

    public void setEvent(String event) {
        mEvent = true;
        mResult = event;
    }

    public void setEvent(boolean mEvent) {
        this.mEvent = mEvent;
    }

    public boolean isHeartbeat() {
        return mEvent && HEARTBEAT_EVENT == mResult;
    }

    @Deprecated
    public void setHeartbeat(boolean isHeartbeat) {
        if (isHeartbeat) {
            setEvent(HEARTBEAT_EVENT);
        }
    }

    public Object getResult() {
        return mResult;
    }

    public void setResult(Object msg) {
        mResult = msg;
    }

    public String getErrorMessage() {
        return mErrorMsg;
    }

    public void setErrorMessage(String msg) {
        mErrorMsg = msg;
    }

    @Override
    public String toString() {
        return "Response [id=" + mId + ", version=" + mVersion + ", status=" + mStatus + ", event=" + mEvent
                + ", error=" + mErrorMsg + ", result=" + (mResult == this ? "this" : mResult) + "]";
    }
}